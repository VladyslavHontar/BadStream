package com.example.plohoystream.ui.viewfinder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.camera.CameraLens
import com.example.plohoystream.ui.theme.PlohoyTheme

/** Lens/zoom chips. Reuses the existing camera [CameraLens] model. */
@Composable
fun ZoomChips(
    lenses: List<CameraLens>,
    selectedZoom: Float,
    onSelect: (CameraLens) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lenses.size <= 1) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        lenses.forEach { lens ->
            FilterChip(
                selected = selectedZoom == lens.zoomRatio,
                onClick = { onSelect(lens) },
                label = { Text(lens.label) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ZoomChipsPreview() = PlohoyTheme {
    ZoomChips(
        lenses = listOf(CameraLens(label = "0.5x", zoomRatio = 0.5f), CameraLens(label = "1x", zoomRatio = 1f), CameraLens(label = "2x", zoomRatio = 2f)),
        selectedZoom = 1f, onSelect = {},
    )
}
