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
import com.example.plohoystream.camera.CameraCapabilities
import com.example.plohoystream.camera.CameraControls
import com.example.plohoystream.camera.CameraEnumerator
import com.example.plohoystream.camera.CameraTargets
import com.example.plohoystream.camera.CameraXController
import com.example.plohoystream.camera.Facing
import com.example.plohoystream.camera.QualityOption
import com.example.plohoystream.stream.LivePipeline
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
    val controller = LivePipeline.camera

    var facing by remember { mutableStateOf(Facing.BACK) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var zoom by remember { mutableStateOf(1f) }

    // Capability-driven quality menu for the ACTIVE camera (CameraX CameraInfo) intersected with
    // the device encoder gate. Hoisted here so the settings UI (Task 9) can consume it. Recomputed
    // on facing change. ProcessCameraProvider.getInstance(...).get() blocks, so it runs on IO.
    var qualityOptions by remember { mutableStateOf<List<QualityOption>>(emptyList()) }
    LaunchedEffect(facing) {
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
                val selector = if (facing == Facing.FRONT) androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA
                else androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
                val info = provider.getCameraInfo(selector)
                val probe = com.example.plohoystream.camera.CameraXComboProbe(info)
                com.example.plohoystream.camera.CaptureMenu.generate(
                    com.example.plohoystream.camera.CaptureMenu.CANDIDATES, probe,
                    LivePipeline.encoderGate,
                )
            }
        }.onSuccess { qualityOptions = it }
            .onFailure { android.util.Log.w("Viewfinder", "menu generation failed", it) }
    }

    val config = remember(cameras, facing, ui.settings.quality.fps) {
        CameraCapabilities.select(cameras, facing, ui.settings.quality.fps)
    }

    LaunchedEffect(config, surface, encoderSurface, activeHdr) {
        val c = config ?: return@LaunchedEffect
        val targets = CameraTargets.select(surface, encoderSurface)
        if (targets.isEmpty()) {
            controller.stop()           // idle + no preview (e.g. backgrounded while not live)
        } else {
            controller.start(c, targets, hdr = activeHdr)
            controller.setZoom(zoom)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // Preview is going away: null the controller's preview surface so the CameraX backend
            // enters encoder-only (backgrounded) mode correctly — the encoder target is no longer
            // misclassified as the preview.
            (controller as? CameraXController)?.setPreviewSurface(null)
            // Detach preview only. If streaming, the camera must keep feeding the encoder, so
            // reconfigure to whatever targets remain (encoder-only) rather than stopping. If
            // idle (no encoder surface), stop the camera.
            val enc = encoderSurface
            val targets = CameraTargets.select<Surface>(null, enc)
            if (targets.isEmpty()) controller.stop()
            else config?.let { controller.start(it, targets, hdr = activeHdr) }
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
                    onSurface = {
                        surface = it
                        // The controller's preview surface is the source of truth for the preview.
                        (controller as? CameraXController)?.setPreviewSurface(it)
                    },
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
                        SettingsPanel(
                            viewModel,
                            qualityOptions = qualityOptions,
                            codecOptions = LivePipeline.codecOptions,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                        )
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
