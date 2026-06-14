package com.example.plohoystream.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Frosted-glass panel: translucent dark scrim + 1dp hairline border + rounded corners.
 * Scrim only — no real backdrop blur (the rail sits in the letterbox bars, not over video).
 */
fun Modifier.glassSurface(
    cornerRadius: Dp = 20.dp,
    scrim: Color = GlassScrim,
    hairline: Color = GlassHairline,
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .background(color = scrim, shape = shape)
        .border(width = 1.dp, color = hairline, shape = shape)
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    scrim: Color = GlassScrim,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.glassSurface(cornerRadius = cornerRadius, scrim = scrim).padding(12.dp)) {
        content()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun GlassSurfacePreview() {
    PlohoyTheme {
        GlassSurface(modifier = Modifier.size(160.dp, 80.dp)) {
            Text("Glass", color = OnSurfaceWhite)
        }
    }
}
