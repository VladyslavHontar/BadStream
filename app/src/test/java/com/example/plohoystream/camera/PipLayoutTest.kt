package com.example.plohoystream.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipLayoutTest {
    private val eps = 1e-4f

    @Test fun pipRect_isSquareInNormalizedSpace_matchingSizeFraction() {
        val l = PipLayout(x = 0.1f, y = 0.2f, size = PipSize.M)
        val r = PipLayout.pipRect(l)
        assertEquals(0.1f, r.left, eps)
        assertEquals(0.2f, r.top, eps)
        assertEquals(0.1f + PipSize.M.widthFraction, r.right, eps)
        assertEquals(0.2f + PipSize.M.widthFraction, r.bottom, eps)
    }

    @Test fun clampInBounds_keepsRectFullyInsideUnitSquare() {
        val wf = PipSize.L.widthFraction
        val tooFar = PipLayout(x = 0.95f, y = -0.3f, size = PipSize.L)
        val c = PipLayout.clampInBounds(tooFar)
        assertTrue(c.x in 0f..(1f - wf) + eps)
        assertTrue(c.y in 0f..(1f - wf) + eps)
        assertEquals(1f - wf, c.x, eps)
        assertEquals(0f, c.y, eps)
    }

    @Test fun snapToNearestCorner_choosesCornerByCenterQuadrant() {
        val wf = PipSize.M.widthFraction
        val m = 0.04f
        val br = PipLayout.snapToNearestCorner(PipLayout(x = 0.7f, y = 0.7f, size = PipSize.M), m)
        assertEquals(1f - wf - m, br.x, eps)
        assertEquals(1f - wf - m, br.y, eps)
        val tl = PipLayout.snapToNearestCorner(PipLayout(x = 0.05f, y = 0.05f, size = PipSize.M), m)
        assertEquals(m, tl.x, eps)
        assertEquals(m, tl.y, eps)
    }

    @Test fun cycleSize_goesSMLthenWrapsAndStaysInBounds() {
        var l = PipLayout(x = 0.9f, y = 0.9f, size = PipSize.S)
        l = PipLayout.cycleSize(l); assertEquals(PipSize.M, l.size)
        l = PipLayout.cycleSize(l); assertEquals(PipSize.L, l.size)
        l = PipLayout.cycleSize(l); assertEquals(PipSize.S, l.size)
        val grown = PipLayout.cycleSize(PipLayout(x = 0.95f, y = 0.95f, size = PipSize.S))
        assertTrue(grown.x in 0f..(1f - grown.size.widthFraction) + eps)
        assertTrue(grown.y in 0f..(1f - grown.size.widthFraction) + eps)
    }

    @Test fun swapPrimary_togglesPrimaryFrontOnly() {
        val l = PipLayout(primaryFront = false)
        val s = PipLayout.swapPrimary(l)
        assertTrue(s.primaryFront)
        assertEquals(l.x, s.x, eps)
        assertEquals(l.y, s.y, eps)
        assertEquals(l.size, s.size)
        assertFalse(PipLayout.swapPrimary(s).primaryFront)
    }
}
