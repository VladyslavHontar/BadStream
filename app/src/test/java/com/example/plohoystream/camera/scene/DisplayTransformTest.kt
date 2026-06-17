package com.example.plohoystream.camera.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTransformTest {
    private val eps = 1e-4f

    // Unified on-device-verified formula: (sensor + display - 90), normalized. Same for both cameras;
    // the front horizontal mirror is applied separately in matrix() and doesn't change net rotation.
    @Test fun netRotation_isSensorPlusDisplayMinusQuarterTurn() {
        // front sensor 270 + display 90 → 270 (upright); back sensor 90 + display 90 → 90 (upright after mirror-free path).
        assertEquals(270, DisplayTransform.netRotationDegrees(sensorDeg = 270, displayDeg = 90, isFront = true))
        assertEquals(90, DisplayTransform.netRotationDegrees(sensorDeg = 90, displayDeg = 90, isFront = false))
        // isFront does not affect the rotation value (only matrix()'s mirror).
        assertEquals(90, DisplayTransform.netRotationDegrees(sensorDeg = 90, displayDeg = 90, isFront = true))
    }

    @Test fun netRotation_isAlwaysNormalizedTo0_359() {
        // raw = 0 + 0 - 90 = -90 must normalize to 270 (the double-mod's reason for existing).
        assertEquals(270, DisplayTransform.netRotationDegrees(sensorDeg = 0, displayDeg = 0, isFront = false))
        assertEquals(90, DisplayTransform.netRotationDegrees(sensorDeg = 270, displayDeg = 270, isFront = false))
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

}
