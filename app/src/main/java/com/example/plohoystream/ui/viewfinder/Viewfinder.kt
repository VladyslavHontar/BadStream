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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
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
    // Manual exposure (shutter for motion blur + ISO). Panel toggled by a button over the preview.
    val exposureFlow = remember(controller) {
        (controller as? CameraXController)?.exposure
            ?: MutableStateFlow(com.example.plohoystream.camera.ExposureState())
    }
    val exposure by exposureFlow.collectAsStateWithLifecycle()
    var exposureOpen by remember { mutableStateOf(false) }
    var scene by remember { mutableStateOf(com.example.plohoystream.camera.scene.Scene.SINGLE) }
    val dualOn = scene.isDual
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

    // One-shot capability probe: can this device run FRONT + BACK concurrently? Logged so we can
    // confirm dual-camera feasibility on real hardware before enabling the mode. Held locally
    // (like facing/zoom); a later plan surfaces it as a toggle.
    var dualSupported by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
                val combos = com.example.plohoystream.camera.ConcurrentCameraProbe.facingCombos(provider)
                combos to com.example.plohoystream.camera.ConcurrentCameraProbe.supportsFrontBack(combos)
            }
        }.onSuccess { (combos, supported) ->
            dualSupported = supported
            android.util.Log.i(
                "Viewfinder",
                "concurrent-camera probe: frontBackSupported=$supported combos=$combos",
            )
        }.onFailure { android.util.Log.w("Viewfinder", "concurrent-camera probe failed", it) }
    }

    val config = remember(cameras, facing, ui.settings.quality.fps) {
        CameraCapabilities.select(cameras, facing, ui.settings.quality.fps)
    }

    // Single source of truth for (re)binding the camera. Keyed on dualOn so toggling dual mode
    // rebinds here — the toggle only flips dualOn, never binds directly (two binders would race and
    // clobber each other). In dual mode bind front+back concurrently; otherwise the single camera.
    LaunchedEffect(config, surface, encoderSurface, activeHdr, dualOn) {
        val c = config ?: return@LaunchedEffect
        val targets = CameraTargets.select(surface, encoderSurface)
        if (targets.isEmpty()) {
            controller.stop()           // idle + no preview (e.g. backgrounded while not live)
        } else {
            val cx = controller as? CameraXController
            if (dualOn && cx != null) {
                cx.startDual(
                    primaryFacing = facing, scene = scene, targets = targets,
                    onFailed = { scene = com.example.plohoystream.camera.scene.Scene.SINGLE },
                )
            } else {
                controller.start(c, targets, hdr = activeHdr)
                controller.setZoom(zoom)
            }
        }
    }

    // Live PiP edits (drag/resize): push the scene to the GL pipeline without rebinding cameras.
    LaunchedEffect(scene) {
        val cx = controller as? CameraXController ?: return@LaunchedEffect
        if (scene.isDual) cx.setScene(scene)
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
    val currentDualOn by rememberUpdatedState(dualOn)
    val currentFacing by rememberUpdatedState(facing)
    val currentScene by rememberUpdatedState(scene)
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
                        val cx = controller as? CameraXController
                        if (currentDualOn && cx != null) {
                            cx.startDual(primaryFacing = currentFacing, scene = currentScene, targets = targets, onFailed = {})
                        } else {
                            controller.start(c, targets, hdr = currentHdr)
                            controller.setZoom(currentZoom)
                        }
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
                val cam = controller as? CameraXController
                if (exposure.supported) {
                    // Top-left toggle for the exposure panel; label reflects current state.
                    val expLabel = if (exposure.mode == com.example.plohoystream.camera.ExposureMode.MANUAL) "EXP•" else "EXP"
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(
                                if (exposureOpen) com.example.plohoystream.ui.theme.OnSurfaceWhite
                                else androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)
                            )
                            .clickable { exposureOpen = !exposureOpen }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        androidx.compose.material3.Text(
                            expLabel,
                            color = if (exposureOpen) androidx.compose.ui.graphics.Color.Black
                                    else com.example.plohoystream.ui.theme.OnSurfaceWhite,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        )
                    }
                    ExposurePanel(
                        state = exposure,
                        fps = config?.targetFps ?: 30,
                        visible = exposureOpen,
                        onAuto = { cam?.setExposureAuto() },
                        onManual = { cam?.setExposureManual() },
                        onShutterNs = { cam?.setShutterNs(it) },
                        onIso = { cam?.setIso(it) },
                        onAutoIso = { cam?.setAutoIso(it) },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 12.dp, top = 58.dp)
                            .width(300.dp),
                    )
                }
                if (dualOn) {
                    PipOverlay(
                        scene = scene,
                        onSceneChange = { scene = it },
                        onSwap = {
                            // Blur over the rebind, then flip which camera is the big (primary) view;
                            // the binder (config depends on facing) rebinds dual with the high-res slot
                            // following the new primary.
                            (controller as? CameraXController)?.beginCameraTransition()
                            facing = CameraControls.opposite(facing)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
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
                                (controller as? CameraXController)?.beginCameraTransition()  // stream freeze-blur
                                zoom = 1f
                                (controller as? CameraXController)?.selectLens(lens.physicalId)
                            },
                            onFlip = {
                                (controller as? CameraXController)?.beginCameraTransition()  // stream freeze-blur
                                facing = CameraControls.opposite(facing); zoom = 1f
                            },
                            onGoLive = viewModel::goLive,
                            onStop = viewModel::stop,
                            onSettings = viewModel::openSettings,
                            showObsScenes = ui.obsSceneSwitcherVisible,
                            obsScenes = ui.obsScenes,
                            obsCurrentScene = ui.obsCurrentScene,
                            onSwitchScene = viewModel::obsSwitchScene,
                            dualSupported = dualSupported,
                            dualOn = dualOn,
                            // Only flip the flag; the binding LaunchedEffect (keyed on dualOn) does the
                            // actual (re)bind, so there's a single binder and no race with start().
                            onToggleDual = {
                                scene = if (scene.isDual) com.example.plohoystream.camera.scene.Scene.SINGLE
                                        else com.example.plohoystream.camera.scene.Scene.dual()
                            },
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}
