package com.example.plohoystream.camera

import java.util.Locale
import kotlin.math.abs

object CameraCapabilities {
    private val TARGET = Resolution(1920, 1080)

    fun select(cameras: List<CameraInfo>, facing: Facing = Facing.BACK): CameraConfig? {
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
        )
    }

    private fun chooseSize(sizes: List<Resolution>): Resolution =
        sizes.minByOrNull { abs(it.pixels - TARGET.pixels) } ?: TARGET

    private fun buildLenses(cam: CameraInfo): List<CameraLens> {
        val ratios = (cam.lensRatios + 1.0f)
            .filter { it in cam.minZoom..cam.maxZoom }
            .distinct().sorted().ifEmpty { listOf(1.0f) }
        return ratios.map { CameraLens(formatRatio(it), it) }
    }

    private fun formatRatio(r: Float): String =
        if (r == r.toInt().toFloat()) "${r.toInt()}×" else String.format(Locale.US, "%.1f×", r)
}
