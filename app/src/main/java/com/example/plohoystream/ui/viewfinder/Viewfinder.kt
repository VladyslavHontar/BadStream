package com.example.plohoystream.ui.viewfinder

import android.view.Surface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.example.plohoystream.ui.theme.SignatureDpSpring
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(SurfaceBlack)) {
        val totalWidth = maxWidth
        val railWidth = 220.dp
        val panelWidth = totalWidth * 0.58f
        // Signature spring: the right region's width animates rail ⇄ panel; the preview keeps
        // weight(1f) and naturally fills whatever space is left (never zero — no crash).
        val rightWidth by animateDpAsState(
            targetValue = if (ui.settingsOpen) panelWidth else railWidth,
            animationSpec = SignatureDpSpring,
            label = "rightWidth",
        )
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
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
            Box(modifier = Modifier.width(rightWidth).fillMaxHeight()) {
                AnimatedContent(
                    targetState = ui.settingsOpen,
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 3 }) togetherWith
                            (fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { it / 3 })
                    },
                    label = "rail-settings",
                ) { settingsOpen ->
                    if (settingsOpen) {
                        SettingsPanel(viewModel, modifier = Modifier.fillMaxSize().padding(8.dp))
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
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}
