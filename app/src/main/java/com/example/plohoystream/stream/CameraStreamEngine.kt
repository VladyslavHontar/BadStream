package com.example.plohoystream.stream

import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Real [StreamEngine]: parses the endpoint, starts the native session via [RtmpStreamer],
 * and polls native state into [state]. Android media setup is injected via [startMedia]/
 * [stopMedia] so the orchestration is unit-tested with fakes.
 */
class CameraStreamEngine(
    private val streamerFactory: () -> RtmpStreamer,
    private val startMedia: (RtmpStreamer) -> Unit,
    private val stopMedia: () -> Unit,
    private val pollIntervalMs: Long = 250,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val fps: Int = 30,
    private val sampleRate: Int = 44100,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : VideoStreamEngine {

    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    override val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _encoderSurface = MutableStateFlow<Surface?>(null)
    override val encoderSurface: StateFlow<Surface?> = _encoderSurface.asStateFlow()

    private var streamer: RtmpStreamer? = null
    private var pollJob: Job? = null

    /** Lets the media-setup lambda publish the encoder surface back to the viewfinder. */
    fun publishEncoderSurface(s: Surface?) { _encoderSurface.value = s }

    override fun start(config: StreamConfig) {
        val endpoint = runCatching { RtmpEndpoint.parse(config.rtmpUrl, config.streamKey) }
            .getOrElse { _state.value = StreamState.Error(it.message ?: "Bad URL"); return }

        _state.value = StreamState.Connecting
        val s = streamerFactory().also { streamer = it }
        s.start(endpoint, width, height, fps, sampleRate)
        startMedia(s)

        pollJob = scope.launch {
            // Poll native state until it settles on a latching outcome. Each outcome is
            // terminal for this poll so we never leave an unbounded timed loop re-queuing
            // (which would otherwise stall a virtual-time test scheduler). A later
            // disconnect re-arms via a fresh start()/native callback.
            while (true) {
                when (s.state()) {
                    2 -> { _state.value = StreamState.Live; break }
                    3 -> { _state.value = StreamState.Error("Stream rejected"); break }
                    0 -> { _state.value = StreamState.Idle; break }
                    else -> delay(pollIntervalMs) // still Connecting (1): keep polling
                }
            }
        }
    }

    override fun stop() {
        _state.value = StreamState.Stopping
        pollJob?.cancel(); pollJob = null
        stopMedia()
        _encoderSurface.value = null
        streamer?.stop(); streamer = null
        _state.value = StreamState.Idle
    }
}
