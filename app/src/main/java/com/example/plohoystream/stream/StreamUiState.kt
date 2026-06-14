package com.example.plohoystream.stream

data class StreamUiState(
    val url: String = "",
    val key: String = "",
    val stream: StreamState = StreamState.Idle,
    val hdrEnabled: Boolean = false,
    val hdrAvailable: Boolean = false,
) {
    val canGoLive: Boolean
        get() = url.isNotBlank() && key.isNotBlank() &&
            (stream is StreamState.Idle || stream is StreamState.Error)

    val isActive: Boolean
        get() = stream is StreamState.Connecting ||
            stream is StreamState.Live ||
            stream is StreamState.Stopping
}
