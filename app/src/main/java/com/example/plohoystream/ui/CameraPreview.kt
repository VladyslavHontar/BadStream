package com.example.plohoystream.ui

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A [TextureView]-backed camera preview that **letterboxes** (fits) the camera's aspect ratio
 * so the full frame is visible and never stretched. The leftover margins are black bars —
 * intentionally usable space for overlay controls.
 *
 * A [TextureView] (not a SurfaceView) is required: it applies the camera's orientation
 * transform when rendering the [SurfaceTexture], so the landscape sensor buffer shows upright.
 * A plain SurfaceView shows the raw buffer and would appear rotated/stretched.
 *
 * @param aspectRatio displayed width / height in the current orientation. For a portrait
 *   phone with a landscape sensor that is `previewSize.height / previewSize.width`.
 * @param bufferWidth / [bufferHeight] the camera's chosen output size (sensor/landscape
 *   orientation); used to size the SurfaceTexture buffer the camera renders into.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    aspectRatio: Float,
    bufferWidth: Int,
    bufferHeight: Int,
    onSurface: (Surface?) -> Unit,
) {
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
                            onSurface(Surface(st))
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                            if (bufferWidth > 0 && bufferHeight > 0) st.setDefaultBufferSize(bufferWidth, bufferHeight)
                        }

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            onSurface(null)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
        )
    }
}
