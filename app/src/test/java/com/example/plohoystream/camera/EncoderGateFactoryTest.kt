package com.example.plohoystream.camera

import com.example.plohoystream.media.CodecCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderGateFactoryTest {
    @Test fun gate_fromSnapshot_usesAvcAndHevcLimits() {
        val snap = CodecCapabilities.VideoSnapshot(
            hasHevc = true,
            hasHevcMain10 = true,
            avcMaxPixelsPerSecond = 1920L * 1080 * 30,   // AVC up to 1080p30
            hevcMaxPixelsPerSecond = 1920L * 1080 * 60,   // HEVC up to 1080p60
        )
        val gate = EncoderGateFactory.from(snap)

        // AVC-class SDR 1080p60 exceeds AVC budget but the gate uses the HEVC ceiling when present.
        assertTrue(gate.canEncode(CaptureCombo(1920, 1080, 60, hdr = false)))
        assertFalse(gate.canEncode(CaptureCombo(3840, 2160, 30, hdr = false))) // 4K beyond both
        assertTrue(gate.hasHevcMain10)
    }
}
