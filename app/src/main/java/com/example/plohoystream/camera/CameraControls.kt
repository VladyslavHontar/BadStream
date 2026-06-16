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

    // --- Exposure / shutter (manual exposure for motion blur). ---

    /**
     * The "180° shutter rule" exposure time, in nanoseconds, for [fps]: a shutter of 1/(2·fps)s
     * gives natural motion blur (e.g. 1/60s at 30fps). Falls back to 1/60s for a non-positive fps.
     */
    fun shutter180Ns(fps: Int): Long =
        if (fps > 0) 1_000_000_000L / (2L * fps) else 1_000_000_000L / 60L

    fun clampShutterNs(ns: Long, min: Long, max: Long): Long = ns.coerceIn(min, max)

    fun clampIso(iso: Int, min: Int, max: Int): Int = iso.coerceIn(min, max)

    /**
     * One shutter-priority Auto-ISO step: with the shutter fixed, nudge ISO toward the target scene
     * brightness. [measuredLuma]/[targetLuma] are average frame luma in 0..1. The correction is the
     * brightness ratio raised to [gain] (<1 damps, preventing oscillation/flicker), then per-step
     * swing is bounded so a sudden scene change can't strobe. Returns the new ISO clamped to range.
     * Pure so the control law is unit-tested without a camera.
     */
    fun autoIsoStep(
        currentIso: Int, measuredLuma: Float, targetLuma: Float,
        minIso: Int, maxIso: Int, gain: Float = 0.5f,
    ): Int {
        if (maxIso <= minIso) return minIso
        val safeMeasured = measuredLuma.coerceAtLeast(0.001f)
        val ratio = (targetLuma / safeMeasured).coerceIn(0.25f, 4.0f)   // cap one step's swing
        val damped = Math.pow(ratio.toDouble(), gain.toDouble()).toFloat()
        val next = Math.round(currentIso * damped)
        return next.coerceIn(minIso, maxIso)
    }
}
