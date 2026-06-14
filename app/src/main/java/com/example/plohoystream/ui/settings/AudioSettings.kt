package com.example.plohoystream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme

@Composable
fun AudioSettings(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    SubScreen(title = "Audio", onBack = { viewModel.navigateSettings(SettingsRoute.Root) }) {
        DimmedWhileLiveBanner(visible = ui.isActive)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Audio bitrate", color = OnSurfaceWhite, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(96_000, 128_000, 192_000).forEach { br ->
                    FilterChip(
                        selected = ui.quality.audioBitrate == br,
                        onClick = { if (!ui.isActive) viewModel.setQuality(ui.quality.copy(audioBitrate = br)) },
                        enabled = !ui.isActive,
                        label = { Text("${br / 1000} kbps") },
                    )
                }
            }
            Text("Codec: AAC (fixed)", color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Preview(widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun AudioPreview() = PlohoyTheme { Column { DimmedWhileLiveBanner(false) } }
