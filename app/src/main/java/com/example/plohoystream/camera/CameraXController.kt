package com.example.plohoystream.camera

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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

    private var camera: Camera? = null

    init {
        registry.currentState = Lifecycle.State.CREATED
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

    override fun start(config: CameraConfig, targets: List<Surface>, hdr: Boolean) {
        mainExecutor.execute {
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
            bindIfReady()
        }
    }

    override fun stop() {
        mainExecutor.execute {
            pendingStart = false
            lastConfig = null
            lastTargets = emptyList()
            camera = null
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

        val selector = when (config.facing) {
            Facing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
            Facing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
        }

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
    }

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
    }
}
