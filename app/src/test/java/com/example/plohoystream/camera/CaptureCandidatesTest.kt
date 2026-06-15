package com.example.plohoystream.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureCandidatesTest {
    @Test fun candidates_areOrderedHighestQualityFirst_andUnique() {
        val c = CaptureMenu.CANDIDATES
        assertEquals(c.distinct(), c) // no duplicates
        // Highest resolution then highest fps first.
        assertEquals(CaptureCombo(3840, 2160, 60, hdr = false), c.first())
        assertEquals(CaptureCombo(1280, 720, 30, hdr = false), c.last())
    }
}
