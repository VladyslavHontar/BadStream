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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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

    // Zoom slider: spans CameraX's authoritative range; appears on touch and auto-hides. A bump to
    // [zoomNonce] (re)starts the hide timer so it stays visible while you adjust, then fades out.
    val zoomRangeFlow = remember(controller) {
        (controller as? CameraXController)?.zoomRange ?: MutableStateFlow(1f..1f)
    }
    val zoomRange by zoomRangeFlow.collectAsStateWithLifecycle()
    // Physical lenses (ultrawide/main/tele) + which one is bound, for the lens buttons.
    val lensesFlow = remember(controller) {
        (controller as? CameraXController)?.lenses ?: MutableStateFlow(emptyList())
    }
    val lenses by lensesFlow.collectAsStateWithLifecycle()
    val selectedLensFlow = remember(controller) {
        (controller as? CameraXController)?.selectedPhysicalId ?: MutableStateFlow<String?>(null)
    }
    val selectedPhysicalId by selectedLensFlow.collectAsStateWithLifecycle()
    var zoomVisible by remember { mutableStateOf(false) }
    var zoomNonce by remember { mutableStateOf(0) }
    LaunchedEffect(zoomNonce) {
        if (zoomNonce > 0) {
            zoomVisible = true
            delay(2500)
            zoomVisible = false
        }
    }
    fun applyZoom(target: Float) {
        zoom = target.coerceIn(zoomRange.start, zoomRange.endInclusive)
        controller.setZoom(zoom)
        zoomNonce++
    }

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

    // Force a fresh camera bind when the app returns to the foreground. On resume the TextureView
    // usually keeps its existing surface, so no surface-change fires and the session is never
    // rebound — the preview shows a frozen last frame until a camera flip forces a rebind. Re-issue
    // start() on ON_RESUME so the live preview always comes back.
    val currentConfig by rememberUpdatedState(config)
    val currentSurface by rememberUpdatedState(surface)
    val currentEncoder by rememberUpdatedState(encoderSurface)
    val currentHdr by rememberUpdatedState(activeHdr)
    val currentZoom by rememberUpdatedState(zoom)
    var resumed by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    resumed = true
                    val c = currentConfig
                    val targets = CameraTargets.select(currentSurface, currentEncoder)
                    if (c != null && targets.isNotEmpty()) {
                        controller.start(c, targets, hdr = currentHdr)
                        controller.setZoom(currentZoom)
                    }
                }
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Preview-time mic level meter: run the monitor only while foreground AND not streaming (the
    // streaming AudioEncoder owns the mic when live and publishes its own level).
    LaunchedEffect(resumed, ui.isActive) {
        if (resumed && !ui.isActive) LivePipeline.micMonitor.start() else LivePipeline.micMonitor.stop()
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
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // Pinch-to-zoom: scale the current ratio by the gesture; applyZoom clamps to
                    // CameraX's real range and reveals the slider.
                    .pointerInput(zoomRange) {
                        detectTransformGestures { _, _, zoomChange, _ -> applyZoom(zoom * zoomChange) }
                    }
                    // Single tap reveals the zoom slider for a few seconds (bumping the nonce
                    // restarts the auto-hide timer) without changing zoom.
                    .pointerInput(Unit) {
                        detectTapGestures { zoomNonce++ }
                    },
            ) {
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
                ZoomSlider(
                    zoom = zoom,
                    range = zoomRange,
                    visible = zoomVisible,
                    onZoom = { applyZoom(it) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(0.7f)
                        .padding(bottom = 24.dp),
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
                            lenses = lenses,
                            selectedPhysicalId = selectedPhysicalId,
                            canGoLive = ui.canGoLive,
                            errorReason = (ui.stream as? StreamState.Error)?.reason,
                            onSelectLens = { lens ->
                                zoom = 1f
                                (controller as? CameraXController)?.selectLens(lens.physicalId)
                            },
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
