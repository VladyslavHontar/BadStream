package com.example.plohoystream.camera

import android.view.Surface

interface CameraController {
    /**
     * Open [config]'s camera and stream into every surface in [targets] (preview, encoder, …).
     * When [hdr] is true the session outputs are configured for HLG10 dynamic range.
     */
    fun start(config: CameraConfig, targets: List<Surface>, hdr: Boolean = false)
    fun stop()
    fun setZoom(ratio: Float)
    fun setLens(lens: CameraLens) = setZoom(lens.zoomRatio)
}
