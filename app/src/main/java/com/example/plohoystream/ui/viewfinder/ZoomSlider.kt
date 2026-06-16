package com.example.plohoystream.ui.viewfinder

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import java.util.Locale

/**
 * Floating zoom control overlaid on the preview: a translucent live-value pill above a continuous
 * track that spans the camera's real [range]. Fades in/out with [visible] (the caller shows it on
 * touch and auto-hides it). Replaces the discrete 0.6×/1×/2× lens chips.
 */
@Composable
fun ZoomSlider(
    zoom: Float,
    range: ClosedFloatingPointRange<Float>,
    visible: Boolean,
    onZoom: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val min = range.start
    val max = range.endInclusive.coerceAtLeast(min)
    AnimatedVisibility(
        visible = visible && max > min,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            ) {
                Text(
                    text = formatZoom(zoom),
                    color = OnSurfaceWhite,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Slider(
                value = zoom.coerceIn(min, max),
                onValueChange = onZoom,
                valueRange = min..max,
                colors = SliderDefaults.colors(
                    thumbColor = OnSurfaceWhite,
                    activeTrackColor = OnSurfaceWhite,
                    inactiveTrackColor = OnSurfaceMuted.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatZoom(min), color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
                Text(formatZoom(max), color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun formatZoom(r: Float): String =
    if (r == r.toInt().toFloat()) String.format(Locale.US, "%.0f×", r)
    else String.format(Locale.US, "%.1f×", r)
