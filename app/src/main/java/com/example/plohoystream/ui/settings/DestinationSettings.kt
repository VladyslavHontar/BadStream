package com.example.plohoystream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.PlohoyTheme

@Composable
fun DestinationSettings(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    SubScreen(title = "Destination", onBack = { viewModel.navigateSettings(SettingsRoute.Root) }) {
        DimmedWhileLiveBanner(visible = ui.isActive)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = ui.url, onValueChange = viewModel::setUrl,
                label = { Text("Server URL") }, singleLine = true, enabled = !ui.isActive,
                placeholder = { Text("rtmp://live.example.com/app") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Example: rtmp://live.twitch.tv/app", color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = ui.key, onValueChange = viewModel::setKey,
                label = { Text("Stream key") }, singleLine = true, enabled = !ui.isActive,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun DestinationPreview() = PlohoyTheme {
    // Preview with a fake VM is heavy; preview the dimmed banner + a field shape instead.
    Column { DimmedWhileLiveBanner(true) }
}
