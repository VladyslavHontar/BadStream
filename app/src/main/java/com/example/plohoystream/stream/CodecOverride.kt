package com.example.plohoystream.stream

/** User codec preference. Auto = today's negotiation; the others force a codec (SDR). */
enum class CodecOverride { Auto, ForceHevc, ForceAvc }

/**
 * Maps a [CodecOverride] (+ device caps + HDR toggle) to the requested [VideoFormat].
 * Auto delegates to [CodecSelector.select]; ForceHevc/ForceAvc pin the codec at SDR
 * (forcing a codec is a debugging/compat escape hatch, so HDR is not implied).
 */
fun resolveRequest(
    override: CodecOverride,
    hevcEncoder: Boolean,
    hevcMain10: Boolean,
    cameraHdr: Boolean,
    hdrOn: Boolean,
): VideoFormat = when (override) {
    CodecOverride.Auto -> CodecSelector.select(hevcEncoder, hevcMain10, cameraHdr, hdrOn)
    CodecOverride.ForceHevc -> VideoFormat(VideoCodecType.HEVC, main10 = false, DynamicRange.SDR)
    CodecOverride.ForceAvc -> VideoFormat(VideoCodecType.AVC, main10 = false, DynamicRange.SDR)
}
