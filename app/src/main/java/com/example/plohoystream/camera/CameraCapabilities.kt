package com.example.plohoystream.camera

import java.util.Locale
import kotlin.math.abs

object CameraCapabilities {
    private val TARGET = Resolution(1920, 1080)

    fun select(
        cameras: List<CameraInfo>,
        facing: Facing = Facing.BACK,
        targetFps: Int = 30,
    ): CameraConfig? {
        if (cameras.isEmpty()) return null
        val pool = cameras.filter { it.facing == facing }.ifEmpty { cameras }
        val cam = pool.firstOrNull { it.isLogical } ?: pool.first()
        return CameraConfig(
            cameraId = cam.id,
            facing = cam.facing,
            previewSize = chooseSize(cam.outputSizes),
            minZoom = cam.minZoom,
            maxZoom = cam.maxZoom,
            lenses = buildLenses(cam),
            hasOis = cam.hasOis,
            supportsHdr = cam.supportsHdr,
            sensorOrientation = cam.sensorOrientation,
            targetFps = targetFps,
            fpsRange = chooseFpsRange(cam.fpsRanges, targetFps),
        )
    }

    private fun chooseSize(sizes: List<Resolution>): Resolution =
        sizes.minByOrNull { abs(it.pixels - TARGET.pixels) } ?: TARGET

    /**
     * Pick the AE target fps range that best matches [targetFps]:
     *  1. an exact `[fps, fps]` range (fixed cadence — best for streaming),
     *  2. else the range whose `upper == fps` with the highest `lower`,
     *  3. else the range with the highest `upper <= fps`,
     *  4. else null (leave the camera default — keeps ~30fps working as before).
     */
    internal fun chooseFpsRange(
        ranges: List<android.util.Range<Int>>,
        targetFps: Int,
    ): android.util.Range<Int>? {
        if (ranges.isEmpty()) return null
        ranges.firstOrNull { it.lower == targetFps && it.upper == targetFps }?.let { return it }
        ranges.filter { it.upper == targetFps }.maxByOrNull { it.lower }?.let { return it }
        return ranges.filter { it.upper <= targetFps }.maxByOrNull { it.upper }
    }

    private fun buildLenses(cam: CameraInfo): List<CameraLens> {
        val ratios = (cam.lensRatios + 1.0f)
            .filter { it in cam.minZoom..cam.maxZoom }
            .distinct().sorted().ifEmpty { listOf(1.0f) }
        return ratios.map { CameraLens(formatRatio(it), it) }
    }

    private fun formatRatio(r: Float): String =
        if (r == r.toInt().toFloat()) "${r.toInt()}×" else String.format(Locale.US, "%.1f×", r)
}
