package com.example.plohoystream.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.SurfaceElevated

@Composable
fun ObsSettings(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val s = ui.settings

    SubScreen(title = "OBS Remote", onBack = { viewModel.navigateSettings(SettingsRoute.Root) }) {
        DimmedWhileLiveBanner(visible = ui.isActive)

        // Connection status
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceElevated)
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Status", color = OnSurfaceWhite, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (ui.obsConnected) "Connected" else if (s.obsHost.isNotBlank()) "Connecting…" else "Disconnected",
                color = if (ui.obsConnected) androidx.compose.ui.graphics.Color(0xFF4CAF50) else OnSurfaceMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // Connection fields
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = s.obsHost, onValueChange = viewModel::setObsHost,
                label = { Text("OBS Host") }, singleLine = true, enabled = !ui.isActive,
                placeholder = { Text("192.168.1.100") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.obsPort.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { viewModel.setObsPort(it) } },
                label = { Text("Port") }, singleLine = true, enabled = !ui.isActive,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.obsPassword, onValueChange = viewModel::setObsPassword,
                label = { Text("Password") }, singleLine = true, enabled = !ui.isActive,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // OBS Stream control — single toggle (label/colour follow the live state).
        Button(
            onClick = { if (ui.obsStreaming) viewModel.obsStopStream() else viewModel.obsStartStream() },
            enabled = ui.obsConnected,
            colors = if (ui.obsStreaming) {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            } else {
                ButtonDefaults.buttonColors()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (ui.obsStreaming) "Stop OBS Stream" else "Start OBS Stream") }

        // Scene list
        if (ui.obsScenes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Scenes", color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)
                ui.obsScenes.forEach { sceneName ->
                    val isCurrent = sceneName == ui.obsCurrentScene
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isCurrent) SurfaceElevated else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { viewModel.obsSwitchScene(sceneName) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(sceneName, color = if (isCurrent) OnSurfaceWhite else OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
                        if (isCurrent) Text("●", color = androidx.compose.ui.graphics.Color(0xFF4CAF50), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // BRB auto-switch section
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Text("BRB Auto-Switch", color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)

            SceneDropdown(
                label = "Main Scene",
                selected = s.obsMainSceneName,
                scenes = ui.obsScenes,
                onSelect = viewModel::setObsMainScene,
            )
            SceneDropdown(
                label = "BRB Scene",
                selected = s.obsBrbSceneName,
                scenes = ui.obsScenes,
                onSelect = viewModel::setObsBrbScene,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Auto-switch on reconnect", color = OnSurfaceWhite, style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = s.obsAutoSwitchEnabled,
                    onCheckedChange = viewModel::setObsAutoSwitch,
                )
            }
        }
    }
}

/**
 * A scene picker backed by the live OBS scene list. When connected it offers the real scenes
 * (no typos possible); when not connected it still shows the saved value and a hint, so a
 * previously-chosen scene is never silently lost. A saved scene missing from the current list is
 * kept as a selectable option so renames/disconnects don't reset the choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SceneDropdown(
    label: String,
    selected: String,
    scenes: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = (scenes + selected).filter { it.isNotBlank() }.distinct()
    val enabled = options.isNotEmpty()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text(if (enabled) "Select scene" else "Connect to OBS to choose") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            enabled = enabled,
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { scene ->
                DropdownMenuItem(
                    text = { Text(scene) },
                    onClick = { onSelect(scene); expanded = false },
                )
            }
        }
    }
}
