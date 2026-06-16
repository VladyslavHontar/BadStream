package com.example.plohoystream.ui.viewfinder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.ui.theme.GlassHairline
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme

/** Dark glass scrim for the dropdown surface — matches the rail's frosted look, opaque enough to
 *  stay readable when it floats over the bright camera preview. */
private val SceneMenuScrim = Color(0xF2141416)
private val ChipScrim = Color(0x59000000) // ~35% black, a subtle fill inside the glass rail

/**
 * Compact OBS scene switcher for the right control rail. Collapsed, it shows the current scene;
 * tapping opens a floating dropdown of all scenes. The list is a popup (its own window), so it is
 * never clipped by — and never fights for space with — the rail's tight vertical layout while live.
 * Selecting a scene calls [onSwitchScene] and dismisses. The caller gates visibility (only when OBS
 * is connected with a non-empty list) — see StreamUiState.obsSceneSwitcherVisible.
 *
 * The chip and the popup are styled to match the rail's glass aesthetic: dark scrim, white hairline
 * border, rounded corners.
 */
@Composable
fun ObsSceneChip(
    scenes: List<String>,
    currentScene: String?,
    onSwitchScene: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val chipShape = RoundedCornerShape(12.dp)
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(chipShape)
                .background(ChipScrim)
                .border(1.dp, GlassHairline, chipShape)
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Scene: ${currentScene ?: "—"}",
                color = OnSurfaceWhite,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse scenes" else "Expand scenes",
                tint = OnSurfaceMuted,
            )
        }
        // A popup, so the list floats above the rail (and the camera preview) instead of being
        // squeezed into the rail column where it would be clipped or sit behind the bottom actions.
        // The system repositions it to stay on-screen and makes it scrollable past the viewport.
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = SceneMenuScrim,
            border = BorderStroke(1.dp, GlassHairline),
        ) {
            scenes.forEach { scene ->
                val isCurrent = scene == currentScene
                DropdownMenuItem(
                    text = {
                        Text(
                            text = scene,
                            color = if (isCurrent) OnSurfaceWhite else OnSurfaceMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    trailingIcon = if (isCurrent) {
                        { Text("●", color = Color(0xFF34C759), style = MaterialTheme.typography.bodyMedium) }
                    } else {
                        null
                    },
                    colors = MenuDefaults.itemColors(
                        textColor = OnSurfaceWhite,
                        trailingIconColor = Color(0xFF34C759),
                    ),
                    onClick = { onSwitchScene(scene); expanded = false },
                )
            }
        }
    }
}

@Preview(name = "scene chip", widthDp = 200, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun ObsSceneChipPreview() = PlohoyTheme {
    ObsSceneChip(scenes = listOf("Main", "BRB", "Starting Soon"), currentScene = "Main", onSwitchScene = {})
}
