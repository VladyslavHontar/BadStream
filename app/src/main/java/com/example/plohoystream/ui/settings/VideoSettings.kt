package com.example.plohoystream.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.stream.CodecOverride
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.stream.VideoQuality
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme

@Composable
fun VideoSettings(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    SubScreen(title = "Video", onBack = { viewModel.navigateSettings(SettingsRoute.Root) }) {
        DimmedWhileLiveBanner(visible = ui.isActive)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Resolution & frame rate", color = OnSurfaceWhite, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoQuality.Presets.forEach { q ->
                    FilterChip(
                        selected = ui.settings.quality.height == q.height && ui.settings.quality.fps == q.fps,
                        onClick = { if (!ui.isActive) viewModel.setQuality(q) },
                        enabled = !ui.isActive,
                        label = { Text("${q.height}p ${q.fps}") },
                    )
                }
            }
            Text("Codec", color = OnSurfaceWhite, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CodecOverride.Auto to "Auto", CodecOverride.ForceHevc to "HEVC", CodecOverride.ForceAvc to "AVC").forEach { (c, label) ->
                    FilterChip(
                        selected = ui.settings.codecOverride == c,
                        onClick = { if (!ui.isActive) viewModel.setCodecOverride(c) },
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
        }
    }
}

@Preview(widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun VideoPreview() = PlohoyTheme { Column { DimmedWhileLiveBanner(true) } }
