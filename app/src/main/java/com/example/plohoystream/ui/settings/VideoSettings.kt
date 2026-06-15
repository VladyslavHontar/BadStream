package com.example.plohoystream.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.camera.QualityOption
import com.example.plohoystream.camera.VideoCodecOption
import com.example.plohoystream.stream.CodecOverride
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.stream.VideoQuality
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme

private fun bitrateFor(opt: QualityOption): Int = when {
    opt.height >= 2160 -> 20_000_000
    opt.height >= 1080 && opt.fps >= 60 -> 9_000_000
    opt.height >= 1080 -> 6_000_000
    else -> 3_500_000
}

@Composable
fun VideoSettings(
    viewModel: StreamViewModel,
    qualityOptions: List<QualityOption> = emptyList(),
    codecOptions: List<VideoCodecOption> = emptyList(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    // Fall back to safe defaults if the probe hasn't completed or failed.
    val options = qualityOptions.ifEmpty {
        listOf(
            QualityOption(1280, 720, 30, hdrCapable = false),
            QualityOption(1920, 1080, 30, hdrCapable = false),
        )
    }

    // Fall back to Auto + AVC if empty.
    val codecs = codecOptions.ifEmpty { listOf(VideoCodecOption.Auto, VideoCodecOption.Avc) }

    SubScreen(title = "Video", onBack = { viewModel.navigateSettings(SettingsRoute.Root) }) {
        DimmedWhileLiveBanner(visible = ui.isActive)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Resolution & frame rate", color = OnSurfaceWhite, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { opt ->
                    FilterChip(
                        selected = ui.settings.quality.height == opt.height && ui.settings.quality.fps == opt.fps,
                        onClick = {
                            if (!ui.isActive) viewModel.setQuality(
                                VideoQuality(opt.width, opt.height, opt.fps, bitrateFor(opt), 128_000)
                            )
                        },
                        enabled = !ui.isActive,
                        label = { Text("${opt.height}p ${opt.fps}") },
                    )
                }
            }
            Text("Codec", color = OnSurfaceWhite, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                codecs.forEach { videoCodecOption ->
                    val (codecOverride, label) = when (videoCodecOption) {
                        VideoCodecOption.Auto -> CodecOverride.Auto to "Auto"
                        VideoCodecOption.Hevc -> CodecOverride.ForceHevc to "HEVC"
                        VideoCodecOption.Avc -> CodecOverride.ForceAvc to "AVC"
                    }
                    FilterChip(
                        selected = ui.settings.codecOverride == codecOverride,
                        onClick = { if (!ui.isActive) viewModel.setCodecOverride(codecOverride) },
                        enabled = !ui.isActive,
                        label = { Text(label) },
                    )
                }
            }
            Text("Video bitrate: ${ui.settings.quality.videoBitrate / 1000} kbps", color = OnSurfaceWhite, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Auto lets the server negotiate the best codec. HEVC is more efficient but some servers reject it; AVC is the most compatible.",
                color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium,
            )

            // HDR toggle with per-option availability reason.
            val selectedOpt = options.firstOrNull {
                it.height == ui.settings.quality.height && it.fps == ui.settings.quality.fps
            }
            val hdrState = selectedOpt?.let { com.example.plohoystream.camera.CaptureMenu.hdrToggleState(it) }
                ?: com.example.plohoystream.camera.HdrToggleState(false, "Select a resolution first")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("HDR (HLG10)", color = OnSurfaceWhite, style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = ui.settings.hdrEnabled && hdrState.enabled,
                    onCheckedChange = { if (hdrState.enabled && !ui.isActive) viewModel.setHdr(it) },
                    enabled = hdrState.enabled && !ui.isActive,
                )
            }
            if (!hdrState.enabled) Text(
                hdrState.reason ?: "", color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Record while streaming", color = OnSurfaceWhite, style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = ui.settings.recordWhileStreaming,
                    onCheckedChange = viewModel::setRecordWhileStreaming,
                    enabled = !ui.isActive,
                )
            }
            Text(
                "Saves a copy of the live stream to a local .mp4 while you broadcast (same codec, no extra encoding).",
                color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Preview(widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun VideoPreview() = PlohoyTheme { Column { DimmedWhileLiveBanner(true) } }
