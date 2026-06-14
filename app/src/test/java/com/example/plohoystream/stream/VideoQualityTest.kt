package com.example.plohoystream.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoQualityTest {
    @Test fun auto_delegatesToCodecSelector_hevcWhenAvailable() {
        val fmt = resolveRequest(CodecOverride.Auto, hevcEncoder = true, hevcMain10 = true, cameraHdr = true, hdrOn = true)
        assertEquals(VideoCodecType.HEVC, fmt.codec)
        assertEquals(DynamicRange.HLG10, fmt.dynamicRange)
    }

    @Test fun auto_fallsBackToAvcWhenNoHevc() {
        val fmt = resolveRequest(CodecOverride.Auto, hevcEncoder = false, hevcMain10 = false, cameraHdr = false, hdrOn = false)
        assertEquals(VideoCodecType.AVC, fmt.codec)
    }

    @Test fun forceHevc_forcesHevcSdr_ignoringHdrToggle() {
        val fmt = resolveRequest(CodecOverride.ForceHevc, hevcEncoder = true, hevcMain10 = true, cameraHdr = true, hdrOn = true)
        assertEquals(VideoCodecType.HEVC, fmt.codec)
        assertEquals(DynamicRange.SDR, fmt.dynamicRange)
        assertEquals(false, fmt.main10)
    }

    @Test fun forceAvc_forcesAvcSdr() {
        val fmt = resolveRequest(CodecOverride.ForceAvc, hevcEncoder = true, hevcMain10 = true, cameraHdr = true, hdrOn = true)
        assertEquals(VideoCodecType.AVC, fmt.codec)
        assertEquals(DynamicRange.SDR, fmt.dynamicRange)
    }
}
