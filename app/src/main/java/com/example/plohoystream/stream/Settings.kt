package com.example.plohoystream.stream

import kotlinx.serialization.Serializable

/**
 * Single source of truth for all PERSISTED user settings. Every field here is saved
 * automatically (kotlinx-serialization covers all properties). To add a persisted setting,
 * add a field WITH A DEFAULT here — nothing else in the persistence layer changes. A field of
 * a non-serializable type is a COMPILE ERROR until made serializable or marked @Transient.
 */
@Serializable
data class Settings(
    val rtmpUrl: String = "",
    val streamKey: String = "",
    val quality: VideoQuality = VideoQuality.Default,
    val codecOverride: CodecOverride = CodecOverride.Auto,
    val hdrEnabled: Boolean = false,
    val recordWhileStreaming: Boolean = false,
    val obsHost: String = "",
    val obsPort: Int = 4455,
    val obsPassword: String = "",
    val obsMainSceneName: String = "",
    val obsBrbSceneName: String = "",
    val obsAutoSwitchEnabled: Boolean = false,
    // --- SRT egress (used when the destination URL is srt://). ---
    val srtLatencyMs: Int = 2000,
    val srtStreamId: String = "",
    val abrEnabled: Boolean = true,
    val abrMinKbps: Int = 1000,
    val abrTargetKbps: Int = 6000,
    val abrMaxKbps: Int = 12000,
)
