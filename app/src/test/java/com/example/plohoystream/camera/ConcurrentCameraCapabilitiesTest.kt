package com.example.plohoystream.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrentCameraCapabilitiesTest {
    private val ultrawide = BackLens(id = "2", ratio = 0.8f, minZoom = 1f, maxZoom = 2f)
    private val main = BackLens(id = "0", ratio = 1.0f, minZoom = 1f, maxZoom = 4f)
    private val tele = BackLens(id = "3", ratio = 1.8f, minZoom = 1f, maxZoom = 4f)
    private val lenses = listOf(ultrawide, main, tele)

    // Seeker: only {main #0 + front #1} is concurrent.
    private val seeker = ConcurrentCameraCapabilities(
        backLenses = lenses, frontIds = listOf("1"),
        concurrentSets = setOf(setOf("0", "1")),
    )

    @Test fun seeker_supportsDual_onlyMainPlusFront() {
        assertTrue(seeker.supportsDual())
        assertTrue(seeker.isConcurrent("0", "1"))
        assertFalse(seeker.isConcurrent("2", "1"))
        assertFalse(seeker.isConcurrent("3", "1"))
    }

    @Test fun seeker_dualClass_mainOpen() {
        // Open back = main (#0). main=REAL (it's the concurrent one), tele=ZOOM (1.8 in 1..4),
        // ultrawide=UNAVAILABLE (0.8 < 1.0, can't zoom wider).
        assertEquals(DualClass.REAL, seeker.dualClass(main, openBack = main, frontId = "1"))
        assertEquals(DualClass.ZOOM, seeker.dualClass(tele, openBack = main, frontId = "1"))
        assertEquals(DualClass.UNAVAILABLE, seeker.dualClass(ultrawide, openBack = main, frontId = "1"))
    }

    @Test fun richPhone_ultrawideConcurrent_isReal() {
        val rich = ConcurrentCameraCapabilities(
            backLenses = lenses, frontIds = listOf("1"),
            concurrentSets = setOf(setOf("0", "1"), setOf("2", "1")),
        )
        assertEquals(DualClass.REAL, rich.dualClass(ultrawide, openBack = main, frontId = "1"))
    }

    @Test fun noConcurrency_dualUnsupported() {
        val none = ConcurrentCameraCapabilities(lenses, listOf("1"), emptySet())
        assertFalse(none.supportsDual())
    }

    @Test fun widerNonConcurrent_fromTeleOpen_isUnavailable() {
        // ultrawide (0.8x) is wider than the open tele (1.8x) → can't zoom to it, and it isn't
        // concurrent with the front → UNAVAILABLE.
        assertEquals(DualClass.UNAVAILABLE, seeker.dualClass(ultrawide, openBack = tele, frontId = "1"))
    }
}
