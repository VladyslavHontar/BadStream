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
                            applyPreviewTransform(ctx, this@apply, width, height, sensorOrientation, isFrontFacing)
                            onSurface(Surface(st))
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                            if (bufferWidth > 0 && bufferHeight > 0) st.setDefaultBufferSize(bufferWidth, bufferHeight)
                            applyPreviewTransform(ctx, this@apply, width, height, sensorOrientation, isFrontFacing)
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
                    applyPreviewTransform(context, tv, tv.width, tv.height, sensorOrientation, isFrontFacing)
                }
            },
        )
    }
}

/** Current display rotation in degrees (0/90/180/270). */
private fun displayRotationDegrees(context: Context): Int {
    val rotation = if (Build.VERSION.SDK_INT >= 30) {
        context.display?.rotation ?: Surface.ROTATION_0
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.rotation
    }
    return when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
}

/**
 * Computes and applies the display-only [Matrix] that makes the sensor buffer appear upright
 * and undistorted inside the [TextureView]'s [viewW] x [viewH] box.
 *
 * The buffer the camera renders is in *sensor* orientation. The clockwise rotation needed to
 * make it upright for the current display is `(sensorOrientation - displayRotation + 360) % 360`
 * for back cameras (front cameras add their own mirror). After rotating, when the rotation is
 * 90/270 the content's effective aspect is swapped relative to the view box, so we scale it back
 * up to fill the box (the box itself is already letterboxed to 16:9 by the caller).
 */
private fun applyPreviewTransform(
    context: Context,
    textureView: TextureView,
    viewW: Int,
    viewH: Int,
    sensorOrientation: Int,
    isFrontFacing: Boolean,
) {
    if (viewW == 0 || viewH == 0) return

    val displayRotation = displayRotationDegrees(context)
    // Clockwise degrees to rotate the sensor buffer so it reads upright on the display.
    val rotation = if (isFrontFacing) {
        (sensorOrientation + displayRotation) % 360
    } else {
        (sensorOrientation - displayRotation + 360) % 360
    }

    val matrix = Matrix()
    val cx = viewW / 2f
    val cy = viewH / 2f

    // The TextureView stretches the buffer to fill the view box by default. We rotate around
    // the centre. When the rotation is 90/270 the buffer's wide/tall axes are swapped relative
    // to the box, which would shrink the content; counter it by scaling so it fills the box.
    matrix.postRotate(rotation.toFloat(), cx, cy)
    if (rotation == 90 || rotation == 270) {
        val scale = maxOf(viewW.toFloat() / viewH, viewH.toFloat() / viewW)
        matrix.postScale(scale, scale, cx, cy)
    }
    if (isFrontFacing) {
        // Mirror horizontally for a natural selfie preview.
        matrix.postScale(-1f, 1f, cx, cy)
    }

    textureView.setTransform(matrix)
}
