# OBS Scene Switcher on the Viewfinder — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface OBS scene switching on the live viewfinder (right ControlRail) as a tap-to-expand chip, so the user no longer opens Settings → OBS Remote to change scenes.

**Architecture:** Pure UI. The ViewModel already exposes `obsConnected`, `obsScenes`, `obsCurrentScene`, and `obsSwitchScene(name)` and mirrors them into `StreamUiState`. We add a gating helper on `StreamUiState`, a new `ObsSceneChip` composable, and thread four params through `ControlRail` from the existing `Viewfinder` call site. No ViewModel, OBS-layer, or capture changes.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), JUnit (JVM unit tests in `app/src/test/`). Build via Gradle wrapper.

This is Feature 1 of two from the spec `docs/superpowers/specs/2026-06-16-obs-scene-switch-and-dual-camera-design.md`. Feature 2 (dual-camera PiP) is a separate plan, written after this lands.

---

### Task 1: Gating helper on StreamUiState

The chip shows only when OBS is connected AND has scenes. Make that a tested derived property (the codebase tests UiState logic this way — see `StreamUiStateTest`).

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/stream/StreamUiState.kt`
- Test: `app/src/test/java/com/example/plohoystream/stream/StreamUiStateTest.kt`

- [ ] **Step 1: Write the failing test**

Add this test method inside the existing `class StreamUiStateTest` body in
`app/src/test/java/com/example/plohoystream/stream/StreamUiStateTest.kt`:

```kotlin
    @Test fun obsSceneSwitcherVisible_requiresConnectedAndNonEmptyScenes() {
        assertFalse(StreamUiState().obsSceneSwitcherVisible)                                  // default: not connected
        assertFalse(StreamUiState(obsConnected = true).obsSceneSwitcherVisible)               // connected, no scenes
        assertFalse(StreamUiState(obsScenes = listOf("Main")).obsSceneSwitcherVisible)        // scenes, not connected
        assertTrue(StreamUiState(obsConnected = true, obsScenes = listOf("Main")).obsSceneSwitcherVisible)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.StreamUiStateTest"`
Expected: FAIL to COMPILE — `obsSceneSwitcherVisible` is an unresolved reference.

- [ ] **Step 3: Add the derived property**

In `app/src/main/java/com/example/plohoystream/stream/StreamUiState.kt`, add this property
inside the `StreamUiState` body, next to the existing `settingsOpen` getter:

```kotlin
    /**
     * The viewfinder scene switcher shows only when the OBS WebSocket is connected and OBS has
     * reported at least one scene. Connecting still lives in Settings → OBS Remote.
     */
    val obsSceneSwitcherVisible: Boolean
        get() = obsConnected && obsScenes.isNotEmpty()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.example.plohoystream.stream.StreamUiStateTest"`
Expected: PASS (all methods, including the new one).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/stream/StreamUiState.kt \
        app/src/test/java/com/example/plohoystream/stream/StreamUiStateTest.kt
git commit -m "feat(obs): add obsSceneSwitcherVisible gate to StreamUiState

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: ObsSceneChip composable

A collapsed `Scene: <current> ⌄` chip that expands into a tappable scene list. Styling mirrors `LensButtons` (`Color.Black.copy(alpha = 0.45f)` pill) and the settings scene list (green `●` on the current scene). Holds only local `expanded` state; all data passed in. No unit test — there is no Compose UI-test harness in this project (tests are JVM-only); correctness of the visible widget is verified by the build compiling and on-device check at the end.

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/viewfinder/ObsSceneChip.kt`

- [ ] **Step 1: Create the composable**

Create `app/src/main/java/com/example/plohoystream/ui/viewfinder/ObsSceneChip.kt`:

```kotlin
package com.example.plohoystream.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.plohoystream.ui.theme.OnSurfaceMuted
import com.example.plohoystream.ui.theme.OnSurfaceWhite
import com.example.plohoystream.ui.theme.PlohoyTheme

/**
 * Compact OBS scene switcher for the right control rail. Collapsed, it shows the current scene;
 * tapping toggles a vertical list of all scenes. Selecting a scene calls [onSwitchScene] and
 * collapses. The caller gates visibility (only when OBS is connected with a non-empty list) —
 * see StreamUiState.obsSceneSwitcherVisible.
 */
@Composable
fun ObsSceneChip(
    scenes: List<String>,
    currentScene: String?,
    onSwitchScene: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Scene: ${currentScene ?: "—"}",
                color = OnSurfaceWhite,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse scenes" else "Expand scenes",
                tint = OnSurfaceMuted,
            )
        }
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                scenes.forEach { scene ->
                    val isCurrent = scene == currentScene
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isCurrent) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { onSwitchScene(scene); expanded = false }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = scene,
                            color = if (isCurrent) OnSurfaceWhite else OnSurfaceMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (isCurrent) {
                            Text("●", color = Color(0xFF4CAF50), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Preview(name = "scene chip", widthDp = 200, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun ObsSceneChipPreview() = PlohoyTheme {
    ObsSceneChip(scenes = listOf("Main", "BRB", "Starting Soon"), currentScene = "Main", onSwitchScene = {})
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (the new file compiles; `Icons.Filled.ExpandMore`/`ExpandLess` resolve from the icons library already used by `ControlRail`).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/ui/viewfinder/ObsSceneChip.kt
git commit -m "feat(obs): ObsSceneChip composable for viewfinder scene switching

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Render the chip in ControlRail

Add four params to `ControlRail` and render `ObsSceneChip` in the top cluster (below the lens buttons), gated by a `showObsScenes` flag. Update the four `@Preview` functions to pass the new args.

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/ui/viewfinder/ControlRail.kt`

- [ ] **Step 1: Add the new parameters**

In `ControlRail.kt`, change the function signature. Replace the parameter block
(currently ending `onSettings: () -> Unit,` then `modifier: Modifier = Modifier,`) so it reads:

```kotlin
    onGoLive: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    showObsScenes: Boolean,
    obsScenes: List<String>,
    obsCurrentScene: String?,
    onSwitchScene: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
```

- [ ] **Step 2: Render the chip in the top cluster**

In `ControlRail.kt`, inside the top `Column(verticalArrangement = Arrangement.spacedBy(12.dp))`,
immediately after the `LensButtons(...)` call (line ~71) and before the `if (errorReason != null)`
block, insert:

```kotlin
            if (showObsScenes) {
                ObsSceneChip(
                    scenes = obsScenes,
                    currentScene = obsCurrentScene,
                    onSwitchScene = onSwitchScene,
                )
            }
```

- [ ] **Step 3: Update the four preview functions**

Each `@Preview` composable calls `ControlRail(...)` positionally. Append the four new args
(`false, emptyList(), null, {}`) right before the trailing lambda args. Replace the four preview
bodies so each `ControlRail(...)` call ends with `..., {}, {}, {}, {}, {}, false, emptyList(), null, {})`.
Concretely, update each call:

```kotlin
@Preview(name = "setup", widthDp = 240, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailSetupPreview() = PlohoyTheme {
    ControlRail(StreamState.Idle, "00:00", ConnectionHealth.Good, 0, 0f, emptyList(), null, true, null, {}, {}, {}, {}, {}, false, emptyList(), null, {})
}

@Preview(name = "live", widthDp = 240, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailLivePreview() = PlohoyTheme {
    ControlRail(StreamState.Live, "01:23", ConnectionHealth.Warn, 4200, 0.8f, emptyList(), null, false, null, {}, {}, {}, {}, {}, true, listOf("Main", "BRB"), "Main", {})
}

@Preview(name = "error", widthDp = 240, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailErrorPreview() = PlohoyTheme {
    ControlRail(StreamState.Error("Connection refused"), "00:00", ConnectionHealth.Bad, 0, 0f, emptyList(), null, true, "Connection refused", {}, {}, {}, {}, {}, false, emptyList(), null, {})
}

@Preview(name = "reconnecting", widthDp = 240, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailReconnectingPreview() = PlohoyTheme {
    ControlRail(StreamState.Reconnecting, "01:30", ConnectionHealth.Warn, 0, 0f, emptyList(), null, false, null, {}, {}, {}, {}, {}, true, listOf("Main", "BRB"), "BRB", {})
}
```

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (If it fails on the preview argument count, recount: the
non-default params are now 18 positional args before `modifier`.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/ui/viewfinder/ControlRail.kt
git commit -m "feat(obs): render ObsSceneChip in ControlRail behind a visibility gate

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Wire the Viewfinder call site

Pass the OBS state and the switch callback from the existing `ControlRail(...)` call in
`Viewfinder.kt`. `ui` (the collected `StreamUiState`) and `viewModel` are already in scope there.

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt:312-335`

- [ ] **Step 1: Add the four arguments**

In the `ControlRail(...)` call (the `else` branch of the `AnimatedContent`), add the four new
named arguments immediately after `onSettings = viewModel::openSettings,` and before
`modifier = Modifier.fillMaxSize().padding(12.dp),`:

```kotlin
                            onSettings = viewModel::openSettings,
                            showObsScenes = ui.obsSceneSwitcherVisible,
                            obsScenes = ui.obsScenes,
                            obsCurrentScene = ui.obsCurrentScene,
                            onSwitchScene = viewModel::obsSwitchScene,
                            modifier = Modifier.fillMaxSize().padding(12.dp),
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the full unit test suite (regression check)**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — all existing tests plus the Task 1 test pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt
git commit -m "feat(obs): wire viewfinder scene switcher to OBS state + obsSwitchScene

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: On-device verification

No automated UI test exists for the live screen, so confirm the behavior on a device/emulator.

**Files:** none (manual verification)

- [ ] **Step 1: Build and install the debug APK**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL, app installed.

- [ ] **Step 2: Verify the behavior**

Manual checks:
1. With OBS **not** connected: the `Scene:` chip is **absent** from the right rail.
2. Connect to OBS (Settings → OBS Remote, valid host/port/password). Return to the
   viewfinder: the `Scene: <current> ⌄` chip appears in the rail.
3. Tap the chip → the scene list expands. Tap a different scene → OBS switches to it,
   the chip collapses, and `Scene:` shows the new name.
4. Switch a scene from OBS directly (on the desktop) → the chip's `Scene:` label updates
   to reflect it.

- [ ] **Step 3: Note the result**

Record pass/fail for each check. If all pass, Feature 1 is complete and the branch is ready
to merge (use superpowers:finishing-a-development-branch). Feature 2 (dual-camera PiP) is a
separate plan.
