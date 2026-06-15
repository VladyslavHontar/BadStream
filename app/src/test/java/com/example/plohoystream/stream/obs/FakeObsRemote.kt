package com.example.plohoystream.stream.obs

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeObsRemote : ObsRemote {
    override val connected = MutableStateFlow(false)
    override val scenes = MutableStateFlow<List<String>>(emptyList())
    override val currentScene = MutableStateFlow<String?>(null)
    override val obsStreaming = MutableStateFlow(false)

    val switchedScenes = mutableListOf<String>()
    var startStreamCalled = false
    var stopStreamCalled = false

    override fun switchScene(name: String) { switchedScenes.add(name) }
    override fun startObsStream() { startStreamCalled = true }
    override fun stopObsStream() { stopStreamCalled = true }
}
