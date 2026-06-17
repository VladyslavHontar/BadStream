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
    // The scheme lets the owner pick the native impl: RTMP -> NativeRtmpStreamer, SRT -> NativeSrtStreamer.
    private val streamerFactory: (EndpointScheme) -> RtmpStreamer,
    // record (4th arg) = whether this go-live should also record locally (Settings.recordWhileStreaming).
    private val startMedia: (RtmpStreamer, VideoFormat, VideoQuality, Boolean) -> Unit,
    private val stopMedia: () -> Unit,
    // Session lifecycle (once per go-live / full stop), invoked from the foreground start()/stop().
    // The foreground service must start here — NOT in startMedia, which re-runs on every reconnect
    // attempt and would call startForegroundService() from the background (Android 12+ crash).
    private val onLive: () -> Unit = {},
    private val onStopped: () -> Unit = {},
    // Applies an ABR-chosen encoder bitrate (bps) at runtime. No-op by default (RTMP / tests).
    private val applyBitrate: (Int) -> Unit = {},
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
        // SRT endpoints carry latency/streamid from settings (not the URL); RTMP delegates to its
        // existing parser. A bad URL surfaces as Error and aborts before the reconnect loop.
        val endpoint = runCatching {
            when (Endpoint.schemeOf(config.rtmpUrl)) {
                EndpointScheme.RTMP -> Endpoint.parse(config.rtmpUrl, config.streamKey)
                EndpointScheme.SRT -> {
                    val base = Endpoint.parse(config.rtmpUrl, config.streamKey) as Endpoint.Srt
                    // URL query wins for streamid/latency when present; else fall back to settings.
                    Endpoint.Srt(
                        host = base.host,
                        port = base.port,
                        streamid = base.streamid.ifEmpty { config.srtStreamId },
                        latencyMs = if (base.latencyMs != Endpoint.DEFAULT_SRT_LATENCY_MS) base.latencyMs else config.srtLatencyMs,
                    )
                }
            }
        }.getOrElse { _state.value = StreamState.Error(it.message ?: "Bad URL"); return }

        val scheme = endpoint.scheme
        val abr = AbrParams(
            enabled = scheme == EndpointScheme.SRT && config.abrEnabled,
            minBps = config.abrMinKbps * 1000,
            targetBps = config.abrTargetKbps * 1000,
            maxBps = config.abrMaxKbps * 1000,
        )

        userWantsLive = true
        onLive()   // foreground-safe: start() is the user's go-live tap. Survives reconnects.
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
                val s = streamerFactory(scheme).also { streamer = it }
                s.start(endpoint, requested.codec, width, height, fps, sampleRate, abr)

                val outcome = runSession(s, requested, quality, config.recordWhileStreaming, abr)

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
    private suspend fun runSession(
        s: RtmpStreamer, requested: VideoFormat, quality: VideoQuality, record: Boolean, abr: AbrParams,
    ): Outcome {
        var lastAppliedBps = -1   // per-attempt: only re-apply when the target actually moves
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
                    // ABR (SRT only): poll the session's chosen target, clamp to [min,max], and
                    // apply to the encoder only when it changed (rate-limited by the poll tick).
                    if (abr.enabled) {
                        val target = clampBitrate(s.targetBitrate(), abr)
                        if (target > 0 && target != lastAppliedBps) {
                            applyBitrate(target)
                            lastAppliedBps = target
                        }
                    }
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
        onStopped()   // release the foreground service for the whole session (not per reconnect)
        _state.value = StreamState.Idle
    }

    private enum class Outcome { Dropped, Rejected }

    fun dispose() {
        stop()
        scope.cancel()
    }

    companion object {
        /** Clamp an ABR-proposed bitrate (bps) to the configured [AbrParams] bounds. */
        fun clampBitrate(bps: Int, abr: AbrParams): Int =
            bps.coerceIn(abr.minBps, maxOf(abr.minBps, abr.maxBps))
    }
}
