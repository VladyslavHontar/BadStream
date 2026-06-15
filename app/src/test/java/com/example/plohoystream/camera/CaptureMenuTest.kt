package com.example.plohoystream.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureMenuTest {

    // Camera supports everything EXCEPT 4K, and supports HDR only at <=1080p60.
    private val camera = CameraComboProbe { c ->
        if (c.width >= 3840) false
        else if (c.hdr) c.height <= 1080 && c.fps <= 60
        else true
    }

    // Encoder: AVC always; HEVC present; Main10 present; max 1080p60 (no 4K encode).
    private val encoder = EncoderGate(
        hasHevc = true,
        hasHevcMain10 = true,
        canEncode = { c -> c.height <= 1080 && c.fps <= 60 },
    )

    private val candidates = listOf(
        CaptureCombo(1280, 720, 30, hdr = false),
        CaptureCombo(1920, 1080, 30, hdr = false),
        CaptureCombo(1920, 1080, 60, hdr = false),
        CaptureCombo(3840, 2160, 30, hdr = false), // 4K — camera & encoder both reject
    )

    @Test fun generate_keepsOnlyCameraAndEncoderSupportedCombos() {
        val menu = CaptureMenu.generate(candidates, camera, encoder)
        val labels = menu.map { "${it.height}p${it.fps}" }
        assertEquals(listOf("720p30", "1080p30", "1080p60"), labels)
    }

    @Test fun generate_marksHdrCapableWhenCameraAndMain10Allow() {
        val menu = CaptureMenu.generate(candidates, camera, encoder)
        // 1080p60 HDR is supported by both camera (<=1080p60) and Main10 encoder.
        assertTrue(menu.first { it.height == 1080 && it.fps == 60 }.hdrCapable)
    }

    @Test fun generate_hdrNotCapableWithoutMain10() {
        val noMain10 = encoder.copy(hasHevcMain10 = false)
        val menu = CaptureMenu.generate(candidates, camera, noMain10)
        assertTrue(menu.all { !it.hdrCapable })
    }

    @Test fun codecOptions_excludeHevcWhenAbsent() {
        assertEquals(
            listOf(VideoCodecOption.Auto, VideoCodecOption.Avc),
            CaptureMenu.codecOptions(EncoderGate(hasHevc = false, hasHevcMain10 = false) { true }),
        )
    }

    @Test fun codecOptions_includeHevcWhenPresent() {
        assertTrue(CaptureMenu.codecOptions(encoder).contains(VideoCodecOption.Hevc))
    }

    @Test fun hdrToggle_disabledReason_whenSelectedOptionNotHdrCapable() {
        val option = QualityOption(3840, 2160, 30, hdrCapable = false)
        val state = CaptureMenu.hdrToggleState(option)
        assertFalse(state.enabled)
        assertEquals("HDR needs HEVC Main10 — unavailable at this resolution/fps", state.reason)
    }
}
