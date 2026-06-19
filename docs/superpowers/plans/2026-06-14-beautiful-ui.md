# Beautiful UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the bare functional viewfinder into a landscape, full-screen, glass-over-camera streaming UI with physics-based motion, semantic-color real-time stats (bitrate / connection health / audio level wired from native), and a Moblin-style settings panel.

**Architecture:** A landscape-locked, immersive `MainActivity` hosts a decomposed Compose tree (`Viewfinder` orchestrates a `CameraPreview` + a glass `ControlRail` + a sliding `SettingsPanel`). Real egress stats flow native→Kotlin: C++ `RtmpClient`/`StreamSession` track `bytesSent`/`queueDepth`, JNI exposes them, and `CameraStreamEngine`'s existing ~250ms poll loop feeds pure-Kotlin `BitrateMeter`/`deriveHealth`/`rms16` logic onto new `StateFlow`s consumed by `StreamViewModel`/`StreamUiState`. Settings apply at go-live only (encoder is never reconfigured mid-stream), matching Moblin's dimmed-while-live pattern.

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose (Material 3 Expressive, BOM bumped in Phase 1), Android minSdk 35 / targetSdk 36, C++17 native egress (CMake), JUnit4 host unit tests, GoogleTest host C++ tests.

**Spec:** docs/superpowers/specs/2026-06-14-beautiful-ui-design.md

---

## File Structure

### Created

| File | Responsibility |
|---|---|
| `app/src/main/java/com/example/plohoystream/ui/theme/Color.kt` | Semantic color tokens: `LiveRed`, `HealthGood/Warn/Bad`, dark surfaces, glass tints. |
| `app/src/main/java/com/example/plohoystream/ui/theme/Type.kt` | Expressive type scale (`PlohoyTypography`). |
| `app/src/main/java/com/example/plohoystream/ui/theme/Motion.kt` | Shared spring specs: `SignatureSpring` (medium-bouncy), `PressSpring` (snappy), color-shift `ColorSpring`. |
| `app/src/main/java/com/example/plohoystream/ui/theme/Theme.kt` | `PlohoyTheme` — dark color scheme + typography wrapper. |
| `app/src/main/java/com/example/plohoystream/ui/theme/GlassSurface.kt` | `Modifier.glassSurface()` + `GlassSurface` composable: translucent scrim + 1dp hairline + rounded corners (scrim only, no real blur). |
| `app/src/main/java/com/example/plohoystream/stream/StreamStats.kt` | Pure stats logic: `BitrateMeter` (EMA kbps), `ConnectionHealth` enum, `deriveHealth(...)`, `formatElapsed(...)`. |
| `app/src/main/java/com/example/plohoystream/stream/AudioLevel.kt` | Pure `rms16(pcm, lengthBytes): Float` 0..1 normalized RMS of LE 16-bit PCM. |
| `app/src/main/java/com/example/plohoystream/stream/VideoQuality.kt` | `VideoQuality(width, height, fps, videoBitrate, audioBitrate)` data class + presets. |
| `app/src/main/java/com/example/plohoystream/stream/CodecOverride.kt` | `enum CodecOverride { Auto, ForceHevc, ForceAvc }` + pure `resolveRequest(...)`. |
| `app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt` | Orchestrates landscape split: preview left (shrinks when settings open), rail right, settings panel. |
| `app/src/main/java/com/example/plohoystream/ui/viewfinder/ControlRail.kt` | Right-bar glass rail: status cluster (top) + actions (bottom). |
| `app/src/main/java/com/example/plohoystream/ui/viewfinder/LiveStatusCluster.kt` | LIVE pill (pulsing dot) + elapsed timer. |
| `app/src/main/java/com/example/plohoystream/ui/viewfinder/HealthIndicator.kt` | Color-shifting health dot + bitrate text. |
| `app/src/main/java/com/example/plohoystream/ui/viewfinder/AudioMeter.kt` | Color-zoned audio level bar (green→amber→red). |
| `app/src/main/java/com/example/plohoystream/ui/viewfinder/ZoomChips.kt` | Lens/zoom chip row. |
| `app/src/main/java/com/example/plohoystream/ui/viewfinder/GoLiveButton.kt` | Go Live ring ↔ Stop square morph + connecting pulse. |
| `app/src/main/java/com/example/plohoystream/ui/PermissionGate.kt` | Restyled permission prompt (extracted from `StreamScreen`). |
| `app/src/main/java/com/example/plohoystream/ui/settings/SettingsPanel.kt` | Nav-stack host driven by `settingsRoute`. |
| `app/src/main/java/com/example/plohoystream/ui/settings/DestinationSettings.kt` | Server URL + masked stream key. |
| `app/src/main/java/com/example/plohoystream/ui/settings/VideoSettings.kt` | Resolution / fps / codec / bitrate + footer help. |
| `app/src/main/java/com/example/plohoystream/ui/settings/AudioSettings.kt` | Audio bitrate (AAC fixed). |
| `app/src/main/java/com/example/plohoystream/ui/settings/CameraSettings.kt` | Lens / zoom / flip / HDR (only when supported). |
| `app/src/main/java/com/example/plohoystream/ui/settings/AboutSettings.kt` | Version + reset-to-defaults. |
| `app/src/main/java/com/example/plohoystream/ui/settings/SettingsRoute.kt` | `enum class SettingsRoute` for panel nav. |
| `app/src/test/java/com/example/plohoystream/stream/StreamStatsTest.kt` | Unit tests for `BitrateMeter`, `deriveHealth`, `formatElapsed`. |
| `app/src/test/java/com/example/plohoystream/stream/AudioLevelTest.kt` | Unit tests for `rms16`. |
| `app/src/test/java/com/example/plohoystream/stream/VideoQualityTest.kt` | Unit tests for `CodecOverride.resolveRequest`. |
| `docs/superpowers/BEAUTIFUL_UI_SMOKE_TEST.md` | Device verification results. |

### Modified

| File | Change |
|---|---|
| `gradle/libs.versions.toml` | Bump `composeBom` to the Material 3 Expressive (material3 1.4.x) line. |
| `app/src/main/AndroidManifest.xml` | `android:screenOrientation="landscape"` on `MainActivity`. |
| `app/src/main/java/com/example/plohoystream/MainActivity.kt` | `PlohoyTheme`, immersive insets controller, host `Viewfinder`, route new StateFlows. |
| `app/src/main/cpp/core/rtmp_client.h` / `.cpp` | `bytesSent_` atomic + `Send()` helper + `bytesSent()` accessor. |
| `app/src/main/cpp/core/media_queue.h` | `size_t size() const` accessor. |
| `app/src/main/cpp/core/stream_session.h` / `.cpp` | `bytesSent_`/`queueDepth_` atomics updated in `run()`; accessors. |
| `app/src/main/cpp/native-lib.cpp` | `nativeBytesSent` / `nativeQueueDepth` JNI. |
| `app/src/main/cpp/test/test_rtmp_client.cpp` | Test `bytesSent()` increases. |
| `app/src/main/cpp/test/test_media_queue.cpp` | Test `size()`. |
| `app/src/main/cpp/test/test_stream_session.cpp` | Test `bytesSent()` after Live. |
| `app/src/main/java/com/example/plohoystream/stream/RtmpStreamer.kt` | Add `bytesSent()` / `queueDepth()`. |
| `app/src/main/java/com/example/plohoystream/stream/NativeRtmpStreamer.kt` | `external` decls + impls. |
| `app/src/test/java/com/example/plohoystream/stream/FakeRtmpStreamer.kt` | Settable `bytesSentValue` / `queueDepthValue`. |
| `app/src/main/java/com/example/plohoystream/stream/AudioEncoder.kt` | `onLevel: (Float) -> Unit` callback in `feedLoop`. |
| `app/src/main/java/com/example/plohoystream/stream/StreamEngine.kt` | `VideoStreamEngine`: add `bitrateKbps`/`health`/`audioLevel` flows. |
| `app/src/main/java/com/example/plohoystream/stream/CameraStreamEngine.kt` | Sample stats in poll loop; expose new flows; route `onLevel`; codec override. |
| `app/src/test/java/com/example/plohoystream/stream/CameraStreamEngineTest.kt` | Keep green; cover new flows + override. |
| `app/src/main/java/com/example/plohoystream/stream/StreamConfig.kt` | Add `quality: VideoQuality`, `codecOverride: CodecOverride`. |
| `app/src/main/java/com/example/plohoystream/stream/StreamUiState.kt` | Add stats + settings-mirror + `settingsRoute` fields. |
| `app/src/main/java/com/example/plohoystream/stream/StreamViewModel.kt` | New setters, richer `goLive`, wire engine flows. |
| `app/src/test/java/com/example/plohoystream/stream/StreamUiStateTest.kt` | Keep green; cover new derived fields if any. |
| `app/src/test/java/com/example/plohoystream/stream/StreamViewModelTest.kt` | Keep green; cover new setters + propagation. |
| `app/src/main/java/com/example/plohoystream/ui/StreamScreen.kt` | Slim to a `PermissionGate`/`Viewfinder` switch (decomposed away). |

---

## Phase 1 — Design-system foundation & app chrome

### Task 1.1 — Bump Compose BOM to Material 3 Expressive

**Files:**
- Modify: `gradle/libs.versions.toml` (line 11: `composeBom = "2025.06.00"`)

- [ ] **Step 1: Determine the BOM that ships Material 3 Expressive (material3 1.4.x).** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:dependencies --configuration debugRuntimeClasspath 2>/dev/null | grep -m1 "compose.material3:material3 "` to record the current resolved material3 version (expect `1.3.x` from BOM `2025.06.00`). Material 3 Expressive (`MotionScheme`, expressive components) ships in `androidx.compose.material3:material3:1.4.x`, carried by Compose BOM `2025.08.00` or later. Use the latest stable BOM line `2025.10.00`. (Verify it resolves material3 to `1.4.x` in Step 3; if the BOM does not expose `1.4.x`, fall back to pinning `androidx-material3` with `version = "1.4.0"` directly in `[libraries]`.)
- [ ] **Step 2: Edit the version.** In `gradle/libs.versions.toml`, change line 11 from `composeBom = "2025.06.00"` to `composeBom = "2025.10.00"`.
- [ ] **Step 3: Verify the resolved material3 version is 1.4.x.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:dependencies --configuration debugRuntimeClasspath 2>/dev/null | grep -m1 "compose.material3:material3 "`. Expect a line containing `1.4.`. If it shows `1.3.x` or lower, the BOM is wrong — bump higher until `1.4.x` resolves, or apply the Step-1 fallback (add `version = "1.4.0"` to the `androidx-material3` library entry).
- [ ] **Step 4: Build to confirm the toolchain accepts the bump.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit.** `git add gradle/libs.versions.toml && git commit -m "build(ui): bump Compose BOM to the Material 3 Expressive (material3 1.4.x) line"`

### Task 1.2 — Color tokens

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/theme/Color.kt`

- [ ] **Step 1: Write the semantic color tokens.** Create `app/src/main/java/com/example/plohoystream/ui/theme/Color.kt`:
```kotlin
package com.example.plohoystream.ui.theme

import androidx.compose.ui.graphics.Color

// Semantic color: reserved for data that is changing, never decoration.
val LiveRed = Color(0xFFFF3B30)        // LIVE indicator + Stop
val HealthGood = Color(0xFF34C759)     // green
val HealthWarn = Color(0xFFFFCC00)     // amber
val HealthBad = Color(0xFFFF3B30)      // red

// Dark surfaces (letterbox + panels).
val SurfaceBlack = Color(0xFF000000)
val SurfaceDark = Color(0xFF0E0E10)
val SurfaceElevated = Color(0xFF1C1C1E)
val OnSurfaceWhite = Color(0xFFFFFFFF)
val OnSurfaceMuted = Color(0xFFB0B0B5)

// Glass tints (translucent dark scrim + hairline).
val GlassScrim = Color(0x66101012)     // ~40% dark scrim over letterbox
val GlassOverVideo = Color(0x99000000) // stronger scrim for the LIVE pill over the feed
val GlassHairline = Color(0x33FFFFFF)  // 20% white 1dp border
```
- [ ] **Step 2: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit.** `git add app/src/main/java/com/example/plohoystream/ui/theme/Color.kt && git commit -m "feat(ui): semantic color tokens (live red, health, glass tints, dark surfaces)"`

### Task 1.3 — Type scale

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/theme/Type.kt`

- [ ] **Step 1: Write the expressive type scale.** Create `app/src/main/java/com/example/plohoystream/ui/theme/Type.kt`:
```kotlin
package com.example.plohoystream.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Expressive scale: large readable timer + confident labels. Default font family (no custom
// font asset shipped this milestone); weights carry the expressiveness.
val PlohoyTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 48.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)
```
- [ ] **Step 2: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit.** `git add app/src/main/java/com/example/plohoystream/ui/theme/Type.kt && git commit -m "feat(ui): expressive type scale"`

### Task 1.4 — Shared motion springs

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/theme/Motion.kt`

- [ ] **Step 1: Write the shared spring specs.** Create `app/src/main/java/com/example/plohoystream/ui/theme/Motion.kt`:
```kotlin
package com.example.plohoystream.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

// One shared spring spec for physical consistency across the whole app.

/** Signature medium-bouncy spring — the preview-shrink ⇄ settings move. */
fun <T> signatureSpring() = spring<T>(
    dampingRatio = 0.65f,
    stiffness = Spring.StiffnessMediumLow,
)

/** Snappy press-feedback spring — control scale-down on touch. */
fun <T> pressSpring() = spring<T>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessHigh,
)

/** Color transitions (health dot / audio meter / LIVE) — never snap. */
fun colorSpring() = spring<Color>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow,
)

/** Convenience aliases for the common animated types. */
val SignatureDpSpring get() = signatureSpring<Dp>()
val SignatureFloatSpring get() = signatureSpring<Float>()
val PressFloatSpring get() = pressSpring<Float>()
```
- [ ] **Step 2: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit.** `git add app/src/main/java/com/example/plohoystream/ui/theme/Motion.kt && git commit -m "feat(ui): shared spring specs (signature, press, color)"`

### Task 1.5 — PlohoyTheme

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/theme/Theme.kt`

- [ ] **Step 1: Write the theme.** Create `app/src/main/java/com/example/plohoystream/ui/theme/Theme.kt`:
```kotlin
package com.example.plohoystream.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val PlohoyDarkColors = darkColorScheme(
    primary = OnSurfaceWhite,
    onPrimary = SurfaceBlack,
    background = SurfaceBlack,
    onBackground = OnSurfaceWhite,
    surface = SurfaceDark,
    onSurface = OnSurfaceWhite,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = OnSurfaceMuted,
    error = LiveRed,
)

/** App theme: dark, expressive. Wrap the whole UI tree. */
@Composable
fun PlohoyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PlohoyDarkColors,
        typography = PlohoyTypography,
        content = content,
    )
}
```
- [ ] **Step 2: Replace the bare `MaterialTheme` in `MainActivity`.** In `app/src/main/java/com/example/plohoystream/MainActivity.kt`, change the import `import androidx.compose.material3.MaterialTheme` to `import com.example.plohoystream.ui.theme.PlohoyTheme`, and change the `setContent { MaterialTheme { ... } }` wrapper (around line 66) to `setContent { PlohoyTheme { ... } }`.
- [ ] **Step 3: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 4: Commit.** `git add app/src/main/java/com/example/plohoystream/ui/theme/Theme.kt app/src/main/java/com/example/plohoystream/MainActivity.kt && git commit -m "feat(ui): PlohoyTheme dark scheme; replace bare MaterialTheme"`

### Task 1.6 — GlassSurface

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/theme/GlassSurface.kt`

- [ ] **Step 1: Write the glass modifier + composable + previews.** Create `app/src/main/java/com/example/plohoystream/ui/theme/GlassSurface.kt`:
```kotlin
package com.example.plohoystream.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Frosted-glass panel: translucent dark scrim + 1dp hairline border + rounded corners.
 * Scrim only — no real backdrop blur (the rail sits in the letterbox bars, not over video).
 */
fun Modifier.glassSurface(
    cornerRadius: Dp = 20.dp,
    scrim: Color = GlassScrim,
    hairline: Color = GlassHairline,
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .background(color = scrim, shape = shape)
        .border(width = 1.dp, color = hairline, shape = shape)
}

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    scrim: Color = GlassScrim,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.glassSurface(cornerRadius = cornerRadius, scrim = scrim).padding(12.dp)) {
        content()
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun GlassSurfacePreview() {
    PlohoyTheme {
        GlassSurface(modifier = Modifier.size(160.dp, 80.dp)) {
            Text("Glass", color = OnSurfaceWhite)
        }
    }
}
```
- [ ] **Step 2: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit.** `git add app/src/main/java/com/example/plohoystream/ui/theme/GlassSurface.kt && git commit -m "feat(ui): GlassSurface frosted panel (scrim + hairline, no blur)"`

### Task 1.7 — App chrome: landscape lock + immersive fullscreen

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (the `<activity android:name=".MainActivity">` element, lines 24–32)
- Modify: `app/src/main/java/com/example/plohoystream/MainActivity.kt` (`onCreate`, lines 25–27)

- [ ] **Step 1: Lock the activity to landscape.** In `app/src/main/AndroidManifest.xml`, add `android:screenOrientation="landscape"` to the `MainActivity` `<activity>` element so it reads:
```xml
        <activity
            android:name=".MainActivity"
            android:screenOrientation="landscape"
            android:exported="true">
```
- [ ] **Step 2: Enable sticky-immersive fullscreen.** In `app/src/main/java/com/example/plohoystream/MainActivity.kt`, add the imports
```kotlin
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
```
and inside `onCreate`, immediately after `super.onCreate(savedInstanceState)` (line 26), insert:
```kotlin
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPES
        }
```
- [ ] **Step 3: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 4: Device smoke (manual).** Install and launch: `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:installDebug && adb -s SM02E4060314107 shell am start -n com.example.plohoystream/.MainActivity`. Expected: app opens in landscape, no status/nav bars visible; swiping from an edge transiently reveals bars then re-hides them. (No automated assertion; note the observation.)
- [ ] **Step 5: Commit.** `git add app/src/main/AndroidManifest.xml app/src/main/java/com/example/plohoystream/MainActivity.kt && git commit -m "feat(ui): landscape lock + sticky-immersive fullscreen"`

---

## Phase 2 — Real-stats plumbing (native → Kotlin)

### Task 2.1 — C++ RtmpClient bytesSent

**Files:**
- Modify: `app/src/main/cpp/core/rtmp_client.h` (class `RtmpClient`, lines 38–67)
- Modify: `app/src/main/cpp/core/rtmp_client.cpp` (routes all `t_.Write(...)` through `Send()`)
- Modify: `app/src/main/cpp/test/test_rtmp_client.cpp` (add a test)

- [ ] **Step 1: Write the failing test.** In `app/src/main/cpp/test/test_rtmp_client.cpp`, append at the end of the file (before the final newline):
```cpp
TEST(RtmpClient, BytesSentIncreasesOnPublish) {
    StubTransport t;
    StreamParams p; p.app="live"; p.tcUrl="rtmp://h/live"; p.streamKey="5"; p.host="h";
    RtmpClient c(t, p);
    c.Begin();
    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;
    c.OnBytes(s0s1s2);
    c.OnBytes(MakeResultSuccess());
    c.OnBytes(MakeCreateStreamResult(1));
    c.OnBytes(MakePublishStart());
    ASSERT_EQ(c.state(), RtmpState::Publishing);
    uint64_t before = c.bytesSent();
    EXPECT_GT(before, 0u);                      // handshake + commands already counted
    c.SendVideoConfig({0,0,0,1, 0x67,0x42,0x00,0x1e, 0,0,0,1, 0x68,0xce,0x3c,0x80});
    c.SendVideo({0,0,0,1, 0x65, 0x88}, true, 0, 0);
    EXPECT_GT(c.bytesSent(), before);
}
```
- [ ] **Step 2: Run, expect FAIL.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream/app/src/main/cpp && cmake -S test -B build-test >/dev/null && cmake --build build-test 2>&1 | tail -20`. Expect a compile error: `'class ps::RtmpClient' has no member named 'bytesSent'`.
- [ ] **Step 3: Add the atomic + Send helper + accessor to the header.** In `app/src/main/cpp/core/rtmp_client.h`, add `#include <atomic>` to the includes (top of file). In the `RtmpClient` `public:` section, after `Codec negotiatedCodec() const { return negotiatedCodec_; }` (line 46), add:
```cpp
    uint64_t bytesSent() const { return bytesSent_.load(); }
```
In the `private:` section, after `void afterHandshake();` (line 54), add:
```cpp
    void Send(const Bytes& b) { t_.Write(b); bytesSent_ += b.size(); }
```
and in the member list, after `Transport& t_;` (line 55), add:
```cpp
    std::atomic<uint64_t> bytesSent_{0};
```
- [ ] **Step 4: Route all writes through Send().** In `app/src/main/cpp/core/rtmp_client.cpp`, replace every `t_.Write(...)` call with `Send(...)`. There are exactly these occurrences:
  - `Begin()`: `RtmpHandshake h; t_.Write(h.BuildC0C1());` → `RtmpHandshake h; Send(h.BuildC0C1());`
  - `sendCommand(...)`: `t_.Write(ChunkEncode(3, 0x14, msid, 0, body, outChunkSize_));` → `Send(ChunkEncode(3, 0x14, msid, 0, body, outChunkSize_));`
  - `OnBytes(...)` handshake: `t_.Write(h.BuildC2(s0s1));` → `Send(h.BuildC2(s0s1));`
  - `OnBytes(...)` set-chunk-size: `{ Bytes cs; PutU32BE(cs, 4096); t_.Write(ChunkEncode(2, 0x01, 0, 0, cs, 128)); }` → `... Send(ChunkEncode(2, 0x01, 0, 0, cs, 128)); }`
  - `OnBytes(...)` onMetaData: `t_.Write(ChunkEncode(8, 0x12, streamId_, 0, BuildOnMetaData(...), outChunkSize_));` → `Send(ChunkEncode(8, 0x12, streamId_, 0, BuildOnMetaData(...), outChunkSize_));`
  - `SendVideoConfig(const Bytes& csd)`: `t_.Write(ChunkEncode(5, 0x09, streamId_, 0, body, outChunkSize_));` → `Send(ChunkEncode(5, 0x09, streamId_, 0, body, outChunkSize_));`
  - `SendVideo(...)`: `t_.Write(ChunkEncode(5, 0x09, streamId_, dtsMs, body, outChunkSize_));` → `Send(ChunkEncode(5, 0x09, streamId_, dtsMs, body, outChunkSize_));`
  - `SendAudioConfig(...)`: `t_.Write(ChunkEncode(4, 0x08, streamId_, 0, body, outChunkSize_));` → `Send(ChunkEncode(4, 0x08, streamId_, 0, body, outChunkSize_));`
  - `SendAudio(...)`: `t_.Write(ChunkEncode(4, 0x08, streamId_, ptsMs, body, outChunkSize_));` → `Send(ChunkEncode(4, 0x08, streamId_, ptsMs, body, outChunkSize_));`
- [ ] **Step 5: Run, expect PASS.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream/app/src/main/cpp && cmake --build build-test >/dev/null && ctest --test-dir build-test --output-on-failure -R RtmpClient`. Expect all `RtmpClient.*` tests pass, including `BytesSentIncreasesOnPublish`.
- [ ] **Step 6: Commit.** `git add app/src/main/cpp/core/rtmp_client.h app/src/main/cpp/core/rtmp_client.cpp app/src/main/cpp/test/test_rtmp_client.cpp && git commit -m "feat(native): RtmpClient tracks bytesSent via Send() helper"`

### Task 2.2 — MediaQueue size() accessor

**Files:**
- Modify: `app/src/main/cpp/core/media_queue.h` (class `MediaQueue`, lines 22–60)
- Modify: `app/src/main/cpp/test/test_media_queue.cpp`

- [ ] **Step 1: Write the failing test.** In `app/src/main/cpp/test/test_media_queue.cpp`, append:
```cpp
TEST(MediaQueue, SizeReflectsPending) {
    MediaQueue q(8);
    EXPECT_EQ(q.size(), 0u);
    q.Push(MediaItem{MediaItem::Video, {1}, false, 0, 0});
    q.Push(MediaItem{MediaItem::Video, {2}, false, 0, 0});
    EXPECT_EQ(q.size(), 2u);
    MediaItem out;
    ASSERT_TRUE(q.Pop(out));
    EXPECT_EQ(q.size(), 1u);
}
```
- [ ] **Step 2: Run, expect FAIL.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream/app/src/main/cpp && cmake --build build-test 2>&1 | tail -20`. Expect `'class ps::MediaQueue' has no member named 'size'`.
- [ ] **Step 3: Add the accessor.** In `app/src/main/cpp/core/media_queue.h`, after the `dropped()` method (lines 48–51), add:
```cpp
    size_t size() const {
        std::unique_lock<std::mutex> lk(m_);
        return q_.size();
    }
```
- [ ] **Step 4: Run, expect PASS.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream/app/src/main/cpp && cmake --build build-test >/dev/null && ctest --test-dir build-test --output-on-failure -R MediaQueue`. Expect `MediaQueue.SizeReflectsPending` passes.
- [ ] **Step 5: Commit.** `git add app/src/main/cpp/core/media_queue.h app/src/main/cpp/test/test_media_queue.cpp && git commit -m "feat(native): MediaQueue thread-safe size() accessor"`

### Task 2.3 — StreamSession bytesSent / queueDepth

**Files:**
- Modify: `app/src/main/cpp/core/stream_session.h` (class `StreamSession`, lines 16–48)
- Modify: `app/src/main/cpp/core/stream_session.cpp` (`run()` egress loop, lines 39–92)
- Modify: `app/src/main/cpp/test/test_stream_session.cpp`

- [ ] **Step 1: Write the failing test.** In `app/src/main/cpp/test/test_stream_session.cpp`, append to the `StreamSession, ReachesLiveAndWritesVideo` style — add a new test at the end of the file:
```cpp
TEST(StreamSession, BytesSentIncreasesAfterLive) {
    auto owned = std::make_unique<StubTransport>();
    StubTransport* stub = owned.get();
    PreloadPublishHandshake(*stub);
    StreamParams p; p.host="h"; p.app="app"; p.streamKey="k"; p.tcUrl="rtmp://h/app";
    auto held = std::make_shared<std::unique_ptr<StubTransport>>(std::move(owned));
    StreamSession s(p, [held]() mutable -> std::unique_ptr<Transport> { return std::move(*held); });
    s.Start();
    for (int i = 0; i < 200 && s.state() != SessionState::Live; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    ASSERT_EQ(s.state(), SessionState::Live);
    s.SendVideoConfig({0,0,0,1, 0x67,0x42,0x00,0x1e, 0,0,0,1, 0x68,0xce,0x3c,0x80});
    s.SendVideo({0,0,0,1, 0x65, 0x88}, true, 0, 0);
    uint64_t sent = 0;
    for (int i = 0; i < 100 && sent == 0; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        sent = s.bytesSent();
    }
    EXPECT_GT(sent, 0u);
    s.Stop();
}
```
- [ ] **Step 2: Run, expect FAIL.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream/app/src/main/cpp && cmake --build build-test 2>&1 | tail -20`. Expect `'class ps::StreamSession' has no member named 'bytesSent'`.
- [ ] **Step 3: Add atomics + accessors to the header.** In `app/src/main/cpp/core/stream_session.h`, in the `public:` section after `Codec negotiatedCodec() const { return negotiated_.load(); }` (line 29), add:
```cpp
    uint64_t bytesSent() const { return bytesSent_.load(); }
    int queueDepth() const { return queueDepth_.load(); }
```
In the member list after `std::atomic<Codec> negotiated_{Codec::Avc};` (line 46), add:
```cpp
    std::atomic<uint64_t> bytesSent_{0};
    std::atomic<int> queueDepth_{0};
```
- [ ] **Step 4: Update them in the egress loop.** In `app/src/main/cpp/core/stream_session.cpp`, inside `run()`'s `while (queue_.Pop(item))` loop, immediately after the `switch (item.kind) { ... }` block closes and before the `if (!transport_->connected())` check (i.e. after the closing `}` of the switch, line 88), add:
```cpp
        bytesSent_.store(client.bytesSent());
        queueDepth_.store(static_cast<int>(queue_.size()));
```
- [ ] **Step 5: Run, expect PASS.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream/app/src/main/cpp && cmake --build build-test >/dev/null && ctest --test-dir build-test --output-on-failure -R StreamSession`. Expect `StreamSession.BytesSentIncreasesAfterLive` passes alongside the existing StreamSession tests.
- [ ] **Step 6: Commit.** `git add app/src/main/cpp/core/stream_session.h app/src/main/cpp/core/stream_session.cpp app/src/main/cpp/test/test_stream_session.cpp && git commit -m "feat(native): StreamSession exposes bytesSent + queueDepth"`

### Task 2.4 — JNI bridge for bytesSent / queueDepth

**Files:**
- Modify: `app/src/main/cpp/native-lib.cpp` (after `nativeNegotiatedCodec`, lines 52–55)
- Modify: `app/src/main/java/com/example/plohoystream/stream/NativeRtmpStreamer.kt`

- [ ] **Step 1: Add the JNI functions.** In `app/src/main/cpp/native-lib.cpp`, after the `nativeNegotiatedCodec` function (ends line 55), add:
```cpp
JNIEXPORT jlong JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeBytesSent(JNIEnv*, jobject, jlong h) {
    return h ? static_cast<jlong>(Self(h)->bytesSent()) : 0;
}

JNIEXPORT jint JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeQueueDepth(JNIEnv*, jobject, jlong h) {
    return h ? static_cast<jint>(Self(h)->queueDepth()) : 0;
}
```
- [ ] **Step 2: Add the external decls + lock-guarded impls.** In `app/src/main/java/com/example/plohoystream/stream/NativeRtmpStreamer.kt`, add the `external` declarations after `private external fun nativeNegotiatedCodec(handle: Long): Int` (line 47):
```kotlin
    private external fun nativeBytesSent(handle: Long): Long
    private external fun nativeQueueDepth(handle: Long): Int
```
and add the public impls after `override fun negotiatedCodec()` (line 23):
```kotlin
    override fun bytesSent(): Long = lock.withLock { if (handle != 0L) nativeBytesSent(handle) else 0L }
    override fun queueDepth(): Int = lock.withLock { if (handle != 0L) nativeQueueDepth(handle) else 0 }
```
(These will not compile until the interface gains the methods in Task 2.5 — that is fine; commit lands after Task 2.5's interface change in Step 4 below. To keep this task self-contained, do the interface edit here too.)
- [ ] **Step 3: Add the methods to the `RtmpStreamer` interface.** In `app/src/main/java/com/example/plohoystream/stream/RtmpStreamer.kt`, after `fun negotiatedCodec(): VideoCodecType` (line 9), add:
```kotlin
    /** Total bytes written to the socket since session start (smoothed into kbps by the engine). */
    fun bytesSent(): Long
    /** Pending egress queue depth — the backpressure signal for connection health. */
    fun queueDepth(): Int
```
- [ ] **Step 4: Update `FakeRtmpStreamer`.** In `app/src/test/java/com/example/plohoystream/stream/FakeRtmpStreamer.kt`, add settable backing vars after `private var state = 0` (line 13):
```kotlin
    var bytesSentValue: Long = 0L
    var queueDepthValue: Int = 0
```
and add the overrides (e.g. after `override fun negotiatedCodec()`, line 21):
```kotlin
    override fun bytesSent(): Long = bytesSentValue
    override fun queueDepth(): Int = queueDepthValue
```
- [ ] **Step 5: Build (Kotlin compiles; native compiled in app build).** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug` (expect `BUILD SUCCESSFUL`) and `./gradlew :app:testDebugUnitTest` (expect existing tests still green — the Fake now satisfies the interface).
- [ ] **Step 6: Commit.** `git add app/src/main/cpp/native-lib.cpp app/src/main/java/com/example/plohoystream/stream/NativeRtmpStreamer.kt app/src/main/java/com/example/plohoystream/stream/RtmpStreamer.kt app/src/test/java/com/example/plohoystream/stream/FakeRtmpStreamer.kt && git commit -m "feat(stream): bytesSent/queueDepth across JNI + RtmpStreamer seam"`

### Task 2.5 — StreamStats: BitrateMeter, ConnectionHealth, deriveHealth, formatElapsed (STRICT TDD)

**Files:**
- Create: `app/src/test/java/com/example/plohoystream/stream/StreamStatsTest.kt`
- Create: `app/src/main/java/com/example/plohoystream/stream/StreamStats.kt`

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/example/plohoystream/stream/StreamStatsTest.kt`:
```kotlin
package com.example.plohoystream.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamStatsTest {
    @Test fun bitrateMeter_firstSampleIsZero() {
        val m = BitrateMeter()
        assertEquals(0, m.update(bytesSent = 0L, timestampMs = 0L))
    }

    @Test fun bitrateMeter_computesKbpsFromDelta() {
        val m = BitrateMeter(alpha = 1.0) // no smoothing: raw rate
        m.update(bytesSent = 0L, timestampMs = 0L)
        // 125_000 bytes over 1000 ms = 1_000_000 bits/s = 1000 kbps.
        assertEquals(1000, m.update(bytesSent = 125_000L, timestampMs = 1000L))
    }

    @Test fun bitrateMeter_emaSmoothsTowardNewRate() {
        val m = BitrateMeter(alpha = 0.5)
        m.update(0L, 0L)
        val first = m.update(125_000L, 1000L)   // raw 1000, ema 0.5*1000 = 500
        assertEquals(500, first)
        val second = m.update(250_000L, 2000L)  // raw 1000, ema 0.5*500 + 0.5*1000 = 750
        assertEquals(750, second)
    }

    @Test fun bitrateMeter_ignoresNonPositiveTimeDelta() {
        val m = BitrateMeter(alpha = 1.0)
        m.update(0L, 1000L)
        assertEquals(0, m.update(125_000L, 1000L)) // same ts -> no division
    }

    @Test fun deriveHealth_goodWhenQueueLowAndBitrateNearTarget() {
        assertEquals(ConnectionHealth.Good, deriveHealth(queueDepth = 10, queueCapacity = 256, actualKbps = 5800, targetKbps = 6000))
    }

    @Test fun deriveHealth_warnWhenQueueOver25Percent() {
        assertEquals(ConnectionHealth.Warn, deriveHealth(queueDepth = 70, queueCapacity = 256, actualKbps = 6000, targetKbps = 6000))
    }

    @Test fun deriveHealth_warnWhenBitrateUnder80Percent() {
        assertEquals(ConnectionHealth.Warn, deriveHealth(queueDepth = 0, queueCapacity = 256, actualKbps = 4500, targetKbps = 6000))
    }

    @Test fun deriveHealth_badWhenQueueOver60Percent() {
        assertEquals(ConnectionHealth.Bad, deriveHealth(queueDepth = 160, queueCapacity = 256, actualKbps = 6000, targetKbps = 6000))
    }

    @Test fun deriveHealth_badWhenBitrateUnder50Percent() {
        assertEquals(ConnectionHealth.Bad, deriveHealth(queueDepth = 0, queueCapacity = 256, actualKbps = 2900, targetKbps = 6000))
    }

    @Test fun deriveHealth_goodWhenTargetUnknown() {
        // targetKbps <= 0 -> bitrate ratio not evaluated, judge by queue only.
        assertEquals(ConnectionHealth.Good, deriveHealth(queueDepth = 5, queueCapacity = 256, actualKbps = 100, targetKbps = 0))
    }

    @Test fun formatElapsed_underOneHourIsMmSs() {
        assertEquals("00:00", formatElapsed(0L))
        assertEquals("00:09", formatElapsed(9_000L))
        assertEquals("01:05", formatElapsed(65_000L))
        assertEquals("59:59", formatElapsed(3_599_000L))
    }

    @Test fun formatElapsed_oneHourPlusIsHMmSs() {
        assertEquals("1:00:00", formatElapsed(3_600_000L))
        assertEquals("2:03:04", formatElapsed((2 * 3600 + 3 * 60 + 4) * 1000L))
    }

    @Test fun formatElapsed_negativeClampsToZero() {
        assertTrue(formatElapsed(-5L).startsWith("00:00"))
    }
}
```
- [ ] **Step 2: Run, expect FAIL.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.StreamStatsTest" 2>&1 | tail -20`. Expect a compilation failure: `unresolved reference: BitrateMeter` (and `ConnectionHealth`, `deriveHealth`, `formatElapsed`).
- [ ] **Step 3: Implement the pure logic.** Create `app/src/main/java/com/example/plohoystream/stream/StreamStats.kt`:
```kotlin
package com.example.plohoystream.stream

/**
 * Smooths egress kbps from successive (bytesSent, timestampMs) samples via an EMA.
 * [alpha] is the new-sample weight (1.0 = no smoothing). Not thread-safe; call from one loop.
 */
class BitrateMeter(private val alpha: Double = 0.3) {
    private var lastBytes: Long = -1
    private var lastMs: Long = 0
    private var ema: Double = 0.0

    /** Returns the current smoothed bitrate in kbps (integer). */
    fun update(bytesSent: Long, timestampMs: Long): Int {
        if (lastBytes < 0) { lastBytes = bytesSent; lastMs = timestampMs; return 0 }
        val dt = timestampMs - lastMs
        if (dt <= 0) return ema.toInt()
        val db = bytesSent - lastBytes
        lastBytes = bytesSent; lastMs = timestampMs
        val rawKbps = (db.coerceAtLeast(0) * 8.0) / dt   // bytes*8 / ms == kbits/s == kbps
        ema = alpha * rawKbps + (1 - alpha) * ema
        return ema.toInt()
    }
}

enum class ConnectionHealth { Good, Warn, Bad }

/**
 * Derives connection health from egress backpressure (queue depth vs capacity) and the
 * actual-vs-target bitrate ratio. Thresholds:
 *  - queue > 60% capacity OR actual < 50% target  -> Bad
 *  - queue > 25% capacity OR actual < 80% target  -> Warn
 *  - else                                         -> Good
 * When [targetKbps] <= 0 the bitrate ratio is not evaluated (judge by queue only).
 */
fun deriveHealth(queueDepth: Int, queueCapacity: Int, actualKbps: Int, targetKbps: Int): ConnectionHealth {
    val queueRatio = if (queueCapacity > 0) queueDepth.toDouble() / queueCapacity else 0.0
    val bitrateRatio = if (targetKbps > 0) actualKbps.toDouble() / targetKbps else 1.0
    return when {
        queueRatio > 0.60 || bitrateRatio < 0.50 -> ConnectionHealth.Bad
        queueRatio > 0.25 || bitrateRatio < 0.80 -> ConnectionHealth.Warn
        else -> ConnectionHealth.Good
    }
}

/** Formats elapsed [millis] as `H:MM:SS` (>= 1h) or `MM:SS` (< 1h). Negative clamps to 0. */
fun formatElapsed(millis: Long): String {
    val totalSec = (millis.coerceAtLeast(0L)) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
```
- [ ] **Step 4: Run, expect PASS.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.StreamStatsTest" 2>&1 | tail -15`. Expect all 13 tests pass.
- [ ] **Step 5: Commit.** `git add app/src/main/java/com/example/plohoystream/stream/StreamStats.kt app/src/test/java/com/example/plohoystream/stream/StreamStatsTest.kt && git commit -m "feat(stream): pure stats logic (BitrateMeter EMA, deriveHealth, formatElapsed)"`

### Task 2.6 — AudioLevel rms16 (STRICT TDD) + AudioEncoder onLevel wiring

**Files:**
- Create: `app/src/test/java/com/example/plohoystream/stream/AudioLevelTest.kt`
- Create: `app/src/main/java/com/example/plohoystream/stream/AudioLevel.kt`
- Modify: `app/src/main/java/com/example/plohoystream/stream/AudioEncoder.kt` (constructor + `feedLoop`, lines 17–70)

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/example/plohoystream/stream/AudioLevelTest.kt`:
```kotlin
package com.example.plohoystream.stream

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

class AudioLevelTest {
    private fun le16(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            out[i * 2] = (samples[i].toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((samples[i].toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test fun silenceIsZero() {
        val pcm = le16(ShortArray(512) { 0 })
        assertEquals(0f, rms16(pcm, pcm.size), 1e-4f)
    }

    @Test fun fullScaleIsOne() {
        val pcm = le16(ShortArray(512) { Short.MAX_VALUE })
        assertEquals(1f, rms16(pcm, pcm.size), 1e-3f)
    }

    @Test fun sineIsAboutPointSevenOfPeak() {
        // A full-scale sine has RMS = peak / sqrt(2) ~= 0.707.
        val pcm = le16(ShortArray(1024) { i ->
            (Short.MAX_VALUE * sin(2.0 * Math.PI * i / 64.0)).toInt().toShort()
        })
        assertEquals(0.707f, rms16(pcm, pcm.size), 0.02f)
    }

    @Test fun honoursLengthBytes() {
        // Only the first 2 samples (loud) are counted; the tail (silent) is ignored.
        val loud = ShortArray(4) { Short.MAX_VALUE } + ShortArray(60) { 0 }
        val pcm = le16(loud)
        assertEquals(1f, rms16(pcm, 8), 1e-3f) // 8 bytes = 4 samples, all full-scale
    }
}
```
- [ ] **Step 2: Run, expect FAIL.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.AudioLevelTest" 2>&1 | tail -15`. Expect `unresolved reference: rms16`.
- [ ] **Step 3: Implement `rms16`.** Create `app/src/main/java/com/example/plohoystream/stream/AudioLevel.kt`:
```kotlin
package com.example.plohoystream.stream

import kotlin.math.sqrt

/**
 * Normalized 0..1 RMS of little-endian 16-bit PCM over the first [lengthBytes] bytes.
 * 1.0 == full-scale (peak ±32767). Used to drive the audio meter; pure, no Android deps.
 */
fun rms16(pcm: ByteArray, lengthBytes: Int): Float {
    val n = (lengthBytes.coerceIn(0, pcm.size)) / 2
    if (n == 0) return 0f
    var sumSq = 0.0
    var i = 0
    while (i < n) {
        val lo = pcm[i * 2].toInt() and 0xFF
        val hi = pcm[i * 2 + 1].toInt()           // signed: preserves the high byte's sign
        val sample = (hi shl 8) or lo
        val v = sample.toDouble() / 32768.0
        sumSq += v * v
        i++
    }
    return sqrt(sumSq / n).toFloat().coerceIn(0f, 1f)
}
```
- [ ] **Step 4: Run, expect PASS.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.AudioLevelTest" 2>&1 | tail -15`. Expect 4 tests pass.
- [ ] **Step 5: Wire `onLevel` into `AudioEncoder` (throttled ~10/sec).** In `app/src/main/java/com/example/plohoystream/stream/AudioEncoder.kt`, add the constructor param after `bitRate: Int = 128_000,` (line 20):
```kotlin
    private val onLevel: (Float) -> Unit = {},
```
and in `feedLoop()` (lines 57–70), after `if (read <= 0) continue` (line 61), add throttled level emission:
```kotlin
            val now = System.nanoTime()
            if (now - lastLevelNs >= 100_000_000L) {   // ~10 Hz
                lastLevelNs = now
                onLevel(rms16(pcm, read))
            }
```
and declare the throttle field next to the thread fields (after `private var drainThread: Thread? = null`, line 33):
```kotlin
    @Volatile private var lastLevelNs: Long = 0L
```
- [ ] **Step 6: Build.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug` (expect `BUILD SUCCESSFUL`) and `./gradlew :app:testDebugUnitTest` (expect all green — `onLevel` defaults to a no-op so `MainActivity`'s existing construction still compiles).
- [ ] **Step 7: Commit.** `git add app/src/main/java/com/example/plohoystream/stream/AudioLevel.kt app/src/test/java/com/example/plohoystream/stream/AudioLevelTest.kt app/src/main/java/com/example/plohoystream/stream/AudioEncoder.kt && git commit -m "feat(stream): rms16 audio level + AudioEncoder onLevel callback"`

### Task 2.7 — Engine wiring: sample stats in the existing poll loop, expose flows

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/stream/StreamEngine.kt` (`VideoStreamEngine`, lines 16–25)
- Modify: `app/src/main/java/com/example/plohoystream/stream/CameraStreamEngine.kt` (flows + poll loop + audioLevel sink, lines 38–102)
- Modify: `app/src/test/java/com/example/plohoystream/stream/CameraStreamEngineTest.kt`

- [ ] **Step 1: Write the failing engine test for the new flows.** In `app/src/test/java/com/example/plohoystream/stream/CameraStreamEngineTest.kt`, add a test exercising the bitrate/health flow (append before the final `}`):
```kotlin
    @Test fun live_pollsBytesSentAndQueueDepth_intoStats() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer, targetVideoBitrate = 6_000_000)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        streamer.emitState(2); advanceTimeBy(150); runCurrent()
        assertEquals(StreamState.Live, e.state.value)
        // Simulate ~125 KB sent over the next poll tick at the default 6 Mbps target.
        streamer.bytesSentValue = 125_000L
        streamer.queueDepthValue = 200      // > 60% of 256 -> Bad
        advanceTimeBy(300); runCurrent()
        assertTrue(e.bitrateKbps.value >= 0)
        assertEquals(ConnectionHealth.Bad, e.health.value)
        e.stop()
    }
```
Also update the `engine(...)` helper to thread a target bitrate through (add param `targetVideoBitrate: Int = 6_000_000` and pass it to the `CameraStreamEngine` constructor as `videoBitrate = targetVideoBitrate`).
- [ ] **Step 2: Run, expect FAIL.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.CameraStreamEngineTest" 2>&1 | tail -20`. Expect `unresolved reference: bitrateKbps` / `health` / `videoBitrate`.
- [ ] **Step 3: Extend the `VideoStreamEngine` interface.** In `app/src/main/java/com/example/plohoystream/stream/StreamEngine.kt`, add to the `VideoStreamEngine` interface (after `val activeHdr: StateFlow<Boolean>`, line 23):
```kotlin
    /** Smoothed egress bitrate in kbps (0 when not streaming). */
    val bitrateKbps: StateFlow<Int>
    /** Connection health derived from queue backpressure + actual-vs-target bitrate. */
    val health: StateFlow<ConnectionHealth>
    /** Normalized 0..1 microphone level (0 when not streaming). */
    val audioLevel: StateFlow<Float>
```
- [ ] **Step 4: Add the flows + sampling to `CameraStreamEngine`.** In `app/src/main/java/com/example/plohoystream/stream/CameraStreamEngine.kt`:
  - Add a constructor param after `private val fps: Int = 30,` (line 33): `private val videoBitrate: Int = 6_000_000,`
  - After the `_activeHdr` flow block (lines 44–45), add:
    ```kotlin
    private val _bitrateKbps = MutableStateFlow(0)
    override val bitrateKbps: StateFlow<Int> = _bitrateKbps.asStateFlow()

    private val _health = MutableStateFlow(ConnectionHealth.Good)
    override val health: StateFlow<ConnectionHealth> = _health.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    override val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val bitrateMeter = BitrateMeter()
    private val queueCapacity = 256   // mirrors native MediaQueue capacity
    ```
  - Add a method so `startMedia` can route the audio level back (next to `publishEncoderSurface`, line 52):
    ```kotlin
    /** Lets the media-setup lambda forward AudioEncoder.onLevel into the engine's flow. */
    fun publishAudioLevel(level: Float) { _audioLevel.value = level }
    ```
  - In the poll loop, inside the `2 -> { ... }` branch right after `_state.value = StreamState.Live` (line 82) and before the closing `}`, add stat sampling:
    ```kotlin
                        val kbps = bitrateMeter.update(s.bytesSent(), System.currentTimeMillis())
                        _bitrateKbps.value = kbps
                        _health.value = deriveHealth(
                            queueDepth = s.queueDepth(),
                            queueCapacity = queueCapacity,
                            actualKbps = kbps,
                            targetKbps = videoBitrate / 1000,
                        )
    ```
  - In `stop()` (lines 93–102), after `_activeHdr.value = false` (line 99), reset the new flows:
    ```kotlin
        _bitrateKbps.value = 0
        _health.value = ConnectionHealth.Good
        _audioLevel.value = 0f
    ```
- [ ] **Step 5: Run, expect PASS.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.CameraStreamEngineTest" 2>&1 | tail -15`. Expect all CameraStreamEngine tests pass (existing + the new one).
- [ ] **Step 6: Route `AudioEncoder.onLevel` through `MainActivity`'s `startMedia`.** In `app/src/main/java/com/example/plohoystream/MainActivity.kt`, in the `AudioEncoder(...)` construction inside `startMedia` (lines 49–52), add `onLevel = { lvl -> eng.publishAudioLevel(lvl) },` after `onFrame = { aac, pts -> streamer.sendAudio(aac, pts) },`. (Build verifies; covered in Phase 6 integration too.)
- [ ] **Step 7: Build + full unit tests.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest 2>&1 | tail -10`. Expect `BUILD SUCCESSFUL` and all tests pass.
- [ ] **Step 8: Commit.** `git add app/src/main/java/com/example/plohoystream/stream/StreamEngine.kt app/src/main/java/com/example/plohoystream/stream/CameraStreamEngine.kt app/src/main/java/com/example/plohoystream/MainActivity.kt app/src/test/java/com/example/plohoystream/stream/CameraStreamEngineTest.kt && git commit -m "feat(stream): engine samples bitrate/health/audioLevel in the existing poll loop"`

---

## Phase 3 — State & config model

### Task 3.1 — VideoQuality

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/stream/VideoQuality.kt`
- Create: `app/src/test/java/com/example/plohoystream/stream/VideoQualityTest.kt` (shared with Task 3.2)

- [ ] **Step 1: Write `VideoQuality`.** Create `app/src/main/java/com/example/plohoystream/stream/VideoQuality.kt`:
```kotlin
package com.example.plohoystream.stream

/** Encoder/egress quality applied at go-live (no mid-stream reconfig). */
data class VideoQuality(
    val width: Int = 1920,
    val height: Int = 1080,
    val fps: Int = 30,
    val videoBitrate: Int = 6_000_000,
    val audioBitrate: Int = 128_000,
) {
    companion object {
        val Default = VideoQuality()
        val Presets = listOf(
            VideoQuality(1280, 720, 30, 3_500_000, 128_000),
            VideoQuality(1920, 1080, 30, 6_000_000, 128_000),
            VideoQuality(1920, 1080, 60, 9_000_000, 128_000),
        )
    }
}
```
- [ ] **Step 2: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit.** `git add app/src/main/java/com/example/plohoystream/stream/VideoQuality.kt && git commit -m "feat(stream): VideoQuality data class + presets"`

### Task 3.2 — CodecOverride + resolveRequest (STRICT TDD)

**Files:**
- Create: `app/src/test/java/com/example/plohoystream/stream/VideoQualityTest.kt`
- Create: `app/src/main/java/com/example/plohoystream/stream/CodecOverride.kt`

- [ ] **Step 1: Write the failing test.** Create `app/src/test/java/com/example/plohoystream/stream/VideoQualityTest.kt`:
```kotlin
package com.example.plohoystream.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoQualityTest {
    @Test fun auto_delegatesToCodecSelector_hevcWhenAvailable() {
        val fmt = resolveRequest(CodecOverride.Auto, hevcEncoder = true, hevcMain10 = true, cameraHdr = true, hdrOn = true)
        assertEquals(VideoCodecType.HEVC, fmt.codec)
        assertEquals(DynamicRange.HLG10, fmt.dynamicRange)
    }

    @Test fun auto_fallsBackToAvcWhenNoHevc() {
        val fmt = resolveRequest(CodecOverride.Auto, hevcEncoder = false, hevcMain10 = false, cameraHdr = false, hdrOn = false)
        assertEquals(VideoCodecType.AVC, fmt.codec)
    }

    @Test fun forceHevc_forcesHevcSdr_ignoringHdrToggle() {
        val fmt = resolveRequest(CodecOverride.ForceHevc, hevcEncoder = true, hevcMain10 = true, cameraHdr = true, hdrOn = true)
        assertEquals(VideoCodecType.HEVC, fmt.codec)
        assertEquals(DynamicRange.SDR, fmt.dynamicRange)
        assertEquals(false, fmt.main10)
    }

    @Test fun forceAvc_forcesAvcSdr() {
        val fmt = resolveRequest(CodecOverride.ForceAvc, hevcEncoder = true, hevcMain10 = true, cameraHdr = true, hdrOn = true)
        assertEquals(VideoCodecType.AVC, fmt.codec)
        assertEquals(DynamicRange.SDR, fmt.dynamicRange)
    }
}
```
- [ ] **Step 2: Run, expect FAIL.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.VideoQualityTest" 2>&1 | tail -15`. Expect `unresolved reference: CodecOverride` / `resolveRequest`.
- [ ] **Step 3: Implement `CodecOverride` + `resolveRequest`.** Create `app/src/main/java/com/example/plohoystream/stream/CodecOverride.kt`:
```kotlin
package com.example.plohoystream.stream

/** User codec preference. Auto = today's negotiation; the others force a codec (SDR). */
enum class CodecOverride { Auto, ForceHevc, ForceAvc }

/**
 * Maps a [CodecOverride] (+ device caps + HDR toggle) to the requested [VideoFormat].
 * Auto delegates to [CodecSelector.select]; ForceHevc/ForceAvc pin the codec at SDR
 * (forcing a codec is a debugging/compat escape hatch, so HDR is not implied).
 */
fun resolveRequest(
    override: CodecOverride,
    hevcEncoder: Boolean,
    hevcMain10: Boolean,
    cameraHdr: Boolean,
    hdrOn: Boolean,
): VideoFormat = when (override) {
    CodecOverride.Auto -> CodecSelector.select(hevcEncoder, hevcMain10, cameraHdr, hdrOn)
    CodecOverride.ForceHevc -> VideoFormat(VideoCodecType.HEVC, main10 = false, DynamicRange.SDR)
    CodecOverride.ForceAvc -> VideoFormat(VideoCodecType.AVC, main10 = false, DynamicRange.SDR)
}
```
- [ ] **Step 4: Run, expect PASS.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.VideoQualityTest" 2>&1 | tail -15`. Expect 4 tests pass.
- [ ] **Step 5: Commit.** `git add app/src/main/java/com/example/plohoystream/stream/CodecOverride.kt app/src/test/java/com/example/plohoystream/stream/VideoQualityTest.kt && git commit -m "feat(stream): CodecOverride + resolveRequest codec mapping"`

### Task 3.3 — Extend StreamConfig + apply quality/override in the engine

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/stream/StreamConfig.kt`
- Modify: `app/src/main/java/com/example/plohoystream/stream/CameraStreamEngine.kt` (`start`, line 59)
- Modify: `app/src/test/java/com/example/plohoystream/stream/CameraStreamEngineTest.kt`

- [ ] **Step 1: Write the failing test for override-driven codec request.** In `app/src/test/java/com/example/plohoystream/stream/CameraStreamEngineTest.kt`, add:
```kotlin
    @Test fun forceAvcOverride_requestsAvc_evenWithHevcEncoder() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer, hevcEncoder = true, hevcMain10 = true, cameraHdr = true)
        e.start(StreamConfig("rtmp://h/app", "key", codecOverride = CodecOverride.ForceAvc))
        runCurrent()
        assertEquals(VideoCodecType.AVC, streamer.requestedCodec)
        e.stop()
    }
```
- [ ] **Step 2: Run, expect FAIL.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.CameraStreamEngineTest" 2>&1 | tail -15`. Expect `no value passed for parameter` / `unresolved reference: codecOverride`.
- [ ] **Step 3: Extend `StreamConfig`.** Replace `app/src/main/java/com/example/plohoystream/stream/StreamConfig.kt` with:
```kotlin
package com.example.plohoystream.stream

/** Egress target + encoder settings applied at go-live (no mid-stream reconfig). */
data class StreamConfig(
    val rtmpUrl: String,   // e.g. "rtmp://live.twitch.tv/app"
    val streamKey: String,
    val hdrEnabled: Boolean = false,
    val quality: VideoQuality = VideoQuality.Default,
    val codecOverride: CodecOverride = CodecOverride.Auto,
)
```
- [ ] **Step 4: Use the override in the engine.** In `app/src/main/java/com/example/plohoystream/stream/CameraStreamEngine.kt`, replace the `val requested = CodecSelector.select(...)` line (line 59) with:
```kotlin
        val requested = resolveRequest(
            config.codecOverride, hevcEncoder, hevcMain10, cameraHdr, config.hdrEnabled,
        )
```
- [ ] **Step 5: Run, expect PASS.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.CameraStreamEngineTest" 2>&1 | tail -15`. Expect all CameraStreamEngine tests pass (the existing HDR-downgrade/keep tests still pass because `Auto` is the default).
- [ ] **Step 6: Commit.** `git add app/src/main/java/com/example/plohoystream/stream/StreamConfig.kt app/src/main/java/com/example/plohoystream/stream/CameraStreamEngine.kt app/src/test/java/com/example/plohoystream/stream/CameraStreamEngineTest.kt && git commit -m "feat(stream): StreamConfig carries quality + codecOverride; engine honours override"`

### Task 3.4 — SettingsRoute enum

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/settings/SettingsRoute.kt`

- [ ] **Step 1: Write the enum.** Create `app/src/main/java/com/example/plohoystream/ui/settings/SettingsRoute.kt`:
```kotlin
package com.example.plohoystream.ui.settings

/** Panel nav state. [Root] shows the grouped list; the rest are pushed sub-screens. */
enum class SettingsRoute { Root, Destination, Video, Audio, Camera, About }
```
- [ ] **Step 2: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit.** `git add app/src/main/java/com/example/plohoystream/ui/settings/SettingsRoute.kt && git commit -m "feat(ui): SettingsRoute nav enum"`

### Task 3.5 — Extend StreamUiState

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/stream/StreamUiState.kt`
- Modify: `app/src/test/java/com/example/plohoystream/stream/StreamUiStateTest.kt`

- [ ] **Step 1: Write the failing test for the new fields' defaults + a `settingsOpen` derivation.** In `app/src/test/java/com/example/plohoystream/stream/StreamUiStateTest.kt`, add:
```kotlin
    @Test fun newFields_haveSaneDefaults() {
        val s = StreamUiState()
        assertEquals(0, s.bitrateKbps)
        assertEquals(ConnectionHealth.Good, s.health)
        assertEquals(0f, s.audioLevel, 0f)
        assertEquals("00:00", s.elapsed)
        assertEquals(com.example.plohoystream.ui.settings.SettingsRoute.Root, s.settingsRoute)
        assertFalse(s.settingsOpen)
    }

    @Test fun settingsOpen_trueWhenPanelOpen() {
        assertTrue(StreamUiState(panelOpen = true).settingsOpen)
    }
```
(Add `import org.junit.Assert.assertEquals` if missing.)
- [ ] **Step 2: Run, expect FAIL.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.StreamUiStateTest" 2>&1 | tail -15`. Expect `unresolved reference: bitrateKbps`.
- [ ] **Step 3: Extend `StreamUiState`.** Replace `app/src/main/java/com/example/plohoystream/stream/StreamUiState.kt` with:
```kotlin
package com.example.plohoystream.stream

import com.example.plohoystream.ui.settings.SettingsRoute

data class StreamUiState(
    val url: String = "",
    val key: String = "",
    val stream: StreamState = StreamState.Idle,
    val hdrEnabled: Boolean = false,
    val hdrAvailable: Boolean = false,
    // Live stats (real signals from the engine).
    val bitrateKbps: Int = 0,
    val health: ConnectionHealth = ConnectionHealth.Good,
    val audioLevel: Float = 0f,
    val elapsed: String = "00:00",
    // Settings mirror (applied at go-live).
    val quality: VideoQuality = VideoQuality.Default,
    val codecOverride: CodecOverride = CodecOverride.Auto,
    // Settings panel nav.
    val panelOpen: Boolean = false,
    val settingsRoute: SettingsRoute = SettingsRoute.Root,
) {
    val canGoLive: Boolean
        get() = url.isNotBlank() && key.isNotBlank() &&
            (stream is StreamState.Idle || stream is StreamState.Error)

    val isActive: Boolean
        get() = stream is StreamState.Connecting ||
            stream is StreamState.Live ||
            stream is StreamState.Stopping

    /** The signature preview-shrink is driven by this. */
    val settingsOpen: Boolean get() = panelOpen
}
```
- [ ] **Step 4: Run, expect PASS.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.StreamUiStateTest" 2>&1 | tail -15`. Expect all StreamUiState tests pass.
- [ ] **Step 5: Commit.** `git add app/src/main/java/com/example/plohoystream/stream/StreamUiState.kt app/src/test/java/com/example/plohoystream/stream/StreamUiStateTest.kt && git commit -m "feat(stream): StreamUiState carries stats, settings-mirror, panel nav"`

### Task 3.6 — Extend StreamViewModel: setters, richer goLive, wire engine flows

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/stream/StreamViewModel.kt`
- Modify: `app/src/test/java/com/example/plohoystream/stream/StreamViewModelTest.kt`
- Modify: `app/src/main/java/com/example/plohoystream/stream/FakeStreamEngine.kt` (implement `VideoStreamEngine` flows so the VM can collect them in tests)

- [ ] **Step 1: Write the failing tests.** In `app/src/test/java/com/example/plohoystream/stream/StreamViewModelTest.kt`, add:
```kotlin
    @Test fun setQualityAndCodec_thenGoLive_buildsRicherConfig() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine)
        vm.setUrl("rtmp://h/app"); vm.setKey("k")
        vm.setQuality(VideoQuality(1280, 720, 30, 3_500_000, 128_000))
        vm.setCodecOverride(CodecOverride.ForceHevc)
        vm.goLive()
        advanceUntilIdle()
        assertEquals(
            StreamConfig("rtmp://h/app", "k", quality = VideoQuality(1280, 720, 30, 3_500_000, 128_000), codecOverride = CodecOverride.ForceHevc),
            engine.lastConfig,
        )
    }

    @Test fun panelOpenClose_andRoute_updateUiState() = runTest {
        val vm = StreamViewModel(FakeStreamEngine())
        vm.openSettings()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.panelOpen)
        vm.navigateSettings(com.example.plohoystream.ui.settings.SettingsRoute.Video)
        assertEquals(com.example.plohoystream.ui.settings.SettingsRoute.Video, vm.uiState.value.settingsRoute)
        vm.closeSettings()
        assertFalse(vm.uiState.value.panelOpen)
    }

    @Test fun engineLiveStats_propagateToUiState() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine)
        advanceUntilIdle()
        engine.emitBitrate(5500)
        engine.emitHealth(ConnectionHealth.Warn)
        advanceUntilIdle()
        assertEquals(5500, vm.uiState.value.bitrateKbps)
        assertEquals(ConnectionHealth.Warn, vm.uiState.value.health)
    }
```
(Add `import org.junit.Assert.assertFalse` if missing.)
- [ ] **Step 2: Make `FakeStreamEngine` a `VideoStreamEngine`.** Replace `app/src/main/java/com/example/plohoystream/stream/FakeStreamEngine.kt` with:
```kotlin
package com.example.plohoystream.stream

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory engine: drives states/stats for tests, @Preview, and the app shell. */
class FakeStreamEngine : VideoStreamEngine {
    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    override val state: StateFlow<StreamState> = _state.asStateFlow()

    override val encoderSurface: StateFlow<Surface?> = MutableStateFlow<Surface?>(null)
    override val activeHdr: StateFlow<Boolean> = MutableStateFlow(false)

    private val _bitrateKbps = MutableStateFlow(0)
    override val bitrateKbps: StateFlow<Int> = _bitrateKbps.asStateFlow()
    private val _health = MutableStateFlow(ConnectionHealth.Good)
    override val health: StateFlow<ConnectionHealth> = _health.asStateFlow()
    private val _audioLevel = MutableStateFlow(0f)
    override val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    var lastConfig: StreamConfig? = null
        private set

    override fun start(config: StreamConfig) {
        lastConfig = config
        _state.value = StreamState.Connecting
    }

    override fun stop() {
        _state.value = StreamState.Stopping
        _state.value = StreamState.Idle
    }

    // Test/preview controls.
    fun emitLive() { _state.value = StreamState.Live }
    fun emitError(reason: String) { _state.value = StreamState.Error(reason) }
    fun emitIdle() { _state.value = StreamState.Idle }
    fun emitBitrate(kbps: Int) { _bitrateKbps.value = kbps }
    fun emitHealth(h: ConnectionHealth) { _health.value = h }
    fun emitAudioLevel(level: Float) { _audioLevel.value = level }
}
```
- [ ] **Step 3: Run, expect FAIL.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.StreamViewModelTest" 2>&1 | tail -20`. Expect `unresolved reference: setQuality` / `openSettings` / `emitBitrate`.
- [ ] **Step 4: Extend `StreamViewModel`.** Replace `app/src/main/java/com/example/plohoystream/stream/StreamViewModel.kt` with:
```kotlin
package com.example.plohoystream.stream

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.plohoystream.ui.settings.SettingsRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class StreamViewModel(private val engine: StreamEngine, hdrAvailable: Boolean = false) : ViewModel() {
    private val _uiState = MutableStateFlow(StreamUiState(hdrAvailable = hdrAvailable))
    val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

    val encoderSurface: StateFlow<Surface?> =
        (engine as? VideoStreamEngine)?.encoderSurface ?: MutableStateFlow<Surface?>(null)

    val activeHdr: StateFlow<Boolean> =
        (engine as? VideoStreamEngine)?.activeHdr ?: MutableStateFlow(false)

    private var liveStartMs: Long = 0L

    init {
        viewModelScope.launch {
            engine.state.collect { s ->
                if (s is StreamState.Live && liveStartMs == 0L) liveStartMs = System.currentTimeMillis()
                if (s is StreamState.Idle || s is StreamState.Error) liveStartMs = 0L
                _uiState.update { it.copy(stream = s) }
            }
        }
        (engine as? VideoStreamEngine)?.let { ve ->
            viewModelScope.launch { ve.bitrateKbps.collect { v -> _uiState.update { it.copy(bitrateKbps = v) } } }
            viewModelScope.launch { ve.health.collect { v -> _uiState.update { it.copy(health = v) } } }
            viewModelScope.launch { ve.audioLevel.collect { v -> _uiState.update { it.copy(audioLevel = v) } } }
        }
        // Tick the elapsed timer once per second while live.
        viewModelScope.launch {
            while (isActive) {
                val start = liveStartMs
                val elapsed = if (start > 0L) formatElapsed(System.currentTimeMillis() - start) else "00:00"
                _uiState.update { if (it.elapsed != elapsed) it.copy(elapsed = elapsed) else it }
                delay(1000)
            }
        }
    }

    fun setUrl(value: String) = _uiState.update { it.copy(url = value) }
    fun setKey(value: String) = _uiState.update { it.copy(key = value) }
    fun setHdr(on: Boolean) = _uiState.update { it.copy(hdrEnabled = on) }
    fun setQuality(q: VideoQuality) = _uiState.update { it.copy(quality = q) }
    fun setCodecOverride(c: CodecOverride) = _uiState.update { it.copy(codecOverride = c) }

    fun openSettings() = _uiState.update { it.copy(panelOpen = true, settingsRoute = SettingsRoute.Root) }
    fun closeSettings() = _uiState.update { it.copy(panelOpen = false, settingsRoute = SettingsRoute.Root) }
    fun navigateSettings(route: SettingsRoute) = _uiState.update { it.copy(settingsRoute = route) }

    fun goLive() {
        val s = _uiState.value
        if (s.canGoLive) engine.start(
            StreamConfig(
                rtmpUrl = s.url,
                streamKey = s.key,
                hdrEnabled = s.hdrEnabled,
                quality = s.quality,
                codecOverride = s.codecOverride,
            ),
        )
    }

    fun stop() = engine.stop()
}
```
- [ ] **Step 5: Run, expect PASS.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.StreamViewModelTest" 2>&1 | tail -15`. Expect all StreamViewModel tests pass (existing `StreamConfig("rtmp://h/app", "k")` assertions still hold because the new fields default to `VideoQuality.Default`/`CodecOverride.Auto`).
- [ ] **Step 6: Full unit run.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest 2>&1 | tail -10`. Expect all green.
- [ ] **Step 7: Commit.** `git add app/src/main/java/com/example/plohoystream/stream/StreamViewModel.kt app/src/main/java/com/example/plohoystream/stream/FakeStreamEngine.kt app/src/test/java/com/example/plohoystream/stream/StreamViewModelTest.kt && git commit -m "feat(stream): VM setters, richer goLive, elapsed timer, stats propagation"`

---

## Phase 4 — Viewfinder decomposition & landscape layout

> All Phase 4/5 tasks are composable + `@Preview` work (not host-unit-testable). Each task: write composable using theme tokens + `GlassSurface` + `Motion` springs, add a `@Preview` per relevant state, run `./gradlew :app:assembleDebug` (expect `BUILD SUCCESSFUL`), commit.

### Task 4.1 — PermissionGate (restyled, extracted)

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/PermissionGate.kt`
- Modify: `app/src/main/java/com/example/plohoystream/ui/StreamScreen.kt` (remove the private `PermissionGate`, lines 67–81)

- [ ] **Step 1: Write the restyled gate + preview.** Create `app/src/main/java/com/example/plohoystream/ui/PermissionGate.kt`:
```kotlin
package com.example.plohoystream.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme
import com.example.plohoystream.ui.theme.SurfaceBlack

@Composable
fun PermissionGate(onRequest: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().background(SurfaceBlack).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(
                "PlohoyStream needs camera and microphone access to preview and stream.",
                color = OnSurfaceWhite,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRequest) { Text("Grant access") }
        }
    }
}

@Preview(widthDp = 720, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PermissionGatePreview() {
    PlohoyTheme { PermissionGate(onRequest = {}) }
}
```
- [ ] **Step 2: Remove the private gate from `StreamScreen`.** In `app/src/main/java/com/example/plohoystream/ui/StreamScreen.kt`, delete the private `PermissionGate` composable (lines 67–81). The `StreamScreen` call site already references `PermissionGate(onRequest = ...)` — it now resolves to the new top-level one (same package).
- [ ] **Step 3: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 4: Commit.** `git add app/src/main/java/com/example/plohoystream/ui/PermissionGate.kt app/src/main/java/com/example/plohoystream/ui/StreamScreen.kt && git commit -m "feat(ui): extract + restyle PermissionGate"`

### Task 4.2 — LiveStatusCluster

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/viewfinder/LiveStatusCluster.kt`

- [ ] **Step 1: Write the cluster + previews (idle / live).** Create `app/src/main/java/com/example/plohoystream/ui/viewfinder/LiveStatusCluster.kt`:
```kotlin
package com.example.plohoystream.ui.viewfinder

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.ui.theme.GlassOverVideo
import com.example.plohoystream.ui.theme.LiveRed
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme

/** LIVE pill (pulsing red dot + label) over the elapsed timer. [reconnecting] tints amber. */
@Composable
fun LiveStatusCluster(live: Boolean, elapsed: String, reconnecting: Boolean = false) {
    Column(horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (live) {
            val transition = rememberInfiniteTransition(label = "live-pulse")
            val pulse by transition.animateFloat(
                initialValue = 1f, targetValue = 0.3f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(GlassOverVideo)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(if (reconnecting) com.example.plohoystream.ui.theme.HealthWarn else LiveRed)
                        .alpha(pulse),
                )
                Text(
                    if (reconnecting) "RECONNECTING" else "LIVE",
                    color = OnSurfaceWhite, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(elapsed, color = OnSurfaceWhite, style = androidx.compose.material3.MaterialTheme.typography.displayLarge)
    }
}

@Preview(name = "idle", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun ClusterIdlePreview() = PlohoyTheme { LiveStatusCluster(live = false, elapsed = "00:00") }

@Preview(name = "live", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun ClusterLivePreview() = PlohoyTheme { LiveStatusCluster(live = true, elapsed = "01:23") }

@Preview(name = "reconnecting", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun ClusterReconnectingPreview() = PlohoyTheme { LiveStatusCluster(live = true, elapsed = "02:00", reconnecting = true) }
```
(Add `import androidx.compose.foundation.layout.Box` — used by the dot.)
- [ ] **Step 2: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit.** `git add app/src/main/java/com/example/plohoystream/ui/viewfinder/LiveStatusCluster.kt && git commit -m "feat(ui): LiveStatusCluster (pulsing LIVE pill + timer)"`

### Task 4.3 — HealthIndicator

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/viewfinder/HealthIndicator.kt`

- [ ] **Step 1: Write the indicator + previews (good/warn/bad).** Create `app/src/main/java/com/example/plohoystream/ui/viewfinder/HealthIndicator.kt`:
```kotlin
package com.example.plohoystream.ui.viewfinder

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.stream.ConnectionHealth
import com.example.plohoystream.ui.theme.HealthBad
import com.example.plohoystream.ui.theme.HealthGood
import com.example.plohoystream.ui.theme.HealthWarn
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme
import com.example.plohoystream.ui.theme.colorSpring

/** Colored health dot (green→amber→red, animated) + bitrate text. */
@Composable
fun HealthIndicator(health: ConnectionHealth, bitrateKbps: Int) {
    val target = when (health) {
        ConnectionHealth.Good -> HealthGood
        ConnectionHealth.Warn -> HealthWarn
        ConnectionHealth.Bad -> HealthBad
    }
    val color by animateColorAsState(targetValue = target, animationSpec = colorSpring(), label = "health")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text("$bitrateKbps kbps", color = OnSurfaceWhite, style = MaterialTheme.typography.labelLarge)
    }
}

@Preview(name = "good", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun HealthGoodPreview() = PlohoyTheme { HealthIndicator(ConnectionHealth.Good, 5980) }

@Preview(name = "warn", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun HealthWarnPreview() = PlohoyTheme { HealthIndicator(ConnectionHealth.Warn, 4200) }

@Preview(name = "bad", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun HealthBadPreview() = PlohoyTheme { HealthIndicator(ConnectionHealth.Bad, 1500) }
```
(Add `import androidx.compose.foundation.layout.Box`.)
- [ ] **Step 2: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit.** `git add app/src/main/java/com/example/plohoystream/ui/viewfinder/HealthIndicator.kt && git commit -m "feat(ui): HealthIndicator (animated color dot + bitrate)"`

### Task 4.4 — AudioMeter

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/viewfinder/AudioMeter.kt`

- [ ] **Step 1: Write the color-zoned meter + previews (quiet/loud/clip).** Create `app/src/main/java/com/example/plohoystream/ui/viewfinder/AudioMeter.kt`:
```kotlin
package com.example.plohoystream.ui.viewfinder

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.ui.theme.HealthBad
import com.example.plohoystream.ui.theme.HealthGood
import com.example.plohoystream.ui.theme.HealthWarn
import com.example.plohoystream.ui.theme.PlohoyTheme
import com.example.plohoystream.ui.theme.SignatureFloatSpring

/** Horizontal audio meter: green (< .7) → amber (< .9) → red (clipping). [level] is 0..1. */
@Composable
fun AudioMeter(level: Float, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(targetValue = level.coerceIn(0f, 1f), animationSpec = SignatureFloatSpring, label = "audio")
    val color = when {
        animated >= 0.9f -> HealthBad
        animated >= 0.7f -> HealthWarn
        else -> HealthGood
    }
    Canvas(modifier = modifier.fillMaxWidth().height(6.dp)) {
        val r = CornerRadius(size.height / 2, size.height / 2)
        drawRoundRect(color = HealthGood.copy(alpha = 0.15f), size = size, cornerRadius = r)
        drawRoundRect(
            color = color,
            topLeft = Offset.Zero,
            size = Size(size.width * animated, size.height),
            cornerRadius = r,
        )
    }
}

@Preview(name = "quiet", widthDp = 120, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun AudioQuietPreview() = PlohoyTheme { AudioMeter(level = 0.2f) }

@Preview(name = "loud", widthDp = 120, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun AudioLoudPreview() = PlohoyTheme { AudioMeter(level = 0.8f) }

@Preview(name = "clip", widthDp = 120, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun AudioClipPreview() = PlohoyTheme { AudioMeter(level = 0.97f) }
```
- [ ] **Step 2: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit.** `git add app/src/main/java/com/example/plohoystream/ui/viewfinder/AudioMeter.kt && git commit -m "feat(ui): AudioMeter (color-zoned level bar)"`

### Task 4.5 — ZoomChips

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/viewfinder/ZoomChips.kt`

- [ ] **Step 1: Write the chip row + preview.** Create `app/src/main/java/com/example/plohoystream/ui/viewfinder/ZoomChips.kt`:
```kotlin
package com.example.plohoystream.ui.viewfinder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.camera.CameraLens
import com.example.plohoystream.ui.theme.PlohoyTheme

/** Lens/zoom chips. Reuses the existing camera [CameraLens] model. */
@Composable
fun ZoomChips(
    lenses: List<CameraLens>,
    selectedZoom: Float,
    onSelect: (CameraLens) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lenses.size <= 1) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        lenses.forEach { lens ->
            FilterChip(
                selected = selectedZoom == lens.zoomRatio,
                onClick = { onSelect(lens) },
                label = { Text(lens.label) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ZoomChipsPreview() = PlohoyTheme {
    ZoomChips(
        lenses = listOf(CameraLens(label = "0.5x", zoomRatio = 0.5f), CameraLens(label = "1x", zoomRatio = 1f), CameraLens(label = "2x", zoomRatio = 2f)),
        selectedZoom = 1f, onSelect = {},
    )
}
```
(`CameraLens(val label: String, val zoomRatio: Float)` is defined in `app/src/main/java/com/example/plohoystream/camera/CameraModels.kt`; `config.lenses` is `List<CameraLens>`.)
- [ ] **Step 2: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit.** `git add app/src/main/java/com/example/plohoystream/ui/viewfinder/ZoomChips.kt && git commit -m "feat(ui): ZoomChips lens row"`

### Task 4.6 — GoLiveButton (morph)

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/viewfinder/GoLiveButton.kt`

- [ ] **Step 1: Write the morphing button + previews (setup/connecting/live).** Create `app/src/main/java/com/example/plohoystream/ui/viewfinder/GoLiveButton.kt`:
```kotlin
package com.example.plohoystream.ui.viewfinder

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.stream.StreamState
import com.example.plohoystream.ui.theme.LiveRed
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme
import com.example.plohoystream.ui.theme.SignatureDpSpring

/**
 * Go Live ring (white) morphs to the rounded-square red Stop via shape/size interpolation.
 * In Connecting, the ring pulses. [enabled] gates the tap in setup.
 */
@Composable
fun GoLiveButton(state: StreamState, enabled: Boolean, onGoLive: () -> Unit, onStop: () -> Unit) {
    val active = state is StreamState.Live || state is StreamState.Stopping
    val connecting = state is StreamState.Connecting
    val innerCorner by animateDpAsState(targetValue = if (active) 8.dp else 28.dp, animationSpec = SignatureDpSpring, label = "corner")
    val innerSize by animateDpAsState(targetValue = if (active) 26.dp else 56.dp, animationSpec = SignatureDpSpring, label = "size")

    val pulse = if (connecting) {
        val t = rememberInfiniteTransition(label = "connect")
        t.animateFloat(1f, 0.4f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "p").value
    } else 1f

    Box(
        modifier = Modifier
            .size(72.dp)
            .border(BorderStroke(4.dp, OnSurfaceWhite), RoundedCornerShape(36.dp))
            .alpha(if (connecting) pulse else 1f)
            .clickable(enabled = enabled || active) { if (active) onStop() else onGoLive() }
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(innerSize)
                .clip(RoundedCornerShape(innerCorner))
                .background(if (active) LiveRed else OnSurfaceWhite)
                .alpha(if (enabled || active) 1f else 0.4f),
        )
    }
}

@Preview(name = "setup", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun GoLiveSetupPreview() = PlohoyTheme { GoLiveButton(StreamState.Idle, enabled = true, {}, {}) }

@Preview(name = "connecting", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun GoLiveConnectingPreview() = PlohoyTheme { GoLiveButton(StreamState.Connecting, enabled = false, {}, {}) }

@Preview(name = "live", showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun GoLiveLivePreview() = PlohoyTheme { GoLiveButton(StreamState.Live, enabled = false, {}, {}) }
```
- [ ] **Step 2: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Commit.** `git add app/src/main/java/com/example/plohoystream/ui/viewfinder/GoLiveButton.kt && git commit -m "feat(ui): GoLiveButton ring↔Stop morph + connecting pulse"`

### Task 4.7 — ControlRail

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/viewfinder/ControlRail.kt`

- [ ] **Step 1: Write the rail + previews (setup/live/error).** Create `app/src/main/java/com/example/plohoystream/ui/viewfinder/ControlRail.kt`:
```kotlin
package com.example.plohoystream.ui.viewfinder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.camera.CameraLens
import com.example.plohoystream.stream.ConnectionHealth
import com.example.plohoystream.stream.StreamState
import com.example.plohoystream.ui.theme.GlassHairline
import com.example.plohoystream.ui.theme.LiveRed
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme
import com.example.plohoystream.ui.theme.glassSurface

/** The right letterbox bar: status cluster (top) + actions (bottom). Never over video. */
@Composable
fun ControlRail(
    state: StreamState,
    elapsed: String,
    health: ConnectionHealth,
    bitrateKbps: Int,
    audioLevel: Float,
    lenses: List<CameraLens>,
    selectedZoom: Float,
    canGoLive: Boolean,
    errorReason: String?,
    onSelectLens: (CameraLens) -> Unit,
    onFlip: () -> Unit,
    onGoLive: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = state is StreamState.Live
    Column(
        modifier = modifier.fillMaxHeight().width(220.dp).glassSurface().padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.Start,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LiveStatusCluster(live = live, elapsed = elapsed)
            if (live) {
                HealthIndicator(health = health, bitrateKbps = bitrateKbps)
                AudioMeter(level = audioLevel)
            }
            ZoomChips(lenses = lenses, selectedZoom = selectedZoom, onSelect = onSelectLens)
            if (errorReason != null) {
                Text(errorReason, color = LiveRed, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onGoLive) { Text("Try again") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconButton(onClick = onFlip, modifier = Modifier.size(40.dp).clip(CircleShape)) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = "Flip camera", tint = OnSurfaceWhite)
            }
            GoLiveButton(state = state, enabled = canGoLive, onGoLive = onGoLive, onStop = onStop)
            IconButton(onClick = onSettings, modifier = Modifier.size(40.dp).clip(CircleShape)) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = OnSurfaceMuted)
            }
        }
    }
}

@Preview(name = "setup", widthDp = 240, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailSetupPreview() = PlohoyTheme {
    ControlRail(StreamState.Idle, "00:00", ConnectionHealth.Good, 0, 0f, emptyList(), 1f, true, null, {}, {}, {}, {}, {})
}

@Preview(name = "live", widthDp = 240, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailLivePreview() = PlohoyTheme {
    ControlRail(StreamState.Live, "01:23", ConnectionHealth.Warn, 4200, 0.8f, emptyList(), 1f, false, null, {}, {}, {}, {}, {})
}

@Preview(name = "error", widthDp = 240, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailErrorPreview() = PlohoyTheme {
    ControlRail(StreamState.Error("Connection refused"), "00:00", ConnectionHealth.Bad, 0, 0f, emptyList(), 1f, true, "Connection refused", {}, {}, {}, {}, {})
}
```
- [ ] **Step 2: Verify the Material icons extended dependency is available.** `Icons.Filled.Cameraswitch`/`Settings` need `androidx.compose.material:material-icons-extended`. Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug 2>&1 | tail -20`. If it fails with `unresolved reference: Cameraswitch`, add to `gradle/libs.versions.toml` `[libraries]` an entry `androidx-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended" }` and `implementation(libs.androidx.material.icons.extended)` to `app/build.gradle.kts` dependencies (BOM-versioned), then rebuild. (`Settings` is in core icons; `Cameraswitch` is in extended.)
- [ ] **Step 3: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 4: Commit.** `git add app/src/main/java/com/example/plohoystream/ui/viewfinder/ControlRail.kt gradle/libs.versions.toml app/build.gradle.kts && git commit -m "feat(ui): ControlRail glass bar (status cluster + actions)"`

### Task 4.8 — Viewfinder (landscape split + shrink transition)

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt`
- Modify: `app/src/main/java/com/example/plohoystream/ui/StreamScreen.kt` (remove the private `Viewfinder`, lines 83–229, and `statusText`, lines 231–237; keep only the `StreamScreen` permission switch)

- [ ] **Step 1: Write the Viewfinder orchestrator.** Create `app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt`. It mirrors the existing `Viewfinder` camera lifecycle (config/surface/encoderSurface/activeHdr `LaunchedEffect`, `Camera2Controller`, `CameraEnumerator`, `CameraCapabilities.select`) but lays out a Row: `CameraPreview` (weight animated by the signature spring when settings open) + `ControlRail`, with `SettingsPanel` filling the freed space when `ui.settingsOpen`:
```kotlin
package com.example.plohoystream.ui.viewfinder

import android.view.Surface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.camera.Camera2Controller
import com.example.plohoystream.camera.CameraCapabilities
import com.example.plohoystream.camera.CameraControls
import com.example.plohoystream.camera.CameraEnumerator
import com.example.plohoystream.camera.Facing
import com.example.plohoystream.stream.StreamState
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.CameraPreview
import com.example.plohoystream.ui.settings.SettingsPanel
import com.example.plohoystream.ui.theme.SignatureFloatSpring
import com.example.plohoystream.ui.theme.SurfaceBlack

@Composable
fun Viewfinder(viewModel: StreamViewModel) {
    val context = LocalContext.current
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val encoderSurface by viewModel.encoderSurface.collectAsStateWithLifecycle()
    val activeHdr by viewModel.activeHdr.collectAsStateWithLifecycle()

    val cameras = remember { CameraEnumerator.enumerate(context) }
    val controller = remember { Camera2Controller(context) }

    var facing by remember { mutableStateOf(Facing.BACK) }
    var surface by remember { mutableStateOf<Surface?>(null) }
    var zoom by remember { mutableStateOf(1f) }

    val config = remember(cameras, facing) { CameraCapabilities.select(cameras, facing) }
    DisposableEffect(Unit) { onDispose { controller.stop() } }

    LaunchedEffect(config, surface, encoderSurface, activeHdr) {
        val c = config; val preview = surface
        if (c != null && preview != null) {
            controller.start(c, listOfNotNull(preview, encoderSurface), hdr = activeHdr)
            controller.setZoom(zoom)
        }
    }

    val bufferW = config?.previewSize?.width ?: 1920
    val bufferH = config?.previewSize?.height ?: 1080
    val previewAspect = bufferH.toFloat() / bufferW

    // Signature shrink: preview weight springs from 1.0 to ~0.55 when settings open.
    val previewWeight by animateFloatAsState(
        targetValue = if (ui.settingsOpen) 0.55f else 1f,
        animationSpec = SignatureFloatSpring, label = "shrink",
    )

    Row(modifier = Modifier.fillMaxSize().background(SurfaceBlack)) {
        Box(modifier = Modifier.weight(previewWeight).fillMaxHeight()) {
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                aspectRatio = previewAspect,
                bufferWidth = bufferW,
                bufferHeight = bufferH,
                onSurface = { surface = it },
            )
        }
        if (ui.settingsOpen) {
            Box(modifier = Modifier.weight(1f - previewWeight).fillMaxHeight().padding(8.dp)) {
                SettingsPanel(viewModel)
            }
        } else {
            ControlRail(
                state = ui.stream,
                elapsed = ui.elapsed,
                health = ui.health,
                bitrateKbps = ui.bitrateKbps,
                audioLevel = ui.audioLevel,
                lenses = config?.lenses.orEmpty(),
                selectedZoom = zoom,
                canGoLive = ui.canGoLive,
                errorReason = (ui.stream as? StreamState.Error)?.reason,
                onSelectLens = { lens -> zoom = lens.zoomRatio; controller.setLens(lens) },
                onFlip = { facing = CameraControls.opposite(facing); zoom = 1f },
                onGoLive = viewModel::goLive,
                onStop = viewModel::stop,
                onSettings = viewModel::openSettings,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}
```
- [ ] **Step 2: Slim `StreamScreen.kt`.** Replace `app/src/main/java/com/example/plohoystream/ui/StreamScreen.kt` with just the permission switch (the `Viewfinder` and `statusText` are gone; `PermissionGate` is the top-level one from Task 4.1):
```kotlin
package com.example.plohoystream.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.viewfinder.Viewfinder

@Composable
fun StreamScreen(viewModel: StreamViewModel) {
    val context = LocalContext.current
    val perms = remember { arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO) }
    fun hasAll() = perms.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(hasAll()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = hasAll() }

    if (granted) Viewfinder(viewModel) else PermissionGate(onRequest = { launcher.launch(perms) })
}
```
- [ ] **Step 3: Build (SettingsPanel does not exist yet — stub it).** `SettingsPanel` lands in Phase 5; to keep this task building, first create a minimal placeholder `app/src/main/java/com/example/plohoystream/ui/settings/SettingsPanel.kt` that Phase 5 Task 5.1 replaces:
```kotlin
package com.example.plohoystream.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.glassSurface

@Composable
fun SettingsPanel(viewModel: StreamViewModel) {
    Box(Modifier.fillMaxSize().glassSurface()) { Text("Settings", color = OnSurfaceWhite) }
}
```
- [ ] **Step 4: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit.** `git add app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt app/src/main/java/com/example/plohoystream/ui/StreamScreen.kt app/src/main/java/com/example/plohoystream/ui/settings/SettingsPanel.kt && git commit -m "feat(ui): Viewfinder landscape split + signature preview-shrink transition"`

---

## Phase 5 — Settings panel (Moblin UX, scoped)

### Task 5.1 — SettingsPanel nav host + reusable rows

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/ui/settings/SettingsPanel.kt` (replace the Task 4.8 stub)

- [ ] **Step 1: Write the nav host + shared widgets + previews (root, live-dimmed).** Replace `app/src/main/java/com/example/plohoystream/ui/settings/SettingsPanel.kt`:
```kotlin
package com.example.plohoystream.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.theme.GlassHairline
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme
import com.example.plohoystream.ui.theme.SurfaceElevated
import com.example.plohoystream.ui.theme.glassSurface

/** Amber banner shown on sub-screens whose changes only apply at the next go-live. */
@Composable
fun DimmedWhileLiveBanner(visible: Boolean) {
    if (!visible) return
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(com.example.plohoystream.ui.theme.HealthWarn.copy(alpha = 0.18f)).padding(12.dp),
    ) {
        Text(
            "These settings apply the next time you go live.",
            color = com.example.plohoystream.ui.theme.HealthWarn,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** A tappable grouped row that pushes a sub-screen. */
@Composable
fun NavRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated).clickable(onClick = onClick).padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = OnSurfaceWhite, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = OnSurfaceMuted)
        }
    }
}

/** A sub-screen scaffold: back header + content. */
@Composable
fun SubScreen(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = OnSurfaceWhite) }
            Text(title, color = OnSurfaceWhite, style = MaterialTheme.typography.titleLarge)
        }
        content()
    }
}

@Composable
fun SettingsPanel(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize().glassSurface().padding(16.dp)) {
        AnimatedContent(targetState = ui.settingsRoute, label = "settings-nav") { route ->
            when (route) {
                SettingsRoute.Root -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Settings", color = OnSurfaceWhite, style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = viewModel::closeSettings) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close", tint = OnSurfaceWhite)
                        }
                    }
                    NavRow("Destination", ui.url.ifBlank { "Not set" }) { viewModel.navigateSettings(SettingsRoute.Destination) }
                    NavRow("Video", "${ui.quality.height}p ${ui.quality.fps}") { viewModel.navigateSettings(SettingsRoute.Video) }
                    NavRow("Audio", "${ui.quality.audioBitrate / 1000} kbps") { viewModel.navigateSettings(SettingsRoute.Audio) }
                    NavRow("Camera", "") { viewModel.navigateSettings(SettingsRoute.Camera) }
                    NavRow("About & Reset", "") { viewModel.navigateSettings(SettingsRoute.About) }
                }
                SettingsRoute.Destination -> DestinationSettings(viewModel)
                SettingsRoute.Video -> VideoSettings(viewModel)
                SettingsRoute.Audio -> AudioSettings(viewModel)
                SettingsRoute.Camera -> CameraSettings(viewModel)
                SettingsRoute.About -> AboutSettings(viewModel)
            }
        }
    }
}

@Preview(name = "root", widthDp = 360, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun SettingsRootPreview() = PlohoyTheme {
    Column { NavRow("Destination", "rtmp://…") {}; NavRow("Video", "1080p 30") {} }
}

@Preview(name = "dimmed-banner", widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun DimmedBannerPreview() = PlohoyTheme { DimmedWhileLiveBanner(visible = true) }
```
(Sub-screen composables `DestinationSettings`/`VideoSettings`/`AudioSettings`/`CameraSettings`/`AboutSettings` are created in Tasks 5.2–5.6 — until then the `when` references will not resolve. To keep this task building, create thin stubs for each now and flesh them out in their own tasks; OR sequence Tasks 5.2–5.6 before this build step. The plan sequences 5.2–5.6 first below, then this build. Reorder Step ordering accordingly when executing: write 5.2–5.6 sub-screens, then this panel, then build once.)
- [ ] **Step 2: Commit (after 5.2–5.6 build green together).** Defer the build+commit to Task 5.6's final build (the sub-screens and this host compile as a unit). Commit message for this file when it builds: included in Task 5.6.

> **Execution note:** Tasks 5.2–5.6 each create one sub-screen file. They will not individually `assembleDebug`-pass in isolation because `SettingsPanel`'s `when` references all five. Write all of 5.1's host + 5.2–5.6's sub-screens, then run a single `./gradlew :app:assembleDebug` at the end of Task 5.6, then make one combined commit there. Each of 5.2–5.5's "commit" steps are therefore folded into Task 5.6's commit. (This is the one deliberate exception to one-commit-per-task, because the panel host + sub-screens are mutually dependent.)

### Task 5.2 — DestinationSettings

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/settings/DestinationSettings.kt`

- [ ] **Step 1: Write the destination sub-screen + previews (editable / live-dimmed).** Create `app/src/main/java/com/example/plohoystream/ui/settings/DestinationSettings.kt`:
```kotlin
package com.example.plohoystream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.PlohoyTheme

@Composable
fun DestinationSettings(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    SubScreen(title = "Destination", onBack = { viewModel.navigateSettings(SettingsRoute.Root) }) {
        DimmedWhileLiveBanner(visible = ui.isActive)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = ui.url, onValueChange = viewModel::setUrl,
                label = { Text("Server URL") }, singleLine = true, enabled = !ui.isActive,
                placeholder = { Text("rtmp://live.example.com/app") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Example: rtmp://live.twitch.tv/app", color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = ui.key, onValueChange = viewModel::setKey,
                label = { Text("Stream key") }, singleLine = true, enabled = !ui.isActive,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun DestinationPreview() = PlohoyTheme {
    // Preview with a fake VM is heavy; preview the dimmed banner + a field shape instead.
    Column { DimmedWhileLiveBanner(true) }
}
```
- [ ] **Step 2:** (No standalone build/commit — see Task 5.1 execution note; folded into Task 5.6.)

### Task 5.3 — VideoSettings

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/settings/VideoSettings.kt`

- [ ] **Step 1: Write the video sub-screen + previews.** Create `app/src/main/java/com/example/plohoystream/ui/settings/VideoSettings.kt` with a resolution/fps picker (from `VideoQuality.Presets`), a codec segmented choice (`CodecOverride.Auto/ForceHevc/ForceAvc`), a bitrate readout, and footer help text — all disabled while `ui.isActive` with the dimmed banner:
```kotlin
package com.example.plohoystream.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.stream.CodecOverride
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.stream.VideoQuality
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme

@Composable
fun VideoSettings(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    SubScreen(title = "Video", onBack = { viewModel.navigateSettings(SettingsRoute.Root) }) {
        DimmedWhileLiveBanner(visible = ui.isActive)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Resolution & frame rate", color = OnSurfaceWhite, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoQuality.Presets.forEach { q ->
                    FilterChip(
                        selected = ui.quality.height == q.height && ui.quality.fps == q.fps,
                        onClick = { if (!ui.isActive) viewModel.setQuality(q) },
                        enabled = !ui.isActive,
                        label = { Text("${q.height}p ${q.fps}") },
                    )
                }
            }
            Text("Codec", color = OnSurfaceWhite, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CodecOverride.Auto to "Auto", CodecOverride.ForceHevc to "HEVC", CodecOverride.ForceAvc to "AVC").forEach { (c, label) ->
                    FilterChip(
                        selected = ui.codecOverride == c,
                        onClick = { if (!ui.isActive) viewModel.setCodecOverride(c) },
                        enabled = !ui.isActive,
                        label = { Text(label) },
                    )
                }
            }
            Text("Video bitrate: ${ui.quality.videoBitrate / 1000} kbps", color = OnSurfaceWhite, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Auto lets the server negotiate the best codec. HEVC is more efficient but some servers reject it; AVC is the most compatible.",
                color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Preview(widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun VideoPreview() = PlohoyTheme { Column { DimmedWhileLiveBanner(true) } }
```
- [ ] **Step 2:** (Folded into Task 5.6.)

### Task 5.4 — AudioSettings

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/settings/AudioSettings.kt`

- [ ] **Step 1: Write the audio sub-screen + preview.** Create `app/src/main/java/com/example/plohoystream/ui/settings/AudioSettings.kt` — audio bitrate (default 128 kbps) presets and a "Codec: AAC (fixed)" note, disabled while live:
```kotlin
package com.example.plohoystream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme

@Composable
fun AudioSettings(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    SubScreen(title = "Audio", onBack = { viewModel.navigateSettings(SettingsRoute.Root) }) {
        DimmedWhileLiveBanner(visible = ui.isActive)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Audio bitrate", color = OnSurfaceWhite, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(96_000, 128_000, 192_000).forEach { br ->
                    FilterChip(
                        selected = ui.quality.audioBitrate == br,
                        onClick = { if (!ui.isActive) viewModel.setQuality(ui.quality.copy(audioBitrate = br)) },
                        enabled = !ui.isActive,
                        label = { Text("${br / 1000} kbps") },
                    )
                }
            }
            Text("Codec: AAC (fixed)", color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Preview(widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun AudioPreview() = PlohoyTheme { Column { DimmedWhileLiveBanner(false) } }
```
- [ ] **Step 2:** (Folded into Task 5.6.)

### Task 5.5 — CameraSettings (HDR only when supported)

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/settings/CameraSettings.kt`

- [ ] **Step 1: Write the camera sub-screen + previews (HDR available / unavailable).** Create `app/src/main/java/com/example/plohoystream/ui/settings/CameraSettings.kt` — HDR toggle row only when `ui.hdrAvailable`, else an "HDR unavailable on this device" note; toggle disabled while live:
```kotlin
package com.example.plohoystream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme

@Composable
fun CameraSettings(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    SubScreen(title = "Camera", onBack = { viewModel.navigateSettings(SettingsRoute.Root) }) {
        DimmedWhileLiveBanner(visible = ui.isActive)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Text("Lens, zoom and flip are controlled from the viewfinder.", color = OnSurfaceMuted, style = MaterialTheme.typography.labelMedium)
            if (ui.hdrAvailable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("HDR", color = OnSurfaceWhite, style = MaterialTheme.typography.titleMedium)
                    Switch(checked = ui.hdrEnabled, onCheckedChange = viewModel::setHdr, enabled = !ui.isActive)
                }
            } else {
                Text("HDR unavailable on this device", color = OnSurfaceMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Preview(name = "hdr-unavailable", widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun CameraNoHdrPreview() = PlohoyTheme {
    Column { Text("HDR unavailable on this device", color = OnSurfaceMuted) }
}
```
- [ ] **Step 2:** (Folded into Task 5.6.)

### Task 5.6 — AboutSettings + build & commit the whole settings panel

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/settings/AboutSettings.kt`

- [ ] **Step 1: Write the about sub-screen + preview.** Create `app/src/main/java/com/example/plohoystream/ui/settings/AboutSettings.kt` — version text + a reset-to-defaults button (resets quality + codec override via the VM setters):
```kotlin
package com.example.plohoystream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.stream.CodecOverride
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.stream.VideoQuality
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.PlohoyTheme

@Composable
fun AboutSettings(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    SubScreen(title = "About & Reset", onBack = { viewModel.navigateSettings(SettingsRoute.Root) }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PlohoyStream 1.0", color = OnSurfaceMuted)
            OutlinedButton(
                onClick = {
                    viewModel.setQuality(VideoQuality.Default)
                    viewModel.setCodecOverride(CodecOverride.Auto)
                    if (ui.hdrEnabled) viewModel.setHdr(false)
                },
                enabled = !ui.isActive,
            ) { Text("Reset to defaults") }
        }
    }
}

@Preview(widthDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun AboutPreview() = PlohoyTheme {
    Column { Text("PlohoyStream 1.0", color = OnSurfaceMuted) }
}
```
- [ ] **Step 2: Build the whole settings panel + sub-screens together.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug 2>&1 | tail -20`. Expect `BUILD SUCCESSFUL`. (If `Icons.AutoMirrored.Filled.KeyboardArrowRight`/`ArrowBack` are unresolved, they ship in core compose-material-icons; if not, they exist in the extended artifact added in Task 4.7.)
- [ ] **Step 3: Commit the panel + all sub-screens together.** `git add app/src/main/java/com/example/plohoystream/ui/settings/SettingsPanel.kt app/src/main/java/com/example/plohoystream/ui/settings/DestinationSettings.kt app/src/main/java/com/example/plohoystream/ui/settings/VideoSettings.kt app/src/main/java/com/example/plohoystream/ui/settings/AudioSettings.kt app/src/main/java/com/example/plohoystream/ui/settings/CameraSettings.kt app/src/main/java/com/example/plohoystream/ui/settings/AboutSettings.kt && git commit -m "feat(ui): Moblin-style settings panel + sub-screens (destination/video/audio/camera/about)"`

---

## Phase 6 — Integration & device verification

### Task 6.1 — Rewire MainActivity end-to-end

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/MainActivity.kt`

- [ ] **Step 1: Confirm the wiring is complete.** Re-read `app/src/main/java/com/example/plohoystream/MainActivity.kt`. Verify it already has (from earlier phases): `PlohoyTheme` (Task 1.5), immersive insets (Task 1.7), `onLevel = { lvl -> eng.publishAudioLevel(lvl) }` in `AudioEncoder` (Task 2.7), and that `StreamScreen(vm)` is still the content root (it now routes to the new `Viewfinder`). The `VideoEncoder`'s hardcoded `width=1920, height=1080, fps=30, bitRate=6_000_000` remain — quality is mirrored in UI state but the encoder is built in `startMedia` with these fixed values; to honour `StreamConfig.quality`, thread the negotiated `VideoFormat`'s companion quality through. For this milestone, pass the engine's configured quality into `startMedia` by widening the `startMedia` signature is out of scope of the spec's "apply at go-live" minimal path — instead, set the `CameraStreamEngine(videoBitrate = ...)` (added Task 2.7) used for the health target. Leave `VideoEncoder` dimensions as-is (the quality picker drives the *health target* + future encoder reconfig). Confirm this is consistent with the spec note "Settings apply at go-live … no mid-stream encoder reconfig." (The picker's resolution/fps wiring into `VideoEncoder` is acceptable to defer; document it in the smoke-test notes.)
- [ ] **Step 2: Build.** `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:assembleDebug`. Expect `BUILD SUCCESSFUL`.
- [ ] **Step 3: Full test + cpp test gate.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:testDebugUnitTest 2>&1 | tail -10` (expect all green) and `cd app/src/main/cpp && ctest --test-dir build-test --output-on-failure 2>&1 | tail -15` (expect 100% tests passed).
- [ ] **Step 4: Commit (if any change made).** `git add app/src/main/java/com/example/plohoystream/MainActivity.kt && git commit -m "chore(ui): confirm MainActivity hosts Viewfinder + PlohoyTheme + stats wiring"` (skip if no edit was needed).

### Task 6.2 — Device verification on the Seeker + smoke-test doc

**Files:**
- Create: `docs/superpowers/BEAUTIFUL_UI_SMOKE_TEST.md`

- [ ] **Step 1: Install on the Seeker.** Run `cd /Users/newuser/AndroidStudioProjects/PlohoyStream && ./gradlew :app:installDebug && adb -s SM02E4060314107 shell am start -n com.example.plohoystream/.MainActivity`. Grant camera + mic when prompted. Observe: landscape lock, no system bars, glass rail in the right letterbox, GoLive ring.
- [ ] **Step 2: Start a local RTMP listener (macOS — no `timeout`).** Run, detached: `ffmpeg -y -listen 1 -i rtmp://0.0.0.0:1935/app/test -c copy /tmp/out.flv` (background it). Then reverse the port: `adb -s SM02E4060314107 reverse tcp:1935 tcp:1935`.
- [ ] **Step 3: Drive the app to go live.** In Settings → Destination, set Server URL `rtmp://127.0.0.1/app` and key `test`. (If using `adb input text`, note it does NOT url-decode — type the literal URL; `adb -s SM02E4060314107 shell input text "rtmp://127.0.0.1/app"`.) Tap Go Live.
- [ ] **Step 4: Verify the live HUD shows real, moving signals.** Observe for ~20s: the GoLive ring morphed to the red Stop square; LIVE pill pulses; the elapsed timer increments; bitrate (kbps) is non-zero and updates; the health dot is green/amber by value; the audio meter moves when you speak. Open Settings while live → preview shrinks left via the signature spring and the rows are dimmed with the amber banner.
- [ ] **Step 5: Tear down the listener.** `pkill -f "listen 1 -i rtmp"` then `adb -s SM02E4060314107 reverse --remove tcp:1935`. Confirm `/tmp/out.flv` is non-empty: `ls -l /tmp/out.flv`.
- [ ] **Step 6: Write the smoke-test results.** Create `docs/superpowers/BEAUTIFUL_UI_SMOKE_TEST.md` documenting: device `SM02E4060314107`, the exact commands run, and PASS/FAIL for each verification point (landscape lock, immersive bars, preview-shrink transition, ring↔Stop morph, LIVE pulse, elapsed timer, real bitrate movement, health dot color, audio meter movement, dimmed-while-live banner). Note the Seeker has no camera HLG10 so the HDR row is absent (expected), and note that resolution/fps picker currently drives the health target only (encoder dims still fixed in `startMedia`) as a known carry-forward.
- [ ] **Step 7: Commit.** `git add docs/superpowers/BEAUTIFUL_UI_SMOKE_TEST.md && git commit -m "docs(ui): Beautiful UI device smoke test on the Seeker"`

---

## Self-Review (writing-plans)

**Spec coverage** — every spec section maps to tasks:
- Visual language / design-system foundation (Color/Type/Motion/GlassSurface, M3 Expressive bump) → Tasks 1.1–1.6.
- Orientation landscape + full-screen immersive → Task 1.7.
- Layout (camera left, rail in right letterbox, status top / actions bottom) → Tasks 4.7, 4.8.
- State flow (setup/connecting/live/reconnecting/error) → GoLiveButton (4.6), LiveStatusCluster (4.2, reconnecting variant), ControlRail error inline (4.7).
- Motion language (signature shrink, ring↔Stop morph, color shifts, LIVE pulse, press feedback) → Motion (1.4), GoLiveButton (4.6), HealthIndicator/AudioMeter color springs (4.3, 4.4), LiveStatusCluster pulse (4.2), Viewfinder shrink (4.8).
- Settings (Moblin UX: grouped rows push sub-screens, masked key, example hints, footer help, dimmed-while-live banner, HDR only when supported) → Tasks 3.4, 5.1–5.6.
- Real-stats plumbing (bytesSent EMA, queueDepth health, audio RMS, timer) → Tasks 2.1–2.7 (native + JNI + pure logic + engine wiring), VM timer (3.6).
- State extensions (StreamUiState, StreamConfig, codecOverride→CodecSelector) → Tasks 3.1–3.6.
- Architecture decomposition (Viewfinder/ControlRail/LiveStatusCluster/HealthIndicator/AudioMeter/ZoomChips/GoLiveButton/SettingsPanel+subs/PermissionGate) → Phase 4 + 5.
- Manifest/Activity landscape + immersive → Task 1.7, integration 6.1.
- Testing (unit pure logic, @Previews per state, device-verified) → TDD in 2.1–2.6, 3.1–3.6; @Previews across 4.x/5.x; device 6.2.
- Deferred/non-goals (no blur, no tap-to-focus, no auto-reconnect logic) → respected (scrim-only GlassSurface; reconnecting only *presented*; no focus controls added).

**Placeholder scan** — no `TODO`, no "similar to", no "add error handling": every code step contains complete code. (The one deliberate cross-task build dependency — settings panel + sub-screens — is called out explicitly in Task 5.1's execution note, not hidden.)

**Type/name consistency** — verified identical across tasks: `ConnectionHealth`, `VideoQuality`, `CodecOverride`, `resolveRequest`, `BitrateMeter`, `deriveHealth`, `formatElapsed`, `rms16`, `PlohoyTheme`, `GlassSurface`/`glassSurface`, `bytesSent`/`queueDepth`, `bitrateKbps`/`health`/`audioLevel` StateFlows (mirroring `activeHdr`/`encoderSurface`), `SettingsRoute`, `publishAudioLevel`, `publishEncoderSurface`.
