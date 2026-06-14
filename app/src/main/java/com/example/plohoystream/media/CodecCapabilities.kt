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
}
