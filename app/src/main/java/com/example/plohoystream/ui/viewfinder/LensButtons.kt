package com.example.plohoystream.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.plohoystream.camera.LensOption
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import kotlin.math.abs

/**
 * Physical lens picker (ultrawide / main / tele) shown where the old 0.6×/1×/2× chips were.
 * Tapping a lens switches the bound physical camera. Hidden when the device exposes <2 lenses.
 *
 * [selectedPhysicalId] null means the logical default (the ~1× main lens), so that one is shown
 * selected until the user explicitly picks a lens.
 */
@Composable
fun LensButtons(
    lenses: List<LensOption>,
    selectedPhysicalId: String?,
    onSelect: (LensOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lenses.size < 2) return
    val mainId = lenses.minByOrNull { abs(it.zoomRatio - 1f) }?.physicalId
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        lenses.forEach { lens ->
            val selected = lens.physicalId == (selectedPhysicalId ?: mainId)
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (selected) OnSurfaceWhite else Color.Black.copy(alpha = 0.45f))
                    .clickable { onSelect(lens) }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = lens.label,
                    color = if (selected) Color.Black else OnSurfaceWhite,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}
