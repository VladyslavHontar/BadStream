package com.example.plohoystream.camera

import android.view.Surface

interface CameraController {
    /** Open [config]'s camera and stream into every surface in [targets] (preview, encoder, …). */
    fun start(config: CameraConfig, targets: List<Surface>)
    fun stop()
    fun setZoom(ratio: Float)
    fun setLens(lens: CameraLens) = setZoom(lens.zoomRatio)
}
