package com.example.plohoystream.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A [SurfaceView]-backed preview. Reports its [Surface] up to the caller as it becomes
 * available (created) and unavailable (destroyed) so the camera session can be wired and
 * torn down at the right moments.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onSurface: (Surface?) -> Unit,
) {
    AndroidView(
        modifier = modifier,
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
