package com.example.plohoystream.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.stream.StreamState
import com.example.plohoystream.stream.StreamUiState
import com.example.plohoystream.stream.StreamViewModel

@Composable
fun StreamScreen(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    StreamScreenContent(
        ui = ui,
        onUrl = viewModel::setUrl,
        onKey = viewModel::setKey,
        onGoLive = viewModel::goLive,
        onStop = viewModel::stop,
    )
}

@Composable
private fun StreamScreenContent(
    ui: StreamUiState,
    onUrl: (String) -> Unit,
    onKey: (String) -> Unit,
    onGoLive: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("PlohoyStream", style = MaterialTheme.typography.headlineMedium)
            Text(statusText(ui.stream), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = ui.url, onValueChange = onUrl,
                label = { Text("RTMP URL") }, singleLine = true,
                enabled = !ui.isActive, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ui.key, onValueChange = onKey,
                label = { Text("Stream key") }, singleLine = true,
                enabled = !ui.isActive, modifier = Modifier.fillMaxWidth(),
            )
            if (ui.isActive) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
            } else {
                Button(onClick = onGoLive, enabled = ui.canGoLive, modifier = Modifier.fillMaxWidth()) {
                    Text("Go Live")
                }
            }
        }
    }
}

private fun statusText(s: StreamState): String = when (s) {
    StreamState.Idle -> "Idle"
    StreamState.Connecting -> "Connecting…"
    StreamState.Live -> "● LIVE"
    StreamState.Stopping -> "Stopping…"
    is StreamState.Error -> "Error: ${s.reason}"
}

@Preview(showBackground = true)
@Composable
private fun StreamScreenPreview() {
    MaterialTheme {
        StreamScreenContent(
            ui = StreamUiState(url = "rtmp://live.twitch.tv/app", key = "abc", stream = StreamState.Idle),
            onUrl = {}, onKey = {}, onGoLive = {}, onStop = {},
        )
    }
}
