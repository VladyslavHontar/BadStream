package com.example.plohoystream.camera

import android.view.Surface

interface CameraController {
    /**
     * Open [config]'s camera and stream into every surface in [targets] (preview, encoder, …).
     * When [hdr] is true the session outputs are configured for HLG10 dynamic range.
     *
     * The CameraX implementation ([CameraXController]) maps high-frame-rate / HDR requests to
     * CameraX REQUIRED Feature Groups: `config.targetFps >= 60` requests `GroupableFeature.FPS_60`
     * and [hdr] requests `GroupableFeature.HDR_HLG10`. If the requested combo is unsupported on the
     * device the bind is retried without the feature group rather than failing. The Camera2
     * implementation instead drives `CONTROL_AE_TARGET_FPS_RANGE` / `DynamicRangeProfiles.HLG10`.
     */
    fun start(config: CameraConfig, targets: List<Surface>, hdr: Boolean = false)
    fun stop()
    fun setZoom(ratio: Float)
    fun setLens(lens: CameraLens) = setZoom(lens.zoomRatio)
}
