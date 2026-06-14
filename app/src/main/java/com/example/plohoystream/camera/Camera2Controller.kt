package com.example.plohoystream.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executor

/**
 * Camera2 implementation of [CameraController].
 *
 * All camera work runs on a dedicated [HandlerThread]. The device is opened ONCE per
 * camera id; changing the target surfaces (e.g. adding the encoder on go-live) or flipping
 * zoom rebuilds only the capture *session* on the already-open device. Switching cameras
 * (front/back) closes and reopens.
 *
 * Two subtleties this guards against, learned from a real-device (Solana Seeker) crash:
 *  - **No concurrent opens of the same camera.** [start] can be called several times in
 *    quick succession (the preview SurfaceView re-emits its surface during initial layout).
 *    A second `openCamera` for a camera that is already open/opening makes the camera
 *    service disconnect the first client; the first client's [onOpened] then fires on a
 *    disconnected device and `createCaptureRequest` throws `CAMERA_DISCONNECTED`. We coalesce
 *    repeat starts and reject stale [onOpened] callbacks by camera id.
 *  - **Session setup never crashes the app.** A device can be disconnected out from under us
 *    between open and configure, so all camera calls on the handler thread are guarded.
 *
 * Permission is gated by the UI before [start] is ever called, hence the suppression.
 */
class Camera2Controller(context: Context) : CameraController {

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("PlohoyCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { command -> handler.post(command) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var targets: List<Surface> = emptyList()
    private var openedCameraId: String? = null
    private var opening = false

    // The camera id the UI currently wants open (null after stop()); drives auto-recovery.
    private var wantCameraId: String? = null
    private var reopenAttempts = 0

    private var minZoom = 1.0f
    private var maxZoom = 1.0f
    private var currentZoom = 1.0f

    @SuppressLint("MissingPermission")
    override fun start(config: CameraConfig, targets: List<Surface>) {
        handler.post {
            wantCameraId = config.cameraId
            // Skip a redundant start for the same camera + identical surfaces already
            // active/in-flight (the preview can re-issue an unchanged start at launch).
            if (config.cameraId == openedCameraId && targets == this.targets && (device != null || opening)) {
                Log.i(TAG, "start ignored: camera ${config.cameraId} already active with same targets")
                return@post
            }
            this.targets = targets
            reopenAttempts = 0 // fresh user-initiated start: reset the recovery budget
            minZoom = config.minZoom
            maxZoom = config.maxZoom
            currentZoom = CameraControls.clampZoom(currentZoom, minZoom, maxZoom)

            val open = device
            when {
                // Same camera already open: just rebuild the session with the new targets.
                config.cameraId == openedCameraId && open != null -> {
                    Log.i(TAG, "reconfigure open camera ${config.cameraId} (${targets.size} targets)")
                    reconfigure(open)
                }
                // Open already in flight for this camera: coalesce — onOpened uses latest targets.
                config.cameraId == openedCameraId && opening -> {
                    Log.i(TAG, "open in flight for ${config.cameraId}; coalescing (${targets.size} targets)")
                }
                // Different camera (or nothing open): close current and (re)open.
                else -> {
                    Log.i(TAG, "opening camera ${config.cameraId}")
                    closeCamera()
                    openCameraInternal(config.cameraId)
                }
            }
        }
    }

    override fun stop() {
        handler.post {
            wantCameraId = null
            closeCamera()
        }
    }

    override fun setZoom(ratio: Float) {
        handler.post {
            currentZoom = CameraControls.clampZoom(ratio, minZoom, maxZoom)
            val builder = requestBuilder ?: return@post // not configured yet; applied on configure
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom)
            runCatching { session?.setRepeatingRequest(builder.build(), null, handler) }
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            // Reject a stale open that a newer start() has superseded (different camera id).
            if (camera.id != openedCameraId) {
                Log.i(TAG, "ignoring stale onOpened for ${camera.id} (want $openedCameraId)")
                runCatching { camera.close() }
                return
            }
            opening = false
            reopenAttempts = 0 // a successful open clears the recovery budget
            device = camera
            reconfigure(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "camera ${camera.id} disconnected")
            runCatching { camera.close() }
            resetDeviceState(camera)
            maybeReopen()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.w(TAG, "camera ${camera.id} error $error")
            runCatching { camera.close() }
            resetDeviceState(camera)
            maybeReopen()
        }
    }

    /** Open [cameraId], tracking the in-flight state so concurrent/stale opens are coalesced. */
    @SuppressLint("MissingPermission")
    private fun openCameraInternal(cameraId: String) {
        openedCameraId = cameraId
        opening = true
        runCatching { manager.openCamera(cameraId, deviceCallback, handler) }
            .onFailure {
                Log.w(TAG, "openCamera failed", it)
                opening = false
                openedCameraId = null
            }
    }

    /** Recover from an unexpected disconnect/error by reopening the wanted camera, bounded. */
    private fun maybeReopen() {
        val want = wantCameraId ?: return // UI no longer wants a camera (stopped)
        if (targets.isEmpty()) return
        if (reopenAttempts >= MAX_REOPEN) {
            Log.w(TAG, "giving up reopening $want after $MAX_REOPEN attempts")
            return
        }
        reopenAttempts++
        Log.i(TAG, "reopening $want (attempt $reopenAttempts/$MAX_REOPEN)")
        openCameraInternal(want)
    }

    /** (Re)build a capture session for the current [targets] on an already-open [camera]. */
    private fun reconfigure(camera: CameraDevice) {
        runCatching { session?.close() }
        session = null
        requestBuilder = null

        val valid = targets.filter { it.isValid }
        targets.filterNot { it.isValid }.forEach { Log.w(TAG, "dropping abandoned target $it") }
        if (valid.isEmpty()) {
            Log.w(TAG, "no valid camera targets; skipping configure")
            return
        }
        try {
            val outputs = valid.map { OutputConfiguration(it) }
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                valid.forEach { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom)
            }
            requestBuilder = builder
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        session = configured
                        runCatching { configured.setRepeatingRequest(builder.build(), null, handler) }
                            .onFailure { Log.w(TAG, "setRepeatingRequest failed", it) }
                    }

                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        Log.w(TAG, "camera session configuration failed")
                    }
                },
            )
            camera.createCaptureSession(sessionConfig)
        } catch (e: CameraAccessException) {
            // Device was disconnected between open and configure — reset so a later start() reopens.
            Log.w(TAG, "configure failed; camera disconnected", e)
            if (device === camera) device = null
            openedCameraId = null
            runCatching { camera.close() }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "configure failed; illegal state", e)
        }
    }

    private fun resetDeviceState(camera: CameraDevice) {
        if (device === camera) device = null
        session = null
        requestBuilder = null
        opening = false
        openedCameraId = null
    }

    private fun closeCamera() {
        runCatching { session?.close() }
        session = null
        requestBuilder = null
        runCatching { device?.close() }
        device = null
        openedCameraId = null
        opening = false
        // NOTE: do NOT clear `targets` here. On a front/back flip, start() sets the new
        // targets and then calls closeCamera() before reopening; the reopened camera's
        // onOpened -> reconfigure() needs those targets. start() always sets targets fresh,
        // so leaving stale targets here is harmless when there is no device.
    }

    private companion object {
        const val TAG = "Camera2Controller"
        const val MAX_REOPEN = 3
    }
}
