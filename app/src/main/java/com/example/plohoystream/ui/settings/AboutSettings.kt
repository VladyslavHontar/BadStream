package com.example.plohoystream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.stream.CodecOverride
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.stream.VideoQuality
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.PlohoyTheme

@Composable
fun AboutSettings(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    SubScreen(title = "About & Reset", onBack = { viewModel.navigateSettings(SettingsRoute.Root) }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PlohoyStream 1.0", color = OnSurfaceMuted)
            OutlinedButton(
                onClick = {
                    viewModel.setQuality(VideoQuality.Default)
                    viewModel.setCodecOverride(CodecOverride.Auto)
                    if (ui.settings.hdrEnabled) viewModel.setHdr(false)
                },
                enabled = !ui.isActive,
            ) { Text("Reset to defaults") }
        }
    }
}

@Preview(widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun AboutPreview() = PlohoyTheme {
    Column { Text("PlohoyStream 1.0", color = OnSurfaceMuted) }
}
