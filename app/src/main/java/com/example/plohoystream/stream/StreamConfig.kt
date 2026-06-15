package com.example.plohoystream.stream

/** Egress target + encoder settings applied at go-live (no mid-stream reconfig). */
data class StreamConfig(
    val rtmpUrl: String,   // e.g. "rtmp://live.twitch.tv/app"
    val streamKey: String,
    val hdrEnabled: Boolean = false,
    val quality: VideoQuality = VideoQuality.Default,
    val codecOverride: CodecOverride = CodecOverride.Auto,
    val recordWhileStreaming: Boolean = false,
)
