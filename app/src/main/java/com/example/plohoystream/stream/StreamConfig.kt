package com.example.plohoystream.stream

/** Egress target for M1-B. Resolution/bitrate/codec are auto-selected natively (M1-B.3). */
data class StreamConfig(
    val rtmpUrl: String,   // e.g. "rtmp://live.twitch.tv/app"
    val streamKey: String,
)
