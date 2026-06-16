package com.example.plohoystream.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrentCameraProbeTest {
    @Test fun supportsFrontBack_trueWhenACombinationHasBothFacings() {
        val combos = listOf(
            listOf(Facing.BACK, Facing.BACK),
            listOf(Facing.FRONT, Facing.BACK),
        )
        assertTrue(ConcurrentCameraProbe.supportsFrontBack(combos))
    }

    @Test fun supportsFrontBack_falseWhenNoComboMixesFacings() {
        val combos = listOf(
            listOf(Facing.BACK, Facing.BACK),
            listOf(Facing.FRONT, Facing.FRONT),
        )
        assertFalse(ConcurrentCameraProbe.supportsFrontBack(combos))
    }

    @Test fun supportsFrontBack_falseWhenNoConcurrentCombosReported() {
        assertFalse(ConcurrentCameraProbe.supportsFrontBack(emptyList()))
    }

    @Test fun maxDualHeight_is720() {
        assertEquals(720, ConcurrentCameraProbe.MAX_DUAL_HEIGHT)
    }
}
