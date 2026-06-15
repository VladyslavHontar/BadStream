package com.example.plohoystream.stream

import com.example.plohoystream.ui.settings.SettingsRoute

data class StreamUiState(
    // All persisted user settings (single source of truth).
    val settings: Settings = Settings(),
    val stream: StreamState = StreamState.Idle,
    val hdrAvailable: Boolean = false,
    // Live stats (real signals from the engine).
    val bitrateKbps: Int = 0,
    val health: ConnectionHealth = ConnectionHealth.Good,
    val audioLevel: Float = 0f,
    val elapsed: String = "00:00",
    // Settings panel nav.
    val panelOpen: Boolean = false,
    val settingsRoute: SettingsRoute = SettingsRoute.Root,
    // OBS remote state
    val obsConnected: Boolean = false,
    val obsScenes: List<String> = emptyList(),
    val obsCurrentScene: String? = null,
    val obsStreaming: Boolean = false,
) {
    val canGoLive: Boolean
        get() {
            val idle = stream is StreamState.Idle || stream is StreamState.Error
            // SRT carries identity in the URL (streamid); only RTMP requires a separate stream key.
            val isSrt = settings.rtmpUrl.startsWith("srt://")
            val haveTarget = settings.rtmpUrl.isNotBlank() && (isSrt || settings.streamKey.isNotBlank())
            return idle && haveTarget
        }

    val isActive: Boolean
        get() = stream is StreamState.Connecting ||
            stream is StreamState.Live ||
            stream is StreamState.Reconnecting ||
            stream is StreamState.Stopping

    /** The signature preview-shrink is driven by this. */
    val settingsOpen: Boolean get() = panelOpen
}
