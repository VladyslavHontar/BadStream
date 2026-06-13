package com.example.plohoystream.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory engine: drives states for tests, @Preview, and the M1-B.1 app shell. */
class FakeStreamEngine : StreamEngine {
    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    override val state: StateFlow<StreamState> = _state.asStateFlow()

    var lastConfig: StreamConfig? = null
        private set

    override fun start(config: StreamConfig) {
        lastConfig = config
        _state.value = StreamState.Connecting
    }

    override fun stop() {
        _state.value = StreamState.Stopping
        _state.value = StreamState.Idle
    }

    // Test/preview controls to drive transitions a real engine would make over time.
    fun emitLive() { _state.value = StreamState.Live }
    fun emitError(reason: String) { _state.value = StreamState.Error(reason) }
    fun emitIdle() { _state.value = StreamState.Idle }
}
