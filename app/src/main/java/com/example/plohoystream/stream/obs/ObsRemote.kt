package com.example.plohoystream.stream.obs

import kotlinx.coroutines.flow.StateFlow

/**
 * Minimal interface for OBS remote control. Allows the ViewModel + tests to depend on this
 * abstraction rather than the concrete OkHttp-based controller.
 */
interface ObsRemote {
    val connected: StateFlow<Boolean>
    val scenes: StateFlow<List<String>>
    val currentScene: StateFlow<String?>
    val obsStreaming: StateFlow<Boolean>

    fun switchScene(name: String)
    fun startObsStream()
    fun stopObsStream()
}
