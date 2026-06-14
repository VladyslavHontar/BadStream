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
    /** True only while streaming with a resolved HLG10/HDR format (after codec negotiation). */
    val activeHdr: StateFlow<Boolean>
    /** Smoothed egress bitrate in kbps (0 when not streaming). */
    val bitrateKbps: StateFlow<Int>
    /** Connection health derived from queue backpressure + actual-vs-target bitrate. */
    val health: StateFlow<ConnectionHealth>
    /** Normalized 0..1 microphone level (0 when not streaming). */
    val audioLevel: StateFlow<Float>
}
