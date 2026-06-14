package com.example.plohoystream.stream

import android.view.Surface
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

/**
 * A [StreamEngine] that also produces an encoder input [Surface] when live, so the camera
 * can render into the H.264 encoder. Null when not streaming.
 */
interface VideoStreamEngine : StreamEngine {
    val encoderSurface: StateFlow<Surface?>
}
