# OBS Connection (obs-websocket v5 remote) — Design

**Date:** 2026-06-15
**Milestone:** Moblin-parity — sub-project 2 (of 3: background streaming ✅ → OBS remote → network bonding)
**Status:** Approved (ready for implementation plan)

## Goal

Let the phone connect to a remote OBS (on the LAN, the "phone → OBS-at-home" workflow) and
control it over **obs-websocket v5**: start/stop OBS's stream, list and switch scenes, and
**auto-switch OBS to a "BRB" scene when the phone's own stream drops** — switching back to the
main scene when it recovers.

## Decisions (locked during brainstorm)

- Phone **controls a remote OBS** (obs-websocket client); not an ingest/source.
- **Full v1 scope incl. BRB auto-switch.**
- BRB trigger is **our** `StreamState` (`Reconnecting` → BRB, `Live` → main), NOT OBS's stream
  events — matching Moblin (it switches off its own field-stream health, not OBS state).
- Manual Start/Stop of OBS's stream (the phone's go-live does **not** auto-start OBS).
- Auto-switch fires only on `Reconnecting`→BRB / `Live`→main (not on user-Stop/Idle/Error).
- WebSocket lib: **OkHttp** `4.12.0` (`com.squareup.okhttp3:okhttp`) — new dependency.

## obs-websocket v5 protocol (the parts we use)

- Connect `ws://host:port` (default port **4455**). Envelope both ways: `{ "op": Int, "d": Object }`.
- **Handshake:** server **Hello** (op 0) → client **Identify** (op 1) → server **Identified** (op 2).
  - Hello `d`: `{ obsWebSocketVersion, rpcVersion, authentication?: { salt, challenge } }`
    (`authentication` absent when OBS auth is disabled).
  - Identify `d`: `{ rpcVersion: 1, authentication?: <string>, eventSubscriptions: 68 }`
    (68 = Scenes(4) | Outputs(64)). Omit `authentication` when Hello had none.
- **Auth string:** `secret = base64(SHA256(password + salt))`;
  `authentication = base64(SHA256(secret + challenge))` (string concatenation; SHA-256 over UTF-8
  bytes; base64 standard). Verified against Moblin's `ObsWebSocket.swift`.
- **Request** (op 6): `{ requestType, requestId, requestData? }`.
  **Response** (op 7): `{ requestType, requestId, requestStatus: { result, code }, responseData? }`
  — success = `result == true` (code 100).
- **Requests used:** `GetSceneList` → `{ currentProgramSceneName, scenes: [{ sceneName, sceneIndex }] }`;
  `GetCurrentProgramScene` → `{ currentProgramSceneName }`;
  `SetCurrentProgramScene` ← `{ sceneName }`; `StartStream`; `StopStream`;
  `GetStreamStatus` → `{ outputActive, ... }`.
- **Events** (op 5): `{ eventType, eventData }` — `CurrentProgramSceneChanged` (`{ sceneName }`),
  `StreamStateChanged` (`{ outputActive, outputState }`).
- **Keepalive:** no app-level heartbeat — use WebSocket ping every 10 s; missed pong → reconnect.

## Architecture

```
LivePipeline (process-scoped)
  ├─ engine: CameraStreamEngine  (exposes state: StateFlow<StreamState>)
  └─ obs: ObsWebSocketController  (NEW)
        • OkHttp WebSocket client + v5 handshake/auth
        • request/response dispatch by requestId; event handling
        • 10s ping + exponential-backoff reconnect (500ms→10s), re-sync on reconnect
        • BRB state machine: collects engine.state -> SetCurrentProgramScene
        • exposes StateFlows: connected, scenes, currentScene, obsStreaming
        • reads Settings (host/port/password/scenes/autoSwitch) from settingsFlow
```

- **`stream/ObsWebSocketController.kt`** (new class): constructed in `LivePipeline.ensureInit`
  with the engine's `state: StateFlow<StreamState>` and the settings `Flow<Settings>`; owns its
  own `CoroutineScope(SupervisorJob() + Dispatchers.IO)`. Connects/disconnects based on
  `obsHost` non-blank + an enabled flag; re-reads settings reactively.
- Exposed via `LivePipeline.obs`. The `StreamViewModel` surfaces `isObsConnected`,
  `obsScenes`, `obsCurrentScene`, `obsStreaming` into `StreamUiState` for the settings UI, and
  forwards UI actions (connect/disconnect, switch scene, start/stop OBS stream).

### BRB auto-switch
The controller collects `engine.state`:
- `StreamState.Reconnecting` → if `obsAutoSwitchEnabled` && connected && `obsBrbSceneName`
  non-blank && current OBS scene == `obsMainSceneName` → `SetCurrentProgramScene(brb)`.
- `StreamState.Live` → if `obsAutoSwitchEnabled` && connected && current OBS scene ==
  `obsBrbSceneName` → `SetCurrentProgramScene(main)`.
It tracks the current OBS scene locally (seeded by `GetCurrentProgramScene`/`GetSceneList`,
updated by `CurrentProgramSceneChanged`) so it never sends a redundant switch. The switch
guard ("only from main"/"only from BRB") avoids fighting a manual scene change.

## Settings (added to the `@Serializable Settings` trait — auto-persists)

```kotlin
val obsHost: String = ""
val obsPort: Int = 4455
val obsPassword: String = ""
val obsMainSceneName: String = ""
val obsBrbSceneName: String = ""
val obsAutoSwitchEnabled: Boolean = false
```

(DataStore serializer uses `ignoreUnknownKeys = true` + defaults → forward/backward-compatible;
no migration.)

## UI — OBS settings sub-screen (Moblin-style)

A new `Obs` route in the settings panel (alongside Destination/Video/Audio/Camera/About):
- **Enable** toggle; **host**, **port**, **password** (masked) fields; **connection status**
  (Connecting / Connected / Disconnected + error).
- **Scenes:** list from `obsScenes`, current highlighted; tap to `SetCurrentProgramScene`.
- **OBS stream:** Start/Stop button reflecting `obsStreaming`.
- **Main scene** / **BRB scene** pickers (from the scene list) + **auto-switch** toggle.
- Follows existing settings conventions (grouped rows, masked secret like the stream key,
  dimmed-while-live where a field shouldn't change mid-stream — host/port/password).

## Error handling
- Auth failure / wrong password → surface a clear "OBS auth failed" status; do not spin-retry
  tightly (backoff). Connection refused / host down → backoff reconnect while enabled.
- OBS not reachable → controller stays Disconnected; BRB switching is a no-op (guarded on
  connected). The phone stream is unaffected by OBS being down (fully decoupled).
- A `SetCurrentProgramScene` for a missing scene → response `result==false`; log + surface, no
  crash. The BRB guard (only switch from the expected scene) limits damage.
- Settings change (host/password) while connected → reconnect with new params.

## Testing
- **Host/JVM unit tests:**
  - **Auth algorithm** — `base64(SHA256(...))` two-step, with a self-computed known vector
    (password/salt/challenge → expected string via `MessageDigest`+`Base64`), plus the no-auth
    case (no `authentication` field emitted).
  - **Message build/parse** — all 6 request envelopes (op 6) + response (op 7) success/failure,
    and the 2 events (op 5) via `kotlinx.serialization`.
  - **BRB state machine** — drive a fake `StateFlow<StreamState>` + a fake socket/sender; assert
    Reconnecting→`SetCurrentProgramScene(brb)` (only from main), Live→`(main)` (only from BRB),
    no redundant sends, no-op when disabled/disconnected.
- **On-device (manual — the real gate):** run OBS on the LAN with obs-websocket enabled; connect
  from the phone; verify scene list + switch, Start/Stop OBS stream, and BRB auto-switch
  (go live, kill the phone's network → OBS flips to BRB → restore → OBS flips back). Document in
  a smoke-test note.

## Out of scope (later)
Recording control (StartRecord/StopRecord); audio mixer/mute/sync; source screenshots; OBS
studio mode/transitions; multiple OBS instances; auto-start OBS on phone go-live; TLS (`wss://`)
client certs.
