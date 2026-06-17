package com.example.plohoystream.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.plohoystream.camera.scene.Scene
import com.example.plohoystream.camera.scene.SceneEdits
import com.example.plohoystream.camera.scene.SourceId
import kotlin.math.roundToInt

/**
 * Interactive PiP layer over the preview. Drag to move (corner-snap on release); drag the bottom-
 * right handle to resize; tap the swap button to exchange the big view and the PiP camera. Every
 * gesture mutates the [Scene] via [onSceneChange] (live on preview AND stream); [onSwap] triggers
 * the camera rebind. Coordinates are normalized; converted with the measured overlay pixel size.
 */
@Composable
fun PipOverlay(
    scene: Scene,
    onSceneChange: (Scene) -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pip = scene.layer(SourceId.SECONDARY)?.rect ?: return
    // The gesture coroutines below are keyed only on boxW/boxH (so a scene change mid-drag doesn't
    // cancel the in-flight pointer tracking); they read the LATEST scene through this snapshot so each
    // drag delta accumulates from the current position rather than the stale pre-drag one.
    val latestScene by rememberUpdatedState(scene)
    var boxW by remember { mutableStateOf(1) }
    var boxH by remember { mutableStateOf(1) }
    val density = LocalDensity.current

    Box(modifier = modifier.onSizeChanged { boxW = it.width.coerceAtLeast(1); boxH = it.height.coerceAtLeast(1) }) {
        val pipWpx = pip.width * boxW
        val pipHpx = pip.height * boxH
        Box(
            modifier = Modifier
                .offset { IntOffset((pip.left * boxW).roundToInt(), (pip.top * boxH).roundToInt()) }
                .size(with(density) { pipWpx.toDp() }, with(density) { pipHpx.toDp() })
                .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .pointerInput(boxW, boxH) {
                    detectDragGestures(
                        onDrag = { change, drag ->
                            change.consume()
                            val cur = latestScene.layer(SourceId.SECONDARY)?.rect ?: return@detectDragGestures
                            val cx = cur.centerX + drag.x / boxW
                            val cy = cur.centerY + drag.y / boxH
                            onSceneChange(latestScene.updateLayer(SourceId.SECONDARY) { SceneEdits.moveTo(it, cx, cy) })
                        },
                        onDragEnd = {
                            onSceneChange(latestScene.updateLayer(SourceId.SECONDARY) {
                                SceneEdits.snapToCorner(it, Scene.PIP_MARGIN)
                            })
                        },
                    )
                },
        ) {
            IconButton(
                onClick = onSwap,
                modifier = Modifier.align(Alignment.TopStart).size(36.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
            ) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap cameras", tint = Color.White)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(44.dp)                                   // >=48dp-ish touch target for one-handed use
                    .semantics { contentDescription = "Resize PiP" }
                    .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .pointerInput(boxW, boxH) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            // Bottom-right handle: dragging right OR down grows the (square) PiP.
                            val deltaWf = (drag.x / boxW + drag.y / boxH) * 0.5f
                            onSceneChange(latestScene.updateLayer(SourceId.SECONDARY) {
                                SceneEdits.resizeKeepingCenter(it, it.width + deltaWf)
                            })
                        }
                    },
            )
        }
    }
}
