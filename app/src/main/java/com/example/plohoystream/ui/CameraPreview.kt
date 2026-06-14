package com.example.plohoystream.ui

import android.graphics.Matrix
import android.graphics.RectF
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
import android.os.Build
import android.view.WindowManager

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

/** Raw display rotation constant (`Surface.ROTATION_0/90/180/270`). */
private fun displayRotation(context: Context): Int =
    if (Build.VERSION.SDK_INT >= 30) {
        context.display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.rotation
    }

/**
 * Applies the canonical Camera2 `configureTransform` [Matrix] (per the AOSP Camera2Basic
 * sample) so the sensor buffer appears upright AND undistorted inside the [TextureView]'s
 * [viewW] x [viewH] box. This is a **display-only** transform (never touches the
 * [SurfaceTexture] buffer), so the shared MediaCodec encoder surface is unaffected.
 *
 * The buffer ([bufferW] x [bufferH], landscape sensor output) is mapped to the view via
 * [Matrix.setRectToRect] against a **swapped** buffer rect, then uniformly scaled to fill, then
 * rotated by `90 * (rotation - 2)` — the setRectToRect step is what compensates the width/height
 * swap so a 90/270 rotation does not stretch the image. On the Seeker (`ROTATION_90`) this
 * yields a 270° rotation, calibrated against the device. Front cameras add a horizontal mirror.
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
    if (viewW == 0 || viewH == 0 || bufferW == 0 || bufferH == 0) return

    val rotation = displayRotation(context)
    val matrix = Matrix()
    val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
    // Buffer rect with swapped dimensions — the camera output is landscape but the view is
    // rotated, so we match the rotated footprint.
    val bufferRect = RectF(0f, 0f, bufferH.toFloat(), bufferW.toFloat())
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()

    if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = maxOf(viewH.toFloat() / bufferH, viewW.toFloat() / bufferW)
        matrix.postScale(scale, scale, centerX, centerY)
        matrix.postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
    } else if (rotation == Surface.ROTATION_180) {
        matrix.postRotate(180f, centerX, centerY)
    }
    if (isFrontFacing) {
        // Mirror horizontally for a natural selfie preview.
        matrix.postScale(-1f, 1f, centerX, centerY)
    }

    textureView.setTransform(matrix)
}
