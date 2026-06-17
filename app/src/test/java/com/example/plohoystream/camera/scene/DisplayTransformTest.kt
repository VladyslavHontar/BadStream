package com.example.plohoystream.camera.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTransformTest {
    private val eps = 1e-4f

    @Test fun netRotation_back_portraitSensor_landscapeDisplay() {
        assertEquals(0, DisplayTransform.netRotationDegrees(sensorDeg = 90, displayDeg = 90, isFront = false))
        assertEquals(90, DisplayTransform.netRotationDegrees(sensorDeg = 90, displayDeg = 0, isFront = false))
    }

    @Test fun netRotation_front_addsDisplay() {
        assertEquals(0, DisplayTransform.netRotationDegrees(sensorDeg = 270, displayDeg = 90, isFront = true))
        assertEquals(180, DisplayTransform.netRotationDegrees(sensorDeg = 90, displayDeg = 90, isFront = true))
    }

    @Test fun netRotation_isAlwaysNormalizedTo0_359() {
        assertEquals(0, DisplayTransform.netRotationDegrees(sensorDeg = 270, displayDeg = 270, isFront = false))
        // raw = 0 - 90 = -90 must normalize to 270 (the double-mod's reason for existing).
        assertEquals(270, DisplayTransform.netRotationDegrees(sensorDeg = 0, displayDeg = 90, isFront = false))
    }

    @Test fun coverCrop_rectWiderThanContent_cropsHeight() {
        val (cx, cy) = DisplayTransform.coverCrop(contentAspect = 1f, rectAspect = 16f / 9f)
        assertEquals(1f, cx, eps)
        assertEquals(9f / 16f, cy, eps)
    }

    @Test fun coverCrop_rectTallerThanContent_cropsWidth() {
        val (cx, cy) = DisplayTransform.coverCrop(contentAspect = 16f / 9f, rectAspect = 1f)
        assertEquals(9f / 16f, cx, eps)
        assertEquals(1f, cy, eps)
    }

    @Test fun coverCrop_equalAspect_isNoOp() {
        val (cx, cy) = DisplayTransform.coverCrop(contentAspect = 1.5f, rectAspect = 1.5f)
        assertEquals(1f, cx, eps)
        assertEquals(1f, cy, eps)
    }

    @Test fun displayedAspect_swapsOnQuarterTurns() {
        assertEquals(640f / 360f, DisplayTransform.displayedAspect(640, 360, rotationDeg = 0), eps)
        assertEquals(360f / 640f, DisplayTransform.displayedAspect(640, 360, rotationDeg = 90), eps)
        assertEquals(360f / 640f, DisplayTransform.displayedAspect(640, 360, rotationDeg = 270), eps)
        assertEquals(640f / 360f, DisplayTransform.displayedAspect(640, 360, rotationDeg = 180), eps)
    }
}
