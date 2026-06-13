package com.example.plohoystream.stream

sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data object Live : StreamState
    data object Stopping : StreamState
    data class Error(val reason: String) : StreamState
}
