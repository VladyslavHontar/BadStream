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
        executeSafely({
            scene = newScene
            if (!newScene.isDual) { primaryTexture = null; secondaryTexture = null }
        })
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
            encoderSurface = null
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
