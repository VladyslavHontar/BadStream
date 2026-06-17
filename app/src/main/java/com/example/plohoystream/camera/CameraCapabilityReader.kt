package com.example.plohoystream.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Builds the pure [ConcurrentCameraCapabilities] from the platform: the certified concurrent-camera
 * combinations ([CameraManager.getConcurrentCameraIds]) plus per-camera facing / focal / zoom. Back
 * lens [BackLens.ratio] is focal-relative to the 1x main (the back camera with the median focal),
 * approximating the chips' 0.8/1/1.8x. Runs blocking system calls — call off the main thread.
 */
object CameraCapabilityReader {
    private const val TAG = "CameraCapabilityReader"

    fun read(context: Context): ConcurrentCameraCapabilities {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        data class Cam(val id: String, val facing: Int?, val focal: Float?, val minZoom: Float, val maxZoom: Float)
        val cams = mgr.cameraIdList.map { id ->
            val c = mgr.getCameraCharacteristics(id)
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            Cam(id, c.get(CameraCharacteristics.LENS_FACING), focal, zr?.lower ?: 1f, zr?.upper ?: 1f)
        }
        val backs = cams.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK && it.focal != null }
        val fronts = cams.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }.map { it.id }
        // 1x reference = the median back focal (the "main"); ratio = focal / reference.
        val reference = backs.map { it.focal!! }.sorted().getOrNull(backs.size / 2) ?: 1f
        val backLenses = backs.map { BackLens(it.id, ratio = it.focal!! / reference, minZoom = it.minZoom, maxZoom = it.maxZoom) }
        val concurrentSets = runCatching { mgr.concurrentCameraIds }.getOrDefault(emptySet())
        val caps = ConcurrentCameraCapabilities(backLenses, fronts, concurrentSets)
        Log.i(TAG, "concurrentSets=$concurrentSets backLenses=$backLenses fronts=$fronts supportsDual=${caps.supportsDual()}")
        return caps
    }
}
