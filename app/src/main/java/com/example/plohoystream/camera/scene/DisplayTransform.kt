package com.example.plohoystream.camera.scene

import android.opengl.Matrix

/**
 * Orientation + crop math for compositing a camera source whose frames arrive WITHOUT CameraX's
 * display-correcting [androidx.camera.core.SurfaceOutput] transform (the dual-mode secondary).
 *
 * [netRotationDegrees], [displayedAspect] and [coverCrop] are pure and unit-tested. [matrix]
 * assembles the 4x4 texture-coordinate transform with [android.opengl.Matrix] (not available in
 * unit tests) and is verified on-device — it is the documented composition of those tested pieces.
 */
object DisplayTransform {

    /**
     * Clockwise degrees to rotate the sampled sensor image upright on the display, for a camera fed
     * RAW into the compositor (no CameraX SurfaceOutput transform). Back: `(sensor - display - 90)`,
     * Front: `(sensor + display - 90)`. Both carry a common `-90` device/texture offset (the secondary
     * SurfaceTexture arrives a quarter-turn from upright); front flips the display sign because of its
     * horizontal mirror. Calibrated on-device at display ROTATION_90: back sensor 90 → 270 (90° CCW),
     * front sensor 270 → 270, both upright. All normalized to 0..359.
     */
    fun netRotationDegrees(sensorDeg: Int, displayDeg: Int, isFront: Boolean): Int {
        val raw = sensorDeg + displayDeg - 90
        return ((raw % 360) + 360) % 360
    }

    /** Width/height the source presents after a quarter-turn of [rotationDeg] (from
     *  [netRotationDegrees]); 90/270 swap W and H. Returns 1f for a not-yet-known (non-positive)
     *  size, so a startup frame can't push NaN/Inf into the GPU before the camera reports a size. */
    fun displayedAspect(srcW: Int, srcH: Int, rotationDeg: Int): Float {
        if (srcW <= 0 || srcH <= 0) return 1f
        val w = srcW.toFloat()
        val h = srcH.toFloat()
        return if (rotationDeg == 90 || rotationDeg == 270) h / w else w / h
    }

    /** Texture-coord scale (cropX, cropY) about center to COVER a rect of [rectAspect] (w/h) with
     *  content of [contentAspect] (w/h): the over-wide axis is cropped (cover, not letterbox).
     *  Returns no-op (1f, 1f) for a non-positive aspect, so invalid/early input can't render NaN. */
    fun coverCrop(contentAspect: Float, rectAspect: Float): Pair<Float, Float> =
        if (contentAspect <= 0f || rectAspect <= 0f) 1f to 1f
        else if (rectAspect > contentAspect) 1f to (contentAspect / rectAspect)
        else (rectAspect / contentAspect) to 1f

    /**
     * 4x4 texture-coordinate matrix that rotates the sampled image upright ([netRotationDegrees]),
     * mirrors horizontally for the front camera, and applies the cover-crop scale — all about the
     * texture center (0.5, 0.5). Compose this with the raw `SurfaceTexture` transform (this matrix
     * on the LEFT) before uploading to `uTexMatrix`.
     *
     * The mirror-vs-rotate composition order is the one verified on-device (front PiP upright and
     * mirrored on the correct axis); do not reorder without re-checking on a front-camera landscape.
     *
     * @param cropX horizontal texture-space scale from [coverCrop] (<1 crops width).
     * @param cropY vertical texture-space scale from [coverCrop] (<1 crops height).
     */
    fun matrix(
        sensorDeg: Int,
        displayDeg: Int,
        isFront: Boolean,
        cropX: Float,
        cropY: Float,
    ): FloatArray {
        val rotation = netRotationDegrees(sensorDeg, displayDeg, isFront)
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, 0.5f, 0.5f, 0f)
        Matrix.scaleM(m, 0, cropX, cropY, 1f)
        if (isFront) Matrix.scaleM(m, 0, -1f, 1f, 1f)
        Matrix.rotateM(m, 0, rotation.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(m, 0, -0.5f, -0.5f, 0f)
        return m
    }
}
