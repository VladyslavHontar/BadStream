package com.example.plohoystream.media

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/** Queries the device's HEVC encoder support. */
object CodecCapabilities {
    data class Hevc(val encoder: Boolean, val main10: Boolean)

    fun hevc(): Hevc {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC) }.getOrNull() ?: continue
            val main10 = caps.profileLevels.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 ||
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
            }
            return Hevc(encoder = true, main10 = main10)
        }
        return Hevc(encoder = false, main10 = false)
    }

    /** Pure snapshot of encoder limits, so menu gating is unit-testable without MediaCodec. */
    data class VideoSnapshot(
        val hasHevc: Boolean,
        val hasHevcMain10: Boolean,
        val avcMaxPixelsPerSecond: Long,
        val hevcMaxPixelsPerSecond: Long,
    )

    /** Read the device's real AVC/HEVC encoder limits into a [VideoSnapshot]. */
    fun videoSnapshot(): VideoSnapshot {
        val hevc = hevc()
        return VideoSnapshot(
            hasHevc = hevc.encoder,
            hasHevcMain10 = hevc.main10,
            avcMaxPixelsPerSecond = maxPixelsPerSecond(MediaFormat.MIMETYPE_VIDEO_AVC),
            hevcMaxPixelsPerSecond = maxPixelsPerSecond(MediaFormat.MIMETYPE_VIDEO_HEVC),
        )
    }

    private fun maxPixelsPerSecond(mime: String): Long {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            val v = caps.videoCapabilities ?: continue
            // Upper bound on (w*h*fps) the encoder accepts.
            return v.supportedWidths.upper.toLong() *
                v.supportedHeights.upper.toLong() *
                (v.supportedFrameRates?.upper?.toLong() ?: 60L)
        }
        return 0L
    }
}
