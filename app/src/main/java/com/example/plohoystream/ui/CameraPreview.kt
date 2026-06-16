package com.example.plohoystream.ui

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context

/**
 * A [TextureView]-backed camera preview that **letterboxes** (fits) the camera's aspect ratio
 * so the full frame is visible and never stretched. The leftover margins are black bars —
 * intentionally usable space for overlay controls.
 *
 * A [TextureView] is required because the camera renders the sensor buffer into the
 * [SurfaceTexture] in **sensor orientation**, which does not match the display orientation.
 * We correct this for **display only** via [TextureView.setTransform] (a Matrix applied while
 * compositing the TextureView). This never touches the underlying [SurfaceTexture] buffer, so
 * any *other* consumer of the same Camera2 session (e.g. the MediaCodec encoder surface) is
 * unaffected — the streamed video keeps its own orientation.
 *
 * @param aspectRatio displayed width / height of the upright frame in the current orientation.
 *   In landscape this is the wide `bufferWidth / bufferHeight` (16:9).
 * @param bufferWidth / [bufferHeight] the camera's chosen output size (sensor/landscape
 *   orientation); used to size the SurfaceTexture buffer the camera renders into.
 * @param sensorOrientation `CameraCharacteristics.SENSOR_ORIENTATION` of the active camera.
 * @param isFrontFacing whether the active camera is front-facing (applies a horizontal mirror).
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    aspectRatio: Float,
    bufferWidth: Int,
    bufferHeight: Int,
    sensorOrientation: Int,
    isFrontFacing: Boolean = false,
    onSurface: (Surface?) -> Unit,
) {
    val context = LocalContext.current
    BoxWithConstraints(
        modifier = modifier.clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        val containerAspect = if (containerH > 0f) containerW / containerH else 1f
        val ratio = if (aspectRatio > 0f) aspectRatio else containerAspect

        // Fit inside the container (letterbox): the whole frame is visible; the leftover
        // margins are black bars.
        val (wPx, hPx) = if (ratio > containerAspect) {
            containerW to (containerW / ratio)            // bars top & bottom
        } else {
            (containerH * ratio) to containerH            // bars left & right
        }

        val density = LocalDensity.current
        val wDp = with(density) { wPx.toDp() }
        val hDp = with(density) { hPx.toDp() }

        AndroidView(
            modifier = Modifier.size(wDp, hDp),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            if (bufferWidth > 0 && bufferHeight > 0) st.setDefaultBufferSize(bufferWidth, bufferHeight)
                            applyPreviewTransform(ctx, this@apply, width, height, bufferWidth, bufferHeight, isFrontFacing)
                            onSurface(Surface(st))
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                            if (bufferWidth > 0 && bufferHeight > 0) st.setDefaultBufferSize(bufferWidth, bufferHeight)
                            applyPreviewTransform(ctx, this@apply, width, height, bufferWidth, bufferHeight, isFrontFacing)
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            onSurface(null)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            update = { tv ->
                if (tv.isAvailable) {
                    applyPreviewTransform(context, tv, tv.width, tv.height, bufferWidth, bufferHeight, isFrontFacing)
                }
            },
        )
    }
}

/**
 * Display-only [TextureView] transform for the **CameraX** capture path.
 *
 * With CameraX the frame is rendered into this view's [SurfaceTexture] already in display
 * orientation — CameraX's `SurfaceOutput` transform folds in the sensor→display rotation before
 * our GL [com.example.plohoystream.camera.EgressSurfaceProcessor] draws it. So unlike the old raw
 * Camera2 path (which delivered a sensor-oriented landscape buffer and needed a compensating
 * rotation here), the TextureView must NOT re-rotate — doing so double-transforms (the symptom was
 * a 90° preview). We apply identity; the TextureView scales the already-upright buffer to fill the
 * (aspect-matched) view.
 *
 * CameraX already mirrors the front camera in its `SurfaceOutput` transform, so we must NOT add a
 * second flip here — doing so cancels it out and the selfie preview comes back un-mirrored. We
 * apply identity for every camera and let CameraX own orientation AND mirroring. [isFrontFacing]
 * is kept for call-site symmetry but intentionally unused.
 */
private fun applyPreviewTransform(
    context: Context,
    textureView: TextureView,
    viewW: Int,
    viewH: Int,
    bufferW: Int,
    bufferH: Int,
    isFrontFacing: Boolean,
) {
    if (viewW == 0 || viewH == 0) return
    textureView.setTransform(Matrix())  // identity — CameraX handles rotation + front mirror
}
