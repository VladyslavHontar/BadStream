package com.example.plohoystream.stream

/** Egress target + encoder settings applied at go-live (no mid-stream reconfig). */
data class StreamConfig(
    val rtmpUrl: String,   // e.g. "rtmp://live.twitch.tv/app" or "srt://host:port?streamid=…"
    val streamKey: String,
    val hdrEnabled: Boolean = false,
    val quality: VideoQuality = VideoQuality.Default,
    val codecOverride: CodecOverride = CodecOverride.Auto,
    val recordWhileStreaming: Boolean = false,
    // --- SRT (only consulted when the URL scheme is srt://). ---
    val srtLatencyMs: Int = 2000,
    val srtStreamId: String = "",
    val abrEnabled: Boolean = true,
    val abrMinKbps: Int = 1000,
    val abrTargetKbps: Int = 6000,
    val abrMaxKbps: Int = 12000,
)
