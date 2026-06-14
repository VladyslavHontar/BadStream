package com.example.plohoystream.camera

/**
 * Pure camera-control logic, free of android.* so it stays host-JVM testable.
 * The Camera2 controller and the viewfinder UI both lean on these helpers so the
 * clamping/flip rules live in exactly one place.
 */
object CameraControls {

    /** Clamp a requested zoom ratio into the camera's supported [min, max] range. */
    fun clampZoom(ratio: Float, min: Float, max: Float): Float = ratio.coerceIn(min, max)

    /** The other facing — used by the flip control. */
    fun opposite(facing: Facing): Facing = when (facing) {
        Facing.BACK -> Facing.FRONT
        Facing.FRONT -> Facing.BACK
    }
}
