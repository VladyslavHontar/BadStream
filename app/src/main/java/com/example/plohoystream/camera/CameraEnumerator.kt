package com.example.plohoystream.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.SurfaceHolder

/**
 * Reads the device's cameras into the framework-free [CameraInfo] model that
 * [CameraCapabilities.select] reasons over.
 *
 * Lens presets are derived device-agnostically from the zoom range (ultrawide at the
 * min ratio, 1× main, 2× tele) and then filtered to what the camera actually supports
 * by [CameraCapabilities]. Per-physical focal-length derivation is intentionally avoided
 * here: it is fragile across vendors, whereas zoom-ratio presets work everywhere and map
 * straight onto CONTROL_ZOOM_RATIO.
 */
object CameraEnumerator {

    fun enumerate(context: Context): List<CameraInfo> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return manager.cameraIdList.mapNotNull { id ->
            val c = manager.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> Facing.FRONT
                CameraCharacteristics.LENS_FACING_BACK -> Facing.BACK
                else -> return@mapNotNull null // skip external/unknown
            }

            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
            val isLogical = caps.contains(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA,
            )

            val zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val minZoom = zoomRange?.lower ?: 1.0f
            val maxZoom = zoomRange?.upper
                ?: c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f

            val oisModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?: IntArray(0)
            val hasOis = oisModes.any {
                it == CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON
            }

            val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceHolder::class.java)
                ?.map { Resolution(it.width, it.height) }
                .orEmpty()

            val sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            val fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.toList()
                .orEmpty()

            val lensRatios = listOf(minZoom, 1.0f, 2.0f).filter { it in minZoom..maxZoom }

            val supportsHdr = if (android.os.Build.VERSION.SDK_INT >= 33) {
                val profiles = c.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                profiles?.supportedProfiles?.contains(
                    android.hardware.camera2.params.DynamicRangeProfiles.HLG10
                ) == true
            } else false

            CameraInfo(
                id = id,
                facing = facing,
                isLogical = isLogical,
                minZoom = minZoom,
                maxZoom = maxZoom,
                lensRatios = lensRatios,
                outputSizes = sizes,
                hasOis = hasOis,
                supportsHdr = supportsHdr,
                sensorOrientation = sensorOrientation,
                fpsRanges = fpsRanges,
            )
        }
    }
}
