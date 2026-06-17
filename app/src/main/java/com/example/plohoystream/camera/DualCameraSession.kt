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

        openDevice(backId, backSurface, handler, isBack = true)
        openDevice(frontId, frontSurface, handler, isBack = false)
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
                fail()
            }

            override fun onError(device: CameraDevice, error: Int) {
                Log.e(TAG, "device id=$id error=$error")
                device.close()
                fail()
            }
        }
        try {
            cameraManager.openCamera(id, callback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "device id=$id error=open-exception", e)
            fail()
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
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(target)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                }.build()
                runCatching { session.setRepeatingRequest(request, null, handler) }
                    .onFailure { Log.e(TAG, "device id=${device.id} error=repeating-request", it); fail() }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "device id=${device.id} error=session-configure-failed")
                fail()
            }
        }
        try {
            @Suppress("DEPRECATION")
            device.createCaptureSession(listOf(target), callback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "device id=${device.id} error=create-session-exception", e)
            fail()
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

        runCatching { backSession?.close() }
        runCatching { frontSession?.close() }
        backSession = null
        frontSession = null

        runCatching { backDevice?.close() }
        runCatching { frontDevice?.close() }
        backDevice = null
        frontDevice = null

        runCatching { processor.stopStandalone() }
        dualInputs = null

        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }

    private companion object {
        const val TAG = "DualCameraSession"
    }
}
