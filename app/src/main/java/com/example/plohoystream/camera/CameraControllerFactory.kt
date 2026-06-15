package com.example.plohoystream.camera

import android.content.Context

/** Chooses the capture backend. CameraX is default; Camera2 retained for fallback during bring-up. */
object CameraControllerFactory {
    @Volatile var useCameraX: Boolean = true

    fun create(context: Context): CameraController =
        if (useCameraX) CameraXController(context) else Camera2Controller(context)
}
