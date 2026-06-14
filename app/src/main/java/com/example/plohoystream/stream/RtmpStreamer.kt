package com.example.plohoystream.stream

/** The egress seam the engine drives. Native impl crosses JNI; fake impl backs unit tests. */
interface RtmpStreamer {
    fun start(endpoint: RtmpEndpoint, codec: VideoCodecType, width: Int, height: Int, fps: Int, sampleRate: Int)
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
    fun sendVideoConfig(csd: ByteArray)
    fun sendVideo(annexb: ByteArray, keyframe: Boolean, ptsMs: Long, dtsMs: Long)
    fun sendAudioConfig(sampleRate: Int, channels: Int)
    fun sendAudio(aac: ByteArray, ptsMs: Long)
    fun stop()
}
