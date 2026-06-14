package com.example.plohoystream.stream

enum class VideoCodecType(val nativeFlag: Int) { AVC(0), HEVC(1) }
enum class DynamicRange { SDR, HLG10 }

/** The concrete encoder/egress format chosen for a stream. */
data class VideoFormat(
    val codec: VideoCodecType,
    val main10: Boolean,
    val dynamicRange: DynamicRange,
)
