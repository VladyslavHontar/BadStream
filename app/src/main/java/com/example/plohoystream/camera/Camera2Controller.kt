package com.example.plohoystream.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.util.concurrent.Executor

/**
 * Camera2 implementation of [CameraController].
 *
 * All camera work runs on a dedicated [HandlerThread]. [start] is idempotent: calling
 * it again (e.g. on a front/back flip) tears down the previous session first, so the
 * viewfinder can just re-issue [start] with a new [CameraConfig].
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

    private var minZoom = 1.0f
    private var maxZoom = 1.0f
    private var currentZoom = 1.0f

    @SuppressLint("MissingPermission")
    override fun start(config: CameraConfig, targets: List<Surface>) {
        handler.post {
            closeSession()
            this.targets = targets
            minZoom = config.minZoom
            maxZoom = config.maxZoom
            currentZoom = CameraControls.clampZoom(currentZoom, minZoom, maxZoom)
            manager.openCamera(config.cameraId, deviceCallback, handler)
        }
    }

    override fun stop() {
        handler.post { closeSession() }
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
            device = camera
            configureSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            if (device === camera) device = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            if (device === camera) device = null
        }
    }

    private fun configureSession(camera: CameraDevice) {
        val outputs = targets.map { OutputConfiguration(it) }
        if (outputs.isEmpty()) return
        val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            targets.forEach { addTarget(it) }
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
                }

                override fun onConfigureFailed(failed: CameraCaptureSession) {
                    // Leave session null; a subsequent start() can retry.
                }
            },
        )
        camera.createCaptureSession(sessionConfig)
    }

    private fun closeSession() {
        runCatching { session?.close() }
        session = null
        requestBuilder = null
        runCatching { device?.close() }
        device = null
        targets = emptyList()
    }
}
