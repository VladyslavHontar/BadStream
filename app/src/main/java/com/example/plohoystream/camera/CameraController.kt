package com.example.plohoystream.camera

import android.view.Surface

/**
 * Drives a single live camera session feeding one preview [Surface].
 *
 * Lens switching on a modern logical multi-camera is just a zoom-ratio jump (the
 * framework hands off to the ultrawide/tele physical sensor under the hood), so
 * [setLens] defaults to [setZoom]. Flipping front/back is a different physical
 * camera, so it is a fresh [start] with the other facing's config — owned by the
 * caller, not this interface.
 */
interface CameraController {

    /** Open [config]'s camera and start previewing into [previewSurface]. Safe to call again to switch cameras. */
    fun start(config: CameraConfig, previewSurface: Surface)

    /** Tear down the current session and close the device. */
    fun stop()

    /** Apply a zoom ratio (clamped to the active camera's supported range). */
    fun setZoom(ratio: Float)

    /** Jump to a lens preset — on a logical multi-camera this is a zoom-ratio change. */
    fun setLens(lens: CameraLens) = setZoom(lens.zoomRatio)
}
