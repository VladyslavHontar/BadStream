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
    }

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
            val surfaceTexture = SurfaceTexture(renderer.textureName)
            surfaceTexture.setDefaultBufferSize(
                request.resolution.width,
                request.resolution.height,
            )
            val surface = Surface(surfaceTexture)
            request.provideSurface(surface, glExecutor) {
                surfaceTexture.setOnFrameAvailableListener(null)
                surfaceTexture.release()
                surface.release()
                inputSurfaceCount--
                checkReadyToRelease()
            }
            surfaceTexture.setOnFrameAvailableListener({ st -> onFrameAvailable(st) }, glHandler)
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
                val removed = outputSurfaces.remove(output)
                if (removed != null) {
                    renderer.unregisterOutputSurface(removed)
                }
            }
            renderer.registerOutputSurface(surface)
            outputSurfaces[output] = surface
        }, output::close)
    }

    private fun onFrameAvailable(surfaceTexture: SurfaceTexture) {
        if (isReleaseRequested.get()) return
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(textureTransform)
        val timestampNs = surfaceTexture.timestamp

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
