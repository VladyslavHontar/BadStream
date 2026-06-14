package com.example.plohoystream.stream

/** Pure codec/HDR selection. HDR requires HEVC Main10 + an HDR-capable camera. */
object CodecSelector {
    fun select(hevcEncoder: Boolean, hevcMain10: Boolean, cameraHdr: Boolean, hdrOn: Boolean): VideoFormat {
        val wantHdr = hdrOn && hevcEncoder && hevcMain10 && cameraHdr
        return when {
            wantHdr -> VideoFormat(VideoCodecType.HEVC, main10 = true, DynamicRange.HLG10)
            hevcEncoder -> VideoFormat(VideoCodecType.HEVC, main10 = false, DynamicRange.SDR)
            else -> VideoFormat(VideoCodecType.AVC, main10 = false, DynamicRange.SDR)
        }
    }

    /**
     * Whether the HDR toggle should be offered to the user at all.
     * Precondition: [hevcMain10] implies an HEVC encoder exists (Main10 capability is read off
     * the HEVC encoder's profile levels, so it can never be true without HEVC encode support).
     */
    fun hdrAvailable(hevcMain10: Boolean, cameraHdr: Boolean): Boolean = hevcMain10 && cameraHdr
}
