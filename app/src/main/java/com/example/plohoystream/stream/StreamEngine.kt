package com.example.plohoystream.stream

import kotlinx.coroutines.flow.StateFlow

/**
 * What the UI/ViewModel depend on. Real impl (CameraStreamEngine) arrives in M1-B.3;
 * live camera controls (zoom/lens/flip) are added to this interface in M1-B.2.
 */
interface StreamEngine {
    val state: StateFlow<StreamState>
    fun start(config: StreamConfig)
    fun stop()
}
