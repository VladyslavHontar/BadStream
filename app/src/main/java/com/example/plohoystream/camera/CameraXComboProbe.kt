package com.example.plohoystream.camera

import androidx.camera.core.CameraInfo
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/**
 * Camera-side support oracle backed by CameraX Feature Groups. For each [CaptureCombo] we build
 * a [SessionConfig] carrying the matching [GroupableFeature]s and ask the device authoritatively
 * via [CameraInfo.isSessionConfigSupported]. No make/model list — pure capability.
 */
class CameraXComboProbe(
    private val cameraInfo: CameraInfo,
    private val probeUseCaseFactory: (CaptureCombo) -> Preview = ::defaultPreview,
) : CameraComboProbe {

    override fun isSupported(combo: CaptureCombo): Boolean {
        val features = buildList {
            if (combo.fps >= 60) add(GroupableFeature.FPS_60)
            if (combo.hdr) add(GroupableFeature.HDR_HLG10)
            if (combo.stabilized) add(GroupableFeature.PREVIEW_STABILIZATION)
        }
        val preview = probeUseCaseFactory(combo)
        val sessionBuilder = SessionConfig.Builder(preview)
        if (features.isNotEmpty()) {
            sessionBuilder.setRequiredFeatureGroup(*features.toTypedArray())
        }
        val session = sessionBuilder.build()
        return runCatching { cameraInfo.isSessionConfigSupported(session) }.getOrDefault(false)
    }

    private companion object {
        fun defaultPreview(combo: CaptureCombo): Preview =
            Preview.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(combo.width, combo.height),
                                ResolutionStrategy.FALLBACK_RULE_NONE,
                            )
                        )
                        .build()
                )
                .build()
    }
}
