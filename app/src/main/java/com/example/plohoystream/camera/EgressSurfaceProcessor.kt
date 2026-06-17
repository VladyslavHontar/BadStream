package com.example.plohoystream.camera

import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A CameraX [SurfaceProcessor] that GL-renders each incoming camera frame to:
 *  - every [SurfaceOutput] it is given (the on-screen preview), AND
 *  - a separately-registered encoder [Surface] (our MediaCodec input) when streaming.
 *
 * This single GL seam replaces the old "second Camera2 output target". It is the
 * future home of a dual-camera compositor (out of scope for now).
 *
 * Structure and threading are a faithful port of androidx CameraX's
 * `androidx.camera.core.processing.DefaultSurfaceProcessor` (camera-core 1.6.1,
 * `androidx-main` reference): a dedicated GL [HandlerThread] owns the EGL context;
 * all GL work and all mutation of the output maps happen on that thread.
 *
 * SDR-only for now; the GL pipeline lives in [GlRenderer]. See HDR TODOs there.
 *
 * Wrap an instance in a `CameraEffect(PREVIEW or VIDEO_CAPTURE, executor, this, errorListener)`
 * and pass it to `UseCaseGroup`/`ProcessCameraProvider.bindToLifecycle`.
 */
class EgressSurfaceProcessor : SurfaceProcessor {

    private companion object {
        const val TAG = "EgressSurfaceProcessor"
        const val METER_EVERY = 6   // sample luma every Nth frame (~5Hz at 30fps) for Auto-ISO
        const val PIP_CORNER_RADIUS = 0.18f   // PiP rounded-corner radius, in half-height units
    }

    // Scene compositor state. The scene (single source of truth for preview + stream) and the
    // secondary camera's orientation are pushed in from the controller; read on the GL thread.
    @Volatile private var scene: com.example.plohoystream.camera.scene.Scene =
        com.example.plohoystream.camera.scene.Scene.SINGLE
    private var primaryTexture: SurfaceTexture? = null
    private var secondaryTexture: SurfaceTexture? = null
    private val secondaryRawTransform = FloatArray(16)
    @Volatile private var secondarySrcW = 0
    @Volatile private var secondarySrcH = 0
    @Volatile private var secondarySensorDeg = 0
    @Volatile private var secondaryIsFront = true
    @Volatile private var displayDeg = 0

    /** Replace the composited scene (live; safe from any thread). >1 layer engages the compositor. */
    fun setScene(newScene: com.example.plohoystream.camera.scene.Scene) {
        // `scene` is @Volatile and read on the GL thread at composite time, so a live PiP edit
        // (drag/resize, which stays dual) publishes the new reference DIRECTLY — no glHandler post.
        // Posting a runnable per drag event floods the single GL handler ahead of the camera frame
        // callbacks (which post to the SAME handler), starving updateTexImage and dropping the PiP to
        // ~10fps. Only a transition to single mode needs the GL thread, to drop the dual textures.
        scene = newScene
        if (!newScene.isDual) {
            executeSafely({ primaryTexture = null; secondaryTexture = null })
        }
    }

    /** Orientation inputs for the SECONDARY (PiP) source's derived transform. Set at dual bind. */
    fun setDualConfig(sensorDeg: Int, isFront: Boolean, displayDegrees: Int) {
        executeSafely({
            secondarySensorDeg = sensorDeg
            secondaryIsFront = isFront
            displayDeg = displayDegrees
        })
    }

    /** Invoked (on the GL thread) with the SECONDARY camera's displayed aspect (w/h, after its
     *  upright rotation) once its frame size is known — so the UI can size the PiP box to match the
     *  source and avoid cropping. */
    @Volatile var onSecondaryAspect: ((Float) -> Unit)? = null

    private val dualMode: Boolean get() = scene.isDual

    // Standalone-dual (Camera2-fed) mode. When true, this processor is NOT driven by CameraX:
    // `onInputSurface`/`onOutputSurface` are inert and the two input SurfaceTextures are owned here,
    // fed by two independent Camera2 devices. The PRIMARY layer is then oriented with DisplayTransform
    // (like the SECONDARY), since neither source goes through CameraX's display-correcting transform.
    @Volatile private var standalone = false
    // Instant camera swap (standalone only): which scene source maps to which input slot. When false
    // PRIMARY→slotA(texture)/SECONDARY→slotB(texture2); when true the two are swapped. Both textures
    // stream regardless, so flipping this is instant (no device/session reopen). GL-thread mutation.
    @Volatile private var standaloneSwapped = false
    private val primaryRawTransform = FloatArray(16)
    @Volatile private var primarySrcW = 0
    @Volatile private var primarySrcH = 0
    @Volatile private var primarySensorDeg = 0
    @Volatile private var primaryIsFront = false
    // Direct render targets in standalone mode (preview + encoder). GL-thread-only mutation/iteration.
    private val standaloneOutputs = LinkedHashSet<Surface>()
    private var standalonePreview: Surface? = null
    // Surface wrappers around the standalone input SurfaceTextures. GL-thread-only. Must be released
    // on teardown alongside their SurfaceTextures (the single-mode path in onInputSurface releases
    // both in its provideSurface callback; standalone has no such callback, so we track them here).
    private var standalonePrimarySurface: Surface? = null
    private var standaloneSecondarySurface: Surface? = null

    /** When true, [onLuma] is invoked ~5Hz with the average frame luma (0..1) for Auto-ISO. */
    @Volatile var meteringEnabled = false
    var onLuma: ((Float) -> Unit)? = null
    private var meterFrameCount = 0

    private val glThread = HandlerThread("Egress-GL").apply { start() }
    private val glHandler = Handler(glThread.looper)
    private val glExecutor = Executor { command -> glHandler.post(command) }

    private val renderer = GlRenderer()

    // GL-thread state only.
    private val outputSurfaces = LinkedHashMap<SurfaceOutput, Surface>()
    private val textureTransform = FloatArray(16)
    private val surfaceOutputTransform = FloatArray(16)

    // The MediaCodec encoder surface is registered manually (not a CameraX SurfaceOutput), so it
    // has no CameraX-computed transform of its own. We reuse the preview SurfaceOutput's transform
    // — both surfaces are the same size and want the same display orientation — and cache it so the
    // encoder-only (backgrounded) case stays correctly oriented after the preview goes away.
    private val encoderTransform = FloatArray(16)
    private var hasEncoderTransform = false

    // Freeze-blur transition (lens/camera switch): while active, push the captured frozen frame to
    // all outputs (preview + encoder, so it's in the stream too) until the new camera's first frame
    // arrives. Ended frame-based in onFrameAvailable, with a safety timeout.
    @Volatile private var transitionActive = false
    private var lastFrameTimestampNs = 0L   // camera clock — transition PTS continues from here
    private var transitionTsNs = 0L
    private var transitionStartNs = 0L
    private val transitionTick = object : Runnable {
        override fun run() {
            if (!transitionActive || isReleased) return
            renderFrozenToAll()
            glHandler.postDelayed(this, 33L)
        }
    }

    /** Encoder (MediaCodec input) surface, registered/unregistered via [setEncoderSurface]. */
    private var encoderSurface: Surface? = null

    private val isReleaseRequested = AtomicBoolean(false)
    private var isReleased = false
    private var inputSurfaceCount = 0

    init {
        try {
            glExecuteSafelyBlocking { renderer.init() }
        } catch (e: RuntimeException) {
            release()
            throw e
        }
    }

    /**
     * Registers (or unregisters, with null) the MediaCodec input [surface] as an extra render
     * target. Safe to call from any thread; the change is applied on the GL thread. Call with the
     * encoder surface when streaming starts, and with null when it stops.
     */
    fun setEncoderSurface(surface: Surface?) {
        if (isReleaseRequested.get()) return
        executeSafely({
            val previous = encoderSurface
            if (previous === surface) return@executeSafely
            if (previous != null) {
                renderer.unregisterOutputSurface(previous)
            }
            encoderSurface = surface
            if (surface != null) {
                renderer.registerOutputSurface(surface)
            }
        })
    }

    override fun onInputSurface(request: SurfaceRequest) {
        if (isReleaseRequested.get()) {
            request.willNotProvideSurface()
            return
        }
        if (standalone) {
            // Dual is Camera2-driven; the CameraX path is inactive in standalone mode.
            request.willNotProvideSurface()
            return
        }
        executeSafely({
            inputSurfaceCount++
            Log.i(TAG, "onInputSurface #$inputSurfaceCount res=${request.resolution.width}x${request.resolution.height} dual=$dualMode")
            // Only the PRIMARY camera flows through the effect/onInputSurface. The SECONDARY (PiP)
            // camera is fed directly into the renderer's 2nd texture via [provideSecondarySurface].
            val surfaceTexture = SurfaceTexture(renderer.textureName)
            surfaceTexture.setDefaultBufferSize(
                request.resolution.width,
                request.resolution.height,
            )
            if (dualMode) primaryTexture = surfaceTexture
            val surface = Surface(surfaceTexture)
            request.provideSurface(surface, glExecutor) {
                surfaceTexture.setOnFrameAvailableListener(null)
                surfaceTexture.release()
                surface.release()
                if (surfaceTexture === primaryTexture) primaryTexture = null
                if (surfaceTexture === secondaryTexture) secondaryTexture = null
                inputSurfaceCount--
                checkReadyToRelease()
            }
            surfaceTexture.setOnFrameAvailableListener({ st -> onFrameAvailable(st) }, glHandler)
        }, request::willNotProvideSurface)
    }

    /**
     * SurfaceProvider sink for the SECONDARY (PiP) camera in dual mode. The second camera's CameraX
     * [Preview] has NO effect and routes here directly: we hand it a SurfaceTexture bound to the
     * renderer's 2nd OES texture and refresh that texture (+ its transform) on every frame, on the GL
     * thread. The primary frame drives the actual composite. Safe to call from any thread.
     */
    fun provideSecondarySurface(request: SurfaceRequest) {
        if (isReleaseRequested.get()) { request.willNotProvideSurface(); return }
        executeSafely({
            val st = SurfaceTexture(renderer.textureName2)
            st.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            secondarySrcW = request.resolution.width
            secondarySrcH = request.resolution.height
            secondaryTexture = st
            // Tell the UI the secondary's displayed aspect (post-rotation) so it can size the PiP
            // box to the source and avoid cropping.
            // The secondary arrives upright at its NATIVE frame aspect: CameraX bakes the
            // sensor→display rotation into the SurfaceTexture transform, and our orientation matrix
            // is calibrated so the net rotation is aspect-preserving (the face is upright while the
            // frame stays e.g. 4:3). So the PiP box matches the source's native w/h — no swap.
            val aspect = if (secondarySrcH > 0) secondarySrcW.toFloat() / secondarySrcH else 1f
            onSecondaryAspect?.invoke(aspect)
            val sfc = Surface(st)
            request.provideSurface(sfc, glExecutor) {
                st.setOnFrameAvailableListener(null)
                st.release()
                sfc.release()
                if (st === secondaryTexture) secondaryTexture = null
            }
            st.setOnFrameAvailableListener({ s ->
                if (isReleaseRequested.get()) return@setOnFrameAvailableListener
                try {
                    s.updateTexImage()
                    s.getTransformMatrix(secondaryRawTransform)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "secondary updateTexImage failed (producer torn down?)", e)
                }
            }, glHandler)
        }, request::willNotProvideSurface)
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        if (isReleaseRequested.get()) {
            output.close()
            return
        }
        if (standalone) {
            // Dual is Camera2-driven; the CameraX path is inactive in standalone mode.
            output.close()
            return
        }
        executeSafely({
            val surface = output.getSurface(glExecutor) {
                output.close()
                // Remove from the active render set, but KEEP the renderer's EGL window surface
                // alive. It belongs to the on-screen Surface (unchanged across a camera/lens switch),
                // not this SurfaceOutput. A switch closes the old preview output and opens a new one
                // backed by the SAME Surface; destroying + recreating the EGL window surface across
                // that gap races CameraX's surface release/reacquire and leaves the BufferQueue
                // permanently busy → eglCreateWindowSurface fails with EGL_BAD_ALLOC (black/frozen
                // preview). The renderer reuses the live EGL surface when the same Surface
                // re-registers, and GCs surfaces whose Surface became invalid (or on release).
                val removed = outputSurfaces.remove(output)
                Log.d(TAG, "output closed surface=${removed?.hashCode()} (EGL kept; remaining=${outputSurfaces.size})")
            }
            renderer.registerOutputSurface(surface)
            outputSurfaces[output] = surface
        }, output::close)
    }

    /**
     * Begin a freeze-blur transition: capture the current frame and keep pushing it (blurred) to all
     * outputs — preview AND the encoder, so it's in the stream — until the new camera's first frame
     * arrives. Call right before a lens switch / camera flip. Safe to call from any thread.
     */
    fun beginTransition() {
        if (isReleaseRequested.get()) return
        executeSafely({
            if (!hasEncoderTransform) return@executeSafely   // no frame seen yet; nothing to freeze
            runCatching { renderer.captureFrozen(encoderTransform) }
                .onFailure { Log.e(TAG, "captureFrozen failed", it); return@executeSafely }
            if (!transitionActive) {
                transitionActive = true
                transitionStartNs = System.nanoTime()
                transitionTsNs = lastFrameTimestampNs   // continue the camera timebase, monotonically
                glHandler.post(transitionTick)
                glHandler.postDelayed({ transitionActive = false }, 2000L)   // safety stop
            }
        })
    }

    private fun renderFrozenToAll() {
        // Cover ONLY the encoded stream with the GL freeze-blur. The on-screen preview's
        // CameraX-owned SurfaceOutput is being torn down and recreated during a camera/lens switch —
        // creating an EGL window surface on that mid-swap BufferQueue here races CameraX and fails
        // with EGL_BAD_ALLOC (a permanent black preview). The preview just holds its last frame
        // across the brief reopen gap. If we're not streaming there's no encoder and this is a no-op.
        val encoder = encoderSurface ?: return
        transitionTsNs += 33_000_000L   // ~30fps, monotonic in the camera timebase so PTS isn't dropped
        runCatching { renderer.renderFrozen(transitionTsNs, encoder) }
    }

    private fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (isReleaseRequested.get()) return
        // updateTexImage()/getTransformMatrix can fail natively when the camera producer is
        // disconnected mid-frame during a teardown — app backgrounded, screen rotation, or a camera
        // rebind — while this frame callback was already queued on the GL thread. That is a
        // transient, expected condition: skip the frame instead of letting the native
        // RuntimeException go uncaught on the Egress-GL thread and crash the whole process. (The
        // renderer.render() calls below are already guarded the same way.)
        try {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(textureTransform)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Skipping frame: updateTexImage failed (camera producer torn down?)", e)
            return
        }
        val timestampNs = surfaceTexture.timestamp
        lastFrameTimestampNs = timestampNs
        if (transitionActive) {
            // Keep the blur up for at least a min window so a stale in-flight frame can't kill it
            // instantly; after that, the next real frame (the new camera) ends it and renders live.
            if (System.nanoTime() - transitionStartNs < 250_000_000L) return
            transitionActive = false
            Log.d(TAG, "transition ended; rendering live (outputs=${outputSurfaces.size}, encoder=${encoderSurface != null})")
        }

        if (dualMode && primaryTexture != null && secondaryTexture != null) {
            for ((output, surface) in outputSurfaces) {
                output.updateTransformMatrix(surfaceOutputTransform, textureTransform)
                System.arraycopy(surfaceOutputTransform, 0, encoderTransform, 0, 16)
                hasEncoderTransform = true
                try {
                    renderer.renderScene(timestampNs, buildLayers(surfaceOutputTransform, surface), surface)
                } catch (e: RuntimeException) { Log.e(TAG, "composite preview render failed", e) }
            }
            encoderSurface?.let { surface ->
                val primaryTransform = if (hasEncoderTransform) encoderTransform else textureTransform
                try {
                    renderer.renderScene(timestampNs, buildLayers(primaryTransform, surface), surface)
                } catch (e: RuntimeException) { Log.e(TAG, "composite encoder render failed", e) }
            }
            return
        }

        // The scene wants a composite but a source isn't ready yet — during a dual rebind (e.g. a
        // back-lens switch) the textures are briefly torn down and reopened, and the new primary's
        // first frame can arrive before the secondary's. Hold the last composited frame rather than
        // flashing the primary full-frame (the PiP blinking out then back). Falls back to single
        // rendering only when the scene is genuinely single (dualMode false).
        if (dualMode) return

        // Render to each CameraX preview output (transform adjusted by the SurfaceOutput). Cache
        // that orientation-corrected transform so the encoder matches the preview orientation.
        for ((output, surface) in outputSurfaces) {
            output.updateTransformMatrix(surfaceOutputTransform, textureTransform)
            System.arraycopy(surfaceOutputTransform, 0, encoderTransform, 0, 16)
            hasEncoderTransform = true
            try {
                renderer.render(timestampNs, surfaceOutputTransform, surface)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Failed to render preview frame with OpenGL.", e)
            }
        }

        // Render to the encoder input surface using the same display-oriented transform as the
        // preview (cached above), so the streamed/recorded video isn't 90° off. Falls back to the
        // raw transform only if no preview frame has been seen yet. Done last so a swap failure
        // here can't drop the preview.
        encoderSurface?.let { surface ->
            val transform = if (hasEncoderTransform) encoderTransform else textureTransform
            try {
                renderer.render(timestampNs, transform, surface)
            } catch (e: RuntimeException) {
                Log.e(TAG, "Failed to render encoder frame with OpenGL.", e)
            }
        }

        // Auto-ISO metering: sample average luma at a low rate (the control loop runs ~5Hz). Done
        // last so a meter readback can't perturb the preview/encoder renders for this frame.
        if (meteringEnabled) {
            if (meterFrameCount++ % METER_EVERY == 0) {
                onLuma?.let { cb -> runCatching { renderer.meterLuma(textureTransform) }.getOrNull()?.let(cb) }
            }
        }
    }

    /**
     * Map the current [scene] to GL layers for [surface]. PRIMARY uses CameraX's display-correct
     * [primaryTransform] (full-frame, no extra crop). SECONDARY uses the derived orientation+mirror
     * transform composed with its raw SurfaceTexture transform, plus a cover-crop for its PiP rect.
     */
    private fun buildLayers(
        primaryTransform: FloatArray,
        surface: Surface,
    ): List<GlRenderer.RenderLayer> {
        val dt = com.example.plohoystream.camera.scene.DisplayTransform
        val outAspect = renderer.outputAspect(surface)
        return scene.ordered().mapNotNull { layer ->
            when (layer.source) {
                com.example.plohoystream.camera.scene.SourceId.PRIMARY ->
                    GlRenderer.RenderLayer(
                        renderer.textureName, primaryTransform,
                        layer.rect.left, layer.rect.top, layer.rect.right, layer.rect.bottom,
                    )
                com.example.plohoystream.camera.scene.SourceId.SECONDARY -> {
                    // Content is displayed at the source's native aspect (see provideSecondarySurface).
                    val contentAspect = if (secondarySrcH > 0) secondarySrcW.toFloat() / secondarySrcH else 1f
                    val rectAspect = (layer.rect.width * outAspect) / layer.rect.height
                    val (cropX, cropY) = dt.coverCrop(contentAspect, rectAspect)
                    val orient = dt.matrix(secondarySensorDeg, displayDeg, secondaryIsFront, cropX, cropY)
                    val tex = FloatArray(16)
                    android.opengl.Matrix.multiplyMM(tex, 0, orient, 0, secondaryRawTransform, 0)
                    GlRenderer.RenderLayer(
                        renderer.textureName2, tex,
                        layer.rect.left, layer.rect.top, layer.rect.right, layer.rect.bottom,
                        cornerRadius = PIP_CORNER_RADIUS,
                        mirror = secondaryIsFront,   // selfie-mirror the front PiP; back stays un-mirrored
                    )
                }
            }
        }
    }

    // region Standalone-dual (Camera2-fed) mode

    /** The two input [Surface]s returned by [startStandaloneDual]: feed each from a Camera2 device. */
    data class DualInputs(val primary: Surface, val secondary: Surface)

    /**
     * Switch this processor into standalone-dual mode: it owns two input SurfaceTextures (one per
     * Camera2 device) and renders the composite directly to [preview] and [encoder] (each may be
     * null). Both sources are oriented with [com.example.plohoystream.camera.scene.DisplayTransform]
     * (the PRIMARY too, since neither flows through CameraX). Runs setup on the GL thread and returns
     * the two input Surfaces to hand to the Camera2 capture sessions. The CameraX entry points
     * ([onInputSurface]/[onOutputSurface]) become inert once standalone is engaged.
     */
    fun startStandaloneDual(
        preview: Surface?,
        encoder: Surface?,
        primarySensorDeg: Int,
        primaryIsFront: Boolean,
        secondarySensorDeg: Int,
        secondaryIsFront: Boolean,
        displayDeg: Int,
        primarySize: android.util.Size,
        secondarySize: android.util.Size,
        scene: com.example.plohoystream.camera.scene.Scene,
    ): DualInputs {
        var result: DualInputs? = null
        glExecuteSafelyBlocking {
            val primaryST = SurfaceTexture(renderer.textureName)
            primaryST.setDefaultBufferSize(primarySize.width, primarySize.height)
            val secondaryST = SurfaceTexture(renderer.textureName2)
            secondaryST.setDefaultBufferSize(secondarySize.width, secondarySize.height)

            primaryTexture = primaryST
            secondaryTexture = secondaryST
            this.primarySrcW = primarySize.width
            this.primarySrcH = primarySize.height
            this.secondarySrcW = secondarySize.width
            this.secondarySrcH = secondarySize.height
            this.primarySensorDeg = primarySensorDeg
            this.primaryIsFront = primaryIsFront
            this.secondarySensorDeg = secondarySensorDeg
            this.secondaryIsFront = secondaryIsFront
            this.displayDeg = displayDeg
            this.scene = scene
            standaloneSwapped = false

            // Report the secondary's displayed aspect (native w/h; rotation is aspect-preserving) so
            // the UI can size the PiP box to the source. Matches provideSecondarySurface.
            val aspect = if (secondarySrcH > 0) secondarySrcW.toFloat() / secondarySrcH else 1f
            onSecondaryAspect?.invoke(aspect)

            standalonePreview = null
            standaloneOutputs.clear()
            if (preview != null) {
                renderer.registerOutputSurface(preview)
                standaloneOutputs.add(preview)
                standalonePreview = preview
            }
            // The encoder is MODE-AGNOSTIC: it is owned solely by [encoderSurface]/[setEncoderSurface]
            // and is NOT tracked in standaloneOutputs. We're already on the GL thread, so inline the
            // setEncoderSurface logic here (re-registering only if it changed) so the SAME encoder
            // stays registered across single<->dual transitions. stopStandalone must never unregister
            // it; that's setEncoderSurface's job.
            if (encoder !== encoderSurface) {
                encoderSurface?.let { renderer.unregisterOutputSurface(it) }
                encoderSurface = encoder
                encoder?.let { renderer.registerOutputSurface(it) }
            }

            standalone = true

            primaryST.setOnFrameAvailableListener({ st -> onPrimaryFrame(st) }, glHandler)
            secondaryST.setOnFrameAvailableListener({ st -> onSecondaryFrame(st) }, glHandler)

            val primarySfc = Surface(primaryST)
            val secondarySfc = Surface(secondaryST)
            standalonePrimarySurface = primarySfc
            standaloneSecondarySurface = secondarySfc
            result = DualInputs(primarySfc, secondarySfc)
        }
        return result!!
    }

    /** Swap the standalone preview target (null = drop preview, encoder-only). On the GL thread. */
    fun setStandalonePreview(surface: Surface?) {
        if (isReleaseRequested.get()) return
        executeSafely({
            if (!standalone) return@executeSafely
            val previous = standalonePreview
            if (previous === surface) return@executeSafely
            if (previous != null) {
                standaloneOutputs.remove(previous)
                renderer.unregisterOutputSurface(previous)
            }
            standalonePreview = surface
            if (surface != null) {
                renderer.registerOutputSurface(surface)
                standaloneOutputs.add(surface)
            }
        })
    }

    /** Leave standalone-dual mode: release both input textures + their Surfaces, drop all standalone
     *  outputs. On the GL thread. The CameraX path becomes active again. */
    fun stopStandalone() {
        if (isReleaseRequested.get()) return
        executeSafely({
            // Unregister ONLY the standalone preview outputs. The encoder is mode-agnostic (owned by
            // encoderSurface/setEncoderSurface); leaving it registered keeps the stream alive across
            // the dual->single transition and avoids the "surface is not registered" render failure.
            for (surface in standaloneOutputs) {
                renderer.unregisterOutputSurface(surface)
            }
            standaloneOutputs.clear()
            standalonePreview = null
            primaryTexture?.let { st ->
                st.setOnFrameAvailableListener(null)
                st.release()
            }
            standalonePrimarySurface?.release()
            secondaryTexture?.let { st ->
                st.setOnFrameAvailableListener(null)
                st.release()
            }
            standaloneSecondarySurface?.release()
            primaryTexture = null
            secondaryTexture = null
            standalonePrimarySurface = null
            standaloneSecondarySurface = null
            standalone = false
        })
    }

    /** Standalone PRIMARY frame: refresh the texture, then composite to every standalone output. */
    private fun onPrimaryFrame(st: SurfaceTexture) {
        if (isReleaseRequested.get() || !standalone) return
        try {
            st.updateTexImage()
            st.getTransformMatrix(primaryRawTransform)
        } catch (e: RuntimeException) {
            Log.w(TAG, "standalone primary updateTexImage failed (producer torn down?)", e)
            return
        }
        val ts = st.timestamp
        for (surface in standaloneOutputs) {
            try {
                renderer.renderScene(ts, buildLayersStandalone(surface), surface)
            } catch (e: RuntimeException) {
                Log.e(TAG, "standalone composite failed", e)
            }
        }
        // The encoder lives outside standaloneOutputs (mode-agnostic; owned by encoderSurface), so
        // render the composite to it explicitly when streaming in dual.
        encoderSurface?.let { enc ->
            try {
                renderer.renderScene(ts, buildLayersStandalone(enc), enc)
            } catch (e: RuntimeException) {
                Log.e(TAG, "standalone encoder render failed", e)
            }
        }
    }

    /** Standalone SECONDARY frame: refresh the texture only; the PRIMARY frame drives the composite. */
    private fun onSecondaryFrame(st: SurfaceTexture) {
        if (isReleaseRequested.get() || !standalone) return
        try {
            st.updateTexImage()
            st.getTransformMatrix(secondaryRawTransform)
        } catch (e: RuntimeException) {
            Log.w(TAG, "standalone secondary updateTexImage failed (producer torn down?)", e)
        }
    }

    /** A composite input source bound to one GL texture: the slot the scene maps PRIMARY/SECONDARY to. */
    private class Slot(
        val textureName: Int,
        val rawTransform: FloatArray,
        val sensorDeg: Int,
        val isFront: Boolean,
        val srcW: Int,
        val srcH: Int,
    )

    private fun slotA() = Slot(
        renderer.textureName, primaryRawTransform, primarySensorDeg, primaryIsFront, primarySrcW, primarySrcH,
    )

    private fun slotB() = Slot(
        renderer.textureName2, secondaryRawTransform, secondarySensorDeg, secondaryIsFront, secondarySrcW, secondarySrcH,
    )

    /**
     * Map the current [scene] to GL layers for [surface] in standalone mode. Unlike [buildLayers],
     * the PRIMARY (big) source is oriented exactly like the SECONDARY (PiP) — DisplayTransform composed
     * with its raw SurfaceTexture transform — because neither source flows through CameraX's transform
     * here. Scene sources map to input slots by [standaloneSwapped]: when false PRIMARY→slotA(texture)
     * / SECONDARY→slotB(texture2); when true they swap. Either way the big layer is full-frame with
     * square corners and the PiP layer gets [PIP_CORNER_RADIUS]; each layer mirrors iff its mapped
     * slot is the front camera (preserves the front-PiP-mirror behavior under swap).
     */
    private fun buildLayersStandalone(surface: Surface): List<GlRenderer.RenderLayer> {
        val dt = com.example.plohoystream.camera.scene.DisplayTransform
        val outAspect = renderer.outputAspect(surface)
        val swapped = standaloneSwapped
        val primarySlot = if (swapped) slotB() else slotA()
        val secondarySlot = if (swapped) slotA() else slotB()
        fun build(slot: Slot, layer: com.example.plohoystream.camera.scene.SceneLayer, isPip: Boolean):
            GlRenderer.RenderLayer {
            val contentAspect = if (slot.srcH > 0) slot.srcW.toFloat() / slot.srcH else 1f
            val rectAspect = (layer.rect.width * outAspect) / layer.rect.height
            val (cropX, cropY) = dt.coverCrop(contentAspect, rectAspect)
            val orient = dt.matrix(slot.sensorDeg, displayDeg, slot.isFront, cropX, cropY)
            val tex = FloatArray(16)
            android.opengl.Matrix.multiplyMM(tex, 0, orient, 0, slot.rawTransform, 0)
            return GlRenderer.RenderLayer(
                slot.textureName, tex,
                layer.rect.left, layer.rect.top, layer.rect.right, layer.rect.bottom,
                cornerRadius = if (isPip) PIP_CORNER_RADIUS else 0f,
                mirror = slot.isFront,
            )
        }
        return scene.ordered().mapNotNull { layer ->
            when (layer.source) {
                com.example.plohoystream.camera.scene.SourceId.PRIMARY -> build(primarySlot, layer, isPip = false)
                com.example.plohoystream.camera.scene.SourceId.SECONDARY -> build(secondarySlot, layer, isPip = true)
            }
        }
    }

    /**
     * Instant camera swap (standalone only): set whether the scene's PRIMARY/SECONDARY map to the
     * swapped input slots. After the flag flips, re-report the aspect of whichever slot is now in the
     * PiP (SECONDARY) so the UI re-sizes the PiP box. On the GL thread. No-op outside standalone.
     */
    fun setStandaloneSwapped(swapped: Boolean) {
        if (isReleaseRequested.get()) return
        executeSafely({
            if (!standalone) return@executeSafely
            standaloneSwapped = swapped
            // The PiP shows slotA when swapped (PRIMARY→slotB, SECONDARY→slotA), else slotB.
            val pip = if (swapped) slotA() else slotB()
            val aspect = if (pip.srcH > 0) pip.srcW.toFloat() / pip.srcH else 1f
            onSecondaryAspect?.invoke(aspect)
        })
    }

    /**
     * Update the BACK camera's sensor orientation + facing after a [DualCameraSession.switchBack],
     * writing it into the slot the back occupies: slotA (primary fields) when [inSlotA] is true,
     * otherwise slotB (secondary fields). On the GL thread. No-op outside standalone.
     */
    fun setStandaloneBackOrientation(sensorDeg: Int, isFront: Boolean, inSlotA: Boolean) {
        if (isReleaseRequested.get()) return
        executeSafely({
            if (!standalone) return@executeSafely
            if (inSlotA) {
                primarySensorDeg = sensorDeg
                primaryIsFront = isFront
            } else {
                secondarySensorDeg = sensorDeg
                secondaryIsFront = isFront
            }
        })
    }

    // endregion

    /** Releases the processor and the GL thread. Idempotent. */
    fun release() {
        if (isReleaseRequested.getAndSet(true)) return
        executeSafely({
            isReleased = true
            checkReadyToRelease()
        })
    }

    private fun checkReadyToRelease() {
        if (isReleased && inputSurfaceCount == 0) {
            for (output in outputSurfaces.keys) {
                output.close()
            }
            outputSurfaces.clear()
            // Unregister the mode-agnostic encoder once (renderer.release() below also tears down all
            // GL window surfaces, but unregistering keeps the bookkeeping balanced).
            encoderSurface?.let { renderer.unregisterOutputSurface(it) }
            encoderSurface = null
            // Standalone-dual teardown: unregister the direct outputs and release the input textures
            // + their Surfaces (these never go through inputSurfaceCount / the CameraX provideSurface
            // release callbacks, so they must be cleaned up here).
            for (surface in standaloneOutputs) {
                renderer.unregisterOutputSurface(surface)
            }
            standaloneOutputs.clear()
            standalonePreview = null
            primaryTexture?.let { st ->
                st.setOnFrameAvailableListener(null)
                st.release()
            }
            standalonePrimarySurface?.release()
            secondaryTexture?.let { st ->
                st.setOnFrameAvailableListener(null)
                st.release()
            }
            standaloneSecondarySurface?.release()
            primaryTexture = null
            secondaryTexture = null
            standalonePrimarySurface = null
            standaloneSecondarySurface = null
            renderer.release()
            glThread.quit()
        }
    }

    /** Posts [block] to the GL thread; runs [onFailure] inline if the executor was shut down. */
    private fun executeSafely(block: () -> Unit, onFailure: () -> Unit = {}) {
        try {
            glExecutor.execute {
                if (isReleased) {
                    onFailure()
                } else {
                    block()
                }
            }
        } catch (e: RejectedExecutionException) {
            Log.w(TAG, "Unable to executor runnable", e)
            onFailure()
        }
    }

    /** Runs [block] on the GL thread and blocks until it completes (used for init). */
    private fun glExecuteSafelyBlocking(block: () -> Unit) {
        val latch = java.util.concurrent.CountDownLatch(1)
        var thrown: RuntimeException? = null
        glHandler.post {
            try {
                block()
            } catch (e: RuntimeException) {
                thrown = e
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        thrown?.let { throw it }
    }
}

private typealias RejectedExecutionException = java.util.concurrent.RejectedExecutionException
