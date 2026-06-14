package com.example.plohoystream.stream

/** The egress seam the engine drives. Native impl crosses JNI; fake impl backs unit tests. */
interface RtmpStreamer {
    /** Native session state: 0=Idle, 1=Connecting, 2=Live, 3=Error. */
    fun start(endpoint: RtmpEndpoint, codec: VideoCodecType, width: Int, height: Int, fps: Int, sampleRate: Int)
    fun state(): Int
    /** The codec the server agreed to after the enhanced-RTMP connect handshake (valid once Live). */
    fun negotiatedCodec(): VideoCodecType
    fun sendVideoConfig(csd: ByteArray)
    fun sendVideo(annexb: ByteArray, keyframe: Boolean, ptsMs: Long, dtsMs: Long)
    fun sendAudioConfig(sampleRate: Int, channels: Int)
    fun sendAudio(aac: ByteArray, ptsMs: Long)
    fun stop()
}
