package com.example.plohoystream.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilitiesTest {
    private fun cam(
        id: String, facing: Facing = Facing.BACK, logical: Boolean = false,
        minZoom: Float = 1f, maxZoom: Float = 8f, ratios: List<Float> = emptyList(),
        sizes: List<Resolution> = listOf(Resolution(1920, 1080)), ois: Boolean = false,
    ) = CameraInfo(id, facing, logical, minZoom, maxZoom, ratios, sizes, ois)

    @Test fun emptyList_returnsNull() {
        assertNull(CameraCapabilities.select(emptyList()))
    }
    @Test fun prefersLogicalBackCamera() {
        val chosen = CameraCapabilities.select(listOf(cam("0", logical = false), cam("9", logical = true)))!!
        assertEquals("9", chosen.cameraId)
        assertEquals(Facing.BACK, chosen.facing)
    }
    @Test fun fallsBackToFirstBack_whenNoLogical() {
        assertEquals("3", CameraCapabilities.select(listOf(cam("3"), cam("4")))!!.cameraId)
    }
    @Test fun fallsBackToAnyCamera_whenNoBackFacing() {
        assertEquals("front", CameraCapabilities.select(listOf(cam("front", facing = Facing.FRONT)))!!.cameraId)
    }
    @Test fun choosesSizeNearest1080p() {
        val chosen = CameraCapabilities.select(
            listOf(cam("0", sizes = listOf(Resolution(640, 480), Resolution(1920, 1080), Resolution(3840, 2160))))
        )!!
        assertEquals(Resolution(1920, 1080), chosen.previewSize)
    }
    @Test fun buildsLensList_includes1xAndRatiosWithinZoomRange_sorted() {
        val chosen = CameraCapabilities.select(listOf(cam("0", minZoom = 0.6f, maxZoom = 10f, ratios = listOf(2.0f, 0.6f))))!!
        assertEquals(listOf(0.6f, 1.0f, 2.0f), chosen.lenses.map { it.zoomRatio })
        assertEquals(listOf("0.6×", "1×", "2×"), chosen.lenses.map { it.label })
    }
    @Test fun lensList_dropsRatiosOutsideZoomRange() {
        val chosen = CameraCapabilities.select(listOf(cam("0", minZoom = 1f, maxZoom = 3f, ratios = listOf(0.6f, 5.0f))))!!
        assertEquals(listOf(1.0f), chosen.lenses.map { it.zoomRatio })
    }
    @Test fun carriesOisFlag() {
        assertTrue(CameraCapabilities.select(listOf(cam("0", ois = true)))!!.hasOis)
    }
}
