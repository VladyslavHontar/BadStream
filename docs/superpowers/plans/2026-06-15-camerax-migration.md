# CameraX 1.6.1 Migration + Capability-Driven Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Camera2 capture stack with CameraX 1.6.1, drive the resolution/fps/codec menu from real device capability, and unlock 60fps+HDR (and 4K/stabilization where supported) via Feature Groups — gated purely by `isSessionConfigSupported()`, with the existing MediaCodec → native SRT/RTMP/MPEG-TS egress unchanged.

**Architecture:** A new `CameraXController` (implementing the existing `CameraController` interface) binds a `Preview` use case plus a `CameraEffect`/`SurfaceProcessor` that GL-renders camera frames to both the on-screen preview and our MediaCodec input surface. A pure `CaptureMenu` generator intersects camera-supported combos (`CameraInfo.isSessionConfigSupported`) with encoder support (`MediaCodecList`) to build the menu. `Camera2Controller` stays in-tree behind a factory flag until on-device parity is confirmed.

**Tech Stack:** Kotlin, Jetpack Compose, CameraX 1.6.1 (`camera-core`/`camera-camera2`/`camera-lifecycle`/`camera-view`), OpenGL ES 2.0 (SurfaceProcessor), MediaCodec, existing NDK egress.

**Reference (spec):** `docs/superpowers/specs/2026-06-15-camerax-migration-design.md`

---

## Conventions

- Build check (no device): `./gradlew :app:assembleDebug`
- Unit tests (no device): `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.*"`
- "On-device verify" steps are **manual smoke gates** — they have no automated test and MUST be run on a physical device before the task is considered done. Note the result in the commit body.
- Keep `Camera2Controller` and `Camera2` enumeration untouched until Task 11. The new code lives alongside it.
- Commit after every task. Stage only the files the task names — never `git add -A` (the repo has untracked `.idea/`/`.claude/`).

---

## Task 0: Add CameraX 1.6.1 dependencies

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add the CameraX version + libraries to the version catalog**

In `gradle/libs.versions.toml`, under `[versions]` add:

```toml
camerax = "1.6.1"
```

Under `[libraries]` add:

```toml
androidx-camera-core = { module = "androidx.camera:camera-core", version.ref = "camerax" }
androidx-camera-camera2 = { module = "androidx.camera:camera-camera2", version.ref = "camerax" }
androidx-camera-lifecycle = { module = "androidx.camera:camera-lifecycle", version.ref = "camerax" }
androidx-camera-view = { module = "androidx.camera:camera-view", version.ref = "camerax" }
```

- [ ] **Step 2: Reference them in the app module**

In `app/build.gradle.kts`, inside `dependencies { … }` add:

```kotlin
implementation(libs.androidx.camera.core)
implementation(libs.androidx.camera.camera2)
implementation(libs.androidx.camera.lifecycle)
implementation(libs.androidx.camera.view)
```

- [ ] **Step 3: Verify the build resolves the dependencies**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (CameraX artifacts download and resolve).

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add CameraX 1.6.1 dependencies"
```

---

## Task 1: Capture combo model + pure menu generator (TDD)

This is the testable core: given the candidate combos, a camera-support predicate, and encoder gates, produce the menu. No Android framework types so it runs in plain JVM unit tests.

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/camera/CaptureMenu.kt`
- Test: `app/src/test/java/com/example/plohoystream/camera/CaptureMenuTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.plohoystream.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureMenuTest {

    // Camera supports everything EXCEPT 4K, and supports HDR only at <=1080p60.
    private val camera = CameraComboProbe { c ->
        if (c.width >= 3840) false
        else if (c.hdr) c.height <= 1080 && c.fps <= 60
        else true
    }

    // Encoder: AVC always; HEVC present; Main10 present; max 1080p60 (no 4K encode).
    private val encoder = EncoderGate(
        hasHevc = true,
        hasHevcMain10 = true,
        canEncode = { c -> c.height <= 1080 && c.fps <= 60 },
    )

    private val candidates = listOf(
        CaptureCombo(1280, 720, 30, hdr = false),
        CaptureCombo(1920, 1080, 30, hdr = false),
        CaptureCombo(1920, 1080, 60, hdr = false),
        CaptureCombo(3840, 2160, 30, hdr = false), // 4K — camera & encoder both reject
    )

    @Test fun generate_keepsOnlyCameraAndEncoderSupportedCombos() {
        val menu = CaptureMenu.generate(candidates, camera, encoder)
        val labels = menu.map { "${it.height}p${it.fps}" }
        assertEquals(listOf("720p30", "1080p30", "1080p60"), labels)
    }

    @Test fun generate_marksHdrCapableWhenCameraAndMain10Allow() {
        val menu = CaptureMenu.generate(candidates, camera, encoder)
        // 1080p60 HDR is supported by both camera (<=1080p60) and Main10 encoder.
        assertTrue(menu.first { it.height == 1080 && it.fps == 60 }.hdrCapable)
    }

    @Test fun generate_hdrNotCapableWithoutMain10() {
        val noMain10 = encoder.copy(hasHevcMain10 = false)
        val menu = CaptureMenu.generate(candidates, camera, noMain10)
        assertTrue(menu.all { !it.hdrCapable })
    }

    @Test fun codecOptions_excludeHevcWhenAbsent() {
        assertEquals(
            listOf(VideoCodecOption.Auto, VideoCodecOption.Avc),
            CaptureMenu.codecOptions(EncoderGate(hasHevc = false, hasHevcMain10 = false) { true }),
        )
    }

    @Test fun codecOptions_includeHevcWhenPresent() {
        assertTrue(CaptureMenu.codecOptions(encoder).contains(VideoCodecOption.Hevc))
    }

    @Test fun hdrToggle_disabledReason_whenSelectedOptionNotHdrCapable() {
        val option = QualityOption(3840, 2160, 30, hdrCapable = false)
        val state = CaptureMenu.hdrToggleState(option)
        assertFalse(state.enabled)
        assertEquals("HDR needs HEVC Main10 — unavailable at this resolution/fps", state.reason)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.CaptureMenuTest"`
Expected: FAIL — `CaptureMenu`, `CaptureCombo`, etc. unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.example.plohoystream.camera

/** A concrete capture request: pixel size, frame rate, and whether HDR (HLG10) is requested. */
data class CaptureCombo(
    val width: Int,
    val height: Int,
    val fps: Int,
    val hdr: Boolean,
    val stabilized: Boolean = false,
)

/** Camera-side support oracle. Real impl wraps CameraX `isSessionConfigSupported`; fakes in tests. */
fun interface CameraComboProbe {
    fun isSupported(combo: CaptureCombo): Boolean
}

/** Encoder-side gates from `MediaCodecList`. */
data class EncoderGate(
    val hasHevc: Boolean,
    val hasHevcMain10: Boolean,
    val canEncode: (CaptureCombo) -> Boolean,
)

/** A resolution/fps the menu offers, with whether HDR can pair with it. */
data class QualityOption(
    val width: Int,
    val height: Int,
    val fps: Int,
    val hdrCapable: Boolean,
)

enum class VideoCodecOption { Auto, Hevc, Avc }

/** Whether the HDR toggle is enabled for the current selection, plus a reason when disabled. */
data class HdrToggleState(val enabled: Boolean, val reason: String?)

object CaptureMenu {

    /** Achievable resolution/fps options = camera-supported ∩ encoder-supported (SDR baseline). */
    fun generate(
        candidates: List<CaptureCombo>,
        camera: CameraComboProbe,
        encoder: EncoderGate,
    ): List<QualityOption> = candidates
        .filter { !it.hdr } // candidates are SDR; HDR-capability is derived per option below
        .filter { camera.isSupported(it) && encoder.canEncode(it) }
        .map { c ->
            val hdrCombo = c.copy(hdr = true)
            val hdrCapable = encoder.hasHevcMain10 &&
                camera.isSupported(hdrCombo) && encoder.canEncode(hdrCombo)
            QualityOption(c.width, c.height, c.fps, hdrCapable)
        }

    /** Codec chips the device can actually offer. Auto + AVC always; HEVC only when present. */
    fun codecOptions(encoder: EncoderGate): List<VideoCodecOption> = buildList {
        add(VideoCodecOption.Auto)
        if (encoder.hasHevc) add(VideoCodecOption.Hevc)
        add(VideoCodecOption.Avc)
    }

    /** HDR toggle availability for the currently selected option. */
    fun hdrToggleState(selected: QualityOption): HdrToggleState =
        if (selected.hdrCapable) HdrToggleState(enabled = true, reason = null)
        else HdrToggleState(
            enabled = false,
            reason = "HDR needs HEVC Main10 — unavailable at this resolution/fps",
        )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.CaptureMenuTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/CaptureMenu.kt \
        app/src/test/java/com/example/plohoystream/camera/CaptureMenuTest.kt
git commit -m "feat(camera): pure capability-driven capture menu generator"
```

---

## Task 2: Candidate combo list + ordering (TDD)

The fixed list of combos we probe, newest-first ordering, and de-dup. Keeps `generate()` callers from hand-rolling the candidate set.

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/camera/CaptureMenu.kt`
- Test: `app/src/test/java/com/example/plohoystream/camera/CaptureCandidatesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.plohoystream.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureCandidatesTest {
    @Test fun candidates_areOrderedHighestQualityFirst_andUnique() {
        val c = CaptureMenu.CANDIDATES
        assertEquals(c.distinct(), c) // no duplicates
        // Highest resolution then highest fps first.
        assertEquals(CaptureCombo(3840, 2160, 60, hdr = false), c.first())
        assertEquals(CaptureCombo(1280, 720, 30, hdr = false), c.last())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.CaptureCandidatesTest"`
Expected: FAIL — `CaptureMenu.CANDIDATES` unresolved.

- [ ] **Step 3: Add the candidate set to `CaptureMenu`**

Add inside `object CaptureMenu`:

```kotlin
    /** The SDR combos we probe the device for, highest quality first. HDR variants derived in [generate]. */
    val CANDIDATES: List<CaptureCombo> = listOf(
        CaptureCombo(3840, 2160, 60, hdr = false),
        CaptureCombo(3840, 2160, 30, hdr = false),
        CaptureCombo(1920, 1080, 60, hdr = false),
        CaptureCombo(1920, 1080, 30, hdr = false),
        CaptureCombo(1280, 720, 60, hdr = false),
        CaptureCombo(1280, 720, 30, hdr = false),
    )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.CaptureCandidatesTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/CaptureMenu.kt \
        app/src/test/java/com/example/plohoystream/camera/CaptureCandidatesTest.kt
git commit -m "feat(camera): probe candidate combo set, highest-quality-first"
```

---

## Task 3: Encoder gate from MediaCodecList (TDD where pure, build-verify the query)

Extend `CodecCapabilities` to expose a `videoCanEncode(width,height,fps,hevc)` predicate and build an `EncoderGate`. The size/rate query uses `MediaCodecInfo.VideoCapabilities.areSizeAndRateSupported`, so the *builder* is device code; keep the pure mapping testable.

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/media/CodecCapabilities.kt`
- Create: `app/src/main/java/com/example/plohoystream/camera/EncoderGateFactory.kt`
- Test: `app/src/test/java/com/example/plohoystream/camera/EncoderGateFactoryTest.kt`

- [ ] **Step 1: Write the failing test (pure mapping from a caps snapshot)**

```kotlin
package com.example.plohoystream.camera

import com.example.plohoystream.media.CodecCapabilities
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderGateFactoryTest {
    @Test fun gate_fromSnapshot_usesAvcAndHevcLimits() {
        val snap = CodecCapabilities.VideoSnapshot(
            hasHevc = true,
            hasHevcMain10 = true,
            avcMaxPixelsPerSecond = 1920L * 1080 * 30,   // AVC up to 1080p30
            hevcMaxPixelsPerSecond = 1920L * 1080 * 60,   // HEVC up to 1080p60
        )
        val gate = EncoderGateFactory.from(snap)

        // AVC-class SDR 1080p60 exceeds AVC budget but the gate uses the HEVC ceiling when present.
        assertTrue(gate.canEncode(CaptureCombo(1920, 1080, 60, hdr = false)))
        assertFalse(gate.canEncode(CaptureCombo(3840, 2160, 30, hdr = false))) // 4K beyond both
        assertTrue(gate.hasHevcMain10)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.EncoderGateFactoryTest"`
Expected: FAIL — `CodecCapabilities.VideoSnapshot` / `EncoderGateFactory` unresolved.

- [ ] **Step 3: Add a `VideoSnapshot` + query to `CodecCapabilities`**

Append to `object CodecCapabilities`:

```kotlin
    /** Pure snapshot of encoder limits, so menu gating is unit-testable without MediaCodec. */
    data class VideoSnapshot(
        val hasHevc: Boolean,
        val hasHevcMain10: Boolean,
        val avcMaxPixelsPerSecond: Long,
        val hevcMaxPixelsPerSecond: Long,
    )

    /** Read the device's real AVC/HEVC encoder limits into a [VideoSnapshot]. */
    fun videoSnapshot(): VideoSnapshot {
        val hevc = hevc()
        return VideoSnapshot(
            hasHevc = hevc.encoder,
            hasHevcMain10 = hevc.main10,
            avcMaxPixelsPerSecond = maxPixelsPerSecond(MediaFormat.MIMETYPE_VIDEO_AVC),
            hevcMaxPixelsPerSecond = maxPixelsPerSecond(MediaFormat.MIMETYPE_VIDEO_HEVC),
        )
    }

    private fun maxPixelsPerSecond(mime: String): Long {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
            val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: continue
            val v = caps.videoCapabilities ?: continue
            // Upper bound on (w*h*fps) the encoder accepts.
            return v.supportedWidths.upper.toLong() *
                v.supportedHeights.upper.toLong() *
                (v.supportedFrameRates?.upper?.toLong() ?: 60L)
        }
        return 0L
    }
```

- [ ] **Step 4: Create `EncoderGateFactory`**

```kotlin
package com.example.plohoystream.camera

import com.example.plohoystream.media.CodecCapabilities

/** Builds an [EncoderGate] from a [CodecCapabilities.VideoSnapshot]. Pure — unit-testable. */
object EncoderGateFactory {
    fun from(snap: CodecCapabilities.VideoSnapshot): EncoderGate {
        val ceiling = if (snap.hasHevc) snap.hevcMaxPixelsPerSecond else snap.avcMaxPixelsPerSecond
        return EncoderGate(
            hasHevc = snap.hasHevc,
            hasHevcMain10 = snap.hasHevcMain10,
            canEncode = { c -> c.width.toLong() * c.height * c.fps <= ceiling },
        )
    }

    /** Device convenience: snapshot the real encoders and build the gate. */
    fun fromDevice(): EncoderGate = from(CodecCapabilities.videoSnapshot())
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.EncoderGateFactoryTest"`
Expected: PASS.

- [ ] **Step 6: Build-verify the device query compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/media/CodecCapabilities.kt \
        app/src/main/java/com/example/plohoystream/camera/EncoderGateFactory.kt \
        app/src/test/java/com/example/plohoystream/camera/EncoderGateFactoryTest.kt
git commit -m "feat(media): encoder gate from MediaCodecList video limits"
```

---

## Task 4: CameraX combo probe (build-verify; device-backed)

Wrap `CameraInfo.isSessionConfigSupported()` behind the `CameraComboProbe` interface. This is framework code; its correctness is verified on-device in Task 9. Isolate it so nothing else depends on CameraX types.

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/camera/CameraXComboProbe.kt`

- [ ] **Step 1: Implement the probe**

```kotlin
package com.example.plohoystream.camera

import androidx.camera.core.CameraInfo
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.featuregroup.GroupableFeature

/**
 * Camera-side support oracle backed by CameraX Feature Groups. For each [CaptureCombo] we build
 * a [SessionConfig] carrying the matching [GroupableFeature]s and ask the device authoritatively
 * via [CameraInfo.isSessionConfigSupported]. No make/model list — pure capability.
 *
 * Note: resolution is expressed through the bound use cases' target resolution; fps/HDR/4K are
 * expressed as groupable features. A throwaway [Preview] use case is used purely for the query.
 */
class CameraXComboProbe(
    private val cameraInfo: CameraInfo,
    private val probeUseCaseFactory: (CaptureCombo) -> Preview = ::defaultPreview,
) : CameraComboProbe {

    override fun isSupported(combo: CaptureCombo): Boolean {
        val features = buildList {
            if (combo.fps >= 60) add(GroupableFeature.FPS_60)
            if (combo.hdr) add(GroupableFeature.HDR_HLG10)
            if (combo.width >= 3840) add(GroupableFeature.UHD_RECORDING)
            if (combo.stabilized) add(GroupableFeature.PREVIEW_STABILIZATION)
        }
        val session = SessionConfig.Builder(probeUseCaseFactory(combo))
            .setRequiredFeatureGroup(features)
            .build()
        return runCatching { cameraInfo.isSessionConfigSupported(session) }.getOrDefault(false)
    }

    private companion object {
        fun defaultPreview(combo: CaptureCombo): Preview =
            Preview.Builder()
                .setTargetResolution(android.util.Size(combo.width, combo.height))
                .build()
    }
}
```

> NOTE on API names: confirm against CameraX 1.6.1 javadoc at implementation time — `SessionConfig.Builder`, `setRequiredFeatureGroup`, `GroupableFeature.{FPS_60,HDR_HLG10,UHD_RECORDING,PREVIEW_STABILIZATION}`, and `CameraInfo.isSessionConfigSupported(SessionConfig)`. If a name differs, adjust here only — callers depend on the `CameraComboProbe` interface, not these symbols.

- [ ] **Step 2: Build-verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/CameraXComboProbe.kt
git commit -m "feat(camera): CameraX Feature-Group combo probe behind CameraComboProbe"
```

---

## Task 5: GL egress SurfaceProcessor (build-verify; device-backed)

A `SurfaceProcessor` that receives the camera frame and renders it to every requested output surface (the on-screen preview output AND our MediaCodec input surface). This replaces "add the MediaCodec surface as a 2nd Camera2 target" and is the future home of the dual-cam compositor.

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/camera/EgressSurfaceProcessor.kt`

- [ ] **Step 1: Implement the processor**

Port the OpenGL ES 2.0 external-texture renderer from the official CameraX `SurfaceProcessor` sample (`androidx.camera.core.SurfaceProcessor` + the AOSP `OpenGlRenderer` in `camera-effects`). The structure:

```kotlin
package com.example.plohoystream.camera

import android.view.Surface
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * Receives the camera frame (an external GL texture) and renders it to:
 *  - every CameraX [SurfaceOutput] (the on-screen preview), and
 *  - the registered [encoderSurface] (our MediaCodec input surface) when streaming.
 *
 * This is the single GL seam for capture fan-out. Dual-camera scene composition will later
 * compose two input textures here before the same outputs — no caller changes required.
 *
 * GL details (EGL context, external-texture shader, transform matrix application) are ported
 * from the official CameraX OpenGlRenderer sample; keep that renderer in [GlRenderer].
 */
class EgressSurfaceProcessor : SurfaceProcessor {

    private val renderer = GlRenderer()                       // ported EGL/GLES helper
    private val outputs = ConcurrentHashMap<SurfaceOutput, Surface>()

    @Volatile private var encoderSurface: Surface? = null

    /** Register/unregister the MediaCodec input surface as an extra render target. */
    fun setEncoderSurface(surface: Surface?) {
        encoderSurface = surface
    }

    override fun onInputSurface(request: SurfaceRequest) {
        renderer.attachInput(request) { frameTimestampNs, textureTransform ->
            // Draw the just-updated external texture to every live output.
            outputs.values.forEach { renderer.drawTo(it, textureTransform, frameTimestampNs) }
            encoderSurface?.let { renderer.drawTo(it, textureTransform, frameTimestampNs) }
        }
    }

    override fun onOutputSurface(output: SurfaceOutput) {
        val surface = output.getSurface(renderer.executor) { _ ->
            outputs.remove(output)
            output.close()
        }
        outputs[output] = surface
    }

    fun release() {
        renderer.release()
        outputs.clear()
        encoderSurface = null
    }
}
```

Then implement `GlRenderer` (separate file `app/src/main/java/com/example/plohoystream/camera/GlRenderer.kt`) by porting the sample's EGL setup, the `samplerExternalOES` passthrough shader, `SurfaceTexture.updateTexImage()` + `getTransformMatrix()` on the input, and an EGL window surface per output. Source of truth: CameraX `camera-effects` `OpenGlRenderer`/`DefaultSurfaceProcessor` — adapt, do not invent.

> RATIONALE: the encoder surface is just one more EGL window surface drawn with the same shader, so 60fps/HDR frames flow to it identically to the preview. HDR (10-bit) requires an `RGBA1010102`/`EGL_GL_COLORSPACE_BT2020_HLG` window surface for the encoder output — gate that on the active dynamic range.

- [ ] **Step 2: Build-verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/EgressSurfaceProcessor.kt \
        app/src/main/java/com/example/plohoystream/camera/GlRenderer.kt
git commit -m "feat(camera): GL SurfaceProcessor fanning camera frames to preview + encoder"
```

---

## Task 6: CameraXController (build-verify; device-backed)

The `CameraController` implementation. Owns a `ProcessCameraProvider`, a controllable `LifecycleRegistry`, binds `Preview` + `CameraEffect(EgressSurfaceProcessor)` under a `SessionConfig` with the selected combo's `requiredFeatureGroup`, and maps zoom/lens/flip.

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/camera/CameraXController.kt`
- Modify: `app/src/main/java/com/example/plohoystream/camera/CameraController.kt`

- [ ] **Step 1: Extend the `CameraController` contract with the selected combo**

The controller needs the chosen `(fps, hdr, size)` to build the feature group. `CameraConfig` already carries `previewSize`/`targetFps`; add the selected `hdr` via the existing `start(..., hdr)` param (already present). No interface signature change required — `start(config, targets, hdr)` stays. Add a doc note to `CameraController.start`:

```kotlin
    /**
     * Open [config]'s camera and stream into every surface in [targets] (preview, encoder, …).
     * When [hdr] is true the session is configured for HLG10. With CameraX the requested
     * frame rate ([CameraConfig.targetFps]) and [hdr] become a required Feature Group.
     */
```

- [ ] **Step 2: Implement `CameraXController`**

```kotlin
package com.example.plohoystream.camera

import android.content.Context
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SessionConfig
import androidx.camera.core.featuregroup.GroupableFeature
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

/**
 * CameraX implementation of [CameraController]. Replaces [Camera2Controller].
 *
 * Lifecycle: CameraX binds to a [LifecycleOwner]; we drive our OWN [LifecycleRegistry] so capture
 * survives the Activity (background streaming under the foreground service). The registry is moved
 * to STARTED whenever we want the camera bound, and to CREATED on [stop].
 */
class CameraXController(context: Context) : CameraController, LifecycleOwner {

    private val appCtx = context.applicationContext
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(appCtx)
    private val registry = LifecycleRegistry(this).apply {
        currentState = Lifecycle.State.CREATED
    }
    override val lifecycle: Lifecycle get() = registry

    private var provider: ProcessCameraProvider? = null
    private val processor = EgressSurfaceProcessor()
    private var currentZoom = 1.0f

    init {
        val future = ProcessCameraProvider.getInstance(appCtx)
        future.addListener({ provider = future.get() }, mainExecutor)
    }

    override fun start(config: CameraConfig, targets: List<Surface>, hdr: Boolean) {
        mainExecutor.execute {
            val p = provider ?: run { Log.w(TAG, "provider not ready; retry on next start"); return@execute }
            p.unbindAll()

            // The encoder surface (if any) is the non-preview target; register it with the processor.
            // Preview output is owned by CameraX via the Preview use case + our CameraEffect.
            processor.setEncoderSurface(targets.firstOrNull { it != previewSurface })

            val selector = if (config.facing == Facing.FRONT)
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

            val preview = Preview.Builder()
                .setTargetResolution(android.util.Size(config.previewSize.width, config.previewSize.height))
                .build()

            val effect = CameraEffect.Builder(
                CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE,
                mainExecutor,
                processor,
            ) { t -> Log.w(TAG, "effect error", t) }.build()

            val features = buildList {
                if (config.targetFps >= 60) add(GroupableFeature.FPS_60)
                if (hdr) add(GroupableFeature.HDR_HLG10)
                if (config.previewSize.width >= 3840) add(GroupableFeature.UHD_RECORDING)
            }

            val session = SessionConfig.Builder(preview)
                .setRequiredFeatureGroup(features)
                .setEffects(listOf(effect))
                .build()

            registry.currentState = Lifecycle.State.STARTED
            val camera = runCatching { p.bindToLifecycle(this, selector, session) }
                .onFailure { Log.w(TAG, "bind failed for $features; falling back", it) }
                .getOrNull()

            camera?.cameraControl?.setZoomRatio(currentZoom)
            // Hand the preview its surface via our processor's output (wired by CameraEffect);
            // the on-screen Surface is provided by the UI through setPreviewSurface() below.
        }
    }

    @Volatile private var previewSurface: Surface? = null

    /** UI supplies the on-screen Surface; CameraX renders preview into it via the effect. */
    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        // Re-bind handled by the next start(); the processor adds/removes preview outputs.
    }

    override fun stop() {
        mainExecutor.execute {
            provider?.unbindAll()
            processor.setEncoderSurface(null)
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    override fun setZoom(ratio: Float) {
        currentZoom = ratio
        provider // zoom applied on next bind / immediately if a camera is bound
    }

    private companion object { const val TAG = "CameraXController" }
}
```

> IMPLEMENTATION NOTE: the exact Preview-surface wiring (whether the on-screen surface comes through a `Preview.SurfaceProvider` or as an extra `SurfaceOutput` of the effect) is finalized against the CameraX 1.6.1 sample during build. The contract for callers — `start/stop/setZoom` + `setPreviewSurface` — does not change regardless. Verify zoom is applied live via `camera.cameraControl.setZoomRatio` once a `Camera` is bound; cache `Camera` to apply zoom without rebinding.

- [ ] **Step 3: Build-verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/CameraXController.kt \
        app/src/main/java/com/example/plohoystream/camera/CameraController.kt
git commit -m "feat(camera): CameraXController with Feature-Group session + custom lifecycle"
```

---

## Task 7: Controller factory + LivePipeline wiring (build-verify)

Swap `LivePipeline` to construct the controller through a factory so we can flip Camera2 ⇄ CameraX during bring-up, and expose the generated menu + encoder gate from the pipeline.

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/camera/CameraControllerFactory.kt`
- Modify: `app/src/main/java/com/example/plohoystream/stream/LivePipeline.kt`

- [ ] **Step 1: Add the factory**

```kotlin
package com.example.plohoystream.camera

import android.content.Context

/** Chooses the capture backend. CameraX is default; Camera2 retained for fallback during bring-up. */
object CameraControllerFactory {
    @Volatile var useCameraX: Boolean = true

    fun create(context: Context): CameraController =
        if (useCameraX) CameraXController(context) else Camera2Controller(context)
}
```

- [ ] **Step 2: Wire LivePipeline to the factory + expose the menu**

In `LivePipeline.kt`:
- Change the field type from `Camera2Controller` to the interface:

```kotlin
    lateinit var camera: CameraController
        private set
```

- Replace the construction line `camera = Camera2Controller(appCtx)` with:

```kotlin
            camera = CameraControllerFactory.create(appCtx)
```

- After `val cameras = CameraEnumerator.enumerate(appCtx)` and the existing HEVC caps block, add the generated menu + codec options (camera probe is built per-CameraInfo at preview time in the UI; here expose the encoder gate and codec options which are device-global):

```kotlin
    lateinit var encoderGate: com.example.plohoystream.camera.EncoderGate
        private set
    lateinit var codecOptions: List<com.example.plohoystream.camera.VideoCodecOption>
        private set
```

and inside `ensureInit`, after `hdrAvailable = …`:

```kotlin
            encoderGate = com.example.plohoystream.camera.EncoderGateFactory.fromDevice()
            codecOptions = com.example.plohoystream.camera.CaptureMenu.codecOptions(encoderGate)
```

- Add the import: `import com.example.plohoystream.camera.CameraController`.

- [ ] **Step 3: Build-verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/CameraControllerFactory.kt \
        app/src/main/java/com/example/plohoystream/stream/LivePipeline.kt
git commit -m "feat(camera): controller factory + expose encoder gate/codec menu from pipeline"
```

---

## Task 8: Generate the per-camera quality menu in the Viewfinder (build-verify; device-backed)

Build the camera probe from the active CameraX `CameraInfo`, generate the `QualityOption` list, and feed it to the settings UI. Preview surface flows to `CameraXController.setPreviewSurface`.

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt`

- [ ] **Step 1: Route the preview surface to the controller**

In `Viewfinder`, where `onSurface = { surface = it }` is set on `CameraPreview`, also forward it to the controller when it's the CameraX backend:

```kotlin
                    onSurface = {
                        surface = it
                        (controller as? com.example.plohoystream.camera.CameraXController)?.setPreviewSurface(it)
                    },
```

- [ ] **Step 2: Generate the menu from the active camera + encoder gate**

After `val cameras = remember { CameraEnumerator.enumerate(context) }`, obtain the CameraX `CameraInfo` for the current facing (via `ProcessCameraProvider`), build the probe, and compute the menu. Because `CameraInfo` requires the provider, compute the menu in a `LaunchedEffect(facing)` and hold it in state:

```kotlin
    var qualityOptions by remember { mutableStateOf<List<com.example.plohoystream.camera.QualityOption>>(emptyList()) }
    LaunchedEffect(facing) {
        val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
        val selector = if (facing == Facing.FRONT) androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA
                       else androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
        val info = provider.getCameraInfo(selector)
        val probe = com.example.plohoystream.camera.CameraXComboProbe(info)
        qualityOptions = com.example.plohoystream.camera.CaptureMenu.generate(
            com.example.plohoystream.camera.CaptureMenu.CANDIDATES, probe, com.example.plohoystream.stream.LivePipeline.encoderGate,
        )
    }
```

> NOTE: `provider.getCameraInfo(selector)` returns a `CameraInfo` without binding (available in CameraX 1.5+). Confirm the exact accessor name against 1.6.1; if unavailable, query via a transient `bindToLifecycle` on a CREATED lifecycle and unbind.

- [ ] **Step 3: Pass `qualityOptions` to the settings panel**

Thread `qualityOptions` (and `LivePipeline.codecOptions`) down to `SettingsPanel` → `VideoSettings` (Task 9 consumes them). For now just hoist them; the wiring compiles even before VideoSettings reads them.

- [ ] **Step 4: Build-verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt
git commit -m "feat(camera): generate per-camera capability menu in viewfinder"
```

---

## Task 9: VideoSettings reads the dynamic menu (build-verify)

Replace the static `VideoQuality.Presets` chips with the generated `qualityOptions`, the codec chips with `LivePipeline.codecOptions`, and add the HDR toggle with the disabled-reason behavior.

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/ui/settings/VideoSettings.kt`
- Modify: `app/src/main/java/com/example/plohoystream/ui/settings/SettingsPanel.kt` (thread params)

- [ ] **Step 1: Accept the dynamic options as parameters**

Change `VideoSettings(viewModel)` to `VideoSettings(viewModel, qualityOptions: List<QualityOption>, codecOptions: List<VideoCodecOption>)` and thread them from `SettingsPanel` (which receives them from `Viewfinder`).

- [ ] **Step 2: Render resolution/fps from `qualityOptions`**

Replace the `VideoQuality.Presets.forEach { … }` block with:

```kotlin
                qualityOptions.forEach { opt ->
                    FilterChip(
                        selected = ui.settings.quality.height == opt.height && ui.settings.quality.fps == opt.fps,
                        onClick = {
                            if (!ui.isActive) viewModel.setQuality(
                                VideoQuality(opt.width, opt.height, opt.fps,
                                    videoBitrate = bitrateFor(opt), audioBitrate = 128_000)
                            )
                        },
                        enabled = !ui.isActive,
                        label = { Text("${opt.height}p ${opt.fps}") },
                    )
                }
```

Add a small `private fun bitrateFor(opt: QualityOption): Int` mapping size/fps → the existing preset bitrates (720p30→3.5M, 1080p30→6M, 1080p60→9M, 4K→20M), clamped later by the engine.

- [ ] **Step 3: Render codec chips from `codecOptions`**

Replace the hardcoded `listOf(CodecOverride.Auto … )` with a map over `codecOptions` → the matching `CodecOverride`, so HEVC only shows when present.

- [ ] **Step 4: Add the HDR toggle with disabled reason**

```kotlin
            val selectedOpt = qualityOptions.firstOrNull {
                it.height == ui.settings.quality.height && it.fps == ui.settings.quality.fps
            }
            val hdrState = selectedOpt?.let { CaptureMenu.hdrToggleState(it) }
                ?: HdrToggleState(false, "Select a resolution first")
            Row(/* … */) {
                Text("HDR (HLG10)", color = OnSurfaceWhite, style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = ui.settings.hdrEnabled && hdrState.enabled,
                    onCheckedChange = { if (hdrState.enabled && !ui.isActive) viewModel.setHdrEnabled(it) },
                    enabled = hdrState.enabled && !ui.isActive,
                )
            }
            if (!hdrState.enabled) Text(hdrState.reason ?: "", color = OnSurfaceMuted,
                style = MaterialTheme.typography.labelMedium)
```

> If `viewModel.setHdrEnabled` / `settings.hdrEnabled` don't exist yet, add them mirroring `setCodecOverride` (the engine already consumes `StreamConfig.hdrEnabled`). Confirm in `StreamViewModel`/`Settings.kt` during build.

- [ ] **Step 5: Build-verify**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/ui/settings/VideoSettings.kt \
        app/src/main/java/com/example/plohoystream/ui/settings/SettingsPanel.kt
git commit -m "feat(ui): capability-driven quality/codec menu + HDR toggle with reason"
```

---

## Task 10: On-device parity smoke (manual gate)

No code unless a defect is found. Run on a physical device (ideally one Pixel/Samsung that supports 60+HDR, plus the Solana Seeker for the fallback path).

- [ ] **Step 1: Preview** — launch app; preview is smooth, correct orientation, not stretched; front/back flip works.
- [ ] **Step 2: 60fps** — on a 60-capable device, select 1080p60; confirm preview is visibly 60fps (smooth motion) and `logcat` shows the feature group bound. On the Seeker, confirm 1080p60 is **not offered** (correctly absent).
- [ ] **Step 3: HDR** — where HDR-capable, toggle HDR; confirm HEVC Main10 path engages and stream/record is HDR; where not capable, the toggle is disabled with the reason.
- [ ] **Step 4: Codec** — changing AVC/HEVC visibly changes the negotiated codec at go-live (`logcat`), and HEVC only appears when the device has the encoder.
- [ ] **Step 5: Stream egress** — go live to Twitch (RTMP) and to an SRT relay; both connect and play; bitrate/health update.
- [ ] **Step 6: Background streaming** — start streaming, background the app; confirm the stream continues (custom lifecycle keeps capture feeding the encoder).
- [ ] **Step 7: Recovery** — cover/uncover or trigger a disconnect; confirm CameraX recovers without a crash.
- [ ] **Step 8: Zoom/lens** — lens chips and pinch/zoom apply live.

- [ ] **Step 9: Record the results** in a commit (docs only):

```bash
git commit --allow-empty -m "test(camera): on-device CameraX parity smoke — <PASS/notes per step>"
```

---

## Task 11: Retire Camera2 backend (only after Task 10 passes)

**Files:**
- Delete: `app/src/main/java/com/example/plohoystream/camera/Camera2Controller.kt`
- Modify: `app/src/main/java/com/example/plohoystream/camera/CameraControllerFactory.kt`
- Modify: `app/src/main/java/com/example/plohoystream/camera/CameraCapabilities.kt` (remove now-unused `chooseFpsRange`/`fpsRange` plumbing superseded by the menu), `CameraModels.kt` (`fpsRange`/`targetFps` if unused)

- [ ] **Step 1: Remove the Camera2 branch from the factory**

```kotlin
object CameraControllerFactory {
    fun create(context: Context): CameraController = CameraXController(context)
}
```

- [ ] **Step 2: Delete `Camera2Controller.kt` and prune dead capability plumbing**

Remove `Camera2Controller.kt`. In `CameraModels.kt`/`CameraCapabilities.kt`, delete the `fpsRange`/`chooseFpsRange`/`targetFps` members now superseded by `CaptureMenu`. Keep `CameraInfo`/`CameraConfig` fields still used by the Viewfinder (lenses, previewSize, sensorOrientation).

- [ ] **Step 3: Build + unit tests**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all tests pass.

- [ ] **Step 4: Final on-device sanity** — one more quick launch + go-live to confirm nothing regressed after deletion.

- [ ] **Step 5: Commit**

```bash
git add -u app/src/main/java/com/example/plohoystream/camera/
git commit -m "refactor(camera): retire Camera2 backend after CameraX parity"
```

---

## Self-Review Notes (for the executor)

- The only strictly unit-tested logic is Tasks 1–3 (menu generation, candidates, encoder gate). Tasks 4–9 are framework/GL/UI verified by build + the Task 10 on-device gate — this is intentional; CameraX/GL/lifecycle cannot be meaningfully unit-tested without a device.
- CameraX 1.6.1 symbol names (`SessionConfig.Builder`, `setRequiredFeatureGroup`, `GroupableFeature.*`, `isSessionConfigSupported`, `getCameraInfo(selector)`, `CameraEffect.Builder`) are used per the 1.6 release notes; confirm against the resolved javadoc at build time and adjust **inside the wrapper classes only** — the `CameraController`/`CameraComboProbe`/`CaptureMenu` interfaces shield all callers.
- Do NOT delete `Camera2Controller` before Task 10 passes (rollback safety).
- The GL renderer body (Task 5 `GlRenderer`) ports the official CameraX `OpenGlRenderer`/`DefaultSurfaceProcessor` sample; do not hand-roll EGL from scratch.
