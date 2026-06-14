package com.example.plohoystream.stream
import org.junit.Assert.assertEquals
import org.junit.Test

class CodecSelectorTest {
    @Test fun hdrOnAllSupported_picksHevcMain10Hlg() {
        val f = CodecSelector.select(hevcEncoder = true, hevcMain10 = true, cameraHdr = true, hdrOn = true)
        assertEquals(VideoCodecType.HEVC, f.codec)
        assertEquals(DynamicRange.HLG10, f.dynamicRange)
        assertEquals(true, f.main10)
    }
    @Test fun hdrOffHevcAvailable_picksHevcSdr() {
        val f = CodecSelector.select(hevcEncoder = true, hevcMain10 = true, cameraHdr = true, hdrOn = false)
        assertEquals(VideoCodecType.HEVC, f.codec)
        assertEquals(DynamicRange.SDR, f.dynamicRange)
        assertEquals(false, f.main10)
    }
    @Test fun noHevc_picksAvc() {
        val f = CodecSelector.select(hevcEncoder = false, hevcMain10 = false, cameraHdr = false, hdrOn = true)
        assertEquals(VideoCodecType.AVC, f.codec)
        assertEquals(DynamicRange.SDR, f.dynamicRange)
    }
    @Test fun hdrOnButNoMain10_fallsBackToHevcSdr() {
        val f = CodecSelector.select(hevcEncoder = true, hevcMain10 = false, cameraHdr = true, hdrOn = true)
        assertEquals(VideoCodecType.HEVC, f.codec)
        assertEquals(DynamicRange.SDR, f.dynamicRange)
    }
    @Test fun hdrOnButNoCameraHdr_fallsBackToSdr() {
        val f = CodecSelector.select(hevcEncoder = true, hevcMain10 = true, cameraHdr = false, hdrOn = true)
        assertEquals(VideoCodecType.HEVC, f.codec)
        assertEquals(DynamicRange.SDR, f.dynamicRange)
    }
}
