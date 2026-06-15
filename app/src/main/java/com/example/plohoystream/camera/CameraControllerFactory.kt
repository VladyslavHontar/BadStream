package com.example.plohoystream.camera

import android.content.Context

/** Constructs the capture backend. CameraX is the sole backend (Camera2 retired post-parity). */
object CameraControllerFactory {
    fun create(context: Context): CameraController = CameraXController(context)
}
