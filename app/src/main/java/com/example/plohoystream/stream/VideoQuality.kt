package com.example.plohoystream.stream

/** Encoder/egress quality applied at go-live (no mid-stream reconfig). */
data class VideoQuality(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val videoBitrate: Int = 6_000_000,
    val audioBitrate: Int = 128_000,
) {
    companion object {
        val Default = VideoQuality()
        val Presets = listOf(
            VideoQuality(1280, 720, 30, 3_500_000, 128_000),
            VideoQuality(1920, 1080, 30, 6_000_000, 128_000),
            VideoQuality(1920, 1080, 60, 9_000_000, 128_000),
        )
    }
}
