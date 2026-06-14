package com.example.plohoystream.stream

import com.example.plohoystream.ui.settings.SettingsRoute

data class StreamUiState(
    val url: String = "",
    val key: String = "",
    val stream: StreamState = StreamState.Idle,
    val hdrEnabled: Boolean = false,
    val hdrAvailable: Boolean = false,
    // Live stats (real signals from the engine).
    val bitrateKbps: Int = 0,
    val health: ConnectionHealth = ConnectionHealth.Good,
    val audioLevel: Float = 0f,
    val elapsed: String = "00:00",
    // Settings mirror (applied at go-live).
    val quality: VideoQuality = VideoQuality.Default,
    val codecOverride: CodecOverride = CodecOverride.Auto,
    // Settings panel nav.
    val panelOpen: Boolean = false,
    val settingsRoute: SettingsRoute = SettingsRoute.Root,
) {
    val canGoLive: Boolean
        get() = url.isNotBlank() && key.isNotBlank() &&
            (stream is StreamState.Idle || stream is StreamState.Error)

    val isActive: Boolean
        get() = stream is StreamState.Connecting ||
            stream is StreamState.Live ||
            stream is StreamState.Stopping

    /** The signature preview-shrink is driven by this. */
    val settingsOpen: Boolean get() = panelOpen
}
