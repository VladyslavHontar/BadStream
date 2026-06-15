package com.example.plohoystream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme

@Composable
fun DestinationSettings(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val s = ui.settings
    val enabled = !ui.isActive
    val isSrt = s.rtmpUrl.startsWith("srt://")

    SubScreen(title = "Destination", onBack = { viewModel.navigateSettings(SettingsRoute.Root) }) {
        DimmedWhileLiveBanner(visible = ui.isActive)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = s.rtmpUrl, onValueChange = viewModel::setUrl,
                label = { Text("Server URL") }, singleLine = true, enabled = enabled,
                placeholder = { Text("rtmp:// or srt://") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Examples: rtmp://live.twitch.tv/app  •  srt://relay:8890?streamid=…",
                color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium,
            )
            // Stream key is RTMP-only; SRT carries identity via streamid below.
            if (!isSrt) {
                OutlinedTextField(
                    value = s.streamKey, onValueChange = viewModel::setKey,
                    label = { Text("Stream key") }, singleLine = true, enabled = enabled,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (isSrt) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Text("SRT", color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = s.srtStreamId, onValueChange = viewModel::setSrtStreamId,
                    label = { Text("Stream ID") }, singleLine = true, enabled = enabled,
                    placeholder = { Text("publish/live (overridden by URL ?streamid=)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s.srtLatencyMs.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { viewModel.setSrtLatency(it) } },
                    label = { Text("Latency (ms)") }, singleLine = true, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Adaptive bitrate", color = OnSurfaceWhite, style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = s.abrEnabled, onCheckedChange = viewModel::setAbrEnabled, enabled = enabled)
                }

                if (s.abrEnabled) {
                    OutlinedTextField(
                        value = s.abrMinKbps.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.let { viewModel.setAbrMinKbps(it) } },
                        label = { Text("ABR min (kbps)") }, singleLine = true, enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s.abrTargetKbps.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.let { viewModel.setAbrTargetKbps(it) } },
                        label = { Text("ABR target (kbps)") }, singleLine = true, enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = s.abrMaxKbps.toString(),
                        onValueChange = { v -> v.toIntOrNull()?.let { viewModel.setAbrMaxKbps(it) } },
                        label = { Text("ABR max (kbps)") }, singleLine = true, enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Preview(widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun DestinationPreview() = PlohoyTheme {
    // Preview with a fake VM is heavy; preview the dimmed banner + a field shape instead.
    Column { DimmedWhileLiveBanner(true) }
}
