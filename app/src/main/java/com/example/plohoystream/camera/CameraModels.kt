package com.example.plohoystream.camera

enum class Facing { BACK, FRONT }

data class Resolution(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

data class CameraLens(val label: String, val zoomRatio: Float)

/**
 * A selectable physical lens of the active logical camera (ultrawide / main / tele). [physicalId]
 * is the Camera2 id used to bind it; [zoomRatio] is its intrinsic zoom relative to the main lens
 * (≈0.6 ultrawide, 1.0 main, ≥2.0 tele) and drives the [label] (e.g. "0.6×").
 */
data class LensOption(val label: String, val physicalId: String, val zoomRatio: Float)

data class CameraInfo(
    val id: String,
    val facing: Facing,
    val isLogical: Boolean,
    val minZoom: Float,
    val maxZoom: Float,
    val lensRatios: List<Float>,
    val outputSizes: List<Resolution>,
    val hasOis: Boolean,
    val supportsHdr: Boolean = false,
    /** CameraCharacteristics.SENSOR_ORIENTATION (degrees: 0/90/180/270). */
    val sensorOrientation: Int = 0,
)

data class CameraConfig(
    val cameraId: String,
    val facing: Facing,
    val previewSize: Resolution,
    val minZoom: Float,
    val maxZoom: Float,
    val lenses: List<CameraLens>,
    val hasOis: Boolean,
    val supportsHdr: Boolean = false,
    /** CameraCharacteristics.SENSOR_ORIENTATION (degrees: 0/90/180/270). */
    val sensorOrientation: Int = 0,
    /**
     * Desired capture frame rate plumbed from the quality settings. The CameraX backend maps
     * `targetFps >= 60` to the `FPS_60` Feature Group when binding.
     */
    val targetFps: Int = 30,
)
