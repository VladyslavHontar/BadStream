package com.example.plohoystream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme

@Composable
fun CameraSettings(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    SubScreen(title = "Camera", onBack = { viewModel.navigateSettings(SettingsRoute.Root) }) {
        DimmedWhileLiveBanner(visible = ui.isActive)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Lens, zoom and flip are controlled from the viewfinder.", color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)
            if (ui.hdrAvailable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("HDR", color = OnSurfaceWhite, style = MaterialTheme.typography.titleMedium)
                    Switch(checked = ui.hdrEnabled, onCheckedChange = viewModel::setHdr, enabled = !ui.isActive)
                }
            } else {
                Text("HDR unavailable on this device", color = OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Preview(name = "hdr-unavailable", widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun CameraNoHdrPreview() = PlohoyTheme {
    Column { Text("HDR unavailable on this device", color = OnSurfaceMuted) }
}
