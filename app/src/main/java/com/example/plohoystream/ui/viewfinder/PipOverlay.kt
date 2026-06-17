package com.example.plohoystream.ui.viewfinder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
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

/** Corner radius for the PiP border; matches the GL-rendered video's rounded corners. */
val PIP_CORNER_RADIUS = 12.dp

/**
 * Interactive PiP layer over the preview. Drag to move (corner-snap on release); drag the bottom-
 * right handle to resize; single-tap to swap the big view and the PiP camera. Every edit mutates the
 * [Scene] via [onSceneChange] (live on preview AND stream); [onSwap] triggers the camera rebind.
 * Coordinates are normalized; converted with the measured overlay pixel size.
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
                .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(PIP_CORNER_RADIUS))
                // Single tap swaps which camera is the big view (the rendered swap is a rebind).
                .pointerInput(Unit) { detectTapGestures(onTap = { onSwap() }) }
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
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(44.dp)                                   // generous (invisible) touch target
                    .semantics { contentDescription = "Resize PiP" }
                    .pointerInput(boxW, boxH) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            // Bottom-right handle: dragging right OR down grows the PiP (aspect kept).
                            val deltaWf = (drag.x / boxW + drag.y / boxH) * 0.5f
                            onSceneChange(latestScene.updateLayer(SourceId.SECONDARY) {
                                SceneEdits.resizeKeepingCenter(it, it.width + deltaWf)
                            })
                        }
                    },
                contentAlignment = Alignment.BottomEnd,
            ) {
                // Subtle resize affordance: a corner-bracket whose corner is ROUNDED (a quarter-arc
                // matching the PiP border's rounded outline), sitting just inside the bottom-right
                // corner — instead of a filled square or a sharp 90° bracket.
                Canvas(
                    modifier = Modifier
                        .padding(end = 5.dp, bottom = 5.dp)
                        .size(16.dp),
                ) {
                    val stroke = 2.dp.toPx()
                    val r = 7.dp.toPx()                 // corner radius ≈ PiP border radius, inset
                    val x = size.width
                    val y = size.height
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(x, 0f)                    // top of the right arm
                        lineTo(x, y - r)                 // down the right edge
                        quadraticBezierTo(x, y, x - r, y) // rounded corner
                        lineTo(0f, y)                    // along the bottom edge
                    }
                    drawPath(
                        path,
                        Color.White.copy(alpha = 0.85f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = stroke,
                            cap = StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        ),
                    )
                }
            }
        }
    }
}
