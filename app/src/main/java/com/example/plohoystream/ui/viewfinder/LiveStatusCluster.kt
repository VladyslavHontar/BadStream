package com.example.plohoystream.ui.viewfinder

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.ui.theme.GlassOverVideo
import com.example.plohoystream.ui.theme.LiveRed
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme

/** LIVE pill (pulsing red dot + label) over the elapsed timer. [reconnecting] tints amber. */
@Composable
fun LiveStatusCluster(live: Boolean, elapsed: String, reconnecting: Boolean = false) {
    Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live) {
            val transition = rememberInfiniteTransition(label = "live-pulse")
            val pulse by transition.animateFloat(
                initialValue = 1f, targetValue = 0.3f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(GlassOverVideo)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier.size(7.dp).clip(CircleShape)
                        .background(if (reconnecting) com.example.plohoystream.ui.theme.HealthWarn else LiveRed)
                        .alpha(pulse),
                )
                Text(
                    if (reconnecting) "RECONNECTING" else "LIVE",
                    color = OnSurfaceWhite, fontWeight = FontWeight.SemiBold,
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                )
            }
        }
        Text(elapsed, color = OnSurfaceWhite, style = androidx.compose.material3.MaterialTheme.typography.displaySmall)
    }
}

@Preview(name = "idle", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun ClusterIdlePreview() = PlohoyTheme { LiveStatusCluster(live = false, elapsed = "00:00") }

@Preview(name = "live", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun ClusterLivePreview() = PlohoyTheme { LiveStatusCluster(live = true, elapsed = "01:23") }

@Preview(name = "reconnecting", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun ClusterReconnectingPreview() = PlohoyTheme { LiveStatusCluster(live = true, elapsed = "02:00", reconnecting = true) }
