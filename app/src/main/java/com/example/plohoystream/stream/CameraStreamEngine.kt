package com.example.plohoystream.stream

import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Real [StreamEngine]: parses the endpoint, selects a requested [VideoFormat] from device
 * capabilities, starts the native session via [RtmpStreamer], and polls native state into
 * [state]. The codec is negotiated at the enhanced-RTMP connect handshake, so the media
 * pipeline ([startMedia]) is built only once native reaches Live, with the *negotiated*
 * format (the server may downgrade HEVC -> AVC). Android media setup is injected via
 * [startMedia]/[stopMedia] so the orchestration is unit-tested with fakes.
 */
class CameraStreamEngine(
    private val streamerFactory: () -> RtmpStreamer,
    private val startMedia: (RtmpStreamer, VideoFormat) -> Unit,
    private val stopMedia: () -> Unit,
    private val pollIntervalMs: Long = 250,
    private val hevcEncoder: Boolean = false,
    private val hevcMain10: Boolean = false,
    private val cameraHdr: Boolean = false,
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
    @Volatile private var mediaStarted = false

    /** Lets the media-setup lambda publish the encoder surface back to the viewfinder. */
    fun publishEncoderSurface(s: Surface?) { _encoderSurface.value = s }

    override fun start(config: StreamConfig) {
        val endpoint = runCatching { RtmpEndpoint.parse(config.rtmpUrl, config.streamKey) }
            .getOrElse { _state.value = StreamState.Error(it.message ?: "Bad URL"); return }

        mediaStarted = false
        val requested = CodecSelector.select(hevcEncoder, hevcMain10, cameraHdr, config.hdrEnabled)

        _state.value = StreamState.Connecting
        val s = streamerFactory().also { streamer = it }
        s.start(endpoint, requested.codec, width, height, fps, sampleRate)

        pollJob = scope.launch {
            // Poll native state. The media pipeline is built lazily on the first Live(2): we
            // read the negotiated codec then, so a server HEVC->AVC downgrade is honoured
            // before the encoder is created. Connecting(1) and Live(2) are non-terminal so a
            // mid-stream native drop (state -> 3) surfaces as Error instead of latching on
            // Live forever. Only Error(3)/Idle(0) break the loop. stop() cancels pollJob.
            while (true) {
                when (s.state()) {
                    2 -> {
                        if (!mediaStarted) {
                            mediaStarted = true
                            val negotiated = s.negotiatedCodec()
                            val actual = if (negotiated == VideoCodecType.HEVC) requested
                                         else VideoFormat(VideoCodecType.AVC, main10 = false, DynamicRange.SDR)
                            startMedia(s, actual)
                        }
                        _state.value = StreamState.Live
                    }
                    3 -> { _state.value = StreamState.Error("Stream rejected"); break }
                    0 -> { _state.value = StreamState.Idle; break }
                    // 1 (Connecting) -> keep polling
                }
                delay(pollIntervalMs)
            }
        }
    }

    override fun stop() {
        _state.value = StreamState.Stopping
        pollJob?.cancel(); pollJob = null
        if (mediaStarted) stopMedia()
        mediaStarted = false
        _encoderSurface.value = null
        streamer?.stop(); streamer = null
        _state.value = StreamState.Idle
    }

    fun dispose() {
        stop()
        scope.cancel()
    }
}
