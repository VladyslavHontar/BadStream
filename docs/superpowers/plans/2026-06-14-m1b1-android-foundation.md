# M1-B.1: Android Foundation (Compose app + StreamEngine + ViewModel + UI) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Android app's architecture and UI — a Jetpack Compose single-screen app driven by a `StreamEngine` abstraction and a `StreamViewModel`, wired to a `FakeStreamEngine` so the whole streaming UI and state machine work end-to-end on any emulator with no camera, network, or JNI.

**Architecture:** UI (Compose `StreamScreen`) → `StreamViewModel` (exposes `StreamUiState` as `StateFlow`) → `StreamEngine` interface (`FakeStreamEngine` now; `CameraStreamEngine` in M1-B.2/B.3). Clean inward dependencies; the UI never knows about camera/JNI. This is build step 1 of the M1-B design spec (`docs/superpowers/specs/2026-06-14-m1b-android-capture-stream-design.md`), split out as its own demonstrable plan.

**Tech Stack:** Kotlin 2.2.10 (built into AGP 9.2.1), Jetpack Compose (BOM 2025.06.00 + compose-compiler plugin 2.2.10), AndroidX Lifecycle ViewModel 2.9.0, Kotlinx Coroutines 1.9.0, JUnit4 + coroutines-test. **All versions below were empirically verified to compile on this exact toolchain.**

---

## File Structure

```
gradle/libs.versions.toml                                  # MODIFIED: add compose/coroutines/lifecycle libs + compose plugin
app/build.gradle.kts                                       # MODIFIED: apply compose plugin, buildFeatures.compose, deps
app/src/main/java/com/example/plohoystream/
  MainActivity.kt                                          # MODIFIED: ComponentActivity + setContent { StreamScreen(...) }
  stream/
    StreamState.kt                                         # NEW: sealed StreamState
    StreamConfig.kt                                        # NEW: StreamConfig (url, key)
    StreamUiState.kt                                       # NEW: StreamUiState (url, key, stream) + derived flags
    StreamEngine.kt                                        # NEW: StreamEngine interface
    FakeStreamEngine.kt                                    # NEW: in-memory engine (tests + app for now)
    StreamViewModel.kt                                     # NEW: ViewModel over StreamEngine
  ui/
    StreamScreen.kt                                        # NEW: Compose UI
app/src/test/java/com/example/plohoystream/
  MainDispatcherRule.kt                                    # NEW: JUnit rule swapping Dispatchers.Main for tests
  stream/
    StreamUiStateTest.kt                                   # NEW
    FakeStreamEngineTest.kt                                # NEW
    StreamViewModelTest.kt                                 # NEW
```

**Build/test commands (from repo root):**
```bash
./gradlew :app:testDebugUnitTest                # run JVM unit tests
./gradlew :app:assembleDebug                    # build the APK
```

---

### Task 0: Enable Compose + foundation dependencies; convert MainActivity

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/example/plohoystream/MainActivity.kt`
- Create: `app/src/test/java/com/example/plohoystream/MainDispatcherRule.kt`

- [ ] **Step 1: Replace `gradle/libs.versions.toml` with the verified versions**

```toml
[versions]
agp = "9.2.1"
kotlin = "2.2.10"
coreKtx = "1.10.1"
junit = "4.13.2"
junitVersion = "1.1.5"
espressoCore = "3.5.1"
appcompat = "1.6.1"
material = "1.10.0"
constraintlayout = "2.1.4"
composeBom = "2025.06.00"
activityCompose = "1.10.1"
lifecycle = "2.9.0"
coroutines = "1.9.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
androidx-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
androidx-constraintlayout = { group = "androidx.constraintlayout", name = "constraintlayout", version.ref = "constraintlayout" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: Update `app/build.gradle.kts`** — apply the compose plugin, enable `compose`, drop `viewBinding`, add deps.

In the `plugins { }` block, after `alias(libs.plugins.android.application)` add:
```kotlin
    alias(libs.plugins.compose.compiler)
```
Replace the `buildFeatures { viewBinding = true }` block with:
```kotlin
    buildFeatures {
        compose = true
    }
```
Replace the entire `dependencies { }` block with:
```kotlin
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
```
(We drop `appcompat`, `material` (the View-system one), and `constraintlayout` — Compose replaces them. The catalog still declares them harmlessly.)

- [ ] **Step 3: Rewrite `MainActivity.kt`** to a Compose host (temporary `FakeStreamEngine` wiring; the real engine arrives in M1-B.3):

```kotlin
package com.example.plohoystream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.plohoystream.stream.FakeStreamEngine
import com.example.plohoystream.stream.StreamEngine
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.StreamScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M1-B.1 uses a FakeStreamEngine; M1-B.3 swaps in CameraStreamEngine.
        val engine: StreamEngine = FakeStreamEngine()
        setContent {
            MaterialTheme {
                val vm: StreamViewModel = viewModel(
                    factory = object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                            StreamViewModel(engine) as T
                    }
                )
                StreamScreen(vm)
            }
        }
    }
}
```
> NOTE: `MainActivity` references `StreamViewModel`, `StreamScreen`, `FakeStreamEngine` which are created in Tasks 2–4. It will not compile until those exist — that's expected; Task 0's build check is deferred to Step 5 after a placeholder, OR run Task 0 build only after Task 4. To keep Task 0 independently green, temporarily make `setContent { MaterialTheme { } }` empty now and wire `StreamScreen` in Task 5. **Do that:** in Step 3 use an empty `setContent { MaterialTheme { } }` body and remove the stream imports; the full wiring is Task 5.

Corrected Step 3 body (use this — compiles standalone):
```kotlin
package com.example.plohoystream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { } }
    }
}
```

- [ ] **Step 4: Create the test dispatcher rule** `app/src/test/java/com/example/plohoystream/MainDispatcherRule.kt`:
```kotlin
package com.example.plohoystream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    val dispatcher: kotlinx.coroutines.test.TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
    override fun finished(description: Description) { Dispatchers.resetMain() }
}
```

- [ ] **Step 5: Build to verify the toolchain**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (Verifies Compose compiler engages and all deps resolve.)

- [ ] **Step 6: Commit**
```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/example/plohoystream/MainActivity.kt app/src/test/java/com/example/plohoystream/MainDispatcherRule.kt
git commit -m "build(m1b1): enable Jetpack Compose + coroutines/lifecycle foundation"
```

---

### Task 1: Stream models (`StreamState`, `StreamConfig`, `StreamUiState`)

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/stream/StreamState.kt`
- Create: `app/src/main/java/com/example/plohoystream/stream/StreamConfig.kt`
- Create: `app/src/main/java/com/example/plohoystream/stream/StreamUiState.kt`
- Test: `app/src/test/java/com/example/plohoystream/stream/StreamUiStateTest.kt`

- [ ] **Step 1: Write the failing test** for the derived UI flags (the only real logic here):
```kotlin
package com.example.plohoystream.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamUiStateTest {
    @Test fun canGoLive_requiresUrlKeyAndIdleOrError() {
        assertFalse(StreamUiState("", "", StreamState.Idle).canGoLive)         // no url/key
        assertFalse(StreamUiState("rtmp://h/app", "", StreamState.Idle).canGoLive) // no key
        assertTrue(StreamUiState("rtmp://h/app", "k", StreamState.Idle).canGoLive)
        assertTrue(StreamUiState("rtmp://h/app", "k", StreamState.Error("x")).canGoLive)
        assertFalse(StreamUiState("rtmp://h/app", "k", StreamState.Live).canGoLive) // already live
    }
    @Test fun isActive_trueWhileConnectingLiveStopping() {
        assertTrue(StreamUiState(stream = StreamState.Connecting).isActive)
        assertTrue(StreamUiState(stream = StreamState.Live).isActive)
        assertTrue(StreamUiState(stream = StreamState.Stopping).isActive)
        assertFalse(StreamUiState(stream = StreamState.Idle).isActive)
        assertFalse(StreamUiState(stream = StreamState.Error("x")).isActive)
    }
}
```

- [ ] **Step 2: Run — verify it fails** (types don't exist).
Run: `./gradlew :app:testDebugUnitTest --tests "*StreamUiStateTest"`
Expected: compile failure (`StreamUiState` unresolved).

- [ ] **Step 3: Implement the models**

`StreamState.kt`:
```kotlin
package com.example.plohoystream.stream

sealed interface StreamState {
    data object Idle : StreamState
    data object Connecting : StreamState
    data object Live : StreamState
    data object Stopping : StreamState
    data class Error(val reason: String) : StreamState
}
```
`StreamConfig.kt`:
```kotlin
package com.example.plohoystream.stream

/** Egress target for M1-B. Resolution/bitrate/codec are auto-selected natively (M1-B.3). */
data class StreamConfig(
    val rtmpUrl: String,   // e.g. "rtmp://live.twitch.tv/app"
    val streamKey: String,
)
```
`StreamUiState.kt`:
```kotlin
package com.example.plohoystream.stream

data class StreamUiState(
    val url: String = "",
    val key: String = "",
    val stream: StreamState = StreamState.Idle,
) {
    val canGoLive: Boolean
        get() = url.isNotBlank() && key.isNotBlank() &&
            (stream is StreamState.Idle || stream is StreamState.Error)

    val isActive: Boolean
        get() = stream is StreamState.Connecting ||
            stream is StreamState.Live ||
            stream is StreamState.Stopping
}
```

- [ ] **Step 4: Run — verify pass**
Run: `./gradlew :app:testDebugUnitTest --tests "*StreamUiStateTest"`
Expected: 2 tests PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/example/plohoystream/stream/StreamState.kt app/src/main/java/com/example/plohoystream/stream/StreamConfig.kt app/src/main/java/com/example/plohoystream/stream/StreamUiState.kt app/src/test/java/com/example/plohoystream/stream/StreamUiStateTest.kt
git commit -m "feat(m1b1): stream models (StreamState/Config/UiState) (TDD)"
```

---

### Task 2: `StreamEngine` interface + `FakeStreamEngine`

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/stream/StreamEngine.kt`
- Create: `app/src/main/java/com/example/plohoystream/stream/FakeStreamEngine.kt`
- Test: `app/src/test/java/com/example/plohoystream/stream/FakeStreamEngineTest.kt`

- [ ] **Step 1: Write the failing test**
```kotlin
package com.example.plohoystream.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class FakeStreamEngineTest {
    @Test fun start_movesToConnecting_andRecordsConfig() {
        val e = FakeStreamEngine()
        e.start(StreamConfig("rtmp://h/app", "k"))
        assertEquals(StreamState.Connecting, e.state.value)
        assertEquals(StreamConfig("rtmp://h/app", "k"), e.lastConfig)
    }
    @Test fun emitLive_thenStop_goesStoppingThenIdle() {
        val e = FakeStreamEngine()
        e.start(StreamConfig("rtmp://h/app", "k"))
        e.emitLive()
        assertEquals(StreamState.Live, e.state.value)
        e.stop()
        assertEquals(StreamState.Idle, e.state.value)
    }
    @Test fun emitError_setsErrorWithReason() {
        val e = FakeStreamEngine()
        e.start(StreamConfig("rtmp://h/app", "k"))
        e.emitError("connect failed")
        assertEquals(StreamState.Error("connect failed"), e.state.value)
    }
}
```

- [ ] **Step 2: Run — verify it fails.**
Run: `./gradlew :app:testDebugUnitTest --tests "*FakeStreamEngineTest"`
Expected: compile failure.

- [ ] **Step 3: Implement**

`StreamEngine.kt`:
```kotlin
package com.example.plohoystream.stream

import kotlinx.coroutines.flow.StateFlow

/**
 * What the UI/ViewModel depend on. Real impl (CameraStreamEngine) arrives in M1-B.3;
 * live camera controls (zoom/lens/flip) are added to this interface in M1-B.2.
 */
interface StreamEngine {
    val state: StateFlow<StreamState>
    fun start(config: StreamConfig)
    fun stop()
}
```
`FakeStreamEngine.kt`:
```kotlin
package com.example.plohoystream.stream

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory engine: drives states for tests, @Preview, and the M1-B.1 app shell. */
class FakeStreamEngine : StreamEngine {
    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    override val state: StateFlow<StreamState> = _state.asStateFlow()

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

    // Test/preview controls to drive transitions a real engine would make over time.
    fun emitLive() { _state.value = StreamState.Live }
    fun emitError(reason: String) { _state.value = StreamState.Error(reason) }
    fun emitIdle() { _state.value = StreamState.Idle }
}
```

- [ ] **Step 4: Run — verify pass** (`--tests "*FakeStreamEngineTest"`) → 3 PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/example/plohoystream/stream/StreamEngine.kt app/src/main/java/com/example/plohoystream/stream/FakeStreamEngine.kt app/src/test/java/com/example/plohoystream/stream/FakeStreamEngineTest.kt
git commit -m "feat(m1b1): StreamEngine interface + FakeStreamEngine (TDD)"
```

---

### Task 3: `StreamViewModel`

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/stream/StreamViewModel.kt`
- Test: `app/src/test/java/com/example/plohoystream/stream/StreamViewModelTest.kt`

- [ ] **Step 1: Write the failing test** (uses `MainDispatcherRule` from Task 0):
```kotlin
package com.example.plohoystream.stream

import com.example.plohoystream.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamViewModelTest {
    @get:Rule val mainRule = MainDispatcherRule()

    @Test fun setUrlKey_thenGoLive_startsEngineWithConfig() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine)
        vm.setUrl("rtmp://h/app"); vm.setKey("k")
        vm.goLive()
        advanceUntilIdle()
        assertEquals(StreamConfig("rtmp://h/app", "k"), engine.lastConfig)
        assertEquals(StreamState.Connecting, vm.uiState.value.stream)
    }

    @Test fun goLive_ignoredWhenUrlOrKeyBlank() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine)
        vm.setUrl("rtmp://h/app")   // no key
        vm.goLive()
        advanceUntilIdle()
        assertEquals(null, engine.lastConfig)
        assertEquals(StreamState.Idle, vm.uiState.value.stream)
    }

    @Test fun engineStateChanges_propagateToUiState() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine)
        advanceUntilIdle()
        engine.start(StreamConfig("rtmp://h/app", "k"))
        engine.emitLive()
        advanceUntilIdle()
        assertEquals(StreamState.Live, vm.uiState.value.stream)
        assertTrue(vm.uiState.value.isActive)
    }

    @Test fun stop_delegatesToEngine() = runTest {
        val engine = FakeStreamEngine()
        val vm = StreamViewModel(engine)
        vm.setUrl("rtmp://h/app"); vm.setKey("k"); vm.goLive(); engine.emitLive()
        advanceUntilIdle()
        vm.stop()
        advanceUntilIdle()
        assertEquals(StreamState.Idle, vm.uiState.value.stream)
    }
}
```

- [ ] **Step 2: Run — verify it fails.**
Run: `./gradlew :app:testDebugUnitTest --tests "*StreamViewModelTest"`
Expected: compile failure.

- [ ] **Step 3: Implement** `StreamViewModel.kt`:
```kotlin
package com.example.plohoystream.stream

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StreamViewModel(private val engine: StreamEngine) : ViewModel() {
    private val _uiState = MutableStateFlow(StreamUiState())
    val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            engine.state.collect { s -> _uiState.update { it.copy(stream = s) } }
        }
    }

    fun setUrl(value: String) = _uiState.update { it.copy(url = value) }
    fun setKey(value: String) = _uiState.update { it.copy(key = value) }

    fun goLive() {
        val s = _uiState.value
        if (s.canGoLive) engine.start(StreamConfig(s.url, s.key))
    }

    fun stop() = engine.stop()
}
```

- [ ] **Step 4: Run — verify pass** (`--tests "*StreamViewModelTest"`) → 4 PASS. Also run the whole suite: `./gradlew :app:testDebugUnitTest` (all green).

- [ ] **Step 5: Commit**
```bash
git add app/src/main/java/com/example/plohoystream/stream/StreamViewModel.kt app/src/test/java/com/example/plohoystream/stream/StreamViewModelTest.kt
git commit -m "feat(m1b1): StreamViewModel over StreamEngine (TDD)"
```

---

### Task 4: `StreamScreen` Compose UI

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/ui/StreamScreen.kt`

(UI is verified by building + the `@Preview` + running on a device, not unit tests — Compose UI behavior is integration, and the logic it renders is already tested in `StreamViewModel`/`StreamUiState`.)

- [ ] **Step 1: Implement `StreamScreen.kt`**
```kotlin
package com.example.plohoystream.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.plohoystream.stream.FakeStreamEngine
import com.example.plohoystream.stream.StreamState
import com.example.plohoystream.stream.StreamUiState
import com.example.plohoystream.stream.StreamViewModel

@Composable
fun StreamScreen(viewModel: StreamViewModel) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    StreamScreenContent(
        ui = ui,
        onUrl = viewModel::setUrl,
        onKey = viewModel::setKey,
        onGoLive = viewModel::goLive,
        onStop = viewModel::stop,
    )
}

@Composable
private fun StreamScreenContent(
    ui: StreamUiState,
    onUrl: (String) -> Unit,
    onKey: (String) -> Unit,
    onGoLive: () -> Unit,
    onStop: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("PlohoyStream", style = MaterialTheme.typography.headlineMedium)
            Text(statusText(ui.stream), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = ui.url, onValueChange = onUrl,
                label = { Text("RTMP URL") }, singleLine = true,
                enabled = !ui.isActive, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ui.key, onValueChange = onKey,
                label = { Text("Stream key") }, singleLine = true,
                enabled = !ui.isActive, modifier = Modifier.fillMaxWidth(),
            )
            if (ui.isActive) {
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
            } else {
                Button(onClick = onGoLive, enabled = ui.canGoLive, modifier = Modifier.fillMaxWidth()) {
                    Text("Go Live")
                }
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

@Preview(showBackground = true)
@Composable
private fun StreamScreenPreview() {
    MaterialTheme {
        StreamScreenContent(
            ui = StreamUiState(url = "rtmp://live.twitch.tv/app", key = "abc", stream = StreamState.Idle),
            onUrl = {}, onKey = {}, onGoLive = {}, onStop = {},
        )
    }
}
```
> Note: `collectAsStateWithLifecycle` comes from `androidx.lifecycle.lifecycle-runtime-compose`, which is pulled in transitively by `lifecycle-viewmodel-compose` 2.9.0. If the import is unresolved, add `implementation("androidx.lifecycle:lifecycle-runtime-compose")` (BOM-less, version 2.9.0) to deps — but with 2.9.0 it resolves transitively.

- [ ] **Step 2: Build to verify it compiles**
Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**
```bash
git add app/src/main/java/com/example/plohoystream/ui/StreamScreen.kt
git commit -m "feat(m1b1): Compose StreamScreen (status + url/key + Go Live/Stop)"
```

---

### Task 5: Wire `MainActivity` → `StreamScreen`; run the app

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/MainActivity.kt`

- [ ] **Step 1: Replace `MainActivity.kt` with the full wiring** (the version deferred in Task 0):
```kotlin
package com.example.plohoystream

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.plohoystream.stream.FakeStreamEngine
import com.example.plohoystream.stream.StreamEngine
import com.example.plohoystream.stream.StreamViewModel
import com.example.plohoystream.ui.StreamScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // M1-B.1: FakeStreamEngine. M1-B.3 replaces this with CameraStreamEngine.
        val engine: StreamEngine = FakeStreamEngine()
        setContent {
            MaterialTheme {
                val vm: StreamViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        StreamViewModel(engine) as T
                })
                StreamScreen(vm)
            }
        }
    }
}
```

- [ ] **Step 2: Build the APK**
Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`; APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Manual run (acceptance)**

Install on an emulator/device (`./gradlew :app:installDebug` or Android Studio Run). Expected: app launches showing "PlohoyStream", status "Idle", RTMP URL + Stream key fields, a disabled "Go Live" button that enables once both fields are filled. Tapping Go Live shows "Connecting…" (the `FakeStreamEngine` stops there by design — it has no `emitLive()` caller in the app yet; that's fine for M1-B.1, the real transition comes with the camera engine). The fields disable while active. This proves the full UI ↔ ViewModel ↔ engine wiring on a real device.

> Optional: to see the full Idle→Live→Idle cycle in the app now, you may temporarily wire a debug button calling the engine's `emitLive()`; not required for the plan.

- [ ] **Step 4: Commit**
```bash
git add app/src/main/java/com/example/plohoystream/MainActivity.kt
git commit -m "feat(m1b1): wire MainActivity to StreamScreen via StreamViewModel"
```

---

## Self-Review

**Spec coverage (M1-B.1 = build step 1 of the spec):**
- `StreamEngine` interface + `FakeStreamEngine` (UI/tests with no camera/network) → ✅ Task 2.
- `StreamViewModel` + state machine surfaced as `StateFlow` → ✅ Task 3.
- Compose `StreamScreen` (status, URL/key, Go Live/Stop) → ✅ Task 4/5.
- `StreamState` (Idle/Connecting/Live/Stopping/Error) → ✅ Task 1.
- TDD for the deterministic logic (UiState flags, Fake transitions, ViewModel) → ✅ Tasks 1–3.
- Deferred to M1-B.2/B.3 (correctly NOT here): camera preview, capability layer, zoom/lens controls + the engine's live-control methods, permissions flow, MediaCodec, JNI/native session, foreground service, DataStore persistence, the real `CameraStreamEngine`. Stated in the spec's build sequence.

**Placeholder scan:** No "TBD"/"add error handling" gaps. Task 0 Step 3 contains a deliberate two-version sequence (empty body to keep Task 0 standalone-green, full wiring in Task 5) — explicitly called out, not a placeholder.

**Type consistency:** `StreamState` cases, `StreamConfig(rtmpUrl, streamKey)`, `StreamUiState(url, key, stream)` + `canGoLive`/`isActive`, `StreamEngine.start/stop/state`, `FakeStreamEngine.lastConfig/emitLive/emitError/emitIdle`, `StreamViewModel.setUrl/setKey/goLive/stop/uiState` are consistent across Tasks 1–5. The verified Gradle aliases (Task 0) match every `libs.*` reference.

**Toolchain note:** every dependency version and the Compose-compiler-plugin setup in Task 0 was empirically compiled on this machine (AGP 9.2.1 / Kotlin 2.2.10) before this plan was written — not guessed.

---

## Execution Handoff

This plan (M1-B.1) delivers a running Compose app with the full streaming UI + state machine + ViewModel over a `FakeStreamEngine`, fully unit-tested, on any emulator. **M1-B.2** (CameraCapabilities + Camera2 preview + live zoom/lens/flip) and **M1-B.3** (MediaCodec encode + native StreamSession/JNI + foreground service + go-live) follow as their own plans.
