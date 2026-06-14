package com.example.plohoystream.ui.viewfinder

import android.view.Surface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.camera.Camera2Controller
import com.example.plohoystream.camera.CameraCapabilities
import com.example.plohoystream.camera.CameraControls
import com.example.plohoystream.camera.CameraEnumerator
import com.example.plohoystream.camera.Facing
import com.example.plohoystream.stream.StreamState
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.CameraPreview
import com.example.plohoystream.ui.settings.SettingsPanel
import com.example.plohoystream.ui.theme.SignatureFloatSpring
import com.example.plohoystream.ui.theme.SurfaceBlack

@Composable
fun Viewfinder(viewModel: StreamViewModel) {
    val context = LocalContext.current
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val encoderSurface by viewModel.encoderSurface.collectAsStateWithLifecycle()
    val activeHdr by viewModel.activeHdr.collectAsStateWithLifecycle()

    val cameras = remember { CameraEnumerator.enumerate(context) }
    val controller = remember { Camera2Controller(context) }

    var facing by remember { mutableStateOf(Facing.BACK) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var zoom by remember { mutableStateOf(1f) }

    val config = remember(cameras, facing) { CameraCapabilities.select(cameras, facing) }
    DisposableEffect(Unit) { onDispose { controller.stop() } }

    LaunchedEffect(config, surface, encoderSurface, activeHdr) {
        val c = config; val preview = surface
        if (c != null && preview != null) {
            controller.start(c, listOfNotNull(preview, encoderSurface), hdr = activeHdr)
            controller.setZoom(zoom)
        }
    }

    val bufferW = config?.previewSize?.width ?: 1920
    val bufferH = config?.previewSize?.height ?: 1080
    // Landscape display: the 16:9 sensor buffer is shown wide (fills height, letterboxed
    // left/right), so the displayed aspect is width/height — not the portrait height/width.
    val previewAspect = bufferW.toFloat() / bufferH

    // Signature shrink: preview weight springs from 1.0 to ~0.55 when settings open.
    val previewWeight by animateFloatAsState(
        targetValue = if (ui.settingsOpen) 0.55f else 1f,
        animationSpec = SignatureFloatSpring, label = "shrink",
    )

    Row(modifier = Modifier.fillMaxSize().background(SurfaceBlack)) {
        Box(modifier = Modifier.weight(previewWeight).fillMaxHeight()) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                aspectRatio = previewAspect,
                bufferWidth = bufferW,
                bufferHeight = bufferH,
                sensorOrientation = config?.sensorOrientation ?: 90,
                isFrontFacing = facing == Facing.FRONT,
                onSurface = { surface = it },
            )
        }
        if (ui.settingsOpen) {
            // Clamp: on the first frame after settings opens, previewWeight is still ~1.0 (it
            // springs down to 0.55), which would make this weight 0 — Compose requires > 0.
            Box(modifier = Modifier.weight((1f - previewWeight).coerceAtLeast(0.001f)).fillMaxHeight().padding(8.dp)) {
                SettingsPanel(viewModel)
            }
        } else {
            ControlRail(
                state = ui.stream,
                elapsed = ui.elapsed,
                health = ui.health,
                bitrateKbps = ui.bitrateKbps,
                audioLevel = ui.audioLevel,
                lenses = config?.lenses.orEmpty(),
                selectedZoom = zoom,
                canGoLive = ui.canGoLive,
                errorReason = (ui.stream as? StreamState.Error)?.reason,
                onSelectLens = { lens -> zoom = lens.zoomRatio; controller.setLens(lens) },
                onFlip = { facing = CameraControls.opposite(facing); zoom = 1f },
                onGoLive = viewModel::goLive,
                onStop = viewModel::stop,
                onSettings = viewModel::openSettings,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
