package com.example.plohoystream.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/**
 * CameraX implementation of [CameraController] (the sole capture backend).
 *
 * We don't drive the device lifecycle by hand: a [ProcessCameraProvider]
 * binds a [Preview] use case to a lifecycle WE own ([lifecycle], a [LifecycleRegistry]) so that
 * binding survives the Activity (the foreground service keeps the process alive while streaming).
 * The on-screen preview and the MediaCodec encoder input are both fed by a single
 * [EgressSurfaceProcessor], attached as a [CameraEffect]:
 *  - Preview output → routed by CameraX through the processor to the UI-supplied [previewSurface]
 *    (via a custom [Preview.SurfaceProvider] that hands that raw surface back to CameraX).
 *  - Encoder output → registered directly on the processor with [EgressSurfaceProcessor.setEncoderSurface].
 *
 * 60fps / HDR-HLG10 are requested as a REQUIRED Feature Group on the [SessionConfig] derived from
 * `config.targetFps >= 60` and `hdr`. If a bind with the feature group fails (combo unsupported on
 * this device) we retry once without it rather than crashing.
 *
 * Everything that touches the provider runs on the main executor.
 */
class CameraXController(context: Context) : CameraController, LifecycleOwner {

    private val appContext = context.applicationContext
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appContext)
    private val registry = LifecycleRegistry(this)

    private val processor = EgressSurfaceProcessor()

    private var provider: ProcessCameraProvider? = null

    // Latest requested state. Applied immediately if the provider is ready, otherwise replayed in
    // [onProviderReady]. `pending` true means a start() is waiting on the provider loading.
    private var pendingStart = false
    private var lastConfig: CameraConfig? = null
    private var lastTargets: List<Surface> = emptyList()
    private var lastHdr = false

    private var previewSurface: Surface? = null
    private var currentZoom = 1.0f
    private var minZoom = 1.0f
    private var maxZoom = 1.0f

    private val _zoomRange = MutableStateFlow(1f..1f)
    /** CameraX's authoritative zoom range for the bound camera (e.g. 1.0..10.0). The UI reads this
     *  so the zoom slider spans exactly what the device can do — no unreachable sub-1.0 region. */
    val zoomRange: StateFlow<ClosedFloatingPointRange<Float>> = _zoomRange.asStateFlow()

    private val _lenses = MutableStateFlow<List<LensOption>>(emptyList())
    /** Physical lenses of the active logical camera (ultrawide/main/tele), for the lens buttons. */
    val lenses: StateFlow<List<LensOption>> = _lenses.asStateFlow()
    private val _selectedPhysicalId = MutableStateFlow<String?>(null)
    /** The currently-bound physical lens id (null = logical default / main). */
    val selectedPhysicalId: StateFlow<String?> = _selectedPhysicalId.asStateFlow()

    private val _exposure = MutableStateFlow(ExposureState())
    /** Manual-exposure state (shutter for motion blur + ISO) for the viewfinder's exposure panel. */
    val exposure: StateFlow<ExposureState> = _exposure.asStateFlow()

    private val _secondaryPipAspect = MutableStateFlow(1f)
    /** The secondary (PiP) camera's displayed aspect (w/h, after upright rotation). The viewfinder
     *  sizes the PiP box to this so the whole secondary frame shows without cropping. */
    val secondaryPipAspect: StateFlow<Float> = _secondaryPipAspect.asStateFlow()

    private var camera: Camera? = null

    // Dual (concurrent) bind state. Non-null [dualPrimaryFacing] means the concurrent pair is the
    // active binding, so a lens switch / rebind must go through [bindDualInternal] (re-binding the
    // PAIR) rather than the single path — otherwise the secondary (PiP) camera is dropped.
    private var dualPrimaryFacing: Facing? = null
    private var dualScene: com.example.plohoystream.camera.scene.Scene? = null
    private var dualOnFailed: () -> Unit = {}

    // TEMP (Task 4 verification; replaced in Task 6): standalone two-Camera2-device dual path.
    // Caps are read once on a background thread in init; the session is built lazily on first use.
    @Volatile private var concurrentCaps: ConcurrentCameraCapabilities? = null
    private var dualSession: DualCameraSession? = null

    init {
        registry.currentState = Lifecycle.State.CREATED
        // Auto-ISO: the GL meter (GL thread) hands us scene luma; run the control law on main.
        processor.onLuma = { luma -> mainExecutor.execute { onMeteredLuma(luma) } }
        // Secondary PiP aspect (GL thread) → publish on main for the viewfinder to size the PiP box.
        processor.onSecondaryAspect = { aspect -> mainExecutor.execute { _secondaryPipAspect.value = aspect } }
        // TEMP (Task 4 verification; replaced in Task 6): read concurrent-camera caps off the main
        // thread so startDual2 can build a DualCameraSession lazily.
        Thread {
            runCatching { CameraCapabilityReader.read(appContext) }
                .onSuccess { concurrentCaps = it }
                .onFailure { Log.w(TAG, "concurrent caps read failed", it) }
        }.start()
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            try {
                provider = future.get()
                onProviderReady()
            } catch (e: Exception) {
                Log.e(TAG, "failed to obtain ProcessCameraProvider", e)
            }
        }, mainExecutor)
    }

    override val lifecycle: Lifecycle get() = registry

    /** UI supplies the on-screen [surface]. A change re-binds so the new surface gets frames. */
    fun setPreviewSurface(surface: Surface?) {
        mainExecutor.execute {
            if (previewSurface === surface) return@execute
            previewSurface = surface
            // Re-bind if we have an active request, so the preview routes to the new surface.
            if (lastConfig != null && lastTargets.isNotEmpty()) bindIfReady()
        }
    }

    /** Start a freeze-blur transition in the GL pipeline (covers preview AND the encoded stream). */
    fun beginCameraTransition() = processor.beginTransition()

    /** Switch to a back/front camera sensor (ultrawide/main/tele) by its Camera2 id; rebinds. In
     *  dual mode this re-binds the CONCURRENT PAIR (primary = the chosen sensor) so the PiP survives;
     *  in single mode it rebinds the one camera. */
    fun selectLens(physicalId: String?) {
        mainExecutor.execute {
            if (_selectedPhysicalId.value == physicalId) return@execute
            _selectedPhysicalId.value = physicalId
            currentZoom = 1.0f   // start at the lens's native field of view
            if (dualPrimaryFacing != null) bindDualInternal() else bindIfReady()
        }
    }

    override fun start(config: CameraConfig, targets: List<Surface>, hdr: Boolean) {
        mainExecutor.execute {
            dualPrimaryFacing = null   // single bind: leave dual mode (selectLens uses the single path)
            dualScene = null
            // A facing change has different physical lenses and a fresh exposure context.
            if (lastConfig?.facing != config.facing) {
                _selectedPhysicalId.value = null
                _exposure.value = ExposureState()   // back to auto; caps re-read on bind
            }
            lastConfig = config
            lastTargets = targets
            lastHdr = hdr
            pendingStart = true
            minZoom = config.minZoom
            maxZoom = config.maxZoom
            currentZoom = CameraControls.clampZoom(currentZoom, minZoom, maxZoom)
            // [previewSurface] (set only via setPreviewSurface) is the sole source of truth for the
            // on-screen preview, rendered via the SurfaceProvider. The encoder is any target that is
            // NOT the preview surface. This is correct for the backgrounded/encoder-only case where
            // previewSurface == null and targets == [encoder]: the encoder is not misclassified as
            // the preview.
            val encoder = targets.firstOrNull { it !== previewSurface }
            processor.setEncoderSurface(encoder)
            processor.setScene(com.example.plohoystream.camera.scene.Scene.SINGLE)  // clear dual state
            Log.i(TAG, "start facing=${config.facing} targets=${targets.size} encoder=${encoder != null}")
            bindIfReady()
        }
    }

    override fun stop() {
        mainExecutor.execute {
            pendingStart = false
            lastConfig = null
            lastTargets = emptyList()
            dualPrimaryFacing = null
            dualScene = null
            camera = null
            // TEMP (Task 4 verification; replaced in Task 6): tear down the standalone dual session.
            dualSession?.stop()
            provider?.unbindAll()
            processor.setEncoderSurface(null)
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    override fun setZoom(ratio: Float) {
        mainExecutor.execute {
            currentZoom = CameraControls.clampZoom(ratio, minZoom, maxZoom)
            camera?.cameraControl?.setZoomRatio(currentZoom)
        }
    }

    /**
     * Bind FRONT + BACK concurrently, both feeding the shared [processor]. [primaryFacing] takes the
     * high-res slot (the big view); the other is the PiP. [scene] is the composited layout. We read
     * the SECONDARY camera's sensor orientation here so its PiP transform is derived correctly. Calls
     * [onFailed] on a rejected concurrent bind (caller reverts to single mode).
     */
    fun startDual(
        primaryFacing: Facing,
        scene: com.example.plohoystream.camera.scene.Scene,
        targets: List<Surface>,
        onFailed: () -> Unit,
    ) {
        mainExecutor.execute {
            // A primary-facing change (a dual swap) has different physical lenses, so drop any
            // selected sensor — the new primary binds to its facing default.
            if (dualPrimaryFacing != primaryFacing) _selectedPhysicalId.value = null
            dualPrimaryFacing = primaryFacing
            dualScene = scene
            dualOnFailed = onFailed
            lastTargets = targets
            bindDualInternal()
        }
    }

    /**
     * TEMP (Task 4 verification; replaced in Task 6): start the standalone two-Camera2-device dual
     * path. Unbinds any CameraX session, picks the default back + front camera ids, and hands the
     * preview + encoder surfaces to a [DualCameraSession] feeding the processor's standalone mode.
     * [onFailed] fires (caller reverts to single) if caps aren't ready, the ids can't be resolved,
     * or a device open/configure fails.
     */
    fun startDual2(
        primaryFacing: Facing,
        preview: Surface?,
        encoder: Surface?,
        scene: com.example.plohoystream.camera.scene.Scene,
        onFailed: () -> Unit,
    ) {
        mainExecutor.execute {
            val caps = concurrentCaps ?: run {
                Log.w(TAG, "startDual2: concurrent caps not ready yet")
                onFailed(); return@execute
            }
            val mgr = runCatching {
                appContext.getSystemService(android.content.Context.CAMERA_SERVICE)
                    as android.hardware.camera2.CameraManager
            }.getOrNull()
            val backId = mgr?.let { defaultCameraId(it, CameraMetadata.LENS_FACING_BACK) }
            val frontId = mgr?.let { defaultCameraId(it, CameraMetadata.LENS_FACING_FRONT) }
            if (backId == null || frontId == null) {
                Log.w(TAG, "startDual2: could not resolve default back/front ids (back=$backId front=$frontId)")
                onFailed(); return@execute
            }
            // Release any CameraX binding first; the processor's standalone mode owns the surfaces.
            runCatching { provider?.unbindAll() }
            camera = null
            dualPrimaryFacing = primaryFacing
            dualScene = scene
            val session = dualSession ?: DualCameraSession(appContext, processor, caps).also { dualSession = it }
            // unbindAll() closes the prior CameraX session ASYNCHRONOUSLY; opening the two Camera2
            // devices before that finishes races CAMERA_IN_USE → onFailed → dual turns off. Gate the
            // session start on both default cameras being free (or a 2s timeout).
            awaitCamerasFreeThenBind {
                session.start(
                    primaryFacing = primaryFacing,
                    backId = backId,
                    frontId = frontId,
                    preview = preview,
                    encoder = encoder,
                    displayDeg = displayDegrees(),
                    scene = scene,
                    onFailed = { mainExecutor.execute { onFailed() } },
                )
            }
        }
    }

    /**
     * (Re)bind the FRONT+BACK concurrent pair from the stored dual state. The PRIMARY (big view)
     * binds to the currently-selected physical lens ([_selectedPhysicalId]) so a lens switch in dual
     * mode rebinds the PAIR without ever dropping the PiP. The concurrent primary [Camera] is adopted
     * as [camera] so zoom/exposure target the big view. If a bind with a specific lens fails (the
     * device may not support that sensor concurrently with the front camera) we revert to the default
     * lens and retry once — keeping dual alive — before finally falling back to single via
     * [dualOnFailed]. Main-thread only.
     */
    private fun bindDualInternal(lensFallback: Boolean = true) {
        val provider = provider ?: run { dualOnFailed(); return }
        val primaryFacing = dualPrimaryFacing ?: return
        val scene = dualScene ?: return
        val encoder = lastTargets.firstOrNull { it !== previewSurface }
        processor.setEncoderSurface(encoder)
        val secondaryFacing = if (primaryFacing == Facing.FRONT) Facing.BACK else Facing.FRONT
        val mgr = runCatching {
            appContext.getSystemService(android.content.Context.CAMERA_SERVICE)
                as android.hardware.camera2.CameraManager
        }.getOrNull()
        val secId = mgr?.let {
            defaultCameraId(it, if (secondaryFacing == Facing.FRONT)
                CameraMetadata.LENS_FACING_FRONT else CameraMetadata.LENS_FACING_BACK)
        }
        val sensorDeg = secId?.let {
            runCatching {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_ORIENTATION)
            }.getOrNull()
        } ?: 0
        processor.setDualConfig(sensorDeg, secondaryFacing == Facing.FRONT, displayDegrees())
        processor.setScene(scene)
        runCatching { provider.unbindAll() }
        // unbindAll() closes the prior session's camera ASYNCHRONOUSLY. Opening the concurrent
        // pair before that close finishes throws ERROR_CAMERA_LIMIT_EXCEEDED (the front camera
        // never opens → frozen feed on a re-toggle). Gate the bind on both cameras being free.
        awaitCamerasFreeThenBind {
            val lensId = _selectedPhysicalId.value
            // PRIMARY binds to the selected physical sensor; SECONDARY (PiP) stays the facing default.
            val primaryCfg = singleConfig(primaryFacing, Size(1280, 720), primary = true, physicalId = lensId)
            val secondaryCfg = singleConfig(secondaryFacing, Size(640, 360), primary = false)
            registry.currentState = Lifecycle.State.STARTED
            try {
                val cc = provider.bindToLifecycle(listOf(primaryCfg, secondaryCfg))
                // Adopt the primary (big view) camera so zoom/exposure act on it, not the PiP.
                camera = cc.cameras.firstOrNull { matchesFacing(it, primaryFacing) } ?: cc.cameras.firstOrNull()
                adoptRealZoomRange()
                camera?.cameraControl?.setZoomRatio(currentZoom)
                Log.i(TAG, "bound dual: primary=$primaryFacing lens=$lensId secondarySensor=$sensorDeg")
            } catch (e: Exception) {
                if (lensId != null && lensFallback) {
                    // This sensor can't be bound concurrently with the front camera on this device:
                    // keep dual alive by reverting to the default lens and rebinding once.
                    Log.w(TAG, "dual bind with lens $lensId failed; reverting to default lens", e)
                    _selectedPhysicalId.value = null
                    bindDualInternal(lensFallback = false)
                } else {
                    Log.e(TAG, "concurrent bind failed; caller falls back to single", e)
                    processor.setScene(com.example.plohoystream.camera.scene.Scene.SINGLE)
                    dualOnFailed()
                }
            }
        }
    }

    /** True if [cam]'s lens facing matches [facing] (to pick the primary out of a ConcurrentCamera). */
    private fun matchesFacing(cam: Camera, facing: Facing): Boolean {
        val want = if (facing == Facing.FRONT) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        return runCatching { cam.cameraInfo.lensFacing }.getOrNull() == want
    }

    /** Push a new composited scene to the GL pipeline (live PiP move/resize). Also remembers the
     *  latest dual layout so a dual rebind (a lens switch) re-applies the user's CURRENT PiP
     *  position/size/aspect rather than the default layout captured at [startDual]. */
    fun setScene(scene: com.example.plohoystream.camera.scene.Scene) {
        if (scene.isDual) dualScene = scene
        processor.setScene(scene)
    }

    /**
     * Run [bind] (on the main thread) once the default front AND back cameras are both reported
     * available by [android.hardware.camera2.CameraManager] — i.e. the prior session's camera has
     * finished its async close — or after a 2s safety timeout. Prevents the
     * ERROR_CAMERA_LIMIT_EXCEEDED that wedges a concurrent re-bind issued too soon after unbindAll().
     */
    private fun awaitCamerasFreeThenBind(bind: () -> Unit) {
        val mgr = runCatching {
            appContext.getSystemService(android.content.Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        }.getOrNull()
        val backId = mgr?.let { defaultCameraId(it, CameraMetadata.LENS_FACING_BACK) }
        val frontId = mgr?.let { defaultCameraId(it, CameraMetadata.LENS_FACING_FRONT) }
        if (mgr == null || backId == null || frontId == null) { bind(); return }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val available = HashSet<String>()
        var done = false
        lateinit var cb: android.hardware.camera2.CameraManager.AvailabilityCallback
        fun finish() {
            if (done) return
            done = true
            handler.removeCallbacksAndMessages(null)
            runCatching { mgr.unregisterAvailabilityCallback(cb) }
            bind()
        }
        cb = object : android.hardware.camera2.CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                available.add(cameraId)
                if (available.contains(backId) && available.contains(frontId)) finish()
            }
            override fun onCameraUnavailable(cameraId: String) { available.remove(cameraId) }
        }
        // registerAvailabilityCallback immediately reports cameras that are already free.
        mgr.registerAvailabilityCallback(cb, handler)
        handler.postDelayed({ finish() }, 2000L)   // safety: bind anyway if availability never settles
    }

    private fun defaultCameraId(mgr: android.hardware.camera2.CameraManager, lensFacing: Int): String? =
        runCatching {
            mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == lensFacing
            }
        }.getOrNull()

    private fun singleConfig(
        facing: Facing,
        size: Size,
        primary: Boolean,
        physicalId: String? = null,
    ): androidx.camera.core.ConcurrentCamera.SingleCameraConfig {
        val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setResolutionStrategy(
                androidx.camera.core.resolutionselector.ResolutionStrategy(
                    size,
                    androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            ).build()
        // PRIMARY: routes through the GL effect to the on-screen preview + encoder (and composites).
        // SECONDARY: no effect — feeds straight into the renderer's 2nd texture via the processor's
        // secondary sink, so the PiP camera actually streams frames (a Preview with no consumer never
        // starts its capture stream).
        val preview = Preview.Builder().setResolutionSelector(resolutionSelector).build().apply {
            if (primary) setSurfaceProvider(mainExecutor, UiSurfaceProvider())
            else setSurfaceProvider(mainExecutor, Preview.SurfaceProvider { req -> processor.provideSecondarySurface(req) })
        }
        val useCaseGroup = androidx.camera.core.UseCaseGroup.Builder()
            .addUseCase(preview)
            .apply {
                if (primary) {
                    addEffect(EgressEffect(processor, mainExecutor))
                    // Concurrent mode hands the primary a 4:3 buffer; crop it to the 16:9 output so it
                    // isn't stretched (vertically squeezed) when drawn full-frame. The ViewPort aspect
                    // must be expressed in the CURRENT display rotation, else the crop is applied on
                    // the wrong axis (this app runs landscape).
                    setViewPort(androidx.camera.core.ViewPort.Builder(android.util.Rational(16, 9), displayRotation()).build())
                }
            }
            .build()
        // Narrow to the requested physical sensor when one is selected (a lens switch on the primary);
        // [lensSelector] falls back to the facing default for a null/unknown id.
        val selector = lensSelector(facing, physicalId)
        return androidx.camera.core.ConcurrentCamera.SingleCameraConfig(selector, useCaseGroup, this)
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = try {
        val wm = appContext.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        wm.defaultDisplay.rotation
    } catch (e: Exception) {
        Surface.ROTATION_0
    }

    /** Current display rotation in degrees (0/90/180/270), for the secondary's derived transform. */
    private fun displayDegrees(): Int = when (displayRotation()) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun onProviderReady() {
        if (pendingStart) bindIfReady()
    }

    /** Build use cases + session config and bind, if both the provider and a request are ready. */
    private fun bindIfReady() {
        val provider = provider ?: return // not loaded yet; replayed by onProviderReady()
        val config = lastConfig ?: return
        if (lastTargets.isEmpty()) return

        provider.unbindAll()

        val preview = buildPreview(config)
        val effect = EgressEffect(processor, mainExecutor)
        val features = requiredFeatures(config, lastHdr)

        val sessionConfig = SessionConfig.Builder(preview)
            .addEffect(effect)
            .apply { if (features.isNotEmpty()) setRequiredFeatureGroup(*features.toTypedArray()) }
            .build()

        enumerateLenses(config)
        val selector = lensSelector(config.facing, _selectedPhysicalId.value)

        registry.currentState = Lifecycle.State.STARTED
        try {
            camera = provider.bindToLifecycle(this, selector, sessionConfig)
            adoptRealZoomRange()
            camera?.cameraControl?.setZoomRatio(currentZoom)
            Log.i(TAG, "bound ${config.facing} fps=${config.targetFps} hdr=$lastHdr features=$features")
        } catch (e: Exception) {
            Log.w(TAG, "bind with feature group $features failed; retrying without it", e)
            if (features.isNotEmpty()) {
                bindWithoutFeatures(provider, preview, effect, selector)
            } else {
                Log.e(TAG, "bind failed (no feature group to drop)", e)
            }
        }
    }

    private fun bindWithoutFeatures(
        provider: ProcessCameraProvider,
        preview: Preview,
        effect: CameraEffect,
        selector: CameraSelector,
    ) {
        runCatching { provider.unbindAll() }
        val fallback = SessionConfig.Builder(preview).addEffect(effect).build()
        try {
            registry.currentState = Lifecycle.State.STARTED
            camera = provider.bindToLifecycle(this, selector, fallback)
            adoptRealZoomRange()
            camera?.cameraControl?.setZoomRatio(currentZoom)
            Log.i(TAG, "bound without feature group")
        } catch (e: Exception) {
            Log.e(TAG, "fallback bind failed", e)
        }
    }

    /**
     * Replace the Camera2-enumerated zoom bounds with CameraX's authoritative [androidx.camera.core.ZoomState]
     * for the camera it actually bound. `setZoomRatio` clamps to THIS range, so a lens chip like 0.6×
     * only does anything if CameraX reports a sub-1.0 minimum — the log makes the real range visible.
     */
    private fun adoptRealZoomRange() {
        camera?.cameraInfo?.zoomState?.value?.let { zs ->
            minZoom = zs.minZoomRatio
            maxZoom = zs.maxZoomRatio
            _zoomRange.value = zs.minZoomRatio..zs.maxZoomRatio
            currentZoom = CameraControls.clampZoom(currentZoom, minZoom, maxZoom)
            Log.i(TAG, "CameraX zoom range = ${zs.minZoomRatio}..${zs.maxZoomRatio}")
        }
        adoptExposureCaps()
    }

    /**
     * Read the bound sensor's manual-exposure ranges (exposure time + ISO) and refresh [exposure].
     * If the sensor lacks manual control the panel stays hidden ([ExposureState.supported] = false)
     * and we leave auto-exposure alone. On a sensor/facing change we re-clamp to the new ranges and
     * re-apply any active manual exposure so the look survives a rebind.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun adoptExposureCaps() {
        val cam = camera ?: return
        val info = runCatching { Camera2CameraInfo.from(cam.cameraInfo) }.getOrNull()
        val expRange = info?.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val isoRange = info?.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (expRange == null || isoRange == null) {
            _exposure.value = ExposureState(supported = false)
            return
        }
        val prev = _exposure.value
        val fps = (lastConfig?.targetFps ?: 30).coerceAtLeast(1)
        val shutterMin = expRange.lower
        // Cap the longest shutter at the frame duration (1/fps): a longer exposure forces the sensor
        // below the target frame rate. 1/(2·fps) (180°) is the motion-blur sweet spot; 1/fps is full.
        val shutterMax = minOf(expRange.upper, 1_000_000_000L / fps).coerceAtLeast(shutterMin + 1)
        val isoMin = isoRange.lower
        val isoMax = isoRange.upper
        // Default to the 180° shutter and a low base ISO (Auto-ISO/metering refines it within ~1s).
        val defaultShutter = CameraControls.clampShutterNs(CameraControls.shutter180Ns(fps), shutterMin, shutterMax)
        val shutterNs = if (prev.supported) CameraControls.clampShutterNs(prev.shutterNs, shutterMin, shutterMax) else defaultShutter
        val iso = if (prev.supported) CameraControls.clampIso(prev.iso, isoMin, isoMax)
                  else CameraControls.clampIso(isoMin * 4, isoMin, isoMax)
        _exposure.value = prev.copy(
            supported = true,
            shutterRangeNs = shutterMin..shutterMax,
            isoRange = isoMin..isoMax,
            shutterNs = shutterNs,
            iso = iso,
        )
        Log.d(TAG, "exposure caps: shutter=$shutterMin..$shutterMax iso=$isoMin..$isoMax -> shutterNs=$shutterNs iso=$iso (fps=$fps)")
        applyExposure()
    }

    /** Return to full auto-exposure (camera AE owns shutter + ISO). */
    fun setExposureAuto() = mainExecutor.execute {
        _exposure.value = _exposure.value.copy(mode = ExposureMode.AUTO)
        applyExposure()
    }

    /** Enter manual exposure (fixed shutter for motion blur; ISO per [ExposureState.autoIso]). */
    fun setExposureManual() = mainExecutor.execute {
        if (!_exposure.value.supported) return@execute
        _exposure.value = _exposure.value.copy(mode = ExposureMode.MANUAL)
        applyExposure()
    }

    /** Set the manual shutter (sensor exposure time, ns); implies manual mode. */
    fun setShutterNs(ns: Long) = mainExecutor.execute {
        val st = _exposure.value
        if (!st.supported) return@execute
        val clamped = CameraControls.clampShutterNs(ns, st.shutterRangeNs.first, st.shutterRangeNs.last)
        _exposure.value = st.copy(mode = ExposureMode.MANUAL, shutterNs = clamped)
        applyExposure()
    }

    /** Set the manual ISO; turns Auto-ISO off (the user is taking over brightness). */
    fun setIso(iso: Int) = mainExecutor.execute {
        val st = _exposure.value
        if (!st.supported) return@execute
        val clamped = CameraControls.clampIso(iso, st.isoRange.first, st.isoRange.last)
        _exposure.value = st.copy(mode = ExposureMode.MANUAL, autoIso = false, iso = clamped)
        applyExposure()
    }

    /** Toggle shutter-priority Auto-ISO (the metering loop rides ISO at the fixed shutter). */
    fun setAutoIso(on: Boolean) = mainExecutor.execute {
        val st = _exposure.value
        if (!st.supported) return@execute
        _exposure.value = st.copy(mode = ExposureMode.MANUAL, autoIso = on)
        applyExposure()
    }

    /**
     * Shutter-priority Auto-ISO step: fed the metered scene luma (~5Hz, from the GL meter via the
     * processor), nudge ISO toward the target brightness while the shutter stays fixed. Runs on the
     * main executor; only active in manual mode with Auto-ISO on (see [updateMetering]).
     */
    private fun onMeteredLuma(luma: Float) {
        val st = _exposure.value
        if (!st.supported || st.mode != ExposureMode.MANUAL || !st.autoIso) return
        val newIso = CameraControls.autoIsoStep(
            st.iso, luma, TARGET_LUMA, st.isoRange.first, st.isoRange.last,
        )
        if (newIso != st.iso) {
            _exposure.value = st.copy(iso = newIso)
            applyExposure()
        }
    }

    /** Turn the GL luma meter on only when shutter-priority Auto-ISO needs it (avoids the readback). */
    private fun updateMetering() {
        val st = _exposure.value
        processor.meteringEnabled = st.supported && st.mode == ExposureMode.MANUAL && st.autoIso
    }

    /** Push the current [ExposureState] to the sensor via Camera2 interop. Main-thread only. */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyExposure() {
        updateMetering()
        val cam = camera ?: return
        val st = _exposure.value
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        val opts = if (st.mode == ExposureMode.MANUAL && st.supported) {
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, st.shutterNs)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, st.iso)
                .build()
        } else {
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                .build()
        }
        c2.setCaptureRequestOptions(opts)
    }

    /**
     * Selector for [facing], optionally narrowed to a specific physical sensor by its Camera2
     * [cameraId]. The Seeker exposes ultrawide/main/tele as SEPARATE back-facing cameras (not
     * physical sub-cameras of one logical camera), so we pick the exact one with a [CameraFilter]
     * matching its id rather than `setPhysicalCameraId`.
     */
    private fun lensSelector(facing: Facing, cameraId: String?): CameraSelector {
        val facingInt =
            if (facing == Facing.FRONT) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        return CameraSelector.Builder()
            .requireLensFacing(facingInt)
            .apply {
                if (cameraId != null) addCameraFilter(object : CameraFilter {
                    override fun filter(cameraInfos: List<CameraInfo>): List<CameraInfo> {
                        val matched = cameraInfos.filter {
                            runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() == cameraId
                        }
                        // An unknown id (e.g. the 1× "main", which is the default camera and not a
                        // separately-selectable entry) falls back to the facing default rather than
                        // crashing the bind on an empty result.
                        return matched.ifEmpty { cameraInfos }
                    }
                })
            }
            .build()
    }

    /**
     * Enumerate the selectable back (or front) camera sensors for the on-screen picker. Each
     * top-level [CameraInfo] of the requested facing is one physical sensor; its
     * [CameraInfo.getIntrinsicZoomRatio] (angle-of-view relative to the default camera) gives the
     * label (≈0.6×/1×/2×) and [Camera2CameraInfo] gives the id used to bind it. Near-duplicate
     * ratios (e.g. a logical wrapper aliasing the main sensor) are collapsed. Published only when
     * ≥2 distinct sensors are selectable (so a single-sensor front camera hides the picker).
     */
    private fun enumerateLenses(config: CameraConfig) {
        val p = provider ?: return
        val facingInt =
            if (config.facing == Facing.FRONT) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        val baseSelector = CameraSelector.Builder().requireLensFacing(facingInt).build()
        // The facing default is the 1.0× reference ("main"). It is the camera bound with no id
        // filter and is often NOT a separate entry in availableCameraInfos, so add it explicitly.
        val mainId = runCatching { Camera2CameraInfo.from(p.getCameraInfo(baseSelector)).cameraId }.getOrNull()
        val others = runCatching { baseSelector.filter(p.availableCameraInfos) }.getOrNull().orEmpty()
            .mapNotNull { info ->
                val id = runCatching { Camera2CameraInfo.from(info).cameraId }.getOrNull() ?: return@mapNotNull null
                val ratio = info.intrinsicZoomRatio
                if (ratio == CameraInfo.INTRINSIC_ZOOM_RATIO_UNKNOWN || ratio <= 0f) return@mapNotNull null
                LensOption(label = formatRatio(ratio), physicalId = id, zoomRatio = ratio)
            }
        val main = mainId?.let { LensOption(label = "1×", physicalId = it, zoomRatio = 1.0f) }
        val sensors = (listOfNotNull(main) + others)
            .sortedBy { it.zoomRatio }
            .distinctBy { Math.round(it.zoomRatio * 10) }   // collapse aliases of the same focal length
        Log.i(TAG, "${config.facing} sensors (main=$mainId): " +
            sensors.joinToString { "${it.label}/${it.physicalId}@${it.zoomRatio}" })
        _lenses.value = if (sensors.size >= 2) sensors else emptyList()
    }

    private fun formatRatio(r: Float): String =
        if (r >= 1f && r == r.toInt().toFloat()) "${r.toInt()}×"
        else String.format(java.util.Locale.US, "%.1f×", r)

    private fun buildPreview(config: CameraConfig): Preview {
        val size = Size(config.previewSize.width, config.previewSize.height)
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_NONE))
            .build()
        return Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .build()
            .apply { setSurfaceProvider(mainExecutor, UiSurfaceProvider()) }
    }

    private fun requiredFeatures(config: CameraConfig, hdr: Boolean): List<GroupableFeature> =
        buildList {
            if (config.targetFps >= 60) add(GroupableFeature.FPS_60)
            if (hdr) add(GroupableFeature.HDR_HLG10)
        }

    /**
     * Hands the UI-supplied [previewSurface] back to CameraX. With the [CameraEffect] attached,
     * CameraX routes camera frames through the [EgressSurfaceProcessor] to this surface (so the
     * processor's preview SurfaceOutput is backed by the on-screen surface).
     */
    private inner class UiSurfaceProvider : Preview.SurfaceProvider {
        override fun onSurfaceRequested(request: SurfaceRequest) {
            val surface = previewSurface
            if (surface == null || !surface.isValid) {
                request.willNotProvideSurface()
                return
            }
            Log.d(TAG, "provide preview surface ${surface.hashCode()}")
            request.provideSurface(surface, mainExecutor, Consumer { /* result; surface owned by UI */ })
        }
    }

    /** [CameraEffect]'s ctor is protected; subclass to instantiate it for PREVIEW + VIDEO_CAPTURE. */
    private class EgressEffect(processor: SurfaceProcessor, executor: Executor) :
        CameraEffect(
            PREVIEW or VIDEO_CAPTURE,
            executor,
            processor,
            Consumer<Throwable> { Log.w("CameraXController", "camera effect error", it) },
        )

    private companion object {
        const val TAG = "CameraXController"
        const val TARGET_LUMA = 0.45f   // mid-gray metering target for shutter-priority Auto-ISO
    }
}
