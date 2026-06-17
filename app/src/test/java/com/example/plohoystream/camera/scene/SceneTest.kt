package com.example.plohoystream.camera.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTest {
    private val eps = 1e-4f

    @Test fun single_isOnePrimaryFullFrameLayer() {
        val s = Scene.SINGLE
        assertEquals(1, s.layers.size)
        val l = s.layers.single()
        assertEquals(SourceId.PRIMARY, l.source)
        assertEquals(NormRect.FULL, l.rect)
    }

    @Test fun dual_hasPrimaryBaseUnderSecondaryPip() {
        val s = Scene.dual()
        val ordered = s.ordered()
        assertEquals(SourceId.PRIMARY, ordered[0].source)
        assertEquals(NormRect.FULL, ordered[0].rect)
        assertEquals(SourceId.SECONDARY, ordered[1].source)
        assertTrue(ordered[1].z > ordered[0].z)
    }

    @Test fun defaultPip_isSquareInTopRightInsetByMargin() {
        val r = Scene.defaultPip(PipSize.M)
        assertEquals(PipSize.M.widthFraction, r.width, eps)
        assertEquals(r.width, r.height, eps)
        assertEquals(1f - PipSize.M.widthFraction - Scene.PIP_MARGIN, r.left, eps)
        assertEquals(Scene.PIP_MARGIN, r.top, eps)
    }

    @Test fun updateLayer_replacesOnlyThatLayersRect() {
        val moved = Scene.dual().updateLayer(SourceId.SECONDARY) { NormRect(0f, 0f, 0.2f, 0.2f) }
        assertEquals(NormRect.FULL, moved.layer(SourceId.PRIMARY)!!.rect)
        assertEquals(NormRect(0f, 0f, 0.2f, 0.2f), moved.layer(SourceId.SECONDARY)!!.rect)
    }

    @Test fun moveTo_recentersAndClampsInside() {
        val r = Scene.defaultPip(PipSize.M)
        val c = SceneEdits.moveTo(r, 0.5f, 0.5f)
        assertEquals(0.5f, c.centerX, eps)
        assertEquals(0.5f, c.centerY, eps)
        val edge = SceneEdits.moveTo(r, 2f, -1f)
        assertTrue(edge.left >= -eps && edge.right <= 1f + eps)
        assertTrue(edge.top >= -eps && edge.bottom <= 1f + eps)
    }

    @Test fun resizeKeepingCenter_clampsWidthToMinMax() {
        val r = NormRect(0.4f, 0.4f, 0.6f, 0.6f)               // square
        val big = SceneEdits.resizeKeepingCenter(r, 10f)
        assertEquals(SceneEdits.MAX_PIP_WF, big.width, eps)
        assertEquals(big.width, big.height, eps)               // square input stays square
        assertEquals(0.5f, big.centerX, eps)
        val small = SceneEdits.resizeKeepingCenter(r, 0f)
        assertEquals(SceneEdits.MIN_PIP_WF, small.width, eps)
    }

    @Test fun resizeKeepingCenter_preservesNonSquareAspect() {
        val r = NormRect(0.4f, 0.3f, 0.6f, 0.7f)               // w=0.2, h=0.4, aspect 0.5
        val resized = SceneEdits.resizeKeepingCenter(r, 0.3f)
        assertEquals(0.3f, resized.width, eps)
        assertEquals(0.6f, resized.height, eps)                // height tracks width at aspect 0.5
        assertEquals(r.centerX, resized.centerX, eps)
        assertEquals(r.centerY, resized.centerY, eps)
    }

    @Test fun setAspect_matchesSourceOnTheRenderSurface() {
        // width 0.2 on a 16:9 surface with a 3:4 (0.75) source → height = 0.2 * (16/9) / 0.75.
        val r = SceneEdits.setAspect(NormRect(0.5f, 0.1f, 0.7f, 0.3f), regionAspect = 16f / 9f, contentAspect = 0.75f)
        assertEquals(0.2f, r.width, eps)
        assertEquals(0.2f * (16f / 9f) / 0.75f, r.height, eps)
    }

    @Test fun snapToCorner_choosesNearestCornerByCenterQuadrant() {
        val wf = PipSize.M.widthFraction
        val m = Scene.PIP_MARGIN
        val br = SceneEdits.snapToCorner(NormRect(0.7f, 0.7f, 0.7f + wf, 0.7f + wf), m)
        assertEquals(1f - wf - m, br.left, eps)
        assertEquals(1f - wf - m, br.top, eps)
        val tl = SceneEdits.snapToCorner(NormRect(0.05f, 0.05f, 0.05f + wf, 0.05f + wf), m)
        assertEquals(m, tl.left, eps)
        assertEquals(m, tl.top, eps)
        val tr = SceneEdits.snapToCorner(NormRect(0.7f, 0.05f, 0.7f + wf, 0.05f + wf), m)
        assertEquals(1f - wf - m, tr.left, eps)
        assertEquals(m, tr.top, eps)
        val bl = SceneEdits.snapToCorner(NormRect(0.05f, 0.7f, 0.05f + wf, 0.7f + wf), m)
        assertEquals(m, bl.left, eps)
        assertEquals(1f - wf - m, bl.top, eps)
    }
}
