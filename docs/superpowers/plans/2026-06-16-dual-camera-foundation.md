# Dual-Camera Foundation (Plan 1 of 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lay the testable, device-agnostic foundation for dual-camera PiP — the `PipLayout` layout model (pure math) and a concurrent-camera capability probe — then run the probe on-device to confirm whether front+back simultaneous capture is actually supported, before building the heavy capture/compositor pipeline.

**Architecture:** Pure Kotlin domain types with unit tests, plus a thin CameraX wrapper. The probe mirrors the existing `ProcessCameraProvider.getInstance(context).get()` pattern already used in `Viewfinder` (the quality-menu probe at Viewfinder.kt:115-128). Dual-camera UI state is held LOCALLY in `Viewfinder` (like `facing`/`zoom`), NOT in `StreamViewModel`/`StreamUiState` — this is a deliberate deviation from the spec, which predates discovering that camera UI state is Viewfinder-local in this codebase.

**Tech Stack:** Kotlin, Jetpack Compose, CameraX (`androidx.camera`), JUnit (JVM unit tests in `app/src/test/`). Build via Gradle wrapper. Device: physical Seeker connected via adb (id `SM02E4060314107`).

This is Plan 1 of 3 for Feature 2 (dual-camera PiP) from spec `docs/superpowers/specs/2026-06-16-obs-scene-switch-and-dual-camera-design.md`. Plan 2 (concurrent capture + two-input GL compositor) and Plan 3 (draggable PiP UI) are written AFTER Task 3 here confirms device support, because their design depends on whether/at-what-resolution the device can run both cameras.

---

### Task 1: PipLayout layout model + pure math

The draggable PiP needs a normalized layout value with pure, testable math (clamp, corner-snap, size cycle, primary swap, rect computation). No Android dependencies — all unit-testable, mirroring the `CameraControls` pure-function pattern.

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/camera/PipLayout.kt`
- Test: `app/src/test/java/com/example/plohoystream/camera/PipLayoutTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/plohoystream/camera/PipLayoutTest.kt`:

```kotlin
package com.example.plohoystream.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipLayoutTest {
    private val eps = 1e-4f

    @Test fun pipRect_isSquareInNormalizedSpace_matchingSizeFraction() {
        val l = PipLayout(x = 0.1f, y = 0.2f, size = PipSize.M)
        val r = PipLayout.pipRect(l)
        assertEquals(0.1f, r.left, eps)
        assertEquals(0.2f, r.top, eps)
        assertEquals(0.1f + PipSize.M.widthFraction, r.right, eps)
        assertEquals(0.2f + PipSize.M.widthFraction, r.bottom, eps)
    }

    @Test fun clampInBounds_keepsRectFullyInsideUnitSquare() {
        val wf = PipSize.L.widthFraction
        val tooFar = PipLayout(x = 0.95f, y = -0.3f, size = PipSize.L)
        val c = PipLayout.clampInBounds(tooFar)
        assertTrue(c.x in 0f..(1f - wf) + eps)
        assertTrue(c.y in 0f..(1f - wf) + eps)
        assertEquals(1f - wf, c.x, eps)   // pushed back from the right edge
        assertEquals(0f, c.y, eps)        // pushed down from above the top
    }

    @Test fun snapToNearestCorner_choosesCornerByCenterQuadrant() {
        val wf = PipSize.M.widthFraction
        val m = 0.04f
        // Center near bottom-right -> BOTTOM_RIGHT corner.
        val br = PipLayout.snapToNearestCorner(PipLayout(x = 0.7f, y = 0.7f, size = PipSize.M), m)
        assertEquals(1f - wf - m, br.x, eps)
        assertEquals(1f - wf - m, br.y, eps)
        // Center near top-left -> TOP_LEFT corner.
        val tl = PipLayout.snapToNearestCorner(PipLayout(x = 0.05f, y = 0.05f, size = PipSize.M), m)
        assertEquals(m, tl.x, eps)
        assertEquals(m, tl.y, eps)
    }

    @Test fun cycleSize_goesSMLthenWrapsAndStaysInBounds() {
        var l = PipLayout(x = 0.9f, y = 0.9f, size = PipSize.S)
        l = PipLayout.cycleSize(l); assertEquals(PipSize.M, l.size)
        l = PipLayout.cycleSize(l); assertEquals(PipSize.L, l.size)
        l = PipLayout.cycleSize(l); assertEquals(PipSize.S, l.size)
        // After growing in a corner, the rect must still be inside the unit square.
        val grown = PipLayout.cycleSize(PipLayout(x = 0.95f, y = 0.95f, size = PipSize.S))
        assertTrue(grown.x in 0f..(1f - grown.size.widthFraction) + eps)
        assertTrue(grown.y in 0f..(1f - grown.size.widthFraction) + eps)
    }

    @Test fun swapPrimary_togglesPrimaryFrontOnly() {
        val l = PipLayout(primaryFront = false)
        val s = PipLayout.swapPrimary(l)
        assertTrue(s.primaryFront)
        assertEquals(l.x, s.x, eps)
        assertEquals(l.y, s.y, eps)
        assertEquals(l.size, s.size)
        assertFalse(PipLayout.swapPrimary(s).primaryFront)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.PipLayoutTest"`
Expected: FAIL to COMPILE — `PipLayout`, `PipSize` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/example/plohoystream/camera/PipLayout.kt`:

```kotlin
package com.example.plohoystream.camera

/** PiP inset size as a fraction of the composited frame's smaller-axis span (see [PipLayout]). */
enum class PipSize(val widthFraction: Float) { S(0.22f), M(0.30f), L(0.40f) }

/** Normalized rectangle in the composited frame: (0,0) top-left .. (1,1) bottom-right. */
data class PipRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Layout of the draggable picture-in-picture inset for dual-camera mode. All coordinates are
 * normalized to the composited frame: (0,0) top-left .. (1,1) bottom-right. [x],[y] is the PiP's
 * top-left corner. The inset is kept square in normalized space (so it renders with the same aspect
 * as the output frame). [primaryFront] true means the FRONT camera is the full-frame main feed and
 * the PiP shows BACK; false means BACK is main and the PiP shows FRONT.
 */
data class PipLayout(
    val x: Float = 0.62f,
    val y: Float = 0.05f,
    val size: PipSize = PipSize.M,
    val primaryFront: Boolean = false,
) {
    companion object {
        /** The PiP rectangle in normalized frame coordinates. */
        fun pipRect(l: PipLayout): PipRect {
            val wf = l.size.widthFraction
            return PipRect(left = l.x, top = l.y, right = l.x + wf, bottom = l.y + wf)
        }

        /** Push [l] so its rectangle lies fully inside the unit square. */
        fun clampInBounds(l: PipLayout): PipLayout {
            val wf = l.size.widthFraction
            val maxXY = (1f - wf).coerceAtLeast(0f)
            return l.copy(x = l.x.coerceIn(0f, maxXY), y = l.y.coerceIn(0f, maxXY))
        }

        /** Snap [l] to whichever corner its center is closest to, inset by [margin]. */
        fun snapToNearestCorner(l: PipLayout, margin: Float = 0.04f): PipLayout {
            val wf = l.size.widthFraction
            val cx = l.x + wf / 2f
            val cy = l.y + wf / 2f
            val near = (1f - wf - margin).coerceAtLeast(0f)
            val targetX = if (cx < 0.5f) margin else near
            val targetY = if (cy < 0.5f) margin else near
            return l.copy(x = targetX, y = targetY)
        }

        /** Cycle S -> M -> L -> S, re-clamping so a grown inset stays on-screen. */
        fun cycleSize(l: PipLayout): PipLayout {
            val next = when (l.size) {
                PipSize.S -> PipSize.M
                PipSize.M -> PipSize.L
                PipSize.L -> PipSize.S
            }
            return clampInBounds(l.copy(size = next))
        }

        /** Flip which camera is the full-frame main feed vs the inset. */
        fun swapPrimary(l: PipLayout): PipLayout = l.copy(primaryFront = !l.primaryFront)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.PipLayoutTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/PipLayout.kt \
        app/src/test/java/com/example/plohoystream/camera/PipLayoutTest.kt
git commit -m "feat(camera): PipLayout model + pure layout math for dual-camera PiP

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Concurrent-camera capability probe

CameraX reports which camera combinations can run concurrently via
`ProcessCameraProvider.getAvailableConcurrentCameraInfos()` (a `List<List<CameraInfo>>`). The
decision logic ("is there a front+back combo?") is pure and unit-testable; the CameraX read is a
thin un-testable wrapper. CameraX guarantees each concurrent stream up to 720p, so we record 720p
as the per-camera ceiling constant for the later capture plan.

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/camera/ConcurrentCameraProbe.kt`
- Test: `app/src/test/java/com/example/plohoystream/camera/ConcurrentCameraProbeTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/plohoystream/camera/ConcurrentCameraProbeTest.kt`:

```kotlin
package com.example.plohoystream.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrentCameraProbeTest {
    @Test fun supportsFrontBack_trueWhenACombinationHasBothFacings() {
        val combos = listOf(
            listOf(Facing.BACK, Facing.BACK),     // dual-back combo, not what we need
            listOf(Facing.FRONT, Facing.BACK),    // the one we want
        )
        assertTrue(ConcurrentCameraProbe.supportsFrontBack(combos))
    }

    @Test fun supportsFrontBack_falseWhenNoComboMixesFacings() {
        val combos = listOf(
            listOf(Facing.BACK, Facing.BACK),
            listOf(Facing.FRONT, Facing.FRONT),
        )
        assertFalse(ConcurrentCameraProbe.supportsFrontBack(combos))
    }

    @Test fun supportsFrontBack_falseWhenNoConcurrentCombosReported() {
        assertFalse(ConcurrentCameraProbe.supportsFrontBack(emptyList()))
    }

    @Test fun maxDualHeight_is720() {
        assertEquals(720, ConcurrentCameraProbe.MAX_DUAL_HEIGHT)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.ConcurrentCameraProbeTest"`
Expected: FAIL to COMPILE — `ConcurrentCameraProbe` unresolved.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/example/plohoystream/camera/ConcurrentCameraProbe.kt`:

```kotlin
package com.example.plohoystream.camera

import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider

/**
 * Decides whether the device can run a FRONT + BACK camera simultaneously, from CameraX's reported
 * concurrent-camera combinations. CameraX guarantees each concurrent stream up to 720p, so dual mode
 * caps per-camera capture at [MAX_DUAL_HEIGHT]; many devices report no concurrent combinations at
 * all, in which case dual mode is unavailable.
 */
object ConcurrentCameraProbe {
    /** CameraX concurrent-mode per-camera resolution guarantee (height, px). */
    const val MAX_DUAL_HEIGHT = 720

    /** True if any reported combination contains both a FRONT and a BACK camera. Pure + testable. */
    fun supportsFrontBack(combos: List<List<Facing>>): Boolean =
        combos.any { it.contains(Facing.FRONT) && it.contains(Facing.BACK) }

    /**
     * Map CameraX's concurrent combinations to plain [Facing] lists. Thin CameraX wrapper (not unit
     * tested); feed its result to [supportsFrontBack]. Runs the blocking provider read on the
     * caller's thread — call it off the main thread.
     */
    fun facingCombos(provider: ProcessCameraProvider): List<List<Facing>> =
        provider.availableConcurrentCameraInfos.map { combo ->
            combo.mapNotNull { info ->
                when (info.lensFacing) {
                    CameraSelector.LENS_FACING_FRONT -> Facing.FRONT
                    CameraSelector.LENS_FACING_BACK -> Facing.BACK
                    else -> null
                }
            }
        }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.camera.ConcurrentCameraProbeTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Verify the whole module still compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (confirms the CameraX `availableConcurrentCameraInfos` / `lensFacing` APIs resolve against this project's camera-core version).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/ConcurrentCameraProbe.kt \
        app/src/test/java/com/example/plohoystream/camera/ConcurrentCameraProbeTest.kt
git commit -m "feat(camera): ConcurrentCameraProbe — detect front+back concurrent support

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Run the probe on-device and record the answer

Wire the probe into `Viewfinder` (mirroring the existing quality-menu probe `LaunchedEffect`) to log whether the connected Seeker supports front+back concurrent capture. This is the de-risking deliverable: its logcat output decides the shape of Plan 2.

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt` (add a one-shot `LaunchedEffect(Unit)` near the existing quality-probe effect around line 114-130)

- [ ] **Step 1: Add the probe LaunchedEffect**

In `Viewfinder.kt`, immediately AFTER the existing `LaunchedEffect(facing) { ... qualityOptions ... }` block (it ends near line 130), insert this new effect. It uses the already-imported `LocalContext` value `context` and the same off-main provider pattern:

```kotlin
    // One-shot capability probe: can this device run FRONT + BACK concurrently? Logged so we can
    // confirm dual-camera feasibility on real hardware before enabling the mode. Held locally
    // (like facing/zoom); a later plan surfaces it as a toggle.
    var dualSupported by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(context).get()
                val combos = com.example.plohoystream.camera.ConcurrentCameraProbe.facingCombos(provider)
                combos to com.example.plohoystream.camera.ConcurrentCameraProbe.supportsFrontBack(combos)
            }
        }.onSuccess { (combos, supported) ->
            dualSupported = supported
            android.util.Log.i(
                "Viewfinder",
                "concurrent-camera probe: frontBackSupported=$supported combos=$combos",
            )
        }.onFailure { android.util.Log.w("Viewfinder", "concurrent-camera probe failed", it) }
    }
```

Note: `dualSupported` is intentionally unused by UI in this plan (a later plan reads it). If the
Kotlin compiler flags it as unused, that is acceptable; do NOT delete it — it documents the seam.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full unit suite (regression)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt
git commit -m "feat(camera): log front+back concurrent-camera support probe on the viewfinder

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 5: Run on the device and capture the answer**

```bash
adb -s SM02E4060314107 logcat -c
./gradlew :app:installDebug
adb -s SM02E4060314107 shell monkey -p com.example.plohoystream -c android.intent.category.LAUNCHER 1
sleep 6
adb -s SM02E4060314107 logcat -d -s Viewfinder | grep "concurrent-camera probe"
```

Expected: a log line `concurrent-camera probe: frontBackSupported=<true|false> combos=[...]`.

- [ ] **Step 6: Record the result**

Write the observed `frontBackSupported` value and the `combos` list into this plan file (append a
"## Probe result (Seeker)" section). This is the input to Plan 2:
- If `true`: Plan 2 builds concurrent capture (capped at 720p per camera) + the two-input GL compositor.
- If `false`: dual-camera is not feasible on this device; Plan 2 is reframed (e.g. fast camera-flip "switching" rather than true simultaneous capture) and discussed with the user before proceeding.

---

## Probe result (Seeker)

Captured 2026-06-16 on the connected Seeker (`SM02E4060314107`):

```
Viewfinder: concurrent-camera probe: frontBackSupported=true combos=[[BACK, FRONT]]
```

**Verdict: SUPPORTED.** The device reports exactly one concurrent combination — `[BACK, FRONT]` —
so true simultaneous front+back capture is feasible. Plan 2 proceeds with concurrent capture
(`bindToConcurrentCamera`) capped at ≤720p per camera + the two-input GL compositor.

## After this plan

Once Task 3's result is known, return to writing-plans for **Plan 2 (concurrent capture + two-input GL compositor)** — which will require reading the full `EgressSurfaceProcessor.kt`, `GlRenderer.kt`, `CameraStreamEngine.kt`, and `LivePipeline.kt` to write concrete, no-placeholder code steps — and then **Plan 3 (draggable PiP UI)** built on the `PipLayout` model from Task 1 here.
