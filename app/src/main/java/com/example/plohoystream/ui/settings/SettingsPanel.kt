package com.example.plohoystream.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.theme.GlassHairline
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme
import com.example.plohoystream.ui.theme.SurfaceElevated
import com.example.plohoystream.ui.theme.glassSurface

/** Amber banner shown on sub-screens whose changes only apply at the next go-live. */
@Composable
fun DimmedWhileLiveBanner(visible: Boolean) {
    if (!visible) return
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(com.example.plohoystream.ui.theme.HealthWarn.copy(alpha = 0.18f)).padding(12.dp),
    ) {
        Text(
            "These settings apply the next time you go live.",
            color = com.example.plohoystream.ui.theme.HealthWarn,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** A tappable grouped row that pushes a sub-screen. */
@Composable
fun NavRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated).clickable(onClick = onClick).padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = OnSurfaceWhite, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = OnSurfaceMuted)
        }
    }
}

/** A sub-screen scaffold: back header + content. */
@Composable
fun SubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Back header stays pinned; only the content below scrolls.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OnSurfaceWhite) }
            Text(title, color = OnSurfaceWhite, style = MaterialTheme.typography.titleLarge)
        }
        Column(
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

@Composable
fun SettingsPanel(viewModel: StreamViewModel, modifier: Modifier = Modifier) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = modifier.fillMaxSize().glassSurface().padding(16.dp)) {
        AnimatedContent(targetState = ui.settingsRoute, label = "settings-nav") { route ->
            when (route) {
                SettingsRoute.Root -> Column(modifier = Modifier.fillMaxSize()) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Settings", color = OnSurfaceWhite, style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = viewModel::closeSettings) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = OnSurfaceWhite)
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        NavRow("Destination", ui.settings.rtmpUrl.ifBlank { "Not set" }) { viewModel.navigateSettings(SettingsRoute.Destination) }
                        NavRow("Video", "${ui.settings.quality.height}p ${ui.settings.quality.fps}") { viewModel.navigateSettings(SettingsRoute.Video) }
                        NavRow("Audio", "${ui.settings.quality.audioBitrate / 1000} kbps") { viewModel.navigateSettings(SettingsRoute.Audio) }
                        NavRow("Camera", "") { viewModel.navigateSettings(SettingsRoute.Camera) }
                        NavRow("OBS Remote", if (ui.obsConnected) "Connected" else ui.settings.obsHost.ifBlank { "Not set" }) { viewModel.navigateSettings(SettingsRoute.Obs) }
                        NavRow("About & Reset", "") { viewModel.navigateSettings(SettingsRoute.About) }
                    }
                }
                SettingsRoute.Destination -> DestinationSettings(viewModel)
                SettingsRoute.Video -> VideoSettings(viewModel)
                SettingsRoute.Audio -> AudioSettings(viewModel)
                SettingsRoute.Camera -> CameraSettings(viewModel)
                SettingsRoute.About -> AboutSettings(viewModel)
                SettingsRoute.Obs -> ObsSettings(viewModel)
            }
        }
    }
}

@Preview(name = "root", widthDp = 360, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun SettingsRootPreview() = PlohoyTheme {
    Column { NavRow("Destination", "rtmp://…") {}; NavRow("Video", "1080p 30") {} }
}

@Preview(name = "dimmed-banner", widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun DimmedBannerPreview() = PlohoyTheme { DimmedWhileLiveBanner(visible = true) }
