package com.example.plohoystream.ui.viewfinder

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.ui.theme.HealthBad
import com.example.plohoystream.ui.theme.HealthGood
import com.example.plohoystream.ui.theme.HealthWarn
import com.example.plohoystream.ui.theme.PlohoyTheme
import com.example.plohoystream.ui.theme.SignatureFloatSpring

/**
 * Visual sensitivity of the meter. Raw RMS of speech is low (~0.05–0.2), so without gain the bar
 * barely moves. Raise [METER_SENSITIVITY] for a livelier meter; lower it if it pins to the top.
 * Visual only — does NOT change the audio sent to the stream.
 */
private const val METER_SENSITIVITY = 7f

/** Horizontal audio meter: green (< .7) → amber (< .9) → red (clipping). [level] is 0..1. */
@Composable
fun AudioMeter(level: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(targetValue = (level * METER_SENSITIVITY).coerceIn(0f, 1f), animationSpec = SignatureFloatSpring, label = "audio")
    val color = when {
        animated >= 0.9f -> HealthBad
        animated >= 0.7f -> HealthWarn
        else -> HealthGood
    }
    Canvas(modifier = modifier.fillMaxWidth().height(6.dp)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(color = HealthGood.copy(alpha = 0.15f), size = size, cornerRadius = r)
        drawRoundRect(
            color = color,
            topLeft = Offset.Zero,
            size = Size(size.width * animated, size.height),
            cornerRadius = r,
        )
    }
}

@Preview(name = "quiet", widthDp = 120, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun AudioQuietPreview() = PlohoyTheme { AudioMeter(level = 0.2f) }

@Preview(name = "loud", widthDp = 120, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun AudioLoudPreview() = PlohoyTheme { AudioMeter(level = 0.8f) }

@Preview(name = "clip", widthDp = 120, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun AudioClipPreview() = PlohoyTheme { AudioMeter(level = 0.97f) }
