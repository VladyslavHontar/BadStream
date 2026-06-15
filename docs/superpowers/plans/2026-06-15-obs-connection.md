# OBS Connection (obs-websocket v5 remote) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Phone controls a remote OBS over obs-websocket v5 — connect/auth, list+switch scenes, start/stop OBS's stream, and auto-switch OBS to a BRB scene when the phone's `StreamState` goes `Reconnecting` (back to main on `Live`).

**Architecture:** A process-scoped `ObsWebSocketController` (held by `LivePipeline`) wraps an OkHttp WebSocket. Pure, host-testable helpers do the v5 auth, the JSON message build/parse, and the BRB switch decision; the controller adds transport, 10 s ping, backoff reconnect, and collects `engine.state` for BRB. Settings gains OBS fields (auto-persisted); a new OBS settings sub-screen drives it.

**Tech Stack:** Kotlin, OkHttp WebSocket (new dep), kotlinx-serialization (JSON), Coroutines/StateFlow, Jetpack Compose. JUnit unit tests. No C++.

**Spec:** `docs/superpowers/specs/2026-06-15-obs-connection-design.md`

**Test command:** `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`. Real-OBS verification is on-device (Task 6).

---

## Phase 1 — Dependency + Settings fields

### Task 1.1: Add OkHttp

**Files:** `gradle/libs.versions.toml`, `app/build.gradle.kts`

- [ ] **Step 1:** In `libs.versions.toml` add under `[versions]`: `okhttp = "4.12.0"`; under `[libraries]`:
  `okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }` and
  `okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }`.
- [ ] **Step 2:** In `app/build.gradle.kts` dependencies add `implementation(libs.okhttp)` and `testImplementation(libs.okhttp.mockwebserver)`.
- [ ] **Step 3:** Run `./gradlew help` (or `:app:dependencies --configuration debugRuntimeClasspath | grep okhttp`) to confirm resolution. Expected: okhttp 4.12.0 resolves.
- [ ] **Step 4:** Commit: `git add gradle/libs.versions.toml app/build.gradle.kts && git commit -m "build(obs): add OkHttp 4.12.0 (+mockwebserver for tests)"`

### Task 1.2: Settings fields

**Files:** `app/src/main/java/com/example/plohoystream/stream/Settings.kt`, test `app/src/test/java/com/example/plohoystream/stream/SettingsRoundTripTest.kt`

- [ ] **Step 1: Write the failing test** — append to `SettingsRoundTripTest.kt` a case asserting the new fields round-trip through the serializer with defaults and explicit values:

```kotlin
@Test fun obsFields_roundTrip() {
    val s = Settings(
        obsHost = "192.168.1.42", obsPort = 4455, obsPassword = "pw",
        obsMainSceneName = "Main", obsBrbSceneName = "BRB", obsAutoSwitchEnabled = true,
    )
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val back = json.decodeFromString(Settings.serializer(), json.encodeToString(Settings.serializer(), s))
    assertEquals(s, back)
}
```

(Match the imports/Json config the existing tests in this file use.)

- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*SettingsRoundTripTest*"` → FAIL to compile (fields don't exist).
- [ ] **Step 3: Add the fields** to `Settings`:

```kotlin
    val obsHost: String = "",
    val obsPort: Int = 4455,
    val obsPassword: String = "",
    val obsMainSceneName: String = "",
    val obsBrbSceneName: String = "",
    val obsAutoSwitchEnabled: Boolean = false,
```

- [ ] **Step 4:** Run the test → PASS.
- [ ] **Step 5:** Commit: `feat(obs): add OBS connection settings fields (auto-persist)`

---

## Phase 2 — Pure logic (TDD, host-testable)

### Task 2.1: v5 auth

**Files:** Create `app/src/main/java/com/example/plohoystream/stream/obs/ObsAuth.kt`; test `app/src/test/java/com/example/plohoystream/stream/obs/ObsAuthTest.kt`

- [ ] **Step 1: Write the failing test** — compute the expected value independently in the test using `MessageDigest`/`Base64` so it's a true cross-check:

```kotlin
package com.example.plohoystream.stream.obs

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class ObsAuthTest {
    private fun expected(pw: String, salt: String, challenge: String): String {
        val sha = { s: String -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()) }
        val secret = Base64.getEncoder().encodeToString(sha(pw + salt))
        return Base64.getEncoder().encodeToString(sha(secret + challenge))
    }

    @Test fun matchesTwoStepAlgorithm() {
        val pw = "supersecret"; val salt = "lM3Gwd+H1NM4F2pdGcAA9w=="; val challenge = "+IxH4CnCleJIBO6bxKf2Lw=="
        assertEquals(expected(pw, salt, challenge), ObsAuth.compute(pw, salt, challenge))
    }

    @Test fun deterministic_and_nonEmpty() {
        val a = ObsAuth.compute("p", "s", "c")
        assertEquals(a, ObsAuth.compute("p", "s", "c"))
        assert(a.isNotEmpty())
    }
}
```

- [ ] **Step 2:** Run `./gradlew testDebugUnitTest --tests "*ObsAuthTest*"` → FAIL (no `ObsAuth`).
- [ ] **Step 3: Implement** `ObsAuth.kt`:

```kotlin
package com.example.plohoystream.stream.obs

import java.security.MessageDigest
import android.util.Base64 as ABase64

/** obs-websocket v5 authentication string: base64(sha256( base64(sha256(password+salt)) + challenge )). */
object ObsAuth {
    fun compute(password: String, salt: String, challenge: String): String {
        val secret = b64(sha256(password + salt))
        return b64(sha256(secret + challenge))
    }
    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    private fun b64(bytes: ByteArray): String = ABase64.encodeToString(bytes, ABase64.NO_WRAP)
}
```

NOTE: `android.util.Base64` is not available in plain JVM unit tests. To keep `ObsAuth` host-testable, use `java.util.Base64` instead (available on the JVM and on Android API 26+; minSdk here is ≥26). Replace the import/impl:

```kotlin
import java.security.MessageDigest
import java.util.Base64

object ObsAuth {
    fun compute(password: String, salt: String, challenge: String): String {
        val secret = b64(sha256(password + salt))
        return b64(sha256(secret + challenge))
    }
    private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
```

(Confirm `minSdk >= 26` in `app/build.gradle.kts`; `java.util.Base64` requires API 26. If minSdk < 26, use `android.util.Base64` and move this test to androidTest — but the project targets modern devices, so 26+ is expected.)

- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit: `feat(obs): v5 auth string helper (host-tested)`

### Task 2.2: Message build/parse

**Files:** Create `app/src/main/java/com/example/plohoystream/stream/obs/ObsMessages.kt`; test `.../obs/ObsMessagesTest.kt`

Use `kotlinx.serialization.json` (`JsonObject`/`buildJsonObject`/`Json.parseToJsonElement`) — works in JVM tests.

- [ ] **Step 1: Write failing tests** covering: building Identify (with and without auth, `eventSubscriptions=68`, `rpcVersion=1`); building a request (op 6) with `requestType`/`requestId`/`requestData`; parsing an incoming Hello (op 0, with and without `authentication`), Identified (op 2), a RequestResponse (op 7, success and failure), and the two events (op 5 `CurrentProgramSceneChanged`, `StreamStateChanged`). Model the parsed result as a sealed type:

```kotlin
sealed interface ObsIncoming {
    data class Hello(val salt: String?, val challenge: String?) : ObsIncoming
    data object Identified : ObsIncoming
    data class Response(val requestId: String, val requestType: String, val ok: Boolean, val data: JsonObject?) : ObsIncoming
    data class SceneChanged(val sceneName: String) : ObsIncoming
    data class StreamStateChanged(val active: Boolean) : ObsIncoming
    data object Unknown : ObsIncoming
}
```

Write assertions like: `parse(helloJsonWithAuth)` → `Hello(salt, challenge)`; `parse(helloNoAuth)` → `Hello(null, null)`; `parse(responseGetSceneList)` → `Response(...)` with `data` containing `scenes`; `buildIdentify(auth="X")` produces `{"op":1,"d":{"rpcVersion":1,"authentication":"X","eventSubscriptions":68}}` (assert by parsing it back); `buildIdentify(auth=null)` omits `authentication`.

- [ ] **Step 2:** Run → FAIL (no `ObsMessages`).
- [ ] **Step 3: Implement** `ObsMessages.kt` with `buildIdentify(auth: String?): String`, `buildRequest(requestType: String, requestId: String, requestData: JsonObject? = null): String`, and `parse(text: String): ObsIncoming`. Provide small typed helpers to read `GetSceneList` response data (`scenesFrom(data): List<String>`, `currentSceneFrom(data): String?`). Use `buildJsonObject { put("op", 6); putJsonObject("d") { ... } }` and `Json.parseToJsonElement`.
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit: `feat(obs): obs-websocket v5 message build/parse (host-tested)`

### Task 2.3: BRB switch decision

**Files:** Create `.../obs/BrbSwitch.kt`; test `.../obs/BrbSwitchTest.kt`

- [ ] **Step 1: Write failing tests** for a pure decision function returning the scene to switch to (or null):

```kotlin
// returns the OBS scene to switch to, or null for "do nothing"
fun decide(
    state: StreamState, autoEnabled: Boolean, connected: Boolean,
    currentScene: String?, mainScene: String, brbScene: String,
): String?
```

Cases:
- `Reconnecting`, enabled, connected, current==main, brb non-blank → returns `brbScene`.
- `Live`, enabled, connected, current==brb → returns `mainScene`.
- not enabled → null. not connected → null. current already brb on Reconnecting → null (no redundant). blank brb/main → null. `Idle`/`Error`/`Connecting`/`Stopping` → null.

- [ ] **Step 2:** Run → FAIL.
- [ ] **Step 3: Implement** `BrbSwitch.decide(...)` as a pure `when` over the cases above (guards: autoEnabled && connected; non-blank scene names; only switch from the expected current scene).
- [ ] **Step 4:** Run → PASS.
- [ ] **Step 5:** Commit: `feat(obs): pure BRB scene-switch decision (host-tested)`

---

## Phase 3 — Controller + LivePipeline integration

### Task 3.1: `ObsWebSocketController`

**Files:** Create `app/src/main/java/com/example/plohoystream/stream/obs/ObsWebSocketController.kt`

A class wrapping OkHttp `WebSocket` + `WebSocketListener`, owning `CoroutineScope(SupervisorJob() + Dispatchers.IO)`.

- [ ] **Step 1: Implement** with this surface (no UI, no Android UI deps):

```kotlin
class ObsWebSocketController(
    private val streamState: StateFlow<StreamState>,
    private val settings: Flow<Settings>,
    private val clientFactory: () -> OkHttpClient = { OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val connected: StateFlow<Boolean>            // Identified
    val scenes: StateFlow<List<String>>
    val currentScene: StateFlow<String?>
    val obsStreaming: StateFlow<Boolean>
    // actions (no-op when not connected):
    fun switchScene(name: String)
    fun startObsStream(); fun stopObsStream()
    fun refreshScenes()
}
```

Behavior:
- Collect `settings`: when `obsHost` is non-blank (treat that as "enabled" along with the UI toggle if you add one — for v1 use `obsHost.isNotBlank()`), (re)connect to `ws://$obsHost:$obsPort`; on host/port/password change, reconnect. When blank, disconnect.
- `WebSocketListener.onMessage` → `ObsMessages.parse`:
  - `Hello` → send `ObsMessages.buildIdentify(auth)` where `auth = salt/challenge?.let { ObsAuth.compute(password, salt, challenge) }` (null when Hello had no auth).
  - `Identified` → `connected=true`; send `GetSceneList` + `GetStreamStatus` (requestIds tracked).
  - `Response` (GetSceneList) → update `scenes` + `currentScene`; (GetStreamStatus) → `obsStreaming`.
  - `SceneChanged` → update `currentScene`. `StreamStateChanged` → update `obsStreaming`.
- Outbound requests use a monotonically increasing requestId (e.g. `"r${n++}"`).
- `pingInterval(10s)` on the OkHttp client handles keepalive; on `onFailure`/`onClosed` set `connected=false` and schedule reconnect with exponential backoff (500ms→cap 10s) while host is non-blank.
- **BRB:** `scope.launch { streamState.collect { st -> val cfg = latestSettings; BrbSwitch.decide(st, cfg.obsAutoSwitchEnabled, connected.value, currentScene.value, cfg.obsMainSceneName, cfg.obsBrbSceneName)?.let { switchScene(it) } } }`. Cache the latest Settings from the settings flow for the decision.
- `fun dispose()` cancels the scope + closes the socket (called only if the app fully tears down; LivePipeline keeps it for the process).

- [ ] **Step 2:** Compile: `./gradlew compileDebugKotlin` → SUCCESS.
- [ ] **Step 3:** Commit: `feat(obs): ObsWebSocketController (OkHttp v5 client + BRB auto-switch)`

### Task 3.2: Wire into `LivePipeline`

**Files:** `app/src/main/java/com/example/plohoystream/stream/LivePipeline.kt`

- [ ] **Step 1:** In `ensureInit`, after the engine is built, construct:

```kotlin
obs = ObsWebSocketController(engine.state, store.data)
```

Expose `lateinit var obs: ObsWebSocketController; private set`. (`engine.state` is the `StateFlow<StreamState>`; `store.data` is the `Flow<Settings>`.)

- [ ] **Step 2:** Compile → SUCCESS.
- [ ] **Step 3:** Commit: `feat(obs): hold ObsWebSocketController in LivePipeline`

---

## Phase 4 — ViewModel + UI

### Task 4.1: Expose OBS state + actions through the ViewModel

**Files:** `app/src/main/java/com/example/plohoystream/stream/StreamUiState.kt`, `StreamViewModel.kt`; test `StreamViewModelTest.kt`

- [ ] **Step 1:** Add to `StreamUiState`: `obsConnected: Boolean = false`, `obsScenes: List<String> = emptyList()`, `obsCurrentScene: String? = null`, `obsStreaming: Boolean = false`.
- [ ] **Step 2:** In `StreamViewModel`, accept the controller (default via `LivePipeline.obs` at the call site, but inject for tests — add a constructor param `obs: ObsRemote?` behind a small interface, OR collect the four StateFlows if a controller is provided). Collect its flows into `_uiState` (mirror the existing engine-stat collectors). Add setters via the existing `mutate { }` path for the OBS settings fields (`setObsHost/Port/Password/MainScene/BrbScene/AutoSwitch`), and action methods `obsSwitchScene(name)`, `obsStartStream()`, `obsStopStream()` delegating to the controller.
  - To keep `StreamViewModelTest` (which constructs the VM with a `FakeStreamEngine` + `FakeSettingsStore`) compiling and Android-free, define a minimal interface `ObsRemote` (the four `StateFlow`s + the three actions) that `ObsWebSocketController` implements; the VM depends on `ObsRemote?` (nullable, default null). Add a `FakeObsRemote` in `src/test` for a focused test (e.g. `obsSwitchScene_delegates`).
- [ ] **Step 3:** Write a small VM test: setting `obsAutoSwitchEnabled` persists via the store; `obsSwitchScene("X")` calls the fake. Run → PASS.
- [ ] **Step 4:** Commit: `feat(obs): expose OBS state + actions via StreamViewModel`

### Task 4.2: OBS settings sub-screen

**Files:** `app/src/main/java/com/example/plohoystream/ui/settings/SettingsRoute.kt`, `ObsSettings.kt` (new), `SettingsPanel.kt`

- [ ] **Step 1:** Add `Obs` to `SettingsRoute`.
- [ ] **Step 2:** Create `ObsSettings.kt` following the existing sub-screen pattern (read `DestinationSettings.kt` for the masked-secret field + grouped rows + `SubScreen` scroll usage). Include: enable/host/port/password fields (host/port/password dimmed-while-live), a connection-status line (from `ui.obsConnected`), a scenes list (from `ui.obsScenes`, highlight `ui.obsCurrentScene`, tap → `viewModel.obsSwitchScene`), Start/Stop OBS stream button (from `ui.obsStreaming`), main/BRB scene pickers (choose from `ui.obsScenes`), and the auto-switch toggle.
- [ ] **Step 3:** Add the `Obs` row to the settings Root list + route to `ObsSettings` in `SettingsPanel`'s `AnimatedContent` switch (mirror the other routes).
- [ ] **Step 4:** Compile + run unit tests: `./gradlew testDebugUnitTest` → all green.
- [ ] **Step 5:** Commit: `feat(obs): OBS settings sub-screen (connect, scenes, start/stop, BRB)`

---

## Phase 5 — Verify + smoke doc

### Task 5.1: Full suite + APK
- [ ] `./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL; all existing + new unit tests pass; APK builds. Commit any incidental fixes.

### Task 5.2: Smoke doc
- [ ] Create `docs/superpowers/OBS_SMOKE.md`: run OBS on the LAN with obs-websocket enabled (Tools → WebSocket Server Settings; note port 4455 + password); enter host/port/password in the app's OBS settings; confirm Connected + scene list; tap a scene → OBS switches; Start/Stop OBS stream from the app; enable auto-switch with main+BRB scenes, go live, drop the phone's network → OBS flips to BRB → restore → OBS flips back to main. Mark as the user's manual acceptance step.
- [ ] Commit: `docs(obs): OBS remote on-device smoke recipe`

---

## Notes for the implementer
- No C++ changes.
- Keep the pure helpers (`ObsAuth`, `ObsMessages`, `BrbSwitch`) free of Android imports so they unit-test on the JVM (`java.util.Base64`, kotlinx-serialization).
- The phone's stream is fully decoupled from OBS — OBS being down must never affect streaming (all OBS actions are no-ops when disconnected).
- Reuse existing settings UI conventions (masked secret like the stream key; dimmed-while-live).
- Don't add recording/audio-mixer OBS controls (out of scope).
