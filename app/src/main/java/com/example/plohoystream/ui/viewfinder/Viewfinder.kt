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
import androidx.compose.foundation.layout.aspectRatio
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
    // Secondary (PiP) camera's displayed aspect, so the PiP box can match the source (no crop).
    val pipAspectFlow = remember(controller) {
        (controller as? CameraXController)?.secondaryPipAspect ?: MutableStateFlow(1f)
    }
    val pipAspect by pipAspectFlow.collectAsStateWithLifecycle()
    var scene by remember { mutableStateOf(com.example.plohoystream.camera.scene.Scene.SINGLE) }
    val dualOn = scene.isDual
    // Set when an UNAVAILABLE lens chip is tapped in dual: the controller's onExitDualRequested hands
    // the LensOption here, which drives the "drop the PiP and switch?" confirm dialog below.
    var exitDualChip by remember { mutableStateOf<com.example.plohoystream.camera.LensOption?>(null) }
    // Which facing is the PRIMARY (big) view in dual. Tracked SEPARATELY from [facing] so a dual swap
    // can flip it WITHOUT changing [facing] (which would recompute [config] and re-run the binding
    // LaunchedEffect → a full dual reopen). The swap is instant (relabel only) via cx.dualSwap.
    var dualPrimary by remember { mutableStateOf(Facing.BACK) }
    // Remember the dual layout so toggling dual off then on restores the user's PiP position/size
    // rather than snapping back to the default corner.
    var lastDualScene by remember { mutableStateOf(com.example.plohoystream.camera.scene.Scene.dual()) }
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
        val cx = controller as? CameraXController
        if (dualOn && cx != null) cx.dualSetZoom(zoom) else controller.setZoom(zoom)
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

    // Tap-to-exit-dual: an UNAVAILABLE chip tap routes through dualSelectChip → onExitDualRequested,
    // which surfaces the LensOption for the confirm dialog. Registered once on the controller.
    LaunchedEffect(controller) {
        (controller as? CameraXController)?.onExitDualRequested = { lens -> exitDualChip = lens }
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
                // Dual ON: open both cameras via DualCameraSession. preview = the on-screen surface;
                // encoder = the non-preview target. primaryFacing is [dualPrimary] (NOT [facing]) so a
                // swap can flip the big view without re-running this binder.
                val previewTarget = surface
                val encoderTarget = targets.firstOrNull { it !== previewTarget }
                cx.enterDual(
                    primaryFacing = dualPrimary,
                    scene = scene,
                    preview = previewTarget,
                    encoder = encoderTarget,
                    onFailed = { scene = com.example.plohoystream.camera.scene.Scene.SINGLE },
                )
            } else {
                // Dual OFF (or no CameraXController): tear down any dual session first, then bind single.
                cx?.exitDual()
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

    // Size the PiP box to the secondary camera's source aspect (no cropping). Fires when the source
    // aspect resolves (bind/swap) or dual turns on — not on every drag, so it never fights gestures.
    // regionAspect is the GL output (16:9 camera surface) the composite renders into.
    LaunchedEffect(pipAspect, dualOn, config) {
        if (!dualOn || pipAspect <= 0f) return@LaunchedEffect   // 0f = sentinel before the source aspect resolves
        val region = config?.let { it.previewSize.width.toFloat() / it.previewSize.height } ?: (16f / 9f)
        scene = scene.updateLayer(com.example.plohoystream.camera.scene.SourceId.SECONDARY) {
            com.example.plohoystream.camera.scene.SceneEdits.setAspect(it, region, pipAspect)
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
    val currentDualOn by rememberUpdatedState(dualOn)
    val currentFacing by rememberUpdatedState(facing)
    val currentDualPrimary by rememberUpdatedState(dualPrimary)
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
                            // Resume in dual: re-enter the standalone dual session (it stops itself
                            // first if already started). primaryFacing tracks the dual swap state.
                            val previewTarget = currentSurface
                            val encoderTarget = targets.firstOrNull { it !== previewTarget }
                            cx.enterDual(
                                primaryFacing = currentDualPrimary,
                                scene = currentScene,
                                preview = previewTarget,
                                encoder = encoderTarget,
                                onFailed = {},
                            )
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
            val cx = controller as? CameraXController
            // Preview is going away: null the controller's preview surface so the CameraX backend
            // enters encoder-only (backgrounded) mode correctly — the encoder target is no longer
            // misclassified as the preview.
            cx?.setPreviewSurface(null)
            val enc = encoderSurface
            val targets = CameraTargets.select<Surface>(null, enc)
            if (currentDualOn && cx != null) {
                // In dual: drop the preview from the compositor but keep both cameras feeding the
                // encoder if streaming; if idle (no encoder), tear the dual session down.
                if (targets.isEmpty()) cx.exitDual() else cx.setDualPreview(null)
            } else {
                // Detach preview only. If streaming, the camera must keep feeding the encoder, so
                // reconfigure to whatever targets remain (encoder-only) rather than stopping. If
                // idle (no encoder surface), stop the camera.
                if (targets.isEmpty()) controller.stop()
                else config?.let { controller.start(it, targets, hdr = activeHdr) }
            }
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
                    // Single tap reveals the zoom slider; double tap flips front/back (single mode).
                    // In dual mode the camera swap is a tap on the PiP instead (see PipOverlay).
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (!currentDualOn) {
                                    (controller as? CameraXController)?.beginCameraTransition()  // stream freeze-blur
                                    facing = CameraControls.opposite(currentFacing); zoom = 1f
                                }
                            },
                            onTap = { zoomNonce++ },
                        )
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
                    // Lay the overlay over the SAME letterboxed camera region the preview shows (and the
                    // GL composite renders into), so the PiP border lines up with the rendered PiP.
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        PipOverlay(
                            scene = scene,
                            onSceneChange = { scene = it },
                            onSwap = {
                                // INSTANT swap (no device/session reopen): flip which open camera is the
                                // big (primary) view. We flip the dedicated [dualPrimary] state — NOT
                                // [facing] — so the binding LaunchedEffect (keyed on config←facing) does
                                // not re-run and tear the dual session down. dualSwap just relabels the
                                // processor's slot mapping; both textures already stream.
                                val newPrimary = CameraControls.opposite(dualPrimary)
                                dualPrimary = newPrimary
                                (controller as? CameraXController)?.dualSwap(newPrimary)
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .aspectRatio(previewAspect),
                        )
                    }
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
                                val cx = controller as? CameraXController
                                if (dualOn && cx != null) {
                                    // Dual: route through the chip classifier (REAL switchBack / ZOOM /
                                    // UNAVAILABLE → exit-dual request). The controller builds the chip
                                    // BackLens from the LensOption in INTRINSIC-zoom scale itself.
                                    cx.dualSelectChip(lens)
                                } else {
                                    cx?.beginCameraTransition()  // stream freeze-blur
                                    zoom = 1f
                                    cx?.selectLens(lens.physicalId)
                                }
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
                            // Dual: classify each chip per device caps so the rail dims UNAVAILABLE
                            // chips; single mode passes null to keep current behavior. activeZoom
                            // drives the nearest-ratio active highlight in dual.
                            dualClassOf = if (dualOn) {
                                { lens -> (controller as? CameraXController)?.classifyDualChip(lens)
                                    ?: com.example.plohoystream.camera.DualClass.REAL }
                            } else null,
                            activeZoom = zoom,
                            // Only flip the flag; the binding LaunchedEffect (keyed on dualOn) does the
                            // actual (re)bind, so there's a single binder and no race with start().
                            onToggleDual = {
                                scene = if (scene.isDual) {
                                    lastDualScene = scene                       // preserve PiP layout
                                    com.example.plohoystream.camera.scene.Scene.SINGLE
                                } else {
                                    // Turning dual ON: keep whichever camera is the current single main
                                    // frame as the dual PRIMARY (big view) so the OTHER goes to the PiP.
                                    // [dualPrimary] is never synced to [facing] elsewhere, so without
                                    // this, entering dual while on FRONT would wrongly keep BACK primary
                                    // and put FRONT in the PiP. The binding LaunchedEffect reads
                                    // [dualPrimary] for enterDual(primaryFacing = ...).
                                    dualPrimary = facing
                                    lastDualScene
                                }
                            },
                            modifier = Modifier.fillMaxSize().padding(12.dp),
                        )
                    }
                }
            }
        }

        // Tap-to-exit-dual confirm: an UNAVAILABLE lens chip can't run alongside the PiP, so offer to
        // drop the PiP and switch the single camera to it. Confirm flips the scene to SINGLE (same
        // path the dual toggle uses, so the single binder rebinds) and selects the chip's lens.
        exitDualChip?.let { lens ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { exitDualChip = null },
                title = { androidx.compose.material3.Text("Switch to ${lens.label}?") },
                text = {
                    androidx.compose.material3.Text(
                        "${lens.label} can't run with the PiP — drop the PiP and switch the single camera to it?"
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val cx = controller as? CameraXController
                        cx?.exitDual()
                        cx?.selectLens(lens.physicalId)
                        // Flip the scene to SINGLE (the dual toggle's path) so dualOn goes false and the
                        // single binder rebinds.
                        if (scene.isDual) lastDualScene = scene
                        scene = com.example.plohoystream.camera.scene.Scene.SINGLE
                        zoom = 1f
                        exitDualChip = null
                    }) { androidx.compose.material3.Text("Switch") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { exitDualChip = null }) {
                        androidx.compose.material3.Text("Cancel")
                    }
                },
            )
        }
    }
}
