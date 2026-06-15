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
    // record (4th arg) = whether this go-live should also record locally (Settings.recordWhileStreaming).
    private val startMedia: (RtmpStreamer, VideoFormat, VideoQuality, Boolean) -> Unit,
    private val stopMedia: () -> Unit,
    private val pollIntervalMs: Long = 250,
    private val reconnectDelayMs: Long = 5000,
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

    private val _activeHdr = MutableStateFlow(false)
    override val activeHdr: StateFlow<Boolean> = _activeHdr.asStateFlow()

    private val _bitrateKbps = MutableStateFlow(0)
    override val bitrateKbps: StateFlow<Int> = _bitrateKbps.asStateFlow()

    private val _health = MutableStateFlow(ConnectionHealth.Good)
    override val health: StateFlow<ConnectionHealth> = _health.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    override val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val bitrateMeter = BitrateMeter()
    private val queueCapacity = 256   // mirrors native MediaQueue capacity

    private var streamer: RtmpStreamer? = null
    private var pollJob: Job? = null
    @Volatile private var mediaStarted = false
    @Volatile private var userWantsLive = false

    /** Lets the media-setup lambda publish the encoder surface back to the viewfinder. */
    fun publishEncoderSurface(s: Surface?) { _encoderSurface.value = s }

    /** Lets the media-setup lambda forward AudioEncoder.onLevel into the engine's flow. */
    fun publishAudioLevel(level: Float) { _audioLevel.value = level }

    override fun start(config: StreamConfig) {
        val endpoint = runCatching { RtmpEndpoint.parse(config.rtmpUrl, config.streamKey) }
            .getOrElse { _state.value = StreamState.Error(it.message ?: "Bad URL"); return }

        userWantsLive = true
        val quality = config.quality
        val requested = resolveRequest(
            config.codecOverride, hevcEncoder, hevcMain10, cameraHdr, config.hdrEnabled,
        )

        pollJob = scope.launch {
            // Reconnect loop: each iteration is one full connect attempt with a fresh streamer +
            // media pipeline (Moblin-style full restart). A transient Dropped → wait 5s → retry
            // forever while the user wants to be live; a server Rejected is terminal. A user
            // stop() cancels this job (interrupting the backoff delay) and tears down itself.
            while (userWantsLive) {
                mediaStarted = false
                _state.value = StreamState.Connecting
                val s = streamerFactory().also { streamer = it }
                s.start(endpoint, requested.codec, width, height, fps, sampleRate)

                val outcome = runSession(s, requested, quality, config.recordWhileStreaming)

                // Per-attempt teardown (mirror of stop()'s media/flow cleanup, minus job cancel).
                if (mediaStarted) stopMedia()
                mediaStarted = false
                _encoderSurface.value = null
                _activeHdr.value = false
                _bitrateKbps.value = 0
                _health.value = ConnectionHealth.Good
                _audioLevel.value = 0f
                s.stop(); streamer = null

                when (outcome) {
                    Outcome.Rejected -> { userWantsLive = false; _state.value = StreamState.Error("Stream rejected") }
                    Outcome.Dropped -> {
                        if (!userWantsLive) break
                        _state.value = StreamState.Reconnecting
                        delay(reconnectDelayMs)      // cancellable: stop() aborts the wait
                    }
                }
            }
        }
    }

    /** Polls native state for one connect attempt; returns why it ended. */
    private suspend fun runSession(s: RtmpStreamer, requested: VideoFormat, quality: VideoQuality, record: Boolean): Outcome {
        while (userWantsLive) {
            when (s.state()) {
                2 -> {
                    if (!mediaStarted) {
                        mediaStarted = true
                        val negotiated = s.negotiatedCodec()
                        val actual = if (negotiated == VideoCodecType.HEVC) requested
                                     else VideoFormat(VideoCodecType.AVC, main10 = false, DynamicRange.SDR)
                        startMedia(s, actual, quality, record)
                        _activeHdr.value = actual.dynamicRange == DynamicRange.HLG10
                    }
                    _state.value = StreamState.Live
                    val kbps = bitrateMeter.update(s.bytesSent(), System.currentTimeMillis())
                    _bitrateKbps.value = kbps
                    _health.value = deriveHealth(
                        queueDepth = s.queueDepth(),
                        queueCapacity = queueCapacity,
                        actualKbps = kbps,
                        targetKbps = quality.videoBitrate / 1000,
                    )
                }
                3 -> return Outcome.Dropped       // native Dropped (transient)
                4 -> return Outcome.Rejected      // native Rejected (terminal)
                // 0 (Idle) / 1 (Connecting) -> keep polling
            }
            delay(pollIntervalMs)
        }
        return Outcome.Dropped                    // userWantsLive cleared mid-poll (user stop)
    }

    override fun stop() {
        userWantsLive = false
        _state.value = StreamState.Stopping
        pollJob?.cancel(); pollJob = null        // also interrupts a pending reconnect delay()
        if (mediaStarted) stopMedia()
        mediaStarted = false
        _encoderSurface.value = null
        _activeHdr.value = false
        _bitrateKbps.value = 0
        _health.value = ConnectionHealth.Good
        _audioLevel.value = 0f
        streamer?.stop(); streamer = null
        _state.value = StreamState.Idle
    }

    private enum class Outcome { Dropped, Rejected }

    fun dispose() {
        stop()
        scope.cancel()
    }
}
