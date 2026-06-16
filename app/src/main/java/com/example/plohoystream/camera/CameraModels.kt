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

enum class ExposureMode { AUTO, MANUAL }

/**
 * Snapshot of the manual-exposure controls for the viewfinder. [supported] is false until a camera
 * that exposes manual sensor control is bound; the panel hides then. In [ExposureMode.MANUAL] the
 * shutter (sensor exposure time, ns) is fixed for motion blur and brightness is set by [iso] —
 * either by the user (when [autoIso] is false) or by the shutter-priority metering loop (when true).
 */
data class ExposureState(
    val supported: Boolean = false,
    val mode: ExposureMode = ExposureMode.AUTO,
    val shutterRangeNs: LongRange = 0L..0L,
    val isoRange: IntRange = 0..0,
    val shutterNs: Long = 0L,
    val iso: Int = 0,
    val autoIso: Boolean = true,
)

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
