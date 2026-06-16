package com.example.plohoystream.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraControlsTest {

    @Test
    fun clampZoom_withinRange_returnsRequestedValue() {
        assertEquals(2.0f, CameraControls.clampZoom(2.0f, min = 0.6f, max = 10.0f), 0.0001f)
    }

    @Test
    fun clampZoom_belowMin_returnsMin() {
        assertEquals(0.6f, CameraControls.clampZoom(0.1f, min = 0.6f, max = 10.0f), 0.0001f)
    }

    @Test
    fun clampZoom_aboveMax_returnsMax() {
        assertEquals(10.0f, CameraControls.clampZoom(99.0f, min = 0.6f, max = 10.0f), 0.0001f)
    }

    @Test
    fun opposite_back_isFront() {
        assertEquals(Facing.FRONT, CameraControls.opposite(Facing.BACK))
    }

    @Test
    fun opposite_front_isBack() {
        assertEquals(Facing.BACK, CameraControls.opposite(Facing.FRONT))
    }

    @Test
    fun shutter180_at30fps_isOneSixtieth() {
        // 1/60s = 16_666_666ns (integer division).
        assertEquals(16_666_666L, CameraControls.shutter180Ns(30))
    }

    @Test
    fun shutter180_at60fps_isOneTwentieth() {
        assertEquals(8_333_333L, CameraControls.shutter180Ns(60))
    }

    @Test
    fun clampShutter_clampsToRange() {
        assertEquals(1000L, CameraControls.clampShutterNs(10L, min = 1000L, max = 100_000L))
        assertEquals(100_000L, CameraControls.clampShutterNs(999_999L, min = 1000L, max = 100_000L))
    }

    @Test
    fun autoIso_darkScene_raisesIso() {
        // Measured luma well below target -> ISO should go up, clamped to max.
        val next = CameraControls.autoIsoStep(
            currentIso = 200, measuredLuma = 0.10f, targetLuma = 0.45f, minIso = 50, maxIso = 3200,
        )
        assert(next > 200) { "expected ISO to rise, got $next" }
    }

    @Test
    fun autoIso_brightScene_lowersIso() {
        val next = CameraControls.autoIsoStep(
            currentIso = 800, measuredLuma = 0.90f, targetLuma = 0.45f, minIso = 50, maxIso = 3200,
        )
        assert(next < 800) { "expected ISO to fall, got $next" }
    }

    @Test
    fun autoIso_clampsToMax() {
        val next = CameraControls.autoIsoStep(
            currentIso = 3000, measuredLuma = 0.001f, targetLuma = 0.45f, minIso = 50, maxIso = 3200,
        )
        assertEquals(3200, next)
    }

    @Test
    fun autoIso_onTarget_holdsSteady() {
        val next = CameraControls.autoIsoStep(
            currentIso = 400, measuredLuma = 0.45f, targetLuma = 0.45f, minIso = 50, maxIso = 3200,
        )
        assertEquals(400, next)
    }
}
