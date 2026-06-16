# Dual-Camera Capture + Compositor (Plan 2 of 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture FRONT + BACK simultaneously (on devices that support it) and composite them on-device into the single existing encoded stream — main camera full-frame, the other as a fixed picture-in-picture inset — behind a capability-gated toggle. Draggable PiP control comes in Plan 3.

**Architecture:** Phone-composites (one stream out), per the spec. CameraX `bindToConcurrentCamera` binds two `Preview` use cases (different resolutions so the inputs are distinguishable), both attached to the SAME `EgressSurfaceProcessor` so one GL context receives two `SurfaceTexture`s. `GlRenderer` gains a second external texture and a `renderComposite()` that draws the primary full-frame then the secondary into a PiP rectangle (reusing the shader's existing `uTransMatrix`). Preview + encoder surfaces are unchanged — they just receive a composited frame. Single-camera paths are untouched; dual is purely additive and gated by `ConcurrentCameraProbe`/`dualSupported` (Plan 1).

**Tech Stack:** Kotlin, CameraX (`androidx.camera` — concurrent camera API), GLES2/EGL14 (existing `GlRenderer`), Jetpack Compose, JUnit. Device: Seeker `SM02E4060314107` (probe verdict: front+back SUPPORTED). GL/camera code is verified on-device (not unit-testable), matching the existing pipeline.

This is Plan 2 of 3 for Feature 2, following `2026-06-16-dual-camera-foundation.md` (Plan 1: `PipLayout`, `ConcurrentCameraProbe`, `dualSupported` — all merged/available). Plan 3 adds the draggable/size/swap PiP UI on top of `PipLayout`.

## Runtime capability gating (non-negotiable requirement)

The app is NOT Seeker-specific. Dual mode is offered ONLY when `ConcurrentCameraProbe.supportsFrontBack(...)` is true for the device at runtime (Plan 1's `dualSupported`). On unsupported devices the toggle is never shown. Every task below preserves this: the single-camera pipeline is the default and is never regressed.

## Key technical decisions (from reading the pipeline)

- **Distinguishing the two inputs:** bind the primary `Preview` at 1280×720 and the secondary at 640×360. `EgressSurfaceProcessor.onInputSurface` routes by `request.resolution` (larger area = primary). A smaller secondary is also cheaper and fine for a PiP.
- **Compositing seam:** `GlRenderer` already has `uTransMatrix` (position transform, currently identity) and `uAlphaScale`. The PiP is one extra textured-quad draw with `uTransMatrix` = scale+translate into the inset rect — no new shader/program.
- **Pacing:** the PRIMARY camera's `onFrameAvailable` drives the composite (using the secondary's latest texture). The secondary's `onFrameAvailable` only refreshes its texture. PTS comes from the primary.
- **Downstream unchanged:** preview Surface + MediaCodec encoder Surface still receive one composited frame each; SRT/RTMP/record paths are untouched.

---

### Task 1: Concurrent-bind spike (de-risk before building the compositor)

Prove that `bindToConcurrentCamera` accepts our `CameraEffect`/`SurfaceProcessor` and delivers two inputs, on-device. This gates the rest of the plan. We add a `startDual()` entry that binds both cameras, both wired to the existing processor, and (for this task only) render just the primary so we can confirm two inputs arrive. No GL changes yet.

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/camera/EgressSurfaceProcessor.kt` (count + log inputs by resolution; no behavior change to single-cam)
- Modify: `app/src/main/java/com/example/plohoystream/camera/CameraXController.kt` (add `startDual`)

- [ ] **Step 1: Add input logging to EgressSurfaceProcessor.onInputSurface**

In `onInputSurface` (the `executeSafely { inputSurfaceCount++ ... }` block), right after `inputSurfaceCount++`, add a log so the spike shows how many inputs arrive and their sizes:

```kotlin
            inputSurfaceCount++
            Log.i(TAG, "onInputSurface #$inputSurfaceCount res=${request.resolution.width}x${request.resolution.height}")
```

- [ ] **Step 2: Add a `startDual` bind path to CameraXController**

Add this method to `CameraXController` (it does NOT touch the existing `start`/`bindIfReady`). It binds front+back concurrently, both Previews attached to the shared `processor` via `EgressEffect`, primary at 1280×720 and secondary at 640×360. Imports needed at top of file: `androidx.camera.core.ConcurrentCamera.SingleCameraConfig`, `androidx.camera.core.UseCaseGroup`, `androidx.camera.core.resolutionselector.ResolutionStrategy`, `android.util.Size` (already imported).

```kotlin
    /**
     * Bind FRONT + BACK concurrently, both feeding the shared [processor]. Primary is [primaryFacing]
     * at 1280x720; the other is the PiP source at 640x360 (distinguishable by resolution in the
     * processor). Targets are the same preview/encoder surfaces as single mode. Falls back to nothing
     * (caller should revert to single) if the device rejects the concurrent bind.
     */
    fun startDual(primaryFacing: Facing, targets: List<Surface>, onFailed: () -> Unit) {
        mainExecutor.execute {
            val provider = provider ?: run { onFailed(); return@execute }
            lastTargets = targets
            val encoder = targets.firstOrNull { it !== previewSurface }
            processor.setEncoderSurface(encoder)
            processor.setDualMode(true)
            runCatching { provider.unbindAll() }
            val secondaryFacing = if (primaryFacing == Facing.FRONT) Facing.BACK else Facing.FRONT
            val primaryCfg = singleConfig(primaryFacing, Size(1280, 720), primary = true)
            val secondaryCfg = singleConfig(secondaryFacing, Size(640, 360), primary = false)
            registry.currentState = Lifecycle.State.STARTED
            try {
                provider.bindToConcurrentCamera(listOf(primaryCfg, secondaryCfg))
                Log.i(TAG, "bound dual: primary=$primaryFacing")
            } catch (e: Exception) {
                Log.e(TAG, "concurrent bind failed; caller falls back to single", e)
                processor.setDualMode(false)
                onFailed()
            }
        }
    }

    private fun singleConfig(facing: Facing, size: Size, primary: Boolean): androidx.camera.core.ConcurrentCamera.SingleCameraConfig {
        val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
            .setResolutionStrategy(
                androidx.camera.core.resolutionselector.ResolutionStrategy(
                    size, androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                ),
            ).build()
        val preview = Preview.Builder().setResolutionSelector(resolutionSelector).build().apply {
            // Only the PRIMARY preview routes to the on-screen surface; the secondary is GL-only.
            if (primary) setSurfaceProvider(mainExecutor, UiSurfaceProvider())
        }
        val effect = EgressEffect(processor, mainExecutor)
        val useCaseGroup = androidx.camera.core.UseCaseGroup.Builder()
            .addUseCase(preview)
            .addEffect(effect)
            .build()
        val selector = if (facing == Facing.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        return androidx.camera.core.ConcurrentCamera.SingleCameraConfig.Builder()
            .setCameraSelector(selector)
            .setUseCaseGroup(useCaseGroup)
            .setLifecycleOwner(this)
            .build()
    }
```

Also add a temporary no-op `setDualMode` to the processor so this compiles (real impl in Task 3):

In `EgressSurfaceProcessor`, add:
```kotlin
    @Volatile private var dualMode = false
    fun setDualMode(on: Boolean) { dualMode = on }
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If `bindToConcurrentCamera`, `ConcurrentCamera.SingleCameraConfig`, or `UseCaseGroup.Builder().addEffect(...)` do not resolve, STOP and report the exact unresolved symbol (the camera-core version may differ) rather than inventing API.

- [ ] **Step 4: Temporary spike trigger + on-device check**

To exercise `startDual` without UI yet, temporarily call it from the existing probe effect in `Viewfinder.kt`. In the `LaunchedEffect(Unit)` probe block (the `onSuccess` branch), after setting `dualSupported = supported`, add:

```kotlin
            if (supported) {
                (controller as? com.example.plohoystream.camera.CameraXController)?.startDual(
                    primaryFacing = com.example.plohoystream.camera.Facing.BACK,
                    targets = listOfNotNull(surface),
                    onFailed = { android.util.Log.w("Viewfinder", "dual bind failed; staying single") },
                )
            }
```

(`surface` is the preview Surface var already in Viewfinder scope.) Build, install, run, and read logs:

```bash
adb -s SM02E4060314107 logcat -c
./gradlew :app:installDebug
adb -s SM02E4060314107 shell monkey -p com.example.plohoystream -c android.intent.category.LAUNCHER 1
sleep 8
adb -s SM02E4060314107 logcat -d | grep -E "EgressSurfaceProcessor: onInputSurface|CameraXController: bound dual|concurrent bind failed"
```

Expected (success): two `onInputSurface` lines with resolutions `1280x720` and `640x360`, plus `bound dual: primary=BACK`. The preview should show the BACK camera (primary).

- [ ] **Step 5: Record the spike result + revert the temporary trigger**

Append a "## Spike result" section to this plan with the observed log lines and whether the concurrent bind + dual inputs worked. Then REMOVE the temporary trigger added in Step 4 (the real toggle is Task 4). Keep `startDual`/`setDualMode`/the logging.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/EgressSurfaceProcessor.kt \
        app/src/main/java/com/example/plohoystream/camera/CameraXController.kt \
        docs/superpowers/plans/2026-06-16-dual-camera-capture-compositor.md
git commit -m "feat(camera): concurrent front+back bind spike (startDual) — de-risk dual capture

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

**GATE:** If the spike shows the concurrent bind rejects the effect or doesn't deliver two inputs, STOP and discuss with the user — the contingency is an `ImageReader`-based secondary capture, which reshapes Tasks 2–3. Do not proceed to Task 2 on a failed spike.

---

### Task 2: GlRenderer — second external texture + renderComposite()

Add a second external OES texture for the secondary camera and a `renderComposite()` that draws primary full-frame then secondary into a PiP rect. Reuses the existing program/shader (`uTransMatrix`, `uAlphaScale`). Single-camera `render()` is untouched. GL is verified on-device.

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/camera/GlRenderer.kt`

- [ ] **Step 1: Add the second texture + accessor**

Add a field beside `externalTextureId`:
```kotlin
    private var externalTextureId2 = -1
```
In `init()`, after `activateExternalTexture(externalTextureId)`, create the second texture:
```kotlin
            externalTextureId2 = createExternalTexture()
```
Add an accessor beside `textureName`:
```kotlin
    /** External OES texture id for the SECONDARY (PiP) camera. Valid after [init]. */
    val textureName2: Int
        get() { checkInitialized(); checkGlThread(); return externalTextureId2 }
```
In `releaseInternal()`, after the existing texture cleanup, add:
```kotlin
        if (externalTextureId2 != -1) { GLES20.glDeleteTextures(1, intArrayOf(externalTextureId2), 0); externalTextureId2 = -1 }
```

- [ ] **Step 2: Add renderComposite()**

Add this method (after `render`). It composites primary + secondary to one output Surface. `pipRect` is the inset in normalized [0..1] frame coords (left,top,right,bottom, origin top-left) — exactly `PipLayout.pipRect` output. It maps that to a GL `uTransMatrix` (NDC scale+translate). Alpha-blends so the inset draws on top.

```kotlin
    /**
     * Composite two camera textures to [surface]: [primaryTransform]/[secondaryTransform] are the
     * respective SurfaceTexture transforms; [pipLeft..pipBottom] is the inset rectangle in normalized
     * frame coords (0..1, origin top-left). Primary fills the frame; secondary draws into the inset.
     */
    fun renderComposite(
        timestampNs: Long,
        primaryTransform: FloatArray,
        secondaryTransform: FloatArray,
        pipLeft: Float, pipTop: Float, pipRight: Float, pipBottom: Float,
        surface: Surface,
    ) {
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

        // 1) Primary, full-frame.
        GLES20.glUniformMatrix4fv(transMatrixLoc, 1, false, identityMatrix, 0)
        GLES20.glUniform1f(alphaScaleLoc, 1.0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, primaryTransform, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // 2) Secondary into the inset rect. Convert [0..1] top-left rect to an NDC scale+translate.
        //    NDC x = 2*cx-1, y = 1-2*cy (flip). Quad spans [-1,1] so scale = rect extent.
        val cx = (pipLeft + pipRight) * 0.5f
        val cy = (pipTop + pipBottom) * 0.5f
        val sx = (pipRight - pipLeft)
        val sy = (pipBottom - pipTop)
        val pipMatrix = FloatArray(16)
        Matrix.setIdentityM(pipMatrix, 0)
        Matrix.translateM(pipMatrix, 0, 2f * cx - 1f, 1f - 2f * cy, 0f)
        Matrix.scaleM(pipMatrix, 0, sx, sy, 1f)
        GLES20.glUniformMatrix4fv(transMatrixLoc, 1, false, pipMatrix, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId2)
        GLES20.glUniformMatrix4fv(texMatrixLoc, 1, false, secondaryTransform, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Restore defaults for the next single-camera render()/frozen path.
        GLES20.glUniformMatrix4fv(transMatrixLoc, 1, false, identityMatrix, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)

        EGLExt.eglPresentationTimeANDROID(eglDisplay, outputSurface.eglSurface, timestampNs)
        if (!EGL14.eglSwapBuffers(eglDisplay, outputSurface.eglSurface)) {
            removeOutputSurfaceInternal(surface, false)
        }
    }
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/GlRenderer.kt
git commit -m "feat(camera): GlRenderer second texture + renderComposite() for PiP

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: EgressSurfaceProcessor — two inputs + composite drive

Manage two input `SurfaceTexture`s keyed by resolution (primary = larger), bind each to the matching GL texture, and in dual mode drive the composite from the primary's frames using the secondary's latest texture + a fixed default `PipLayout`. Single-camera path unchanged.

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/camera/EgressSurfaceProcessor.kt`

- [ ] **Step 1: Replace the no-op dual fields with real two-input state**

Replace the temporary `dualMode`/`setDualMode` (from Task 1) with:

```kotlin
    // Dual-camera (PiP) state. The secondary SurfaceTexture is bound to the renderer's 2nd texture;
    // the primary drives the composite using the secondary's latest frame. Layout is fixed here
    // (default); Plan 3 makes it adjustable.
    @Volatile private var dualMode = false
    private var primaryTexture: SurfaceTexture? = null
    private var secondaryTexture: SurfaceTexture? = null
    private val secondaryTransform = FloatArray(16)
    @Volatile private var pipLayout = com.example.plohoystream.camera.PipLayout()

    fun setDualMode(on: Boolean) {
        executeSafely({
            dualMode = on
            if (!on) { primaryTexture = null; secondaryTexture = null }
        })
    }
```

- [ ] **Step 2: Route the two inputs by resolution in onInputSurface**

In `onInputSurface`, the `SurfaceTexture` is currently always created on `renderer.textureName`. Make it choose the texture by whether dual mode + which input this is (larger = primary → `textureName`; smaller = secondary → `textureName2`). Replace the surfaceTexture creation lines:

```kotlin
            inputSurfaceCount++
            Log.i(TAG, "onInputSurface #$inputSurfaceCount res=${request.resolution.width}x${request.resolution.height} dual=$dualMode")
            val isSecondary = dualMode && secondaryTexture == null && primaryTexture != null
            val texName = if (isSecondary) renderer.textureName2 else renderer.textureName
            val surfaceTexture = SurfaceTexture(texName)
            surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            if (dualMode) {
                if (isSecondary) secondaryTexture = surfaceTexture else primaryTexture = surfaceTexture
            }
```

Note: in dual mode CameraX binds primary first (we bind primary then secondary in `startDual`), so the FIRST input is primary, the SECOND is secondary. Keep the resolution in the log to confirm.

In the `provideSurface` release callback, clear the matching reference:
```kotlin
            request.provideSurface(surface, glExecutor) {
                surfaceTexture.setOnFrameAvailableListener(null)
                surfaceTexture.release()
                surface.release()
                if (surfaceTexture === primaryTexture) primaryTexture = null
                if (surfaceTexture === secondaryTexture) secondaryTexture = null
                inputSurfaceCount--
                checkReadyToRelease()
            }
```

- [ ] **Step 3: Drive the composite from the primary frame in onFrameAvailable**

In `onFrameAvailable`, the secondary texture's callback should only refresh its own texture; the primary drives output. After the guarded `updateTexImage()`/`getTransformMatrix(textureTransform)` block, insert:

```kotlin
        // Secondary (PiP) camera: just refresh its texture; the primary frame drives the composite.
        if (dualMode && surfaceTexture === secondaryTexture) {
            surfaceTexture.getTransformMatrix(secondaryTransform)
            return
        }
```

Wait — `updateTexImage` already ran above for whichever ST fired. For the secondary we still want its transform cached. Adjust: the guarded block already did `updateTexImage()` + `getTransformMatrix(textureTransform)` for the firing ST. For the secondary, copy that into `secondaryTransform` and return:

```kotlin
        if (dualMode && surfaceTexture === secondaryTexture) {
            System.arraycopy(textureTransform, 0, secondaryTransform, 0, 16)
            return
        }
```

Then, in dual mode, the primary renders a COMPOSITE to each output instead of a plain render. Replace the per-output render loop + encoder render with a dual branch. After computing `timestampNs` and (for the primary) the transition handling, add at the start of the output section:

```kotlin
        if (dualMode && primaryTexture != null && secondaryTexture != null) {
            val r = com.example.plohoystream.camera.PipLayout.pipRect(pipLayout)
            for ((output, surface) in outputSurfaces) {
                output.updateTransformMatrix(surfaceOutputTransform, textureTransform)
                System.arraycopy(surfaceOutputTransform, 0, encoderTransform, 0, 16)
                hasEncoderTransform = true
                try {
                    renderer.renderComposite(timestampNs, surfaceOutputTransform, secondaryTransform, r.left, r.top, r.right, r.bottom, surface)
                } catch (e: RuntimeException) { Log.e(TAG, "composite preview render failed", e) }
            }
            encoderSurface?.let { surface ->
                val transform = if (hasEncoderTransform) encoderTransform else textureTransform
                try {
                    renderer.renderComposite(timestampNs, transform, secondaryTransform, r.left, r.top, r.right, r.bottom, surface)
                } catch (e: RuntimeException) { Log.e(TAG, "composite encoder render failed", e) }
            }
            return
        }
```

This sits BEFORE the existing single-camera `for ((output, surface) in outputSurfaces)` loop, which remains the non-dual path.

- [ ] **Step 4: Compile + commit**

Run: `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.
```bash
git add app/src/main/java/com/example/plohoystream/camera/EgressSurfaceProcessor.kt
git commit -m "feat(camera): two-input compositor drive in EgressSurfaceProcessor (fixed PiP)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Capability-gated dual toggle (UI) + start/stop wiring

Surface a dual-camera toggle in the control rail, shown ONLY when `dualSupported`. Toggling calls `startDual`/`start` (revert to single) and flips the processor mode. Fixed default PiP (draggable is Plan 3).

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt`
- Modify: `app/src/main/java/com/example/plohoystream/ui/viewfinder/ControlRail.kt`

- [ ] **Step 1: ControlRail — add the toggle param + button**

Add params to `ControlRail` (before `modifier`): `dualSupported: Boolean`, `dualOn: Boolean`, `onToggleDual: () -> Unit`. In the bottom actions `Row` (beside the flip button), add — only when supported:

```kotlin
            if (dualSupported) {
                IconButton(onClick = onToggleDual, modifier = Modifier.size(40.dp).clip(CircleShape)) {
                    Icon(
                        Icons.Filled.Cameraswitch,
                        contentDescription = if (dualOn) "Disable dual camera" else "Enable dual camera",
                        tint = if (dualOn) OnSurfaceWhite else OnSurfaceMuted,
                    )
                }
            }
```

Update the four `@Preview` calls to pass the three new args (e.g. `false, false, {}`). (Use a distinct icon than the flip if available; `Cameraswitch` is acceptable for now.)

- [ ] **Step 2: Viewfinder — hold dual state, wire toggle, gate on dualSupported**

`dualSupported` already exists (Plan 1 probe). Add:
```kotlin
    var dualOn by remember { mutableStateOf(false) }
```
At the `ControlRail(...)` call, pass:
```kotlin
                            dualSupported = dualSupported,
                            dualOn = dualOn,
                            onToggleDual = {
                                val c = controller as? CameraXController ?: return@ControlRail-callsite
                                if (dualOn) {
                                    dualOn = false
                                    config?.let { c.start(it, listOfNotNull(surface, encoderSurface), hdr = activeHdr) }
                                } else {
                                    dualOn = true
                                    c.startDual(
                                        primaryFacing = facing,
                                        targets = listOfNotNull(surface, encoderSurface),
                                        onFailed = { dualOn = false },
                                    )
                                }
                            },
```
(Write the lambda as a normal lambda — the `return@` placeholder above is illustrative; use an `if (c != null)` guard instead.) Remove the temporary spike trigger from Task 1 Step 4 if still present.

- [ ] **Step 3: Compile + full test suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest` → BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt \
        app/src/main/java/com/example/plohoystream/ui/viewfinder/ControlRail.kt
git commit -m "feat(camera): capability-gated dual-camera toggle (fixed PiP)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: On-device verification

**Files:** none (manual)

- [ ] **Step 1: Install + verify**

```bash
./gradlew :app:installDebug
adb -s SM02E4060314107 shell monkey -p com.example.plohoystream -c android.intent.category.LAUNCHER 1
```
Checks:
1. The dual-camera toggle appears in the rail (Seeker supports it).
2. Tapping it shows BACK full-frame with FRONT as a PiP inset in the **preview**.
3. Going live: the **stream** (and recording) show the same composite (confirm in OBS/file).
4. Tapping again returns to single-camera cleanly (no crash, preview resumes).
5. Background/rotate while dual is active does not crash (the Plan-merge teardown fix covers updateTexImage; confirm).

- [ ] **Step 2: Capture a screenshot of the composited preview**

```bash
adb -s SM02E4060314107 exec-out screencap -p > /tmp/dual.png
```
Confirm the PiP inset is correctly placed and oriented (not mirrored/upside down — front camera transform). If orientation is off, the fix is in the `secondaryTransform` handling (front-camera mirroring) — note it for a follow-up.

- [ ] **Step 3: Record result**

Note pass/fail per check. If all pass, dual capture + fixed-PiP compositing works; proceed to Plan 3 (draggable/size/swap PiP using `PipLayout`). Finish the branch via superpowers:finishing-a-development-branch.

## Risks / contingencies

- **Concurrent bind rejects effects (Task 1 gate):** contingency is an `ImageReader`-based secondary feeding a texture upload — larger change, discuss with user first.
- **720p cap:** concurrent mode limits per-camera resolution; primary is 1280×720. If the device's single-camera stream was higher, dual mode is a deliberate quality trade-off (acceptable, gated behind the toggle).
- **Front-camera mirroring/orientation** in the PiP: handled via `secondaryTransform`; may need a mirror flip — caught in Task 5 Step 2.
- **HDR/60fps** are not requested in dual mode (concurrent mode generally disallows them); dual is SDR/30 — consistent with the SDR-only `GlRenderer`.
