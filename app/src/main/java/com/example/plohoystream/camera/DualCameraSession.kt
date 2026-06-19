package com.example.plohoystream.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.example.plohoystream.camera.scene.Scene

/**
 * Opens the BACK and FRONT cameras as two INDEPENDENT Camera2 [CameraDevice]s, each with its own
 * [CameraCaptureSession] targeting one of the two input [Surface]s vended by
 * [EgressSurfaceProcessor.startStandaloneDual]. The processor composites both into the preview +
 * encoder. This replaces the CameraX concurrent-bind path for dual mode (Task 6 wires it in for real).
 *
 * Threading: a single dedicated camera [HandlerThread] runs all open/session/repeating-request work
 * and all StateCallback callbacks, so the two devices share one serialized control thread. The GL
 * compositing lives entirely inside the [processor].
 *
 * Capacity: per-camera capture sizes are chosen from each sensor's supported sizes — the largest
 * NATIVE-aspect size at/below 720p — to stay within the device's certified concurrent guarantee
 * while preserving each sensor's true aspect (e.g. a 4:3 front sensor stays 4:3, not forced to 16:9).
 */
class DualCameraSession(
    context: Context,
    private val processor: EgressSurfaceProcessor,
    @Suppress("unused") private val caps: ConcurrentCameraCapabilities,
) {

    private val appContext = context.applicationContext
    private val cameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // One serialized camera control thread shared by both devices/sessions.
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var backDevice: CameraDevice? = null
    private var frontDevice: CameraDevice? = null
    private var backSession: CameraCaptureSession? = null
    private var frontSession: CameraCaptureSession? = null

    private var dualInputs: EgressSurfaceProcessor.DualInputs? = null
    @Volatile private var started = false
    @Volatile private var failedFired = false
    private var onFailedCb: () -> Unit = {}

    // --- Bounded open/configure retry (FIX C) -------------------------------------------------
    // On some MediaTek HALs, entering dual right after a single-mode tele session is still tearing
    // down causes the {main+front} open to fail transiently. We retry the WHOLE open sequence a few
    // times before giving up. Once both sessions are configured AND streaming we flip to LIVE; after
    // that an onError is FATAL (no retry) to avoid reopen loops. All read/written on [cameraHandler].
    @Volatile private var phase = Phase.IDLE
    private var openAttempts = 0
    // The full open arguments, kept so a delayed retry can re-run the open sequence verbatim.
    private var openArgs: OpenArgs? = null
    // The pending delayed-retry runnable, so stop() (user toggle-off) can cancel it.
    private var pendingRetry: Runnable? = null

    private enum class Phase { IDLE, OPENING, LIVE }

    private data class OpenArgs(
        val backId: String,
        val frontId: String,
        val backSurface: Surface,
        val frontSurface: Surface,
    )

    // State needed for the blink-free operations (switchBack / setZoom / swapPrimary). All camera
    // mutation runs on [cameraHandler]; these are read/written from there (start() runs on the caller
    // thread but only before the camera thread touches them).
    private var backId: String? = null
    private var frontId: String? = null
    // The facing routed to slot A (the PRIMARY input / textureName) at start(); fixed for the session.
    private var startPrimaryFacing: Facing = Facing.BACK
    // The Surface the back device feeds (slot A if back is primary, else slot B). Reused on switchBack.
    private var backSurface: Surface? = null
    private var displayDeg: Int = 0
    // Back request builder kept so setZoom can rebuild + re-submit without a session/device reopen.
    private var backRequestBuilder: CaptureRequest.Builder? = null
    @Volatile private var zoomRatio: Float = 1f
    @Volatile private var currentSwapped = false

    /**
     * Open back + front, route the device whose facing matches [primaryFacing] to the PRIMARY input
     * and the other to the SECONDARY input, and start a repeating PREVIEW request on each. On any
     * open error/disconnect this calls [stop] then [onFailed] (caller reverts to single).
     */
    @SuppressLint("MissingPermission")
    fun start(
        primaryFacing: Facing,
        backId: String,
        frontId: String,
        preview: Surface?,
        encoder: Surface?,
        displayDeg: Int,
        scene: Scene,
        onFailed: () -> Unit,
    ) {
        if (started) stop()
        started = true
        failedFired = false
        onFailedCb = onFailed
        this.backId = backId
        this.frontId = frontId
        this.startPrimaryFacing = primaryFacing
        this.displayDeg = displayDeg
        this.zoomRatio = 1f
        this.currentSwapped = false
        // A fresh external start() resets the retry counter and re-enters the OPENING phase.
        openAttempts = 0
        phase = Phase.OPENING
        pendingRetry = null

        Log.i(TAG, "dual start primary=$primaryFacing back=$backId front=$frontId")

        val thread = HandlerThread("DualCamera").also { it.start() }
        val handler = Handler(thread.looper)
        cameraThread = thread
        cameraHandler = handler

        val backSensorDeg = sensorOrientation(backId)
        val frontSensorDeg = sensorOrientation(frontId)

        // PRIMARY = the device whose facing == primaryFacing; SECONDARY = the other.
        val backIsPrimary = primaryFacing == Facing.BACK
        val primarySensorDeg = if (backIsPrimary) backSensorDeg else frontSensorDeg
        val primaryIsFront = !backIsPrimary
        val secondarySensorDeg = if (backIsPrimary) frontSensorDeg else backSensorDeg
        val secondaryIsFront = backIsPrimary

        // Phone-agnostic capture sizes: pick each sensor's NATIVE-aspect size (≤720p) so the
        // composite + PiP-box sizing report the true aspect (e.g. a 4:3 front sensor stays 4:3).
        val primaryId = if (backIsPrimary) backId else frontId
        val secondaryId = if (backIsPrimary) frontId else backId
        val primarySize = captureSizeFor(primaryId)
        val secondarySize = captureSizeFor(secondaryId)

        val inputs = processor.startStandaloneDual(
            preview = preview,
            encoder = encoder,
            primarySensorDeg = primarySensorDeg,
            primaryIsFront = primaryIsFront,
            secondarySensorDeg = secondarySensorDeg,
            secondaryIsFront = secondaryIsFront,
            displayDeg = displayDeg,
            primarySize = primarySize,
            secondarySize = secondarySize,
            scene = scene,
        )
        dualInputs = inputs

        // Route each physical device to its scene slot's input Surface.
        val backSurface = if (backIsPrimary) inputs.primary else inputs.secondary
        val frontSurface = if (backIsPrimary) inputs.secondary else inputs.primary
        this.backSurface = backSurface

        // Remember the open args so a transient-failure retry can re-run the open sequence verbatim.
        openArgs = OpenArgs(backId, frontId, backSurface, frontSurface)
        openDevicesForAttempt(handler)
    }

    /**
     * Open BOTH devices for the current attempt. Called from [start] and from the delayed retry. The
     * surfaces (input SurfaceTextures) are reused across attempts; only the Camera2 devices+sessions
     * are reopened. Runs on the camera [handler].
     */
    private fun openDevicesForAttempt(handler: Handler) {
        val args = openArgs ?: return
        openDevice(args.backId, args.backSurface, handler, isBack = true)
        openDevice(args.frontId, args.frontSurface, handler, isBack = false)
    }

    /**
     * A device/session error/disconnect fired DURING the open-or-configure phase. Close any devices +
     * sessions opened for this attempt (the input surfaces are kept) and, if we have retries left,
     * post a delayed reopen on the camera [handler]; otherwise give up via [fail]. Runs on the camera
     * thread. No-op once we're LIVE (those errors are routed to [failFatal] instead).
     */
    private fun retryOrFail() {
        if (!started || phase != Phase.OPENING) return
        val handler = cameraHandler ?: return
        // Tear down whatever this attempt opened; reuse the input surfaces on the next attempt.
        runCatching { backSession?.close() }
        runCatching { frontSession?.close() }
        backSession = null
        frontSession = null
        runCatching { backDevice?.close() }
        runCatching { frontDevice?.close() }
        backDevice = null
        frontDevice = null
        backRequestBuilder = null

        val n = openAttempts + 1
        if (n < MAX_OPEN_ATTEMPTS) {
            openAttempts = n
            Log.w(TAG, "dual open attempt $n failed, retrying")
            val retry = Runnable {
                pendingRetry = null
                if (!started || phase != Phase.OPENING) return@Runnable
                openDevicesForAttempt(handler)
            }
            pendingRetry = retry
            handler.postDelayed(retry, RETRY_DELAY_MS)
        } else {
            Log.e(TAG, "dual open failed after $n attempts")
            fail()
        }
    }

    private fun sensorOrientation(id: String): Int =
        runCatching {
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SENSOR_ORIENTATION)
        }.getOrNull() ?: 0

    /**
     * Pick a capture [Size] for camera [id] from its supported SurfaceTexture output sizes: the
     * size whose aspect ratio is CLOSEST to the sensor's native aspect (from SENSOR_INFO_ACTIVE_ARRAY_SIZE),
     * constrained to height ≤ 720 (the certified concurrent ≤720p guarantee). Ties broken by larger area.
     * Falls back to the smallest-area candidate overall if none qualify at ≤720p.
     * Preserves the sensor's NATIVE aspect (e.g. a 4:3 front sensor → 640x480/960x720),
     * so the composite + PiP box report the true aspect.
     */
    private fun captureSizeFor(id: String): Size {
        val chars = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrNull()
            ?: return Size(640, 480)
        val arr = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val nativeAspect = if (arr != null && arr.height() > 0) arr.width().toFloat() / arr.height() else 4f / 3f
        val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            ?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(640, 480)
        val candidates = sizes.filter { it.height <= 720 }
        val chosen = if (candidates.isNotEmpty()) {
            candidates.minWithOrNull(
                compareBy({ Math.abs(it.width.toFloat() / it.height - nativeAspect) },
                          { -(it.width.toLong() * it.height) })
            )!!
        } else {
            sizes.minByOrNull { it.width.toLong() * it.height }!!
        }
        Log.i("DualCameraSession", "capture id=$id size=${chosen.width}x${chosen.height} nativeAspect=$nativeAspect")
        return chosen
    }

    @SuppressLint("MissingPermission")
    private fun openDevice(id: String, target: Surface, handler: Handler, isBack: Boolean) {
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                Log.i(TAG, "opened device id=${device.id}")
                if (isBack) backDevice = device else frontDevice = device
                createSession(device, target, handler, isBack)
            }

            override fun onDisconnected(device: CameraDevice) {
                Log.e(TAG, "device id=$id error=disconnected")
                device.close()
                onOpenPhaseError()
            }

            override fun onError(device: CameraDevice, error: Int) {
                Log.e(TAG, "device id=$id error=$error")
                device.close()
                onOpenPhaseError()
            }
        }
        try {
            cameraManager.openCamera(id, callback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "device id=$id error=open-exception", e)
            onOpenPhaseError()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createSession(
        device: CameraDevice,
        target: Surface,
        handler: Handler,
        isBack: Boolean,
    ) {
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (isBack) backSession = session else frontSession = session
                val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(target)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                }
                if (isBack) {
                    // Keep the back builder so setZoom can re-submit without a reopen, and restore the
                    // current zoom (1x on start, the prior ratio after a switchBack).
                    backRequestBuilder = builder
                    applyZoomLocked(builder, device.id, zoomRatio)
                }
                val ok = runCatching { session.setRepeatingRequest(builder.build(), null, handler) }
                    .onFailure { Log.e(TAG, "device id=${device.id} error=repeating-request", it) }
                    .isSuccess
                if (!ok) {
                    onOpenPhaseError()
                    return
                }
                // Both devices opened AND both sessions configured + streaming → fully LIVE. After this
                // an error is FATAL (no retry) to avoid reopen loops. A switchBack reopens only the
                // back side while LIVE; that path is its own fatal-on-error open, not this retry path.
                if (phase == Phase.OPENING && backSession != null && frontSession != null) {
                    phase = Phase.LIVE
                    pendingRetry = null
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "device id=${device.id} error=session-configure-failed")
                onOpenPhaseError()
            }
        }
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(target), callback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "device id=${device.id} error=create-session-exception", e)
            onOpenPhaseError()
        }
    }

    /** The back camera's `CONTROL_ZOOM_RATIO_RANGE` (defaults to 1f..1f if unsupported/unknown). */
    private fun zoomRange(id: String): ClosedFloatingPointRange<Float> =
        runCatching {
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        }.getOrNull()?.let { it.lower..it.upper } ?: 1f..1f

    /** Clamp [ratio] to the [id] back camera's zoom range and set it on [builder]. */
    private fun applyZoomLocked(builder: CaptureRequest.Builder, id: String, ratio: Float) {
        val range = zoomRange(id)
        val clamped = ratio.coerceIn(range.start, range.endInclusive)
        builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, clamped)
        zoomRatio = clamped
    }

    /**
     * Set the digital zoom on the BACK device's repeating request and re-submit on the camera thread.
     * Clamped to the back camera's CONTROL_ZOOM_RATIO_RANGE. No texture/device change. No-op safely if
     * the session isn't started / the back session isn't configured yet.
     */
    fun setZoom(ratio: Float) {
        Log.i(TAG, "setZoom $ratio")
        if (!started) return
        val handler = cameraHandler ?: return
        handler.post {
            if (!started) return@post
            val builder = backRequestBuilder ?: return@post
            val session = backSession ?: return@post
            val id = backId ?: return@post
            applyZoomLocked(builder, id, ratio)
            runCatching { session.setRepeatingRequest(builder.build(), null, handler) }
                .onFailure { Log.e(TAG, "setZoom error=repeating-request", it) }
        }
    }

    /** Forward a new preview [surface] (null = drop preview) to the standalone compositor. */
    fun setPreview(surface: Surface?) {
        processor.setStandalonePreview(surface)
    }

    /**
     * Instant camera swap (no device/session reopen): relabel which open source is the scene's big
     * view vs the PiP. The device routed to slot A at start() has [startPrimaryFacing]; swapping just
     * flips the processor's slot mapping since both textures already stream. No-op if unchanged.
     */
    fun swapPrimary(primaryFacing: Facing) {
        val swapped = primaryFacing != startPrimaryFacing
        Log.i(TAG, "swapPrimary -> $primaryFacing swapped=$swapped")
        if (!started) return
        if (swapped == currentSwapped) return
        currentSwapped = swapped
        processor.setStandaloneSwapped(swapped)
    }

    /**
     * Blink-free back-sensor switch: close ONLY the back device + back session (front untouched),
     * reopen [newBackId] and recreate the back session targeting the SAME input Surface. The back
     * input SurfaceTexture keeps its last GL frame across the reopen, so the composite holds the
     * back's last frame while the front stays live. Restores zoom and pushes the new back
     * SENSOR_ORIENTATION into the back's slot. Guarded by [ConcurrentCameraCapabilities.isConcurrent].
     */
    fun switchBack(newBackId: String) {
        if (!started) return
        val front = frontId ?: return
        if (!caps.isConcurrent(newBackId, front)) {
            Log.i(TAG, "switchBack skipped (not concurrent with front)")
            return
        }
        val handler = cameraHandler ?: return
        val target = backSurface ?: return
        handler.post {
            if (!started) return@post
            val old = backId
            if (old == newBackId) return@post
            Log.i(TAG, "switchBack from=$old to=$newBackId (front kept alive)")

            // Close ONLY the back side; front device/session are never touched.
            runCatching { backSession?.close() }
            backSession = null
            backRequestBuilder = null
            runCatching { backDevice?.close() }
            backDevice = null

            backId = newBackId

            // Push the new back sensor orientation into whichever slot the back occupies. The back is
            // in slot A (PRIMARY input) when it was the primary facing at start().
            val backInSlotA = startPrimaryFacing == Facing.BACK
            processor.setStandaloneBackOrientation(
                sensorDeg = sensorOrientation(newBackId),
                isFront = false,
                inSlotA = backInSlotA,
            )

            // Reopen + recreate the back session targeting the SAME input Surface; createSession
            // restores the current zoom onto the rebuilt back request.
            openDevice(newBackId, target, handler, isBack = true)
        }
    }

    /**
     * Route an error/disconnect/configure-failure by phase (camera thread):
     *  - OPENING (not yet fully streaming) → bounded retry of the open sequence ([retryOrFail]).
     *  - LIVE (both sessions configured + streaming) → FATAL: tear down + fall back, NO retry, so a
     *    runtime device error can't trigger an endless reopen loop.
     */
    private fun onOpenPhaseError() {
        if (!started) return
        when (phase) {
            Phase.OPENING -> retryOrFail()
            else -> fail()   // LIVE (or IDLE) → fatal
        }
    }

    /** Tear down on an open/configure failure, then invoke the caller's fallback exactly once. */
    private fun fail() {
        if (failedFired) return
        failedFired = true
        val cb = onFailedCb
        stop()
        cb()
    }

    /** Close both sessions + devices, leave standalone-dual mode, and quit the camera thread. */
    fun stop() {
        if (!started) return
        started = false

        // Cancel any pending open-retry so a user toggle-off (or external restart) doesn't reopen.
        pendingRetry?.let { r -> cameraHandler?.removeCallbacks(r) }
        pendingRetry = null
        phase = Phase.IDLE
        openArgs = null
        openAttempts = 0

        runCatching { backSession?.close() }
        runCatching { frontSession?.close() }
        backSession = null
        frontSession = null

        runCatching { backDevice?.close() }
        runCatching { frontDevice?.close() }
        backDevice = null
        frontDevice = null

        backRequestBuilder = null
        backSurface = null
        backId = null
        frontId = null

        runCatching { processor.stopStandalone() }
        dualInputs = null

        // Don't quit the camera thread immediately: the device.close() calls above complete
        // asynchronously and a late CameraDevice.onClosed can still post to this handler. Quitting
        // now races that post → "sending message to a Handler on a dead thread". Defer the quit a
        // short beat so in-flight close callbacks drain first (non-fatal noise either way, but this
        // removes it cheaply). Capture locally so the deferred runnable doesn't touch reset fields.
        val thread = cameraThread
        cameraThread = null
        cameraHandler = null
        if (thread != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { thread.quitSafely() }
            }, QUIT_DELAY_MS)
        }
    }

    private companion object {
        const val TAG = "DualCameraSession"
        // Bounded open/configure retry: up to MAX_OPEN_ATTEMPTS total opens, ~RETRY_DELAY_MS apart,
        // to ride out a transient HAL teardown conflict (e.g. tele session still closing) on entry.
        const val MAX_OPEN_ATTEMPTS = 5
        const val RETRY_DELAY_MS = 450L
        // Defer the camera HandlerThread quit so late CameraDevice.onClosed callbacks drain first
        // (avoids "sending message to a Handler on a dead thread" noise on stop()).
        const val QUIT_DELAY_MS = 250L
    }
}
