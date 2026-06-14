# M1-B.2: Camera Capability Layer + Camera2 Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a full-screen live camera preview in the app — driven by a per-device capability layer that auto-selects the best back camera — replacing the form with a viewfinder.

**Architecture:** A pure, JVM-testable `CameraCapabilities.select(List<CameraInfo>)` picks the best camera into a `CameraConfig`; `CameraEnumerator` reads `CameraManager`/`CameraCharacteristics` into `CameraInfo` (the integration boundary, so the selection logic stays testable); `Camera2Controller` (behind a `CameraController` interface) opens that camera and drives a preview `CaptureSession` to a `SurfaceView` Surface; the Compose `StreamScreen` shows the preview with overlaid status + Go Live (still `FakeStreamEngine` — real streaming is M1-B.3). Build step 2–3 of the M1-B spec (`docs/superpowers/specs/2026-06-14-m1b-android-capture-stream-design.md`).

**Tech Stack:** Android Camera2 (`android.hardware.camera2`, minSdk 35), Jetpack Compose, the foundation from M1-B.1.

**Scope note:** PREVIEW only. Live zoom / lens-switch / front-back-flip controls are **M1-B.2b**. Encode/JNI/stream/foreground-service are **M1-B.3**. The `CameraController` is standalone here; M1-B.3 folds it into `CameraStreamEngine`.

**Verification boundary:** the Android emulator has a virtual camera, so preview + permission + the pipeline are verified there. Zoom/lens *fidelity* is a real-device concern (M1-B.2b, your Pixel/Samsung). The capability selection logic is unit-tested regardless of hardware.

---

## File Structure

```
app/src/main/AndroidManifest.xml                         # MODIFIED: CAMERA permission + uses-feature
app/src/main/java/com/example/plohoystream/camera/
  CameraModels.kt           # NEW: Facing, Resolution, CameraLens, CameraInfo, CameraConfig
  CameraCapabilities.kt     # NEW: pure select() logic
  CameraEnumerator.kt       # NEW: CameraManager → List<CameraInfo>
  CameraController.kt        # NEW: interface
  Camera2Controller.kt      # NEW: Camera2 preview impl
app/src/main/java/com/example/plohoystream/ui/
  CameraPreview.kt          # NEW: SurfaceView-backed Compose preview
  StreamScreen.kt           # MODIFIED: viewfinder layout + permission gate
app/src/test/java/com/example/plohoystream/camera/
  CameraCapabilitiesTest.kt # NEW
```

**Build/test (from repo root):** `./gradlew :app:testDebugUnitTest` ; `./gradlew :app:assembleDebug` ; install/run: `./gradlew :app:installDebug` then launch.

---

### Task 0: Manifest — camera permission + feature

**Files:** Modify `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add permission + feature declarations** — immediately after the opening `<manifest ...>` tag (before `<application>`), insert:
```xml
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera.any" android:required="true" />
```

- [ ] **Step 2: Build to verify the manifest is valid**
Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**
```bash
git add app/src/main/AndroidManifest.xml
git commit -m "feat(m1b2): declare CAMERA permission + camera feature"
```

---

### Task 1: Camera models + `CameraCapabilities.select` (TDD)

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/camera/CameraModels.kt`
- Create: `app/src/main/java/com/example/plohoystream/camera/CameraCapabilities.kt`
- Test: `app/src/test/java/com/example/plohoystream/camera/CameraCapabilitiesTest.kt`

- [ ] **Step 1: Write the failing test**
```kotlin
package com.example.plohoystream.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilitiesTest {
    private fun cam(
        id: String, facing: Facing = Facing.BACK, logical: Boolean = false,
        minZoom: Float = 1f, maxZoom: Float = 8f, ratios: List<Float> = emptyList(),
        sizes: List<Resolution> = listOf(Resolution(1920, 1080)), ois: Boolean = false,
    ) = CameraInfo(id, facing, logical, minZoom, maxZoom, ratios, sizes, ois)

    @Test fun emptyList_returnsNull() {
        assertNull(CameraCapabilities.select(emptyList()))
    }

    @Test fun prefersLogicalBackCamera() {
        val chosen = CameraCapabilities.select(
            listOf(cam("0", logical = false), cam("9", logical = true))
        )!!
        assertEquals("9", chosen.cameraId)
        assertEquals(Facing.BACK, chosen.facing)
    }

    @Test fun fallsBackToFirstBack_whenNoLogical() {
        val chosen = CameraCapabilities.select(listOf(cam("3"), cam("4")))!!
        assertEquals("3", chosen.cameraId)
    }

    @Test fun fallsBackToAnyCamera_whenNoBackFacing() {
        val chosen = CameraCapabilities.select(listOf(cam("front", facing = Facing.FRONT)))!!
        assertEquals("front", chosen.cameraId)
    }

    @Test fun choosesSizeNearest1080p() {
        val chosen = CameraCapabilities.select(
            listOf(cam("0", sizes = listOf(Resolution(640, 480), Resolution(1920, 1080), Resolution(3840, 2160))))
        )!!
        assertEquals(Resolution(1920, 1080), chosen.previewSize)
    }

    @Test fun buildsLensList_includes1xAndRatiosWithinZoomRange_sorted() {
        val chosen = CameraCapabilities.select(
            listOf(cam("0", minZoom = 0.6f, maxZoom = 10f, ratios = listOf(2.0f, 0.6f)))
        )!!
        assertEquals(listOf(0.6f, 1.0f, 2.0f), chosen.lenses.map { it.zoomRatio })
        assertEquals(listOf("0.6×", "1×", "2×"), chosen.lenses.map { it.label })
    }

    @Test fun lensList_dropsRatiosOutsideZoomRange() {
        val chosen = CameraCapabilities.select(
            listOf(cam("0", minZoom = 1f, maxZoom = 3f, ratios = listOf(0.6f, 5.0f)))
        )!!
        assertEquals(listOf(1.0f), chosen.lenses.map { it.zoomRatio })  // 0.6 and 5.0 out of [1,3]
    }

    @Test fun carriesOisFlag() {
        assertTrue(CameraCapabilities.select(listOf(cam("0", ois = true)))!!.hasOis)
    }
}
```

- [ ] **Step 2: Run — verify it fails** (`CameraInfo`/`CameraCapabilities` unresolved).
Run: `./gradlew :app:testDebugUnitTest --tests "*CameraCapabilitiesTest"`

- [ ] **Step 3: Implement the models** `app/src/main/java/com/example/plohoystream/camera/CameraModels.kt`:
```kotlin
package com.example.plohoystream.camera

/** Lens direction. */
enum class Facing { BACK, FRONT }

/** Plain pixel size (avoids android.util.Size so selection logic is JVM-testable). */
data class Resolution(val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

/** A selectable lens "stop" for the UI (e.g. label "2×", zoomRatio 2.0). */
data class CameraLens(val label: String, val zoomRatio: Float)

/** Plain data extracted from CameraCharacteristics by CameraEnumerator. */
data class CameraInfo(
    val id: String,
    val facing: Facing,
    val isLogical: Boolean,
    val minZoom: Float,
    val maxZoom: Float,
    val lensRatios: List<Float>,        // physical-lens zoom breakpoints, e.g. [0.6, 2.0]
    val outputSizes: List<Resolution>,
    val hasOis: Boolean,
)

/** The chosen camera + derived UI affordances, consumed by CameraController/UI. */
data class CameraConfig(
    val cameraId: String,
    val facing: Facing,
    val previewSize: Resolution,
    val minZoom: Float,
    val maxZoom: Float,
    val lenses: List<CameraLens>,
    val hasOis: Boolean,
)
```

- [ ] **Step 4: Implement** `app/src/main/java/com/example/plohoystream/camera/CameraCapabilities.kt`:
```kotlin
package com.example.plohoystream.camera

import java.util.Locale
import kotlin.math.abs

/** Pure, device-agnostic selection of the best camera + its UI affordances. */
object CameraCapabilities {
    private val TARGET = Resolution(1920, 1080)

    fun select(cameras: List<CameraInfo>, facing: Facing = Facing.BACK): CameraConfig? {
        if (cameras.isEmpty()) return null
        val pool = cameras.filter { it.facing == facing }.ifEmpty { cameras }
        // Prefer a logical multi-camera (seamless optical zoom across lenses, like the stock app).
        val cam = pool.firstOrNull { it.isLogical } ?: pool.first()
        return CameraConfig(
            cameraId = cam.id,
            facing = cam.facing,
            previewSize = chooseSize(cam.outputSizes),
            minZoom = cam.minZoom,
            maxZoom = cam.maxZoom,
            lenses = buildLenses(cam),
            hasOis = cam.hasOis,
        )
    }

    private fun chooseSize(sizes: List<Resolution>): Resolution =
        sizes.minByOrNull { abs(it.pixels - TARGET.pixels) } ?: TARGET

    private fun buildLenses(cam: CameraInfo): List<CameraLens> {
        val ratios = (cam.lensRatios + 1.0f)
            .filter { it in cam.minZoom..cam.maxZoom }
            .distinct()
            .sorted()
            .ifEmpty { listOf(1.0f) }
        return ratios.map { CameraLens(formatRatio(it), it) }
    }

    private fun formatRatio(r: Float): String =
        if (r == r.toInt().toFloat()) "${r.toInt()}×"
        else String.format(Locale.US, "%.1f×", r)
}
```

- [ ] **Step 5: Run — verify pass**
Run: `./gradlew :app:testDebugUnitTest --tests "*CameraCapabilitiesTest"`
Expected: 8 tests PASS.

- [ ] **Step 6: Commit**
```bash
git add app/src/main/java/com/example/plohoystream/camera/CameraModels.kt app/src/main/java/com/example/plohoystream/camera/CameraCapabilities.kt app/src/test/java/com/example/plohoystream/camera/CameraCapabilitiesTest.kt
git commit -m "feat(m1b2): camera models + CameraCapabilities.select (TDD)"
```

---

### Task 2: `CameraEnumerator` (CameraManager → CameraInfo)

This is the integration boundary (touches `CameraManager`); no JVM unit test (verified by running on a device). Keep it a thin, faithful reader.

**Files:** Create `app/src/main/java/com/example/plohoystream/camera/CameraEnumerator.kt`

- [ ] **Step 1: Implement**
```kotlin
package com.example.plohoystream.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** Reads the device's cameras into plain CameraInfo for CameraCapabilities. */
class CameraEnumerator(context: Context) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun enumerate(): List<CameraInfo> =
        manager.cameraIdList.mapNotNull { id -> runCatching { infoFor(id) }.getOrNull() }

    private fun infoFor(id: String): CameraInfo {
        val c = manager.getCameraCharacteristics(id)
        val facing = if (c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
            Facing.FRONT else Facing.BACK
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val isLogical =
            caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
        val zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        val minZoom = zoomRange?.lower ?: 1.0f
        val maxZoom = zoomRange?.upper
            ?: c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1.0f
        val ois = (c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0))
            .contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON)
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.map { Resolution(it.width, it.height) }
            ?: emptyList()
        // Lens breakpoints: expose 1.0x always, plus the ultrawide stop if the range goes below 1.
        // Tele breakpoints are device-specific and refined in M1-B.2b (lens controls).
        val lensRatios = if (minZoom < 1.0f) listOf(minZoom, 1.0f) else listOf(1.0f)
        return CameraInfo(id, facing, isLogical, minZoom, maxZoom, lensRatios, sizes, ois)
    }
}
```

- [ ] **Step 2: Build to verify it compiles**
Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/example/plohoystream/camera/CameraEnumerator.kt
git commit -m "feat(m1b2): CameraEnumerator reads CameraManager into CameraInfo"
```

---

### Task 3: `CameraController` interface + `Camera2Controller` (open + preview + stop)

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/camera/CameraController.kt`
- Create: `app/src/main/java/com/example/plohoystream/camera/Camera2Controller.kt`

- [ ] **Step 1: Implement the interface** `CameraController.kt`:
```kotlin
package com.example.plohoystream.camera

import android.view.Surface

/** Drives a camera preview to a Surface. Zoom/lens/flip controls arrive in M1-B.2b;
 *  M1-B.3 folds this into CameraStreamEngine (adding the encoder Surface as a 2nd target). */
interface CameraController {
    fun start(config: CameraConfig, previewSurface: Surface)
    fun stop()
}
```

- [ ] **Step 2: Implement** `Camera2Controller.kt`:
```kotlin
package com.example.plohoystream.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executor

class Camera2Controller(context: Context) : CameraController {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    @SuppressLint("MissingPermission") // caller guarantees CAMERA permission is granted
    override fun start(config: CameraConfig, previewSurface: Surface) {
        stop()
        val t = HandlerThread("camera-bg").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val executor = Executor { h.post(it) }

        manager.openCamera(config.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }.build()
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(previewSurface)),
                    executor,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session = s
                            runCatching { s.setRepeatingRequest(request, null, h) }
                                .onFailure { Log.e(TAG, "setRepeatingRequest failed", it) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            Log.e(TAG, "capture session configure failed")
                        }
                    },
                )
                camera.createCaptureSession(sessionConfig)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "camera error $error"); camera.close(); device = null
            }
        }, h)
    }

    override fun stop() {
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        thread?.quitSafely(); thread = null; handler = null
    }

    private companion object { const val TAG = "Camera2Controller" }
}
```

- [ ] **Step 3: Build to verify it compiles**
Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/example/plohoystream/camera/CameraController.kt app/src/main/java/com/example/plohoystream/camera/Camera2Controller.kt
git commit -m "feat(m1b2): CameraController + Camera2Controller preview session"
```

---

### Task 4: `CameraPreview` Compose component

**Files:** Create `app/src/main/java/com/example/plohoystream/ui/CameraPreview.kt`

- [ ] **Step 1: Implement** a `SurfaceView`-backed preview that hands its Surface up via callbacks:
```kotlin
package com.example.plohoystream.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A camera viewfinder. Calls [onSurface] with a ready Surface (and null when destroyed) so a
 * CameraController can start/stop a preview against it.
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onSurface: (Surface?) -> Unit,
) {
    val cb by rememberUpdatedState(onSurface)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) { cb(h.surface) }
                    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, height: Int) {}
                    override fun surfaceDestroyed(h: SurfaceHolder) { cb(null) }
                })
            }
        },
    )
}
```

- [ ] **Step 2: Build to verify it compiles**
Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/example/plohoystream/ui/CameraPreview.kt
git commit -m "feat(m1b2): SurfaceView-backed CameraPreview composable"
```

---

### Task 5: Viewfinder UI — permission gate + preview + overlay

Restructure `StreamScreen` into a viewfinder: request CAMERA permission; once granted, enumerate+select a camera, show the full-screen preview, and overlay the status + a settings panel (URL/key) + Go Live (still `FakeStreamEngine`).

**Files:** Modify `app/src/main/java/com/example/plohoystream/ui/StreamScreen.kt`

- [ ] **Step 1: Replace `StreamScreen.kt`** with the viewfinder version:
```kotlin
package com.example.plohoystream.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface as M3Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.camera.Camera2Controller
import com.example.plohoystream.camera.CameraCapabilities
import com.example.plohoystream.camera.CameraController
import com.example.plohoystream.camera.CameraEnumerator
import com.example.plohoystream.stream.StreamState
import com.example.plohoystream.stream.StreamUiState
import com.example.plohoystream.stream.StreamViewModel

@Composable
fun StreamScreen(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamera = granted }

    if (!hasCamera) {
        PermissionPrompt(onGrant = { requestPermission.launch(Manifest.permission.CAMERA) })
        return
    }

    // Enumerate + pick the best back camera once.
    val config = remember { CameraCapabilities.select(CameraEnumerator(context).enumerate()) }
    val controller: CameraController = remember { Camera2Controller(context) }
    DisposableEffect(Unit) { onDispose { controller.stop() } }

    Box(modifier = Modifier.fillMaxSize()) {
        if (config != null) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                onSurface = { surface: Surface? ->
                    if (surface != null) controller.start(config, surface) else controller.stop()
                },
            )
        } else {
            Text(
                "No camera available", color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        ViewfinderOverlay(ui = ui, viewModel = viewModel)
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    M3Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("PlohoyStream needs camera access to stream.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onGrant) { Text("Grant camera access") }
        }
    }
}

@Composable
private fun ViewfinderOverlay(ui: StreamUiState, viewModel: StreamViewModel) {
    var showSettings by remember { mutableStateOf(true) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(statusText(ui.stream), color = Color.White, style = MaterialTheme.typography.titleMedium)
        if (showSettings) {
            OutlinedTextField(
                value = ui.url, onValueChange = viewModel::setUrl,
                label = { Text("RTMP URL") }, singleLine = true, enabled = !ui.isActive,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ui.key, onValueChange = viewModel::setKey,
                label = { Text("Stream key") }, singleLine = true, enabled = !ui.isActive,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Button(
                onClick = { showSettings = !showSettings },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (showSettings) "Hide settings" else "Settings") }
            if (ui.isActive) {
                Button(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
            } else {
                Button(
                    onClick = viewModel::goLive, enabled = ui.canGoLive,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Go Live") }
            }
        }
    }
}

private fun statusText(s: StreamState): String = when (s) {
    StreamState.Idle -> "Idle"
    StreamState.Connecting -> "Connecting…"
    StreamState.Live -> "● LIVE"
    StreamState.Stopping -> "Stopping…"
    is StreamState.Error -> "Error: ${s.reason}"
}
```
> Note: the old `@Preview` is dropped because the screen now requires a camera/permission (not previewable). The pure rendering logic was already covered by `StreamViewModel`/`StreamUiState` tests. This is intentional, not an omission.

- [ ] **Step 2: Build to verify it compiles**
Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/example/plohoystream/ui/StreamScreen.kt
git commit -m "feat(m1b2): viewfinder UI — permission gate + camera preview + overlay"
```

---

### Task 6: Run on the emulator (acceptance) + regression check

**Files:** (none)

- [ ] **Step 1: Confirm unit tests still pass**
Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; `CameraCapabilitiesTest` (8) + `StreamUiStateTest` + `FakeStreamEngineTest` + `StreamViewModelTest` all green.

- [ ] **Step 2: Install + launch on the running emulator**
```bash
./gradlew :app:installDebug
"$HOME/Library/Android/sdk/platform-tools/adb" -s emulator-5554 shell am start -n com.example.plohoystream/.MainActivity
```
Expected: app launches, shows the "Grant camera access" prompt; after granting, the emulator's **virtual camera preview fills the screen** with the status text + settings fields + Go Live overlaid. (The emulator's virtual scene/animated camera is the expected preview content.)

- [ ] **Step 3: Capture a screenshot to confirm the preview renders**
```bash
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" -s emulator-5554 shell pm grant com.example.plohoystream android.permission.CAMERA
"$ADB" -s emulator-5554 shell am force-stop com.example.plohoystream
"$ADB" -s emulator-5554 shell am start -n com.example.plohoystream/.MainActivity
sleep 3
"$ADB" -s emulator-5554 exec-out screencap -p > /tmp/m1b2_preview.png
```
Expected: `/tmp/m1b2_preview.png` shows the live camera preview behind the overlay controls. (`pm grant` pre-authorizes CAMERA so the screenshot shows the preview, not the prompt.)

- [ ] **Step 4: Commit (a marker if any tiny fix was needed; otherwise nothing to commit)**
If Steps 1–3 required a fix, commit it: `git add -A && git commit -m "fix(m1b2): <what>"`. If everything passed with no change, there is nothing to commit for this task.

---

## Self-Review

**Spec coverage (M1-B spec, build steps 2–3):**
- `CameraCapabilities` selection (best back lens, prefer logical, ~1080p, lens list, zoom range, OIS) → ✅ Task 1 (TDD).
- Enumerate device cameras → ✅ Task 2.
- Camera2 preview to a Surface (two-surface session is M1-B.3; here one preview surface) → ✅ Tasks 3–4.
- Viewfinder UI replacing the form; permission flow → ✅ Task 5.
- Run/verify on device → ✅ Task 6.
- Correctly DEFERRED: live zoom/lens/flip controls (M1-B.2b), the `StreamEngine` camera-control methods (M1-B.2b/B.3), MediaCodec encode + JNI + foreground service + go-live (M1-B.3), HDR (M1-C). Stated in the plan header.

**Placeholder scan:** No "TBD"/"add error handling" gaps. The `@SuppressLint("MissingPermission")` is justified (permission checked at the UI gate before `start`). The dropped `@Preview` is explicitly explained.

**Type consistency:** `Facing`, `Resolution(width,height)`+`pixels`, `CameraLens(label,zoomRatio)`, `CameraInfo(id,facing,isLogical,minZoom,maxZoom,lensRatios,outputSizes,hasOis)`, `CameraConfig(cameraId,facing,previewSize,minZoom,maxZoom,lenses,hasOis)`, `CameraCapabilities.select`, `CameraEnumerator.enumerate`, `CameraController.start/stop`, `CameraPreview(onSurface)` are consistent across Tasks 1–5. `StreamViewModel`/`StreamUiState` from M1-B.1 are reused unchanged.

**Android-API note:** uses the non-deprecated `SessionConfiguration`/`OutputConfiguration` session path (API 28+, fine for minSdk 35) and `CONTROL_ZOOM_RATIO_RANGE` (API 30+). All within minSdk 35.

---

## Execution Handoff

This plan delivers a full-screen camera viewfinder driven by the per-device capability layer — the app stops being a form and becomes a streaming app's camera screen. **M1-B.2b** adds live zoom/lens/flip controls; **M1-B.3** adds encode + native StreamSession/JNI + foreground service and makes Go Live actually stream.
