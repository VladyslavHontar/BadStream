package com.example.plohoystream.ui.viewfinder

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.stream.StreamState
import com.example.plohoystream.ui.theme.LiveRed
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme
import com.example.plohoystream.ui.theme.SignatureDpSpring

/**
 * Go Live ring (white) morphs to the rounded-square red Stop via shape/size interpolation.
 * In Connecting, the ring pulses. [enabled] gates the tap in setup.
 */
@Composable
fun GoLiveButton(state: StreamState, enabled: Boolean, onGoLive: () -> Unit, onStop: () -> Unit) {
    val active = state is StreamState.Live || state is StreamState.Stopping || state is StreamState.Reconnecting
    val connecting = state is StreamState.Connecting
    val innerCorner by animateDpAsState(targetValue = if (active) 8.dp else 28.dp, animationSpec = SignatureDpSpring, label = "corner")
    val innerSize by animateDpAsState(targetValue = if (active) 26.dp else 56.dp, animationSpec = SignatureDpSpring, label = "size")

    val pulse = if (connecting) {
        val t = rememberInfiniteTransition(label = "connect")
        t.animateFloat(1f, 0.4f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "p").value
    } else 1f

    Box(
        modifier = Modifier
            .size(72.dp)
            .border(BorderStroke(4.dp, OnSurfaceWhite), RoundedCornerShape(36.dp))
            .alpha(if (connecting) pulse else 1f)
            .clickable(enabled = enabled || active) { if (active) onStop() else onGoLive() }
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(innerCorner))
                .background(if (active) LiveRed else OnSurfaceWhite)
                .alpha(if (enabled || active) 1f else 0.4f),
        )
    }
}

@Preview(name = "setup", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun GoLiveSetupPreview() = PlohoyTheme { GoLiveButton(StreamState.Idle, enabled = true, {}, {}) }

@Preview(name = "connecting", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun GoLiveConnectingPreview() = PlohoyTheme { GoLiveButton(StreamState.Connecting, enabled = false, {}, {}) }

@Preview(name = "live", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun GoLiveLivePreview() = PlohoyTheme { GoLiveButton(StreamState.Live, enabled = false, {}, {}) }
