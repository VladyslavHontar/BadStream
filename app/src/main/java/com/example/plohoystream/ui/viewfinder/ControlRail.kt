package com.example.plohoystream.ui.viewfinder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.camera.LensOption
import com.example.plohoystream.stream.ConnectionHealth
import com.example.plohoystream.stream.StreamState
import com.example.plohoystream.ui.theme.GlassHairline
import com.example.plohoystream.ui.theme.LiveRed
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme
import com.example.plohoystream.ui.theme.glassSurface

/** The right letterbox bar: status cluster (top) + actions (bottom). Never over video. */
@Composable
fun ControlRail(
    state: StreamState,
    elapsed: String,
    health: ConnectionHealth,
    bitrateKbps: Int,
    audioLevel: Float,
    lenses: List<LensOption>,
    selectedPhysicalId: String?,
    canGoLive: Boolean,
    errorReason: String?,
    onSelectLens: (LensOption) -> Unit,
    onFlip: () -> Unit,
    onGoLive: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    showObsScenes: Boolean,
    obsScenes: List<String>,
    obsCurrentScene: String?,
    onSwitchScene: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reconnecting = state is StreamState.Reconnecting
    val live = state is StreamState.Live || reconnecting
    Column(
        modifier = modifier.fillMaxHeight().width(220.dp).glassSurface().padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.Start,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveStatusCluster(live = live, elapsed = elapsed, reconnecting = reconnecting)
            if (live) {
                HealthIndicator(health = health, bitrateKbps = bitrateKbps)
            }
            // Audio meter shows during preview too (driven by MicMonitor when not streaming).
            AudioMeter(level = audioLevel)
            // Physical lens picker (ultrawide/main/tele); zoom WITHIN a lens is the slider over the preview.
            LensButtons(lenses = lenses, selectedPhysicalId = selectedPhysicalId, onSelect = onSelectLens)
            if (showObsScenes) {
                ObsSceneChip(
                    scenes = obsScenes,
                    currentScene = obsCurrentScene,
                    onSwitchScene = onSwitchScene,
                )
            }
            if (errorReason != null) {
                Text(errorReason, color = LiveRed, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onGoLive) { Text("Try again") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = onFlip, modifier = Modifier.size(40.dp).clip(CircleShape)) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = "Flip camera", tint = OnSurfaceWhite)
            }
            GoLiveButton(state = state, enabled = canGoLive, onGoLive = onGoLive, onStop = onStop)
            IconButton(onClick = onSettings, modifier = Modifier.size(40.dp).clip(CircleShape)) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = OnSurfaceMuted)
            }
        }
    }
}

@Preview(name = "setup", widthDp = 240, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailSetupPreview() = PlohoyTheme {
    ControlRail(StreamState.Idle, "00:00", ConnectionHealth.Good, 0, 0f, emptyList(), null, true, null, {}, {}, {}, {}, {}, false, emptyList(), null, {})
}

@Preview(name = "live", widthDp = 240, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailLivePreview() = PlohoyTheme {
    ControlRail(StreamState.Live, "01:23", ConnectionHealth.Warn, 4200, 0.8f, emptyList(), null, false, null, {}, {}, {}, {}, {}, true, listOf("Main", "BRB"), "Main", {})
}

@Preview(name = "error", widthDp = 240, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailErrorPreview() = PlohoyTheme {
    ControlRail(StreamState.Error("Connection refused"), "00:00", ConnectionHealth.Bad, 0, 0f, emptyList(), null, true, "Connection refused", {}, {}, {}, {}, {}, false, emptyList(), null, {})
}

@Preview(name = "reconnecting", widthDp = 240, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailReconnectingPreview() = PlohoyTheme {
    ControlRail(StreamState.Reconnecting, "01:30", ConnectionHealth.Warn, 0, 0f, emptyList(), null, false, null, {}, {}, {}, {}, {}, true, listOf("Main", "BRB"), "BRB", {})
}
