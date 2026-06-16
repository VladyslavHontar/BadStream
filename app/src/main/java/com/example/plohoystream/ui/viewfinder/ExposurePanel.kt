package com.example.plohoystream.ui.viewfinder

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.plohoystream.camera.CameraControls
import com.example.plohoystream.camera.ExposureMode
import com.example.plohoystream.camera.ExposureState
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Floating manual-exposure card overlaid on the preview. AUTO ⇄ MANUAL toggle; in MANUAL a shutter
 * slider (the motion-blur control, with a one-tap 180° snap for the current [fps]) and an ISO row
 * that's either a manual slider or, with Auto-ISO on, a read-out of the metered value. Fades with
 * [visible]; hidden entirely when the sensor lacks manual control ([ExposureState.supported]).
 */
@Composable
fun ExposurePanel(
    state: ExposureState,
    fps: Int,
    visible: Boolean,
    onAuto: () -> Unit,
    onManual: () -> Unit,
    onShutterNs: (Long) -> Unit,
    onIso: (Int) -> Unit,
    onAutoIso: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible && state.supported,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // AUTO | MANUAL segmented toggle.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SegPill("Auto", state.mode == ExposureMode.AUTO, onAuto)
                SegPill("Manual", state.mode == ExposureMode.MANUAL, onManual)
            }

            if (state.mode == ExposureMode.MANUAL) {
                val sMin = state.shutterRangeNs.first.coerceAtLeast(1L)
                val sMax = state.shutterRangeNs.last.coerceAtLeast(sMin + 1)
                val s180 = CameraControls.clampShutterNs(CameraControls.shutter180Ns(fps), sMin, sMax)

                // Shutter row: label + live value + 180° snap.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Shutter", color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        formatShutter(state.shutterNs),
                        color = OnSurfaceWhite,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    SegPill("180°", state.shutterNs == s180) { onShutterNs(s180) }
                }
                Slider(
                    value = shutterToPos(state.shutterNs, sMin, sMax),
                    onValueChange = { onShutterNs(posToShutter(it, sMin, sMax)) },
                    valueRange = 0f..1f,
                    colors = whiteSlider(),
                    modifier = Modifier.fillMaxWidth(),
                )

                // ISO row: label + value + Auto-ISO switch, then the manual slider (when not Auto).
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("ISO", color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.autoIso) "${state.iso} · auto" else "${state.iso}",
                        color = OnSurfaceWhite,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("Auto", color = OnSurfaceMuted, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(6.dp))
                    Switch(
                        checked = state.autoIso,
                        onCheckedChange = onAutoIso,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = OnSurfaceWhite,
                            uncheckedThumbColor = OnSurfaceWhite,
                            uncheckedTrackColor = OnSurfaceMuted.copy(alpha = 0.4f),
                        ),
                    )
                }
                val iMin = state.isoRange.first
                val iMax = state.isoRange.last.coerceAtLeast(iMin + 1)
                Slider(
                    value = state.iso.coerceIn(iMin, iMax).toFloat(),
                    onValueChange = { onIso(it.roundToInt()) },
                    valueRange = iMin.toFloat()..iMax.toFloat(),
                    enabled = !state.autoIso,
                    colors = whiteSlider(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SegPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) OnSurfaceWhite else Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color.Black else OnSurfaceWhite,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun whiteSlider() = SliderDefaults.colors(
    thumbColor = OnSurfaceWhite,
    activeTrackColor = OnSurfaceWhite,
    inactiveTrackColor = OnSurfaceMuted.copy(alpha = 0.4f),
    disabledThumbColor = OnSurfaceMuted,
    disabledActiveTrackColor = OnSurfaceMuted.copy(alpha = 0.5f),
)

// Shutter is mapped on a LOG scale — exposure time spans ~3 decades, so a linear track would bunch
// every useful (fast) shutter into a sliver. Position 0 = longest exposure, 1 = shortest.
private fun shutterToPos(ns: Long, min: Long, max: Long): Float {
    val lo = ln(min.toDouble()); val hi = ln(max.toDouble())
    return ((ln(ns.coerceIn(min, max).toDouble()) - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f)
}

private fun posToShutter(pos: Float, min: Long, max: Long): Long {
    val lo = ln(min.toDouble()); val hi = ln(max.toDouble())
    return exp(lo + pos.coerceIn(0f, 1f) * (hi - lo)).roundToLong().coerceIn(min, max)
}

private fun formatShutter(ns: Long): String {
    if (ns <= 0) return "—"
    val seconds = ns / 1_000_000_000.0
    return if (seconds >= 1.0) String.format(java.util.Locale.US, "%.1fs", seconds)
    else "1/${(1.0 / seconds).roundToInt()}"
}
