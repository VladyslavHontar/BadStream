package com.example.plohoystream.stream

/** In-memory [RtmpStreamer] for engine unit tests. Drives state manually. */
class FakeRtmpStreamer : RtmpStreamer {
    var started = false; private set
    var stopped = false; private set
    var videoConfigCount = 0; private set
    var videoCount = 0; private set
    var audioConfigCount = 0; private set
    var audioCount = 0; private set
    var requestedCodec: VideoCodecType = VideoCodecType.AVC; private set
    var negotiatedCodecValue: VideoCodecType = VideoCodecType.AVC
    private var state = 0

    fun emitState(s: Int) { state = s }

    override fun start(endpoint: RtmpEndpoint, codec: VideoCodecType, width: Int, height: Int, fps: Int, sampleRate: Int) {
        requestedCodec = codec; negotiatedCodecValue = codec; started = true; state = 1 // Connecting
    }
    override fun state(): Int = state
    override fun negotiatedCodec(): VideoCodecType = negotiatedCodecValue
    override fun sendVideoConfig(csd: ByteArray) { videoConfigCount++ }
    override fun sendVideo(annexb: ByteArray, keyframe: Boolean, ptsMs: Long, dtsMs: Long) { videoCount++ }
    override fun sendAudioConfig(sampleRate: Int, channels: Int) { audioConfigCount++ }
    override fun sendAudio(aac: ByteArray, ptsMs: Long) { audioCount++ }
    override fun stop() { stopped = true; state = 0 }
}
