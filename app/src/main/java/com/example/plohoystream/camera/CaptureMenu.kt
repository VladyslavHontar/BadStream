package com.example.plohoystream.camera

/** A concrete capture request: pixel size, frame rate, and whether HDR (HLG10) is requested. */
data class CaptureCombo(
    val width: Int,
    val height: Int,
    val fps: Int,
    val hdr: Boolean,
    val stabilized: Boolean = false,
)

/** Camera-side support oracle. Real impl wraps CameraX `isSessionConfigSupported`; fakes in tests. */
fun interface CameraComboProbe {
    fun isSupported(combo: CaptureCombo): Boolean
}

/** Encoder-side gates from `MediaCodecList`. */
data class EncoderGate(
    val hasHevc: Boolean,
    val hasHevcMain10: Boolean,
    val canEncode: (CaptureCombo) -> Boolean,
)

/** A resolution/fps the menu offers, with whether HDR can pair with it. */
data class QualityOption(
    val width: Int,
    val height: Int,
    val fps: Int,
    val hdrCapable: Boolean,
)

enum class VideoCodecOption { Auto, Hevc, Avc }

/** Whether the HDR toggle is enabled for the current selection, plus a reason when disabled. */
data class HdrToggleState(val enabled: Boolean, val reason: String?)

object CaptureMenu {

    /** The SDR combos we probe the device for, highest quality first. HDR variants derived in [generate]. */
    val CANDIDATES: List<CaptureCombo> = listOf(
        CaptureCombo(3840, 2160, 60, hdr = false),
        CaptureCombo(3840, 2160, 30, hdr = false),
        CaptureCombo(1920, 1080, 60, hdr = false),
        CaptureCombo(1920, 1080, 30, hdr = false),
        CaptureCombo(1280, 720, 60, hdr = false),
        CaptureCombo(1280, 720, 30, hdr = false),
    )

    /** Achievable resolution/fps options = camera-supported ∩ encoder-supported (SDR baseline). */
    fun generate(
        candidates: List<CaptureCombo>,
        camera: CameraComboProbe,
        encoder: EncoderGate,
    ): List<QualityOption> = candidates
        .filter { !it.hdr } // candidates are SDR; HDR-capability is derived per option below
        .filter { camera.isSupported(it) && encoder.canEncode(it) }
        .map { c ->
            val hdrCombo = c.copy(hdr = true)
            val hdrCapable = encoder.hasHevcMain10 &&
                camera.isSupported(hdrCombo) && encoder.canEncode(hdrCombo)
            QualityOption(c.width, c.height, c.fps, hdrCapable)
        }

    /** Codec chips the device can actually offer. Auto + AVC always; HEVC only when present. */
    fun codecOptions(encoder: EncoderGate): List<VideoCodecOption> = buildList {
        add(VideoCodecOption.Auto)
        if (encoder.hasHevc) add(VideoCodecOption.Hevc)
        add(VideoCodecOption.Avc)
    }

    /** HDR toggle availability for the currently selected option. */
    fun hdrToggleState(selected: QualityOption): HdrToggleState =
        if (selected.hdrCapable) HdrToggleState(enabled = true, reason = null)
        else HdrToggleState(
            enabled = false,
            reason = "HDR needs HEVC Main10 — unavailable at this resolution/fps",
        )
}
