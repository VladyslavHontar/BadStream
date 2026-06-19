package com.example.plohoystream.ui.viewfinder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPictureAlt
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
    onGoLive: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    showObsScenes: Boolean,
    obsScenes: List<String>,
    obsCurrentScene: String?,
    onSwitchScene: (String) -> Unit,
    dualSupported: Boolean = false,
    dualOn: Boolean = false,
    onToggleDual: () -> Unit = {},
    dualClassOf: ((LensOption) -> com.example.plohoystream.camera.DualClass)? = null,
    dualActiveId: String? = null,
    modifier: Modifier = Modifier,
) {
    val reconnecting = state is StreamState.Reconnecting
    val live = state is StreamState.Live || reconnecting
    Column(
        modifier = modifier.fillMaxHeight().fillMaxWidth().glassSurface().padding(16.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // Status + secondary controls. Takes all space above the pinned actions (weight(1f)) so
        // the action row anchors to the BOTTOM. Scrolls when the content (large font scale, a
        // live health row, 4+ lens chips, OBS scenes, an error message) is taller than that
        // space — so nothing is silently clipped or pushes the actions off-screen.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LiveStatusCluster(live = live, elapsed = elapsed, reconnecting = reconnecting)
            if (live) {
                HealthIndicator(health = health, bitrateKbps = bitrateKbps)
            }
            // Audio meter shows during preview too (driven by MicMonitor when not streaming).
            AudioMeter(level = audioLevel)
            // Physical lens picker (ultrawide/main/tele); zoom WITHIN a lens is the slider over the preview.
            LensButtons(
                lenses = lenses,
                selectedPhysicalId = selectedPhysicalId,
                onSelect = onSelectLens,
                dualClassOf = dualClassOf,
                dualActiveId = dualActiveId,
            )
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
        Spacer(Modifier.height(12.dp))
        // Primary actions, pinned to the bottom — always reachable regardless of how tall the
        // content above grew. The widest case (PiP + 72dp go-live + settings + spacing ≈ 184dp)
        // fits the rail, and none of these are text so they're immune to font scaling.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Camera flip (front/back) is a double-tap on the preview; dual swap is a tap on the PiP.
            if (dualSupported) {
                IconButton(onClick = onToggleDual, modifier = Modifier.size(40.dp).clip(CircleShape)) {
                    Icon(
                        Icons.Filled.PictureInPictureAlt,
                        contentDescription = if (dualOn) "Disable dual camera" else "Enable dual camera",
                        tint = if (dualOn) OnSurfaceWhite else OnSurfaceMuted,
                    )
                }
            }
            GoLiveButton(state = state, enabled = canGoLive, onGoLive = onGoLive, onStop = onStop)
            IconButton(onClick = onSettings, modifier = Modifier.size(40.dp).clip(CircleShape)) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = OnSurfaceMuted)
            }
        }
    }
}

// A 4-sensor back camera (ultrawide/main/tele/periscope) to verify the chips wrap and the
// actions stay pinned — the device-agnostic cases that the old fixed layout clipped.
private val FOUR_LENSES = listOf(
    LensOption("0.6×", "2", 0.6f),
    LensOption("0.9×", "5", 0.9f),
    LensOption("1×", "0", 1.0f),
    LensOption("2×", "3", 2.0f),
)

@Preview(name = "setup", widthDp = 240, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailSetupPreview() = PlohoyTheme {
    ControlRail(
        state = StreamState.Idle, elapsed = "00:00", health = ConnectionHealth.Good,
        bitrateKbps = 0, audioLevel = 0f, lenses = FOUR_LENSES, selectedPhysicalId = "0",
        canGoLive = true, errorReason = null, onSelectLens = {}, onGoLive = {}, onStop = {},
        onSettings = {}, showObsScenes = false, obsScenes = emptyList(), obsCurrentScene = null,
        onSwitchScene = {}, dualSupported = true,
    )
}

// Worst case: 4 chips + live health + OBS scenes on a SHORT screen → region scrolls, actions pinned.
@Preview(name = "packed-short", widthDp = 240, heightDp = 320, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailPackedShortPreview() = PlohoyTheme {
    ControlRail(
        state = StreamState.Live, elapsed = "01:23", health = ConnectionHealth.Warn,
        bitrateKbps = 4200, audioLevel = 0.8f, lenses = FOUR_LENSES, selectedPhysicalId = "0",
        canGoLive = false, errorReason = null, onSelectLens = {}, onGoLive = {}, onStop = {},
        onSettings = {}, showObsScenes = true, obsScenes = listOf("Main", "BRB"),
        obsCurrentScene = "Main", onSwitchScene = {}, dualSupported = true, dualOn = true,
    )
}

// Large font scale on a narrow rail: chips wrap, text grows, go-live + settings still reachable.
@Preview(name = "large-font", widthDp = 200, heightDp = 360, fontScale = 1.8f, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailLargeFontPreview() = PlohoyTheme {
    ControlRail(
        state = StreamState.Error("Connection refused by server"), elapsed = "00:00",
        health = ConnectionHealth.Bad, bitrateKbps = 0, audioLevel = 0f, lenses = FOUR_LENSES,
        selectedPhysicalId = "0", canGoLive = true, errorReason = "Connection refused by server",
        onSelectLens = {}, onGoLive = {}, onStop = {}, onSettings = {}, showObsScenes = false,
        obsScenes = emptyList(), obsCurrentScene = null, onSwitchScene = {}, dualSupported = true,
    )
}

@Preview(name = "reconnecting", widthDp = 260, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailReconnectingPreview() = PlohoyTheme {
    ControlRail(
        state = StreamState.Reconnecting, elapsed = "01:30", health = ConnectionHealth.Warn,
        bitrateKbps = 0, audioLevel = 0f, lenses = FOUR_LENSES, selectedPhysicalId = "0",
        canGoLive = false, errorReason = null, onSelectLens = {}, onGoLive = {}, onStop = {},
        onSettings = {}, showObsScenes = true, obsScenes = listOf("Main", "BRB"),
        obsCurrentScene = "BRB", onSwitchScene = {}, dualSupported = true, dualOn = true,
    )
}
