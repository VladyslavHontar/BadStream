# Dual-Camera Scene Compositor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the ad-hoc dual-camera PiP path with a local scene compositor where a scene is an ordered list of camera-source layers rendered identically to preview and encoder, fixing the front-PiP orientation/aspect bug at its root and enabling live drag/resize/swap.

**Architecture:** A pure `Scene` model (layers = source + rect + z) is the single source of truth. Each layer's source resolves to an OES texture plus a device-correct texture transform — the primary keeps CameraX's `SurfaceOutput` transform; the secondary gets a derived, unit-tested transform from sensor/display rotation + mirror. `GlRenderer` gains a z-ordered layer renderer; single-camera keeps the existing full-frame `render()`. The UI hoists the `Scene` and pushes live edits to the GL thread; swap rebinds the concurrent cameras (high-res slot follows the big view) under the existing freeze-blur.

**Tech Stack:** Kotlin, CameraX 1.6.1 (concurrent camera + `CameraEffect`), GLES2/EGL14, Jetpack Compose (Material3), JUnit4 (plain unit tests, no Robolectric — keep test logic free of Android classes).

---

## Spec

Design doc: `docs/superpowers/specs/2026-06-17-dual-camera-scene-compositor-design.md`. Read it first.

## Background the implementer needs

- **Why single-cam works but dual PiP doesn't:** every camera in single mode flows through CameraX's `Preview` → `CameraEffect` → `SurfaceOutput`. CameraX computes a device-correct display transform via `SurfaceOutput.updateTransformMatrix(out, in)` (`EgressSurfaceProcessor.onFrameAvailable`, ~line 297) that bakes in sensor orientation, front mirroring, and the 16:9 `ViewPort` crop; the renderer just applies it. The dual **secondary** camera bypasses this — it is fed via a raw `SurfaceProvider` (`EgressSurfaceProcessor.provideSecondarySurface`, ~line 167) with no effect/output/viewport, so only the bare `SurfaceTexture` transform is available and orientation/crop were hand-rolled in `GlRenderer.renderComposite`.
- **Concurrent resolution caps (this device):** primary slot ~`1280x720` requested (binds ~`960x720`), secondary slot ~`640x360` requested (binds ~`320x240`). The big view must always be the primary (hi-res) slot.
- **Unit-test constraint:** existing tests (`app/src/test/java/com/example/plohoystream/camera/PipLayoutTest.kt`) are plain JUnit with NO Android classes. `android.opengl.Matrix` is NOT available in unit tests (throws "not mocked"). Keep all unit-tested logic in plain Kotlin/Int/Float math.
- **GL/EGL and Compose UI cannot be unit-tested here.** Those tasks verify on-device with `adb` (device id `SM02E4060314107`; app id `com.example.plohoystream`; app is landscape-locked).
- **Test command:** `./gradlew :app:testDebugUnitTest` (add `--tests "com.example.plohoystream.camera.SceneTest"` to scope). **Build:** `./gradlew :app:assembleDebug`.

## File Structure

- **Create**
  - `app/src/main/java/com/example/plohoystream/camera/scene/Scene.kt` — `SourceId`, `NormRect`, `PipSize`, `SceneLayer`, `Scene`, `SceneEdits` (pure model + edits).
  - `app/src/main/java/com/example/plohoystream/camera/scene/DisplayTransform.kt` — `netRotationDegrees`, `coverCrop` (pure), `matrix` (android.opengl.Matrix).
  - `app/src/test/java/com/example/plohoystream/camera/scene/SceneTest.kt`
  - `app/src/test/java/com/example/plohoystream/camera/scene/DisplayTransformTest.kt`
  - `app/src/main/java/com/example/plohoystream/ui/viewfinder/PipOverlay.kt` — drag/resize/swap gestures.
- **Modify**
  - `app/src/main/java/com/example/plohoystream/camera/GlRenderer.kt` — add `RenderLayer`, `renderScene`, private `drawLayer`; delete `renderComposite`.
  - `app/src/main/java/com/example/plohoystream/camera/EgressSurfaceProcessor.kt` — hold a `Scene` + secondary orientation; build `RenderLayer`s; `setScene` / `setDualConfig`; delete `setDualMode`/`pipLayout`.
  - `app/src/main/java/com/example/plohoystream/camera/CameraXController.kt` — `startDual` reads secondary `SENSOR_ORIENTATION` + passes scene; add `setScene`; pass display degrees.
  - `app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt` — hoist `Scene`; wire `PipOverlay`; swap = `beginCameraTransition()` + flip `facing`.
- **Delete**
  - `app/src/main/java/com/example/plohoystream/camera/PipLayout.kt` and `app/src/test/java/com/example/plohoystream/camera/PipLayoutTest.kt` (folded into the scene model).

---

### Task 1: Scene data model

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/camera/scene/Scene.kt`
- Test: `app/src/test/java/com/example/plohoystream/camera/scene/SceneTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/example/plohoystream/camera/scene/SceneTest.kt`:

```kotlin
package com.example.plohoystream.camera.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneTest {
    private val eps = 1e-4f

    @Test fun single_isOnePrimaryFullFrameLayer() {
        val s = Scene.SINGLE
        assertEquals(1, s.layers.size)
        val l = s.layers.single()
        assertEquals(SourceId.PRIMARY, l.source)
        assertEquals(NormRect.FULL, l.rect)
    }

    @Test fun dual_hasPrimaryBaseUnderSecondaryPip() {
        val s = Scene.dual()
        val ordered = s.ordered()
        assertEquals(SourceId.PRIMARY, ordered[0].source)   // lowest z first
        assertEquals(NormRect.FULL, ordered[0].rect)
        assertEquals(SourceId.SECONDARY, ordered[1].source)
        assertTrue(ordered[1].z > ordered[0].z)
    }

    @Test fun defaultPip_isSquareInTopRightInsetByMargin() {
        val r = Scene.defaultPip(PipSize.M)
        assertEquals(PipSize.M.widthFraction, r.width, eps)
        assertEquals(r.width, r.height, eps)                 // square in normalized space
        assertEquals(1f - PipSize.M.widthFraction - Scene.PIP_MARGIN, r.left, eps)
        assertEquals(Scene.PIP_MARGIN, r.top, eps)
    }

    @Test fun updateLayer_replacesOnlyThatLayersRect() {
        val moved = Scene.dual().updateLayer(SourceId.SECONDARY) { NormRect(0f, 0f, 0.2f, 0.2f) }
        assertEquals(NormRect.FULL, moved.layer(SourceId.PRIMARY)!!.rect)
        assertEquals(NormRect(0f, 0f, 0.2f, 0.2f), moved.layer(SourceId.SECONDARY)!!.rect)
    }

    @Test fun moveTo_recentersAndClampsInside() {
        val r = Scene.defaultPip(PipSize.M)
        val c = SceneEdits.moveTo(r, 0.5f, 0.5f)
        assertEquals(0.5f, c.centerX, eps)
        assertEquals(0.5f, c.centerY, eps)
        // pushed past the edge -> clamped fully inside the unit square
        val edge = SceneEdits.moveTo(r, 2f, -1f)
        assertTrue(edge.left >= -eps && edge.right <= 1f + eps)
        assertTrue(edge.top >= -eps && edge.bottom <= 1f + eps)
    }

    @Test fun resizeKeepingCenter_clampsToMinMaxAndStaysSquare() {
        val r = NormRect(0.4f, 0.4f, 0.6f, 0.6f)             // center 0.5,0.5
        val big = SceneEdits.resizeKeepingCenter(r, 10f)
        assertEquals(SceneEdits.MAX_PIP_WF, big.width, eps)
        assertEquals(big.width, big.height, eps)
        assertEquals(0.5f, big.centerX, eps)
        val small = SceneEdits.resizeKeepingCenter(r, 0f)
        assertEquals(SceneEdits.MIN_PIP_WF, small.width, eps)
    }

    @Test fun snapToCorner_choosesNearestCornerByCenterQuadrant() {
        val wf = PipSize.M.widthFraction
        val m = Scene.PIP_MARGIN
        val br = SceneEdits.snapToCorner(NormRect(0.7f, 0.7f, 0.7f + wf, 0.7f + wf), m)
        assertEquals(1f - wf - m, br.left, eps)
        assertEquals(1f - wf - m, br.top, eps)
        val tl = SceneEdits.snapToCorner(NormRect(0.05f, 0.05f, 0.05f + wf, 0.05f + wf), m)
        assertEquals(m, tl.left, eps)
        assertEquals(m, tl.top, eps)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.scene.SceneTest"`
Expected: FAIL — `Scene`, `SourceId`, `NormRect`, `PipSize`, `SceneEdits` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/example/plohoystream/camera/scene/Scene.kt`:

```kotlin
package com.example.plohoystream.camera.scene

/** Which camera slot a layer draws. PRIMARY = the high-res/effect slot (always the big view);
 *  SECONDARY = the low-res/direct slot (the PiP). Mapping of slot -> physical camera is owned by
 *  the controller's bind; swapping cameras is a rebind, not a scene mutation. */
enum class SourceId { PRIMARY, SECONDARY }

/** Normalized rectangle in the composited frame: (0,0) top-left .. (1,1) bottom-right. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    companion object { val FULL = NormRect(0f, 0f, 1f, 1f) }
}

/** PiP preset sizes as a fraction of the frame width (square box in normalized space). */
enum class PipSize(val widthFraction: Float) { S(0.22f), M(0.30f), L(0.40f) }

/** A camera [source] drawn into [rect]; layers composite in ascending [z]. */
data class SceneLayer(val source: SourceId, val rect: NormRect, val z: Int)

/** The composited scene: an ordered set of layers, the single source of truth for preview + stream. */
data class Scene(val layers: List<SceneLayer>) {
    val isDual: Boolean get() = layers.size > 1
    fun ordered(): List<SceneLayer> = layers.sortedBy { it.z }
    fun layer(source: SourceId): SceneLayer? = layers.firstOrNull { it.source == source }
    fun updateLayer(source: SourceId, transform: (NormRect) -> NormRect): Scene =
        copy(layers = layers.map { if (it.source == source) it.copy(rect = transform(it.rect)) else it })

    companion object {
        const val PIP_MARGIN = 0.04f
        val SINGLE = Scene(listOf(SceneLayer(SourceId.PRIMARY, NormRect.FULL, z = 0)))
        fun dual(pip: NormRect = defaultPip()): Scene = Scene(
            listOf(
                SceneLayer(SourceId.PRIMARY, NormRect.FULL, z = 0),
                SceneLayer(SourceId.SECONDARY, pip, z = 1),
            ),
        )
        /** A square PiP of [size] inset into the top-right corner by [margin]. */
        fun defaultPip(size: PipSize = PipSize.M, margin: Float = PIP_MARGIN): NormRect {
            val wf = size.widthFraction
            val left = 1f - wf - margin
            return NormRect(left, margin, left + wf, margin + wf)
        }
    }
}

/** Pure edits applied to the PiP layer's rect (kept square in normalized space). */
object SceneEdits {
    const val MIN_PIP_WF = 0.15f
    const val MAX_PIP_WF = 0.5f

    /** Re-center [r] on ([cx],[cy]), keeping its size, clamped fully inside the unit square. */
    fun moveTo(r: NormRect, cx: Float, cy: Float): NormRect {
        val halfW = r.width * 0.5f
        val halfH = r.height * 0.5f
        val clampedCx = cx.coerceIn(halfW, 1f - halfW)
        val clampedCy = cy.coerceIn(halfH, 1f - halfH)
        return NormRect(clampedCx - halfW, clampedCy - halfH, clampedCx + halfW, clampedCy + halfH)
    }

    /** Resize [r] to a square of [widthFraction] (clamped to [MIN_PIP_WF]..[MAX_PIP_WF]) about its
     *  center, then clamp back inside the unit square. */
    fun resizeKeepingCenter(r: NormRect, widthFraction: Float): NormRect {
        val wf = widthFraction.coerceIn(MIN_PIP_WF, MAX_PIP_WF)
        val half = wf * 0.5f
        val centered = NormRect(r.centerX - half, r.centerY - half, r.centerX + half, r.centerY + half)
        return moveTo(centered, centered.centerX, centered.centerY)
    }

    /** Snap [r] to whichever corner its center is closest to, inset by [margin]. */
    fun snapToCorner(r: NormRect, margin: Float): NormRect {
        val near = (1f - r.width - margin).coerceAtLeast(0f)
        val targetLeft = if (r.centerX < 0.5f) margin else near
        val targetTop = if (r.centerY < 0.5f) margin else near
        return NormRect(targetLeft, targetTop, targetLeft + r.width, targetTop + r.height)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.scene.SceneTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/scene/Scene.kt \
        app/src/test/java/com/example/plohoystream/camera/scene/SceneTest.kt
git commit -m "feat(camera): scene model for dual-camera compositor"
```

---

### Task 2: Display-transform + cover-crop math

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/camera/scene/DisplayTransform.kt`
- Test: `app/src/test/java/com/example/plohoystream/camera/scene/DisplayTransformTest.kt`

**Why split:** the rotation formula and crop ratios are where past attempts went wrong, so they live in pure Int/Float functions that unit tests pin down. The 4×4 matrix assembly uses `android.opengl.Matrix` (unavailable in unit tests) and is mechanical — verified on-device in later tasks.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/example/plohoystream/camera/scene/DisplayTransformTest.kt`:

```kotlin
package com.example.plohoystream.camera.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayTransformTest {
    private val eps = 1e-4f

    // BACK camera: rotation needed = (sensor - display + 360) % 360.
    @Test fun netRotation_back_portraitSensor_landscapeDisplay() {
        // back sensor 90°, display rotated 90° (landscape) -> already upright.
        assertEquals(0, DisplayTransform.netRotationDegrees(sensorDeg = 90, displayDeg = 90, isFront = false))
        // back sensor 90°, display 0° (portrait) -> rotate 90°.
        assertEquals(90, DisplayTransform.netRotationDegrees(sensorDeg = 90, displayDeg = 0, isFront = false))
    }

    // FRONT camera: rotation needed = (sensor + display) % 360 (mirror handled separately).
    @Test fun netRotation_front_addsDisplay() {
        assertEquals(0, DisplayTransform.netRotationDegrees(sensorDeg = 270, displayDeg = 90, isFront = true))
        assertEquals(180, DisplayTransform.netRotationDegrees(sensorDeg = 90, displayDeg = 90, isFront = true))
    }

    @Test fun netRotation_isAlwaysNormalizedTo0_359() {
        val r = DisplayTransform.netRotationDegrees(sensorDeg = 270, displayDeg = 270, isFront = false)
        assertEquals(0, r)
    }

    // coverCrop: aspects are width/height. The over-wide axis is cropped to COVER (no letterbox).
    @Test fun coverCrop_rectWiderThanContent_cropsHeight() {
        // rect 16:9 (1.778), content 1:1 -> keep full width, crop height.
        val (cx, cy) = DisplayTransform.coverCrop(contentAspect = 1f, rectAspect = 16f / 9f)
        assertEquals(1f, cx, eps)
        assertEquals(9f / 16f, cy, eps)
    }

    @Test fun coverCrop_rectTallerThanContent_cropsWidth() {
        // rect 1:1, content 16:9 -> keep full height, crop width.
        val (cx, cy) = DisplayTransform.coverCrop(contentAspect = 16f / 9f, rectAspect = 1f)
        assertEquals(9f / 16f, cx, eps)
        assertEquals(1f, cy, eps)
    }

    @Test fun coverCrop_equalAspect_isNoOp() {
        val (cx, cy) = DisplayTransform.coverCrop(contentAspect = 1.5f, rectAspect = 1.5f)
        assertEquals(1f, cx, eps)
        assertEquals(1f, cy, eps)
    }

    // displayedAspect: a 90/270 rotation swaps the source's width and height.
    @Test fun displayedAspect_swapsOnQuarterTurns() {
        assertEquals(640f / 360f, DisplayTransform.displayedAspect(640, 360, rotationDeg = 0), eps)
        assertEquals(360f / 640f, DisplayTransform.displayedAspect(640, 360, rotationDeg = 90), eps)
        assertEquals(360f / 640f, DisplayTransform.displayedAspect(640, 360, rotationDeg = 270), eps)
        assertEquals(640f / 360f, DisplayTransform.displayedAspect(640, 360, rotationDeg = 180), eps)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.scene.DisplayTransformTest"`
Expected: FAIL — `DisplayTransform` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/example/plohoystream/camera/scene/DisplayTransform.kt`:

```kotlin
package com.example.plohoystream.camera.scene

import android.opengl.Matrix

/**
 * Orientation + crop math for compositing a camera source whose frames arrive WITHOUT CameraX's
 * display-correcting [androidx.camera.core.SurfaceOutput] transform (the dual-mode secondary).
 *
 * [netRotationDegrees] and [coverCrop] are pure and unit-tested. [matrix] assembles the 4x4
 * texture-coordinate transform with [android.opengl.Matrix] (not available in unit tests) and is
 * verified on-device — it is the documented composition of those tested pieces.
 */
object DisplayTransform {

    /** Clockwise degrees to rotate the sensor image to be upright on the display.
     *  Back: (sensor - display + 360) % 360. Front: (sensor + display) % 360 (mirror separate). */
    fun netRotationDegrees(sensorDeg: Int, displayDeg: Int, isFront: Boolean): Int {
        val raw = if (isFront) sensorDeg + displayDeg else sensorDeg - displayDeg
        return ((raw % 360) + 360) % 360
    }

    /** Width/height the source presents after a [rotationDeg] quarter-turn (90/270 swap W and H). */
    fun displayedAspect(srcW: Int, srcH: Int, rotationDeg: Int): Float {
        val w = srcW.toFloat(); val h = srcH.toFloat()
        return if (rotationDeg == 90 || rotationDeg == 270) h / w else w / h
    }

    /** Texture-coord scale (cropX, cropY) about center to COVER a rect of [rectAspect] (w/h) with
     *  content of [contentAspect] (w/h): the over-wide axis is cropped (cover, not letterbox). */
    fun coverCrop(contentAspect: Float, rectAspect: Float): Pair<Float, Float> =
        if (rectAspect > contentAspect) 1f to (contentAspect / rectAspect)
        else (rectAspect / contentAspect) to 1f

    /**
     * 4x4 texture-coordinate matrix that rotates the sampled image upright ([netRotationDegrees]),
     * mirrors horizontally for the front camera, and applies the cover-crop scale — all about the
     * texture center (0.5, 0.5). Compose this with the raw `SurfaceTexture` transform (this matrix
     * on the LEFT) before uploading to `uTexMatrix`.
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
        if (isFront) Matrix.scaleM(m, 0, -1f, 1f, 1f)        // mirror X for the selfie camera
        Matrix.rotateM(m, 0, rotation.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(m, 0, -0.5f, -0.5f, 0f)
        return m
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.scene.DisplayTransformTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/scene/DisplayTransform.kt \
        app/src/test/java/com/example/plohoystream/camera/scene/DisplayTransformTest.kt
git commit -m "feat(camera): unit-tested orientation + cover-crop math for compositor"
```

---

### Task 3: GlRenderer — z-ordered layer renderer

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/camera/GlRenderer.kt`

Replace the bespoke `renderComposite` (lines ~335–405) with a generic layer renderer. `render()` (single full-frame) stays untouched.

- [ ] **Step 1: Add the `RenderLayer` holder and `renderScene` / `drawLayer`**

In `GlRenderer.kt`, DELETE the entire `fun renderComposite(...)` method (lines ~335–405) and add in its place:

```kotlin
    /**
     * One composited layer: an OES [textureId] sampled through [texTransform] (already including
     * orientation, mirror and cover-crop), drawn into the normalized rect [left,top,right,bottom]
     * (0..1, origin top-left). Z-order is the list order passed to [renderScene].
     */
    data class RenderLayer(
        val textureId: Int,
        val texTransform: FloatArray,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
    )

    /** Composite [layers] (in order) into [surface]. Each layer is positioned by its rect and
     *  textured by its transform; the base layer (full rect) covers the frame. Swaps once. */
    fun renderScene(timestampNs: Long, layers: List<RenderLayer>, surface: Surface) {
        checkInitialized(); checkGlThread()
        var outputSurface = getOutSurfaceOrThrow(surface)
        if (outputSurface === NO_OUTPUT_SURFACE) {
            val created = createOutputSurfaceInternal(surface) ?: return
            outputSurfaceMap[surface] = created
            outputSurface = created
        }
        makeCurrent(outputSurface.eglSurface)
        currentSurface = surface
        GLES20.glViewport(0, 0, outputSurface.width, outputSurface.height)
        for (layer in layers) drawLayer(layer)
        // Restore defaults for the next single-camera render()/frozen path.
        GLES20.glUniformMatrix4fv(transMatrixLoc, 1, false, identityMatrix, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, outputSurface.eglSurface, timestampNs)
        if (!EGL14.eglSwapBuffers(eglDisplay, outputSurface.eglSurface)) {
            removeOutputSurfaceInternal(surface, false)
        }
    }

    /** Place [layer]'s rect via uTransMatrix (NDC), bind its texture, draw the strip. */
    private fun drawLayer(layer: RenderLayer) {
        val cx = (layer.left + layer.right) * 0.5f
        val cy = (layer.top + layer.bottom) * 0.5f
        val sx = layer.right - layer.left
        val sy = layer.bottom - layer.top
        val trans = FloatArray(16)
        Matrix.setIdentityM(trans, 0)
        // Normalized rect (origin top-left) -> NDC: x in [-1,1], y flipped (top-left -> +y up).
        Matrix.translateM(trans, 0, 2f * cx - 1f, 1f - 2f * cy, 0f)
        Matrix.scaleM(trans, 0, sx, sy, 1f)
        GLES20.glUniformMatrix4fv(transMatrixLoc, 1, false, trans, 0)
        GLES20.glUniform1f(alphaScaleLoc, 1.0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, layer.textureId)
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, layer.texTransform, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }
```

Note: `RenderLayer` is a `data class` with a `FloatArray` field — that's fine here because instances are short-lived per-frame and never compared/hashed. Do not add `equals`/`hashCode`.

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (Compile fails here would be unresolved `renderComposite` references — they are removed in Task 4; if building Task 3 alone fails for that reason, proceed to Task 4 which removes the caller, then build.)

Because the only caller of `renderComposite` is `EgressSurfaceProcessor` (rewritten in Task 4), commit Tasks 3 and 4 together if the build cannot pass in isolation. Prefer: implement Step 1 here, then go straight to Task 4, then build + commit.

- [ ] **Step 3: Commit (with Task 4) — see Task 4 Step 6**

---

### Task 4: EgressSurfaceProcessor — scene plumbing

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/camera/EgressSurfaceProcessor.kt`

Replace the dual-mode fields and the composite branch with a `Scene` + secondary orientation, and build `RenderLayer`s.

- [ ] **Step 1: Replace the dual-state fields**

In `EgressSurfaceProcessor.kt`, REPLACE the block (lines ~39–55):

```kotlin
    @Volatile private var dualMode = false
    private var primaryTexture: SurfaceTexture? = null
    private var secondaryTexture: SurfaceTexture? = null
    private val secondaryTransform = FloatArray(16)
    @Volatile private var secondarySrcW = 0
    @Volatile private var secondarySrcH = 0
    @Volatile private var pipLayout = com.example.plohoystream.camera.PipLayout()

    fun setDualMode(on: Boolean) {
        executeSafely({
            dualMode = on
            if (!on) { primaryTexture = null; secondaryTexture = null }
        })
    }
```

with:

```kotlin
    // Scene compositor state. The scene (single source of truth for preview + stream) and the
    // secondary camera's orientation are pushed in from the controller; read on the GL thread.
    @Volatile private var scene: com.example.plohoystream.camera.scene.Scene =
        com.example.plohoystream.camera.scene.Scene.SINGLE
    private var primaryTexture: SurfaceTexture? = null
    private var secondaryTexture: SurfaceTexture? = null
    private val secondaryRawTransform = FloatArray(16)
    @Volatile private var secondarySrcW = 0
    @Volatile private var secondarySrcH = 0
    @Volatile private var secondarySensorDeg = 0
    @Volatile private var secondaryIsFront = true
    @Volatile private var displayDeg = 0

    /** Replace the composited scene (live; safe from any thread). >1 layer engages the compositor. */
    fun setScene(newScene: com.example.plohoystream.camera.scene.Scene) {
        executeSafely({
            scene = newScene
            if (!newScene.isDual) { primaryTexture = null; secondaryTexture = null }
        })
    }

    /** Orientation inputs for the SECONDARY (PiP) source's derived transform. Set at dual bind. */
    fun setDualConfig(sensorDeg: Int, isFront: Boolean, displayDegrees: Int) {
        executeSafely({
            secondarySensorDeg = sensorDeg
            secondaryIsFront = isFront
            displayDeg = displayDegrees
        })
    }

    private val dualMode: Boolean get() = scene.isDual
```

- [ ] **Step 2: Update `onInputSurface` and `provideSecondarySurface` field names**

In `onInputSurface` (~line 146) the line `if (dualMode) primaryTexture = surfaceTexture` stays as-is (now reads the derived `dualMode`).

In `provideSecondarySurface` (~line 186), rename the matrix field: change
`s.getTransformMatrix(secondaryTransform)` to `s.getTransformMatrix(secondaryRawTransform)`.

- [ ] **Step 3: Replace the dual composite branch in `onFrameAvailable`**

REPLACE the dual branch (lines ~275–292):

```kotlin
        if (dualMode && primaryTexture != null && secondaryTexture != null) {
            val r = com.example.plohoystream.camera.PipLayout.pipRect(pipLayout)
            for ((output, surface) in outputSurfaces) {
                output.updateTransformMatrix(surfaceOutputTransform, textureTransform)
                System.arraycopy(surfaceOutputTransform, 0, encoderTransform, 0, 16)
                hasEncoderTransform = true
                try {
                    renderer.renderComposite(timestampNs, surfaceOutputTransform, secondaryTransform, r.left, r.top, r.right, r.bottom, secondarySrcW, secondarySrcH, surface)
                } catch (e: RuntimeException) { Log.e(TAG, "composite preview render failed", e) }
            }
            encoderSurface?.let { surface ->
                val transform = if (hasEncoderTransform) encoderTransform else textureTransform
                try {
                    renderer.renderComposite(timestampNs, transform, secondaryTransform, r.left, r.top, r.right, r.bottom, secondarySrcW, secondarySrcH, surface)
                } catch (e: RuntimeException) { Log.e(TAG, "composite encoder render failed", e) }
            }
            return
        }
```

with:

```kotlin
        if (dualMode && primaryTexture != null && secondaryTexture != null) {
            for ((output, surface) in outputSurfaces) {
                output.updateTransformMatrix(surfaceOutputTransform, textureTransform)
                System.arraycopy(surfaceOutputTransform, 0, encoderTransform, 0, 16)
                hasEncoderTransform = true
                try {
                    renderer.renderScene(timestampNs, buildLayers(surfaceOutputTransform, surface), surface)
                } catch (e: RuntimeException) { Log.e(TAG, "composite preview render failed", e) }
            }
            encoderSurface?.let { surface ->
                val primaryTransform = if (hasEncoderTransform) encoderTransform else textureTransform
                try {
                    renderer.renderScene(timestampNs, buildLayers(primaryTransform, surface), surface)
                } catch (e: RuntimeException) { Log.e(TAG, "composite encoder render failed", e) }
            }
            return
        }
```

- [ ] **Step 4: Add `buildLayers` (maps the Scene to GL RenderLayers)**

Add this private method to `EgressSurfaceProcessor` (e.g. just after `onFrameAvailable`):

```kotlin
    /**
     * Map the current [scene] to GL layers for [surface]. PRIMARY uses CameraX's display-correct
     * [primaryTransform] (full-frame, no extra crop). SECONDARY uses the derived orientation+mirror
     * transform (see [com.example.plohoystream.camera.scene.DisplayTransform]) composed with its raw
     * SurfaceTexture transform, plus a cover-crop for its PiP rect. Sized via the EGL surface so the
     * cover-crop matches the actual output aspect (the renderer queries the same dimensions).
     */
    private fun buildLayers(
        primaryTransform: FloatArray,
        surface: Surface,
    ): List<GlRenderer.RenderLayer> {
        val dt = com.example.plohoystream.camera.scene.DisplayTransform
        val outAspect = renderer.outputAspect(surface)        // width/height of the output surface
        return scene.ordered().mapNotNull { layer ->
            when (layer.source) {
                com.example.plohoystream.camera.scene.SourceId.PRIMARY ->
                    GlRenderer.RenderLayer(
                        renderer.textureName, primaryTransform,
                        layer.rect.left, layer.rect.top, layer.rect.right, layer.rect.bottom,
                    )
                com.example.plohoystream.camera.scene.SourceId.SECONDARY -> {
                    val rot = dt.netRotationDegrees(secondarySensorDeg, displayDeg, secondaryIsFront)
                    val contentAspect = dt.displayedAspect(secondarySrcW, secondarySrcH, rot)
                    val rectAspect = (layer.rect.width * outAspect) / layer.rect.height
                    val (cropX, cropY) = dt.coverCrop(contentAspect, rectAspect)
                    val orient = dt.matrix(secondarySensorDeg, displayDeg, secondaryIsFront, cropX, cropY)
                    val tex = FloatArray(16)
                    android.opengl.Matrix.multiplyMM(tex, 0, orient, 0, secondaryRawTransform, 0)
                    GlRenderer.RenderLayer(
                        renderer.textureName2, tex,
                        layer.rect.left, layer.rect.top, layer.rect.right, layer.rect.bottom,
                    )
                }
            }
        }
    }
```

- [ ] **Step 5: Add `outputAspect` to GlRenderer**

In `GlRenderer.kt`, add this public method (near `render`):

```kotlin
    /** Width/height of [surface]'s output (1f if it has no live EGL surface yet). */
    fun outputAspect(surface: Surface): Float {
        val out = outputSurfaceMap[surface]
        return if (out == null || out === NO_OUTPUT_SURFACE || out.height == 0) 1f
        else out.width.toFloat() / out.height.toFloat()
    }
```

- [ ] **Step 6: Build, then commit Tasks 3 + 4**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (No `renderComposite` / `pipLayout` / `setDualMode` references remain. `CameraXController` still calls `setDualMode` — that line is fixed in Task 5; if building now, temporarily expect a CameraXController error and proceed to Task 5 before the final build. To keep commits green, do Task 5 Step 1–2 before this build.)

```bash
git add app/src/main/java/com/example/plohoystream/camera/GlRenderer.kt \
        app/src/main/java/com/example/plohoystream/camera/EgressSurfaceProcessor.kt
git commit -m "feat(camera): scene-driven compositor in renderer + processor"
```

---

### Task 5: CameraXController — secondary orientation, scene, swap

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/camera/CameraXController.kt`

- [ ] **Step 1: Replace `setDualMode` usage in single `start()`**

In `start()` (~line 157) REPLACE:

```kotlin
            processor.setDualMode(false)   // single-camera path: clear any prior dual state
```

with:

```kotlin
            processor.setScene(com.example.plohoystream.camera.scene.Scene.SINGLE)  // clear dual state
```

- [ ] **Step 2: Rewrite `startDual` to take a Scene + push secondary orientation**

REPLACE the whole `fun startDual(...)` (lines ~188–214) with:

```kotlin
    /**
     * Bind FRONT + BACK concurrently, both feeding the shared [processor]. [primaryFacing] takes the
     * high-res slot (the big view); the other is the PiP. [scene] is the composited layout. We read
     * the SECONDARY camera's sensor orientation here so its PiP transform is derived correctly
     * (see [com.example.plohoystream.camera.scene.DisplayTransform]). Calls [onFailed] on a rejected
     * concurrent bind (caller reverts to single mode).
     */
    fun startDual(
        primaryFacing: Facing,
        scene: com.example.plohoystream.camera.scene.Scene,
        targets: List<Surface>,
        onFailed: () -> Unit,
    ) {
        mainExecutor.execute {
            val provider = provider ?: run { onFailed(); return@execute }
            lastTargets = targets
            val encoder = targets.firstOrNull { it !== previewSurface }
            processor.setEncoderSurface(encoder)
            val secondaryFacing = if (primaryFacing == Facing.FRONT) Facing.BACK else Facing.FRONT
            val mgr = runCatching {
                appContext.getSystemService(android.content.Context.CAMERA_SERVICE)
                    as android.hardware.camera2.CameraManager
            }.getOrNull()
            val secId = mgr?.let {
                defaultCameraId(it, if (secondaryFacing == Facing.FRONT)
                    CameraMetadata.LENS_FACING_FRONT else CameraMetadata.LENS_FACING_BACK)
            }
            val sensorDeg = secId?.let {
                runCatching {
                    mgr.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_ORIENTATION)
                }.getOrNull()
            } ?: 0
            processor.setDualConfig(sensorDeg, secondaryFacing == Facing.FRONT, displayDegrees())
            processor.setScene(scene)
            runCatching { provider.unbindAll() }
            awaitCamerasFreeThenBind {
                val primaryCfg = singleConfig(primaryFacing, Size(1280, 720), primary = true)
                val secondaryCfg = singleConfig(secondaryFacing, Size(640, 360), primary = false)
                registry.currentState = Lifecycle.State.STARTED
                try {
                    provider.bindToLifecycle(listOf(primaryCfg, secondaryCfg))
                    Log.i(TAG, "bound dual: primary=$primaryFacing secondarySensor=$sensorDeg")
                } catch (e: Exception) {
                    Log.e(TAG, "concurrent bind failed; caller falls back to single", e)
                    processor.setScene(com.example.plohoystream.camera.scene.Scene.SINGLE)
                    onFailed()
                }
            }
        }
    }

    /** Push a new composited scene to the GL pipeline (live PiP move/resize). */
    fun setScene(scene: com.example.plohoystream.camera.scene.Scene) = processor.setScene(scene)
```

- [ ] **Step 3: Add `displayDegrees()` next to `displayRotation()`**

After `displayRotation()` (~line 303) add:

```kotlin
    /** Current display rotation in degrees (0/90/180/270), for the secondary's derived transform. */
    private fun displayDegrees(): Int = when (displayRotation()) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. (`Viewfinder` still calls the old `startDual(primaryFacing, targets, onFailed)` 3-arg form — fixed in Task 6; if building now, expect a Viewfinder arg error and proceed to Task 6 before the final build.)

- [ ] **Step 5: Commit (with Task 6) — see Task 6 Step 5**

---

### Task 6: Viewfinder — hoist Scene + wire swap

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt`

- [ ] **Step 1: Replace the `dualOn` flag with a hoisted `Scene`**

REPLACE (line ~95):

```kotlin
    var dualOn by remember { mutableStateOf(false) }
```

with:

```kotlin
    var scene by remember { mutableStateOf(com.example.plohoystream.camera.scene.Scene.SINGLE) }
    val dualOn = scene.isDual
```

- [ ] **Step 2: Update the binding LaunchedEffect**

REPLACE the dual branch inside `LaunchedEffect(config, surface, encoderSurface, activeHdr, dualOn)` (lines ~167–172):

```kotlin
            if (dualOn && cx != null) {
                cx.startDual(primaryFacing = facing, targets = targets, onFailed = { dualOn = false })
            } else {
                controller.start(c, targets, hdr = activeHdr)
                controller.setZoom(zoom)
            }
```

with:

```kotlin
            if (dualOn && cx != null) {
                cx.startDual(
                    primaryFacing = facing, scene = scene, targets = targets,
                    onFailed = { scene = com.example.plohoystream.camera.scene.Scene.SINGLE },
                )
            } else {
                controller.start(c, targets, hdr = activeHdr)
                controller.setZoom(zoom)
            }
```

- [ ] **Step 3: Push live scene edits + fix the ON_RESUME dual rebind**

Add this effect right after that binding `LaunchedEffect` block (after line ~174):

```kotlin
    // Live PiP edits (drag/resize): push the scene to the GL pipeline without rebinding cameras.
    LaunchedEffect(scene) {
        val cx = controller as? CameraXController ?: return@LaunchedEffect
        if (scene.isDual) cx.setScene(scene)
    }
```

Then in the `DisposableEffect` ON_RESUME branch REPLACE (lines ~198–199):

```kotlin
                        if (currentDualOn && cx != null) {
                            cx.startDual(primaryFacing = currentFacing, targets = targets, onFailed = {})
```

with:

```kotlin
                        if (currentDualOn && cx != null) {
                            cx.startDual(primaryFacing = currentFacing, scene = currentScene, targets = targets, onFailed = {})
```

And update the `rememberUpdatedState` mirror: REPLACE (line ~185):

```kotlin
    val currentDualOn by rememberUpdatedState(dualOn)
```

with:

```kotlin
    val currentDualOn by rememberUpdatedState(dualOn)
    val currentScene by rememberUpdatedState(scene)
```

- [ ] **Step 4: Wire the dual toggle + PiP overlay**

REPLACE the toggle wiring (lines ~375–378):

```kotlin
                            dualOn = dualOn,
                            // Only flip the flag; the binding LaunchedEffect (keyed on dualOn) does the
                            // ...
                            onToggleDual = { dualOn = !dualOn },
```

with:

```kotlin
                            dualOn = dualOn,
                            // Toggling swaps the scene (single <-> dual); the dualOn-keyed binder rebinds.
                            onToggleDual = {
                                scene = if (scene.isDual) com.example.plohoystream.camera.scene.Scene.SINGLE
                                        else com.example.plohoystream.camera.scene.Scene.dual()
                            },
```

Then add the `PipOverlay` inside the preview `Box` (right after the control rail composable, so it sits above the preview). Place this where the preview content is composed — locate the `Box` that wraps the `CameraPreview`/control rail and add as its last child:

```kotlin
                if (dualOn) {
                    com.example.plohoystream.ui.viewfinder.PipOverlay(
                        scene = scene,
                        onSceneChange = { scene = it },
                        onSwap = {
                            // Swap the big view: blur over the rebind, then flip which camera is primary.
                            // The dualOn-keyed binder rebinds with the new facing (high-res slot follows).
                            controller.beginCameraTransition()
                            facing = if (facing == Facing.BACK) Facing.FRONT else Facing.BACK
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
```

(If `facing` is a `val`/derived state rather than a `var`, mirror the existing camera-flip button's mechanism for changing facing instead of assigning directly — use the same handler the flip button calls.)

- [ ] **Step 5: Build, then commit Tasks 5 + 6**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/java/com/example/plohoystream/camera/CameraXController.kt \
        app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt
git commit -m "feat(camera): hoist scene state, wire dual toggle + swap rebind"
```

---

### Task 7: PipOverlay — drag / resize / swap gestures

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/viewfinder/PipOverlay.kt`

- [ ] **Step 1: Create the overlay composable**

Create `app/src/main/java/com/example/plohoystream/ui/viewfinder/PipOverlay.kt`:

```kotlin
package com.example.plohoystream.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.plohoystream.camera.scene.PipSize
import com.example.plohoystream.camera.scene.Scene
import com.example.plohoystream.camera.scene.SceneEdits
import com.example.plohoystream.camera.scene.SourceId
import kotlin.math.roundToInt

/**
 * Interactive PiP layer over the preview. Drag to move (corner-snap on release); drag the bottom-
 * right handle to resize; tap the swap button to exchange the big view and the PiP camera. Every
 * gesture mutates the [Scene] via [onSceneChange] (live on preview AND stream); [onSwap] triggers
 * the camera rebind. Coordinates are normalized; we convert with the measured overlay pixel size.
 */
@Composable
fun PipOverlay(
    scene: Scene,
    onSceneChange: (Scene) -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pip = scene.layer(SourceId.SECONDARY)?.rect ?: return
    var boxW by remember { mutableStateOf(1) }
    var boxH by remember { mutableStateOf(1) }

    Box(modifier = modifier.onSizeChanged { boxW = it.width.coerceAtLeast(1); boxH = it.height.coerceAtLeast(1) }) {
        Box(
            modifier = Modifier
                .offset { IntOffset((pip.left * boxW).roundToInt(), (pip.top * boxH).roundToInt()) }
                .size((pip.width * boxW).dp / androidx.compose.ui.platform.LocalDensity.current.density,
                      (pip.height * boxH).dp / androidx.compose.ui.platform.LocalDensity.current.density)
                .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                .pointerInput(boxW, boxH) {
                    detectDragGestures(
                        onDrag = { change, drag ->
                            change.consume()
                            val cur = scene.layer(SourceId.SECONDARY)?.rect ?: return@detectDragGestures
                            val cx = cur.centerX + drag.x / boxW
                            val cy = cur.centerY + drag.y / boxH
                            onSceneChange(scene.updateLayer(SourceId.SECONDARY) { SceneEdits.moveTo(it, cx, cy) })
                        },
                        onDragEnd = {
                            onSceneChange(scene.updateLayer(SourceId.SECONDARY) {
                                SceneEdits.snapToCorner(it, Scene.PIP_MARGIN)
                            })
                        },
                    )
                },
        ) {
            // Swap button, top-left of the PiP.
            IconButton(
                onClick = onSwap,
                modifier = Modifier.align(Alignment.TopStart).size(36.dp)
                    .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
            ) {
                Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap cameras", tint = Color.White)
            }
            // Resize handle, bottom-right of the PiP.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .background(Color.White.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .pointerInput(boxW, boxH) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val cur = scene.layer(SourceId.SECONDARY)?.rect ?: return@detectDragGestures
                            // Grow/shrink by the larger drag axis, normalized to frame width.
                            val deltaWf = (drag.x / boxW)
                            onSceneChange(scene.updateLayer(SourceId.SECONDARY) {
                                SceneEdits.resizeKeepingCenter(it, it.width + deltaWf)
                            })
                        }
                    },
            )
        }
    }
}
```

Note on sizing: if the `.size(... .dp / density)` expression is awkward in this codebase, use a `with(LocalDensity.current) { (pip.width * boxW).toDp() }` block instead — match whatever density-conversion idiom the existing viewfinder composables use. The behavior (PiP box sized to the normalized rect in measured pixels) must be preserved.

- [ ] **Step 2: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Resolve any density/`toDp` idiom mismatch per the note above.

- [ ] **Step 3: Run the full unit-test suite (no regressions)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (existing suite + `SceneTest` + `DisplayTransformTest`; `PipLayoutTest` is deleted in Task 8).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/ui/viewfinder/PipOverlay.kt
git commit -m "feat(ui): draggable/resizable/swappable PiP overlay"
```

---

### Task 8: Delete the obsolete PipLayout + on-device verification

**Files:**
- Delete: `app/src/main/java/com/example/plohoystream/camera/PipLayout.kt`
- Delete: `app/src/test/java/com/example/plohoystream/camera/PipLayoutTest.kt`

- [ ] **Step 1: Confirm there are no remaining references**

Run: `grep -rn "PipLayout\|PipSize\|PipRect" app/src/main/java | grep -v "/scene/"`
Expected: no output. (`PipSize` now lives in `scene/Scene.kt`; any remaining `com.example.plohoystream.camera.PipLayout` / `PipRect` reference must be removed first.)

- [ ] **Step 2: Delete the files**

```bash
git rm app/src/main/java/com/example/plohoystream/camera/PipLayout.kt \
       app/src/test/java/com/example/plohoystream/camera/PipLayoutTest.kt
```

- [ ] **Step 3: Build + full test suite**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all unit tests PASS.

- [ ] **Step 4: Install + on-device verification**

```bash
./gradlew :app:installDebug
adb -s SM02E4060314107 shell monkey -p com.example.plohoystream -c android.intent.category.LAUNCHER 1
```

Verify (screenshot with `adb -s SM02E4060314107 exec-out screencap -p > /tmp/shot.png` between steps):
1. Single-cam back and front still look correct (no regression).
2. Toggle dual ON → front PiP appears **upright and undistorted** in a landscape box (the original bug).
3. **Drag** the PiP — it follows the finger and **snaps to the nearest corner** on release.
4. **Resize** via the bottom-right handle — the box grows/shrinks, content stays undistorted (cover-crop).
5. **Swap** — brief freeze-blur, then the big view and PiP cameras exchange; the big view is full-resolution (not upscaled 320×240).
6. **On-stream check:** with SRT/OBS connected, confirm drag/resize/swap are reflected in the encoded output, not just the local preview.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(camera): remove obsolete PipLayout (folded into scene model)"
```

---

## Final review

After all tasks, dispatch a final code-reviewer over the whole branch (`feat/dual-camera-capture`), then use `superpowers:finishing-a-development-branch`.

## Notes / intentional deviations from the spec

- **`Scene.swapSources()` is omitted.** `SourceId` is a render slot (PRIMARY = hi-res/big, SECONDARY = lo-res/PiP), so the big view is always hi-res. Swap is therefore a camera **rebind** (flip `facing` → the existing dual binder rebinds, under `beginCameraTransition()`'s blur), not a scene mutation. This is DRY-er and respects the concurrent-resolution cap. Behavior matches the spec's swap requirement.
- **`displayTransform` is split** into pure `netRotationDegrees`/`coverCrop`/`displayedAspect` (unit-tested) + a mechanical `matrix(...)` (android.opengl.Matrix, on-device verified), because `android.opengl.Matrix` is unavailable in plain unit tests.
- **Single-cam keeps `render()`** (zero regression); the compositor (`renderScene`) runs only when `scene.isDual`.
```
