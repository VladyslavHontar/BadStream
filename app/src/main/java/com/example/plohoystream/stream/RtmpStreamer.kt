package com.example.plohoystream.stream

/** Adaptive-bitrate bounds (bps) handed to an SRT egress session. RTMP ignores it. */
data class AbrParams(
    val enabled: Boolean = false,
    val minBps: Int = 1_000_000,
    val targetBps: Int = 6_000_000,
    val maxBps: Int = 12_000_000,
)

/**
 * The egress seam the engine drives. Native impls cross JNI ([NativeRtmpStreamer] over RTMP,
 * [NativeSrtStreamer] over SRT); the fake impl backs unit tests. The engine selects the impl
 * from the endpoint scheme and drives either identically.
 *
 * Named historically after RTMP; it is the shared egress interface for all schemes.
 */
interface RtmpStreamer {
    /** Start the session for [endpoint]. [abr] only affects SRT (RTMP ignores it). */
    fun start(
        endpoint: Endpoint,
        codec: VideoCodecType,
        width: Int,
        height: Int,
        fps: Int,
        sampleRate: Int,
        abr: AbrParams = AbrParams(),
    )
    /**
     * Native session state integer (see SessionState in stream_session.h):
     * 0=Idle, 1=Connecting, 2=Live, 3=Dropped (transient — engine reconnects),
     * 4=Rejected (terminal — server refused).
     */
    fun state(): Int
    /** The codec the server agreed to after the enhanced-RTMP connect handshake (valid once Live). */
    fun negotiatedCodec(): VideoCodecType
    /** Total bytes written to the socket since session start (smoothed into kbps by the engine). */
    fun bytesSent(): Long
    /** Pending egress queue depth — the backpressure signal for connection health. */
    fun queueDepth(): Int
    /** The latest ABR target encoder bitrate (bps), or 0 if not applicable (RTMP). SRT only. */
    fun targetBitrate(): Int = 0
    fun sendVideoConfig(csd: ByteArray)
    fun sendVideo(annexb: ByteArray, keyframe: Boolean, ptsMs: Long, dtsMs: Long)
    fun sendAudioConfig(sampleRate: Int, channels: Int)
    fun sendAudio(aac: ByteArray, ptsMs: Long)
    fun stop()
}
