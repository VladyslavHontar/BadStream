package com.example.plohoystream.ui.viewfinder

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.stream.ConnectionHealth
import com.example.plohoystream.ui.theme.HealthBad
import com.example.plohoystream.ui.theme.HealthGood
import com.example.plohoystream.ui.theme.HealthWarn
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme
import com.example.plohoystream.ui.theme.colorSpring

/** Colored health dot (green→amber→red, animated) + bitrate text. */
@Composable
fun HealthIndicator(health: ConnectionHealth, bitrateKbps: Int) {
    val target = when (health) {
        ConnectionHealth.Good -> HealthGood
        ConnectionHealth.Warn -> HealthWarn
        ConnectionHealth.Bad -> HealthBad
    }
    val color by animateColorAsState(targetValue = target, animationSpec = colorSpring(), label = "health")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text("$bitrateKbps kbps", color = OnSurfaceWhite, style = MaterialTheme.typography.labelLarge)
    }
}

@Preview(name = "good", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun HealthGoodPreview() = PlohoyTheme { HealthIndicator(ConnectionHealth.Good, 5980) }

@Preview(name = "warn", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun HealthWarnPreview() = PlohoyTheme { HealthIndicator(ConnectionHealth.Warn, 4200) }

@Preview(name = "bad", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun HealthBadPreview() = PlohoyTheme { HealthIndicator(ConnectionHealth.Bad, 1500) }
