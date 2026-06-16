package com.example.plohoystream.camera

import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider

/**
 * Decides whether the device can run a FRONT + BACK camera simultaneously, from CameraX's reported
 * concurrent-camera combinations. CameraX guarantees each concurrent stream up to 720p, so dual mode
 * caps per-camera capture at [MAX_DUAL_HEIGHT]; many devices report no concurrent combinations at
 * all, in which case dual mode is unavailable.
 */
object ConcurrentCameraProbe {
    /** CameraX concurrent-mode per-camera resolution guarantee (height, px). */
    const val MAX_DUAL_HEIGHT = 720

    /** True if any reported combination contains both a FRONT and a BACK camera. Pure + testable. */
    fun supportsFrontBack(combos: List<List<Facing>>): Boolean =
        combos.any { it.contains(Facing.FRONT) && it.contains(Facing.BACK) }

    /**
     * Map CameraX's concurrent combinations to plain [Facing] lists. Thin CameraX wrapper (not unit
     * tested); feed its result to [supportsFrontBack]. Runs the blocking provider read on the
     * caller's thread — call it off the main thread.
     */
    fun facingCombos(provider: ProcessCameraProvider): List<List<Facing>> =
        provider.availableConcurrentCameraInfos.map { combo ->
            combo.mapNotNull { info ->
                when (info.lensFacing) {
                    CameraSelector.LENS_FACING_FRONT -> Facing.FRONT
                    CameraSelector.LENS_FACING_BACK -> Facing.BACK
                    else -> null
                }
            }
        }
}
