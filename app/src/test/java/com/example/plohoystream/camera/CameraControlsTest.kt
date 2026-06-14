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
}
