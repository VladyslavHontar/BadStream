package com.example.plohoystream.stream

/** The egress seam the engine drives. Native impl crosses JNI; fake impl backs unit tests. */
interface RtmpStreamer {
    /** Native session state: 0=Idle, 1=Connecting, 2=Live, 3=Error. */
    fun start(endpoint: RtmpEndpoint, width: Int, height: Int, fps: Int, sampleRate: Int)
    fun state(): Int
    fun sendVideoConfig(csd: ByteArray)
    fun sendVideo(annexb: ByteArray, keyframe: Boolean, ptsMs: Long, dtsMs: Long)
    fun sendAudioConfig(sampleRate: Int, channels: Int)
    fun sendAudio(aac: ByteArray, ptsMs: Long)
    fun stop()
}
