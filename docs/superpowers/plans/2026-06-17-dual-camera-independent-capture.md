# Dual-Camera Independent Capture — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the dual-camera PiP as two *independent* Camera2 sessions feeding the shared GL compositor, so a back-lens/zoom change never disturbs the front PiP where the hardware certifies the pair — eliminating the blink and the impossible-combo thrash, phone-agnostically.

**Architecture:** Hybrid. Single-camera mode stays on CameraX (unchanged: HDR/60fps/exposure/lens). Dual mode bypasses CameraX: a new `DualCameraSession` opens back + front as separate `CameraDevice`s, each rendering into one of the compositor's two input `SurfaceTexture`s; the `EgressSurfaceProcessor` gains a "standalone" mode that renders directly to the preview + encoder `Surface`s. A pure, unit-tested `ConcurrentCameraCapabilities` (built from `getConcurrentCameraIds()`) classifies each lens chip as REAL / ZOOM / UNAVAILABLE so behavior is identical logic on every phone.

**Tech Stack:** Kotlin, Camera2 (`CameraManager`/`CameraDevice`/`CameraCaptureSession`), GLES2/EGL14 (existing `GlRenderer`), Jetpack Compose (Material3), JUnit4 (pure tests only — no Robolectric; `android.*` is unavailable in unit tests).

## Global Constraints

- No `android.*` classes in unit tests (pure JUnit only — matches `SceneTest`/`DisplayTransformTest`). Android/GL/Camera2 code is verified on-device via adb + logcat.
- Dual capture resolution capped at the concurrent guarantee: primary ≈ `1280×720`, secondary ≈ `640×360` (per-camera ≤ 720p).
- Single-camera mode (CameraX) must stay behavior-identical — do not modify its bind path beyond what's needed to hand off to/from dual.
- Capability logic must be runtime-derived from `getConcurrentCameraIds()` + characteristics. No device-specific branches or hardcoded ids.
- All GL work + texture/EGL mutation happens on the existing single GL `HandlerThread`; mode switches serialize on it.
- Keep the already-landed keepers from this branch: rounded PiP resize grip (`PipOverlay`), and the drag-perf fix (`setScene` direct volatile write).
- Device under test: Seeker `SM02E4060314107`. Certified combo: `{back-main #0 + front #1}` only.
- Commit message trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

## File Structure

- **Create** `app/src/main/java/com/example/plohoystream/camera/ConcurrentCameraCapabilities.kt` — pure capability model + `DualClass`. Unit-tested.
- **Create** `app/src/test/java/com/example/plohoystream/camera/ConcurrentCameraCapabilitiesTest.kt` — pure tests.
- **Create** `app/src/main/java/com/example/plohoystream/camera/CameraCapabilityReader.kt` — thin Camera2 adapter that builds `ConcurrentCameraCapabilities` from the system. Device-verified.
- **Create** `app/src/main/java/com/example/plohoystream/camera/DualCameraSession.kt` — owns the two independent Camera2 devices/sessions. Device-verified.
- **Modify** `app/src/main/java/com/example/plohoystream/camera/EgressSurfaceProcessor.kt` — add standalone-dual mode (own two input `SurfaceTexture`s, render to direct output `Surface`s, compute the PRIMARY transform via `DisplayTransform` too).
- **Modify** `app/src/main/java/com/example/plohoystream/camera/scene/DisplayTransform.kt` — none expected (already general); extend tests only if a gap surfaces.
- **Modify** `app/src/main/java/com/example/plohoystream/camera/CameraXController.kt` — route dual through `DualCameraSession`; remove the superseded `ConcurrentCamera` dual path (`startDual`/`bindDualInternal`/dual fields); expose capabilities + dual controls.
- **Modify** `app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt` — dual toggle via the new controller API; lifecycle (single⇄dual, background/resume); tap-to-exit-dual.
- **Modify** `app/src/main/java/com/example/plohoystream/ui/viewfinder/ControlRail.kt` (+ the lens-chip composable it uses) — render chips by `DualClass`; tap-to-exit-dual confirm.
- **Modify** `app/src/test/java/com/example/plohoystream/camera/scene/DisplayTransformTest.kt` — add primary-source cases if needed.

---

## Task 1: `ConcurrentCameraCapabilities` (pure capability model)

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/camera/ConcurrentCameraCapabilities.kt`
- Test: `app/src/test/java/com/example/plohoystream/camera/ConcurrentCameraCapabilitiesTest.kt`

**Interfaces:**
- Consumes: `Facing` (`com.example.plohoystream.camera.Facing`).
- Produces:
  - `enum class DualClass { REAL, ZOOM, UNAVAILABLE }`
  - `data class BackLens(val id: String, val ratio: Float, val minZoom: Float, val maxZoom: Float)`
  - `class ConcurrentCameraCapabilities(val backLenses: List<BackLens>, val frontIds: List<String>, val concurrentSets: Set<Set<String>>)` with:
    - `fun supportsDual(): Boolean`
    - `fun isConcurrent(backId: String, frontId: String): Boolean`
    - `fun dualClass(chip: BackLens, openBack: BackLens, frontId: String): DualClass`

**Classification rule (the core logic):**
- `REAL` if `isConcurrent(chip.id, frontId)` — the chip's sensor can run with the front for real.
- else `ZOOM` if `chip.ratio in openBack.ratio*openBack.minZoom .. openBack.ratio*openBack.maxZoom` — reachable by zooming the currently-open back sensor (you can only zoom *in*, never wider).
- else `UNAVAILABLE`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.plohoystream.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrentCameraCapabilitiesTest {
    private val ultrawide = BackLens(id = "2", ratio = 0.8f, minZoom = 1f, maxZoom = 2f)
    private val main = BackLens(id = "0", ratio = 1.0f, minZoom = 1f, maxZoom = 4f)
    private val tele = BackLens(id = "3", ratio = 1.8f, minZoom = 1f, maxZoom = 4f)
    private val lenses = listOf(ultrawide, main, tele)

    // Seeker: only {main #0 + front #1} is concurrent.
    private val seeker = ConcurrentCameraCapabilities(
        backLenses = lenses, frontIds = listOf("1"),
        concurrentSets = setOf(setOf("0", "1")),
    )

    @Test fun seeker_supportsDual_onlyMainPlusFront() {
        assertTrue(seeker.supportsDual())
        assertTrue(seeker.isConcurrent("0", "1"))
        assertFalse(seeker.isConcurrent("2", "1"))
        assertFalse(seeker.isConcurrent("3", "1"))
    }

    @Test fun seeker_dualClass_mainOpen() {
        // Open back = main (#0). main=REAL (it's the concurrent one), tele=ZOOM (1.8 in 1..4),
        // ultrawide=UNAVAILABLE (0.8 < 1.0, can't zoom wider).
        assertEquals(DualClass.REAL, seeker.dualClass(main, openBack = main, frontId = "1"))
        assertEquals(DualClass.ZOOM, seeker.dualClass(tele, openBack = main, frontId = "1"))
        assertEquals(DualClass.UNAVAILABLE, seeker.dualClass(ultrawide, openBack = main, frontId = "1"))
    }

    @Test fun richPhone_ultrawideConcurrent_isReal() {
        val rich = ConcurrentCameraCapabilities(
            backLenses = lenses, frontIds = listOf("1"),
            concurrentSets = setOf(setOf("0", "1"), setOf("2", "1")),
        )
        assertEquals(DualClass.REAL, rich.dualClass(ultrawide, openBack = main, frontId = "1"))
    }

    @Test fun noConcurrency_dualUnsupported() {
        val none = ConcurrentCameraCapabilities(lenses, listOf("1"), emptySet())
        assertFalse(none.supportsDual())
    }

    @Test fun teleOpen_mainBecomesZoomDown_isUnavailableNotZoom() {
        // If tele (#3, 1.8x) were the open back, main (1.0x) is WIDER → not reachable by zoom-in.
        assertEquals(DualClass.UNAVAILABLE, seeker.dualClass(main, openBack = tele, frontId = "1"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.ConcurrentCameraCapabilitiesTest"`
Expected: FAIL — unresolved reference `ConcurrentCameraCapabilities` / `BackLens` / `DualClass`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.plohoystream.camera

/** How a lens chip can be honored while dual (PiP) is on, on THIS device's hardware. */
enum class DualClass { REAL, ZOOM, UNAVAILABLE }

/** A selectable back sensor: its Camera2 [id], focal [ratio] vs the 1x main, and zoom bounds. */
data class BackLens(val id: String, val ratio: Float, val minZoom: Float, val maxZoom: Float)

/**
 * Pure, phone-agnostic model of what dual-camera framing is achievable, derived from the device's
 * certified concurrent-camera combinations. Built by [CameraCapabilityReader]; consumed by the UI
 * (chip rendering) and [DualCameraSession] (which switch is legal). No Android types — unit-tested.
 */
class ConcurrentCameraCapabilities(
    val backLenses: List<BackLens>,
    val frontIds: List<String>,
    val concurrentSets: Set<Set<String>>,
) {
    /** True if any certified combo contains a front id together with some back lens id. */
    fun supportsDual(): Boolean =
        concurrentSets.any { set -> frontIds.any { it in set } && backLenses.any { it.id in set } }

    /** True if [backId] and [frontId] appear together in some certified concurrent combo. */
    fun isConcurrent(backId: String, frontId: String): Boolean =
        concurrentSets.any { it.containsAll(listOf(backId, frontId)) }

    /**
     * Classify [chip] for dual when [openBack] is the currently-open back sensor and [frontId] is the
     * PiP front. REAL = its own sensor runs with the front; ZOOM = reachable by zooming the open
     * sensor (zoom only narrows FOV); UNAVAILABLE = wider than the open sensor and not concurrent.
     */
    fun dualClass(chip: BackLens, openBack: BackLens, frontId: String): DualClass = when {
        isConcurrent(chip.id, frontId) -> DualClass.REAL
        chip.ratio in (openBack.ratio * openBack.minZoom)..(openBack.ratio * openBack.maxZoom) -> DualClass.ZOOM
        else -> DualClass.UNAVAILABLE
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.ConcurrentCameraCapabilitiesTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/ConcurrentCameraCapabilities.kt \
        app/src/test/java/com/example/plohoystream/camera/ConcurrentCameraCapabilitiesTest.kt
git commit -m "feat(camera): pure ConcurrentCameraCapabilities + DualClass model

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `CameraCapabilityReader` (Camera2 → capability model)

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/camera/CameraCapabilityReader.kt`
- Modify (temporary verification only): `app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt`

**Interfaces:**
- Consumes: `ConcurrentCameraCapabilities`, `BackLens` (Task 1); `android.hardware.camera2.CameraManager`.
- Produces: `object CameraCapabilityReader { fun read(context: Context): ConcurrentCameraCapabilities }`.

This is Android (no unit test). Build the model from the system, then verify the values on-device.

- [ ] **Step 1: Implement the reader**

```kotlin
package com.example.plohoystream.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Builds the pure [ConcurrentCameraCapabilities] from the platform: the certified concurrent-camera
 * combinations ([CameraManager.getConcurrentCameraIds]) plus per-camera facing / focal / zoom. Back
 * lens [BackLens.ratio] is focal-relative to the 1x main (the back camera with the median focal),
 * approximating the chips' 0.8/1/1.8x. Runs blocking system calls — call off the main thread.
 */
object CameraCapabilityReader {
    private const val TAG = "CameraCapabilityReader"

    fun read(context: Context): ConcurrentCameraCapabilities {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        data class Cam(val id: String, val facing: Int?, val focal: Float?, val minZoom: Float, val maxZoom: Float)
        val cams = mgr.cameraIdList.map { id ->
            val c = mgr.getCameraCharacteristics(id)
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            Cam(id, c.get(CameraCharacteristics.LENS_FACING), focal, zr?.lower ?: 1f, zr?.upper ?: 1f)
        }
        val backs = cams.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK && it.focal != null }
        val fronts = cams.filter { it.facing == CameraCharacteristics.LENS_FACING_FRONT }.map { it.id }
        // 1x reference = the median back focal (the "main"); ratio = focal / reference.
        val reference = backs.map { it.focal!! }.sorted().getOrNull(backs.size / 2) ?: 1f
        val backLenses = backs.map { BackLens(it.id, ratio = it.focal!! / reference, minZoom = it.minZoom, maxZoom = it.maxZoom) }
        val concurrentSets = runCatching { mgr.concurrentCameraIds }.getOrDefault(emptySet())
        val caps = ConcurrentCameraCapabilities(backLenses, fronts, concurrentSets)
        Log.i(TAG, "concurrentSets=$concurrentSets backLenses=$backLenses fronts=$fronts supportsDual=${caps.supportsDual()}")
        return caps
    }
}
```

- [ ] **Step 2: Add a one-shot verification call** in `Viewfinder`'s existing dual-probe `LaunchedEffect(Unit)` (the block that calls `ConcurrentCameraProbe`), inside the `withContext(Dispatchers.IO)`:

```kotlin
                com.example.plohoystream.camera.CameraCapabilityReader.read(context)
```

- [ ] **Step 3: Build, install, verify on device**

```bash
./gradlew :app:installDebug -q
adb shell am force-stop com.example.plohoystream
adb logcat -c
adb shell am start -n com.example.plohoystream/.MainActivity
sleep 8
adb logcat -d | grep "CameraCapabilityReader"
```

Expected on the Seeker (id-level may vary slightly): `concurrentSets=[[0, 1]]`, `backLenses` contains ids `0/2/3` with ratios ≈ `1.0 / 0.42 / 1.35` (focal-relative; exact values device-specific), `fronts=[1]`, `supportsDual=true`.

NOTE: confirm the ratio mapping puts main≈1.0 and orders the lenses sensibly; if the median pick is off for this device, adjust `reference` selection (e.g. choose the back focal closest to the CameraX-reported 1x main id) and re-verify.

- [ ] **Step 4: Remove the temporary verification call** added in Step 2 (leave the reader in place).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/CameraCapabilityReader.kt
git commit -m "feat(camera): CameraCapabilityReader builds capabilities from getConcurrentCameraIds()

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `EgressSurfaceProcessor` standalone-dual mode

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/camera/EgressSurfaceProcessor.kt`

**Interfaces:**
- Consumes: `GlRenderer` (`textureName`, `textureName2`, `registerOutputSurface`, `unregisterOutputSurface`, `renderScene`, `outputAspect`); `DisplayTransform`; `Scene`.
- Produces (new public API on `EgressSurfaceProcessor`):
  - `data class DualInputs(val primary: Surface, val secondary: Surface)`
  - `fun startStandaloneDual(preview: Surface?, encoder: Surface?, primarySensorDeg: Int, primaryIsFront: Boolean, secondarySensorDeg: Int, secondaryIsFront: Boolean, displayDeg: Int, primarySize: Size, secondarySize: Size, scene: Scene): DualInputs`
  - `fun setStandalonePreview(surface: Surface?)`
  - `fun setStandaloneEncoder(surface: Surface?)`
  - `fun stopStandalone()`

**Design notes for the implementer:**
- Add `@Volatile private var standalone = false`. When true, `onInputSurface`/`onOutputSurface` (the CameraX entry points) are inert (return `willNotProvideSurface()` / `close()`), because in dual nothing flows through CameraX.
- `startStandaloneDual` runs on the GL thread (use the existing `glExecuteSafelyBlocking`). It: creates `primaryTexture`/`secondaryTexture` (`SurfaceTexture(renderer.textureName)` / `...textureName2`) with `setDefaultBufferSize(primarySize/secondarySize)`; stores `primarySrcW/H` + `secondarySrcW/H`; registers `preview` and `encoder` (non-null) as direct outputs via `renderer.registerOutputSurface(...)` into a new `standaloneOutputs: LinkedHashSet<Surface>`; sets the source-orientation fields; sets `scene`; sets `standalone = true`; sets each input texture's `onFrameAvailable` listener (primary → `onPrimaryFrame`, secondary → `onSecondaryFrame`); returns `DualInputs(Surface(primaryTexture), Surface(secondaryTexture))`.
- `onPrimaryFrame`: `updateTexImage()` + `getTransformMatrix(primaryRawTransform)`; then composite to **every** surface in `standaloneOutputs` via `renderer.renderScene(ts, buildLayersStandalone(surface), surface)`. Guard with try/catch like the existing frame path.
- `onSecondaryFrame`: `updateTexImage()` + `getTransformMatrix(secondaryRawTransform)` only (the primary frame drives the composite), exactly like today's `provideSecondarySurface` listener.
- `buildLayersStandalone(surface)`: like the current `buildLayers`, but the **PRIMARY** layer is computed the same way as the secondary — `contentAspect = primarySrcW/primarySrcH`, `rectAspect` from the layer rect × output aspect, `coverCrop`, `orient = DisplayTransform.matrix(primarySensorDeg, displayDeg, primaryIsFront, cropX, cropY)`, `tex = orient ∘ primaryRawTransform`, full-frame rect, no corner radius, `mirror = primaryIsFront`. The SECONDARY layer is identical to today (corner radius `PIP_CORNER_RADIUS`, `mirror = secondaryIsFront`). Reuse `onSecondaryAspect` for the PiP-box sizing.
- `setStandalonePreview(surface)`: on the GL thread, unregister the old preview Surface from `standaloneOutputs`/renderer and register the new one (null = drop preview, encoder-only). Keep the encoder registration independent (`setStandaloneEncoder`).
- `stopStandalone`: on the GL thread, unregister all `standaloneOutputs`, release both input textures + their `Surface`s, clear listeners, `standalone = false`.
- Add fields: `private val primaryRawTransform = FloatArray(16)`, `@Volatile primarySrcW/H`, `primarySensorDeg`, `primaryIsFront`, plus a `standaloneOutputs` set. Keep the existing single-mode fields untouched.

- [ ] **Step 1: Implement standalone fields + the inert guards in `onInputSurface`/`onOutputSurface`**

Add at the top of `onInputSurface`'s and `onOutputSurface`'s bodies (after the release check):

```kotlin
        if (standalone) { /* dual is Camera2-driven; CameraX path is inactive */
            // onInputSurface:  request.willNotProvideSurface(); return
            // onOutputSurface: output.close(); return
        }
```

(Implement the appropriate one in each method.)

- [ ] **Step 2: Implement `startStandaloneDual` / `setStandalonePreview` / `setStandaloneEncoder` / `stopStandalone` + `onPrimaryFrame` / `onSecondaryFrame` / `buildLayersStandalone`**

Implement per the design notes above. Key composite body (mirrors the existing dual branch in `onFrameAvailable`):

```kotlin
    private fun onPrimaryFrame(st: SurfaceTexture) {
        if (isReleaseRequested.get() || !standalone) return
        try {
            st.updateTexImage(); st.getTransformMatrix(primaryRawTransform)
        } catch (e: RuntimeException) { Log.w(TAG, "standalone primary updateTexImage failed", e); return }
        val ts = st.timestamp
        for (surface in standaloneOutputs) {
            try { renderer.renderScene(ts, buildLayersStandalone(surface), surface) }
            catch (e: RuntimeException) { Log.e(TAG, "standalone composite failed", e) }
        }
    }
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:compileDebugKotlin -q`
Expected: BUILD SUCCESSFUL (this task is exercised on-device in Task 4/5; nothing calls the new API yet).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/EgressSurfaceProcessor.kt
git commit -m "feat(camera): standalone-dual mode in EgressSurfaceProcessor (Camera2-fed)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `DualCameraSession` — open both devices and stream

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/camera/DualCameraSession.kt`

**Interfaces:**
- Consumes: `EgressSurfaceProcessor` (Task 3 API), `ConcurrentCameraCapabilities` (Task 1), `Facing`, `android.hardware.camera2.*`.
- Produces:
  - `class DualCameraSession(context, processor: EgressSurfaceProcessor, caps: ConcurrentCameraCapabilities)`
  - `fun start(primaryFacing: Facing, backId: String, frontId: String, preview: Surface?, encoder: Surface?, displayDeg: Int, scene: Scene, onFailed: () -> Unit)`
  - `fun stop()`
  - (Task 5 adds `switchBack`, `setZoom`, `swapPrimary`, `setPreview`.)

**Design notes:**
- Own two `CameraDevice`s (`backDevice`, `frontDevice`) + two `CameraCaptureSession`s, each on a dedicated background `Handler` (or reuse one camera `HandlerThread`).
- `start`: read `SENSOR_ORIENTATION` for the back id and the front id; call `processor.startStandaloneDual(...)` to get `DualInputs`; open the back `CameraDevice` (`mgr.openCamera(backId, ...)`) and the front `CameraDevice` (`mgr.openCamera(frontId, ...)`); for each, `createCaptureSession` targeting its input `Surface` from `DualInputs`; build a repeating `PREVIEW` request per device with its target. Which physical id is primary vs secondary in the scene follows `primaryFacing` (back-primary → back=primary texture).
- The primary texture must be the one whose facing is `primaryFacing`. `processor.startStandaloneDual` takes `primarySensorDeg/primaryIsFront` + `secondarySensorDeg/secondaryIsFront` accordingly; route the back device to `DualInputs.primary` if back is primary, else to `.secondary`.
- On any `onError`/`onDisconnected` during open → `stop()` + `onFailed()` (caller reverts to single).
- Concurrent-open ordering: open both; if the device rejects the second open (`CameraAccessException`/`onError` MAX_CAMERAS_IN_USE) despite certification → `onFailed()`.
- Use `CONTROL_AE_MODE_ON` default; no manual exposure in dual v1 (matches today).

- [ ] **Step 1: Implement `DualCameraSession.start` + `stop`** per the notes. Include explicit log markers:

```kotlin
Log.i(TAG, "dual start primary=$primaryFacing back=$backId front=$frontId")
// in each device's StateCallback.onOpened:
Log.i(TAG, "opened device id=${device.id}")
// onError:
Log.e(TAG, "device id=$id error=$error"); stop(); onFailed()
```

- [ ] **Step 2: Wire `CameraXController` to start/stop it (minimal, temporary path for verification)**

In `CameraXController`, add a `DualCameraSession` field (lazy, built once `caps` are available) and a temporary `fun startDual2(primaryFacing, preview, encoder, scene, onFailed)` that picks `backId` = the current main back id, `frontId` = default front id, and calls `dualSession.start(...)`. (This replaces the old `startDual` in Task 6; for now it's an additive path used only to verify.) Have the Viewfinder dual toggle call `startDual2` instead of `startDual` behind a temporary switch, OR drive it via a debug log. Keep it minimal — the goal is to see both devices stream.

- [ ] **Step 3: Build, install, verify both cameras stream in dual**

```bash
./gradlew :app:installDebug -q
adb shell am force-stop com.example.plohoystream
adb logcat -c
adb shell am start -n com.example.plohoystream/.MainActivity
# Manually: toggle dual ON in the app.
sleep 4
adb logcat -d | grep -E "DualCameraSession|EgressSurfaceProcessor" | tail -30
```

Expected: `dual start ...`, `opened device id=0`, `opened device id=1`, and the PiP composite visible on the phone (back full-frame + front PiP), no crash, no `MAX_CAMERAS_IN_USE`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/DualCameraSession.kt \
        app/src/main/java/com/example/plohoystream/camera/CameraXController.kt \
        app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt
git commit -m "feat(camera): DualCameraSession opens back+front as independent Camera2 devices

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Independent back switch, zoom, swap (the blink-free operations)

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/camera/DualCameraSession.kt`

**Interfaces:**
- Produces:
  - `fun switchBack(newBackId: String)` — close/reopen ONLY the back device + session; front untouched.
  - `fun setZoom(ratio: Float)` — set `CONTROL_ZOOM_RATIO` on the back session's repeating request (clamped to the back lens range).
  - `fun swapPrimary(primaryFacing: Facing)` — relabel which open source is primary/secondary in the scene; no device reopen.
  - `fun setPreview(surface: Surface?)` — forward to `processor.setStandalonePreview`.

**Design notes:**
- `switchBack`: guard `caps.isConcurrent(newBackId, frontId)` (no-op + log if false — callers only pass REAL chips). Close `backDevice` + back session; the back input `SurfaceTexture` keeps its last GL frame (so the PiP/back source holds its last frame). Re-`openCamera(newBackId)`, recreate the back session targeting the SAME `DualInputs` surface, push the new back `SENSOR_ORIENTATION` to the processor (`primarySensorDeg` or `secondarySensorDeg` depending on slot). Front device/session are never touched. Log `switchBack from=$old to=$newBackId (front kept alive)`.
- `setZoom`: update the back request builder's `CONTROL_ZOOM_RATIO` and `setRepeatingRequest`. No texture/device change.
- `swapPrimary`: update the processor's source-orientation mapping (primary↔secondary) and the scene's source assignment; both textures already stream, so it's instant.

- [ ] **Step 1: Implement `switchBack`, `setZoom`, `swapPrimary`, `setPreview`** per the notes, with the log markers above.

- [ ] **Step 2: Build, install, verify the front stays live across a back switch**

```bash
./gradlew :app:installDebug -q
adb shell am force-stop com.example.plohoystream; adb logcat -c
adb shell am start -n com.example.plohoystream/.MainActivity
# Manually: dual ON; trigger a REAL back switch (on Seeker that's main→main / zoom; on a multi-back
# phone, switch sensors). Then a swap (tap PiP). Then pinch-zoom.
sleep 6
adb logcat -d | grep -E "DualCameraSession" | tail -30
```

Expected: `switchBack ... (front kept alive)` with NO `opened device id=1` (front) reopen between switches; the front PiP visibly never blinks; swap is instant; zoom is smooth. On Seeker, confirm `1×↔1.8×` is driven by `setZoom` (no `switchBack` device reopen).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/DualCameraSession.kt
git commit -m "feat(camera): blink-free back switch/zoom/swap in DualCameraSession (front stays live)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Controller + Viewfinder integration; retire the CameraX dual path

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/camera/CameraXController.kt`
- Modify: `app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt`

**Interfaces:**
- Consumes: `DualCameraSession` (Tasks 4–5), `ConcurrentCameraCapabilities`/`CameraCapabilityReader` (Tasks 1–2).
- Produces on `CameraXController`:
  - `val capabilities: StateFlow<ConcurrentCameraCapabilities?>` (read once async at init).
  - `fun enterDual(primaryFacing: Facing, scene: Scene, preview: Surface?, encoder: Surface?, onFailed: () -> Unit)`
  - `fun exitDual()` (back to single via the existing CameraX bind).
  - `fun dualSelectChip(chip: BackLens)` — REAL → `switchBack`; ZOOM → `setZoom(chip.ratio)`; UNAVAILABLE → invoke an `onExitDualRequested(chip)` callback the UI handles.
  - `fun dualSwap()`, `fun dualSetZoom(ratio)`, `fun setScene(scene)` (keep; routes to processor).
- Keep the single-mode `start`/`stop`/`selectLens`/`setZoom`/exposure exactly as-is.

**Design notes:**
- **Remove** the superseded CameraX concurrent dual path added earlier this branch: `startDual`, `bindDualInternal`, `matchesFacing`, the `dualPrimaryFacing/dualScene/dualOnFailed` fields, the `physicalId` param on `singleConfig` (revert `singleConfig` to its pre-this-round selector), and the `concurrent`-specific bits. Single mode returns to its original shape.
- The Viewfinder binding `LaunchedEffect(... dualOn)` now calls `controller.enterDual(...)` / `controller.exitDual()` instead of `startDual` / single `start`. The ON_RESUME and DisposableEffect paths mirror this (dual → `enterDual` if `currentDualOn`; preview-gone → `controller.setPreview(null)` style via the dual session when dual).
- Keep the existing `secondaryPipAspect` StateFlow fed by `processor.onSecondaryAspect`, and the `LaunchedEffect(pipAspect, dualOn, config)` PiP-box sizing.
- Lifecycle serialization: `enterDual` must wait for CameraX's `unbindAll()` async close before the Camera2 opens — reuse the `awaitCamerasFreeThenBind` gate (move/extend it to cover the dual-session open).

- [ ] **Step 1: Remove the CameraX concurrent dual path** (`startDual`/`bindDualInternal`/`matchesFacing`/dual fields/`singleConfig` `physicalId`); revert `singleConfig`'s selector to `if (facing==FRONT) DEFAULT_FRONT else DEFAULT_BACK`.

- [ ] **Step 2: Add `capabilities` StateFlow** (read via `CameraCapabilityReader.read` on an IO thread in `init`), and the `enterDual`/`exitDual`/`dualSelectChip`/`dualSwap`/`dualSetZoom` methods delegating to `DualCameraSession`.

- [ ] **Step 3: Rewire `Viewfinder`** dual toggle, swap (PiP tap → `dualSwap`), zoom (pinch → `dualSetZoom` when dual; `setZoom` when single), and lifecycle (ON_RESUME / DisposableEffect) to the new API. Remove any temporary `startDual2` switch from Task 4.

- [ ] **Step 4: Build + run the full unit suite**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest -q`
Expected: compiles; `ConcurrentCameraCapabilitiesTest` + `SceneTest` + `DisplayTransformTest` pass. (Pre-existing `CameraStreamEngineTest` `Log`-not-mocked failures are unrelated — see Task 9 note.)

- [ ] **Step 5: Build, install, verify single⇄dual transitions + streaming**

```bash
./gradlew :app:installDebug -q
adb shell am force-stop com.example.plohoystream; adb logcat -c
adb shell am start -n com.example.plohoystream/.MainActivity
# Manually: toggle dual ON/OFF a few times; go live (stream) in dual; background+resume while live.
sleep 8
adb logcat -d | grep -E "DualCameraSession|CameraXController|EgressSurfaceProcessor|FATAL" | tail -40
```

Expected: clean single→dual→single with no crash; both devices open in dual and close on exit; streaming works in dual (encoder fed); resume restores the live preview.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/CameraXController.kt \
        app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt
git commit -m "feat(camera): route dual through DualCameraSession; retire CameraX concurrent path

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Lens-chip UI by `DualClass` + tap-to-exit-dual

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/ui/viewfinder/ControlRail.kt` (and the lens-chip composable it renders)
- Modify: `app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt`

**Interfaces:**
- Consumes: `controller.capabilities`, `DualClass`, `BackLens`, `controller.dualSelectChip`.
- Produces: chip rendering that reflects `DualClass` when dual is on; a confirm dialog for tap-to-exit-dual.

**Design notes:**
- When dual is OFF: chips behave exactly as today (physical lens switch via `selectLens`).
- When dual is ON: classify each chip. **Ratio-scale consistency (important):** the chips come from `controller.lenses` (`LensOption(label, physicalId, zoomRatio)`), so build the `BackLens` passed to `dualClass` from the `LensOption` itself — `BackLens(id = lens.physicalId, ratio = lens.zoomRatio, minZoom = zoomRange.start, maxZoom = zoomRange.endInclusive)` — and likewise for `openBack` (the currently-bound lens). This keeps the ZOOM check on the chips' own intrinsic-ratio scale. Use `caps` only for `isConcurrent` (REAL) via the camera ids and `frontId`. Compute `caps.dualClass(chipLens, openBackLens, frontId)`. Render REAL/ZOOM as normal selectable chips (tap → `controller.dualSelectChip(chip)`); render UNAVAILABLE dimmed with a small lock/strike marker, still tappable.
- Tapping an UNAVAILABLE chip → show a Material3 confirm (`AlertDialog`): title "Ultra-wide needs the full screen", text "Drop the PiP and switch the camera to {label}?", Confirm → `controller.exitDual()` then `controller.selectLens(chip.id)`; Cancel → dismiss.
- Pinch-zoom in dual highlights the nearest chip by ratio (visual only).

- [ ] **Step 1: Implement chip-by-class rendering** in the lens-chip composable (add a `dualClass: DualClass?` param; `null` = single mode = current behavior). Dim + mark UNAVAILABLE.

- [ ] **Step 2: Implement the tap-to-exit-dual `AlertDialog`** in `Viewfinder`, state-driven (`var exitDualChip by remember { mutableStateOf<BackLens?>(null) }`); set it when an UNAVAILABLE chip is tapped; Confirm runs `exitDual()` + `selectLens`.

- [ ] **Step 3: Build, install, verify on Seeker**

```bash
./gradlew :app:installDebug -q
adb shell am force-stop com.example.plohoystream; adb logcat -c
adb shell am start -n com.example.plohoystream/.MainActivity
# Manually in dual: tap 1.8x (ZOOM → smooth zoom, no blink); tap 0.8x (UNAVAILABLE → confirm dialog →
# Confirm drops PiP and shows ultrawide full-screen).
```

Expected: `0.8×` chip dimmed+marked in dual; its tap shows the confirm; Confirm exits dual to single ultrawide. `1×`/`1.8×` work blink-free.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/ui/viewfinder/ControlRail.kt \
        app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt
git commit -m "feat(ui): dual lens chips by DualClass + tap-to-exit-dual confirm

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Verify keepers preserved; cleanup

**Files:**
- Modify (verify only): `app/src/main/java/com/example/plohoystream/ui/viewfinder/PipOverlay.kt`, `EgressSurfaceProcessor.kt`

- [ ] **Step 1:** Confirm the rounded PiP resize grip (`PipOverlay` Canvas corner-bracket) renders in dual — it's mode-independent UI, should be unaffected. Visual check on device.

- [ ] **Step 2:** Confirm `setScene` is still the direct-volatile write (drag-perf keeper) and is used by the new dual path (`controller.setScene` → processor). Drag the PiP in dual; confirm it tracks (≥30fps acceptable).

- [ ] **Step 3:** Remove any dead code left from the retired CameraX dual path and the temporary verification hooks. Run `./gradlew :app:compileDebugKotlin -q`.

- [ ] **Step 4: Commit** (if any cleanup):

```bash
git add -A
git commit -m "chore(camera): remove dead CameraX dual-path code; keep resize grip + drag-perf fix

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: End-to-end on-device verification matrix

**Files:** none (verification only).

- [ ] **Step 1: Single-mode regression** — back/front flip (double-tap), physical lens switch (`0.8/1/1.8×` all bind), pinch-zoom, manual exposure panel, HDR/60fps if enabled, go-live + reconnect. All identical to before this branch.

- [ ] **Step 2: Dual-mode matrix on Seeker** — dual ON; PiP drag/resize/snap; PiP single-tap swap (instant, no blink); `1×↔1.8×` zoom (no blink, front live the whole time); `0.8×` → exit-dual confirm; go-live in dual; background+resume while live; dual OFF restores single.

- [ ] **Step 3: Capability correctness** — confirm via logcat that on Seeker only `{0,1}` drives a REAL back, others are ZOOM/UNAVAILABLE, and that no `switchBack` ever reopens the front device (`grep "front kept alive"`; assert no `opened device id=<front>` between switches).

- [ ] **Step 4: Note the pre-existing test failures** — `CameraStreamEngineTest` (15) fail due to `android.util.Log.i` not mocked in plain JUnit (a prior commit's state logging), unrelated to this work. Decide separately whether to add `testOptions.unitTests.returnDefaultValues = true` or mock `Log`.

- [ ] **Step 5:** Final commit / branch wrap-up per the user's instruction (merge / PR / keep).
