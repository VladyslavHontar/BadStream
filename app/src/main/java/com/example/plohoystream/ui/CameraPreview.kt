package com.example.plohoystream.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A [SurfaceView]-backed preview that **center-crops** to the camera's aspect ratio so the
 * image is never stretched. [aspectRatio] is the preview's width / height in the current
 * display orientation (for a portrait phone with a landscape sensor that is
 * `previewSize.height / previewSize.width`). The SurfaceView is sized to fully cover the
 * container and the overflow is clipped — matching how native camera apps fill the screen.
 *
 * Reports its [Surface] up to the caller as it becomes available (created) / unavailable
 * (destroyed) so the camera session can be wired and torn down at the right moments.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    aspectRatio: Float,
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

        // Cover the container (center-crop): scale up so both dimensions are >= the container.
        val (wPx, hPx) = if (ratio > containerAspect) {
            // preview is wider than the container → match height, overflow (crop) width
            (containerH * ratio) to containerH
        } else {
            // preview is taller than the container → match width, overflow (crop) height
            containerW to (containerW / ratio)
        }

        val density = LocalDensity.current
        val wDp = with(density) { wPx.toDp() }
        val hDp = with(density) { hPx.toDp() }

        AndroidView(
            modifier = Modifier.size(wDp, hDp),
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = onSurface(holder.surface)
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null)
                    })
                }
            },
        )
    }
}
