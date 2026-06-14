package com.example.plohoystream.stream

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory engine: drives states/stats for tests, @Preview, and the app shell. */
class FakeStreamEngine : VideoStreamEngine {
    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    override val state: StateFlow<StreamState> = _state.asStateFlow()

    override val encoderSurface: StateFlow<Surface?> = MutableStateFlow<Surface?>(null)
    override val activeHdr: StateFlow<Boolean> = MutableStateFlow(false)

    private val _bitrateKbps = MutableStateFlow(0)
    override val bitrateKbps: StateFlow<Int> = _bitrateKbps.asStateFlow()
    private val _health = MutableStateFlow(ConnectionHealth.Good)
    override val health: StateFlow<ConnectionHealth> = _health.asStateFlow()
    private val _audioLevel = MutableStateFlow(0f)
    override val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

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

    // Test/preview controls.
    fun emitLive() { _state.value = StreamState.Live }
    fun emitError(reason: String) { _state.value = StreamState.Error(reason) }
    fun emitIdle() { _state.value = StreamState.Idle }
    fun emitReconnecting() { _state.value = StreamState.Reconnecting }
    fun emitBitrate(kbps: Int) { _bitrateKbps.value = kbps }
    fun emitHealth(h: ConnectionHealth) { _health.value = h }
    fun emitAudioLevel(level: Float) { _audioLevel.value = level }
}
