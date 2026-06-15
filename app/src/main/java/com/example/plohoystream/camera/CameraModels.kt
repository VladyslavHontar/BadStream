package com.example.plohoystream.camera

enum class Facing { BACK, FRONT }

data class Resolution(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

data class CameraLens(val label: String, val zoomRatio: Float)

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
    /** CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES. */
    val fpsRanges: List<android.util.Range<Int>> = emptyList(),
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
    /** Desired capture frame rate plumbed from the quality settings. */
    val targetFps: Int = 30,
    /** AE target fps range chosen for [targetFps], or null to leave the camera default. */
    val fpsRange: android.util.Range<Int>? = null,
)
