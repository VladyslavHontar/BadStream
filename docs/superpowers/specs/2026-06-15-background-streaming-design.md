# Background Streaming — Design

**Date:** 2026-06-15
**Milestone:** Moblin-parity — sub-project 1 (of 3: background streaming → OBS remote → network bonding)
**Status:** Approved (ready for implementation plan)

## Goal

Keep the stream running with no end and no lag/stall when the app is minimized or
backgrounded (home button, app switch) but still in memory — including the case where Android
destroys the backgrounded Activity while the process lives.

## Problem (current behavior)

- **Egress + encoders already survive a minimize.** The native RTMP session (C++ egress
  thread), `VideoEncoder`/`AudioEncoder` (MediaCodec threads), and the `engine`
  (a `MainActivity` field) all keep running while the Activity is merely stopped.
- **The camera↔preview coupling breaks.** Preview is a `TextureView`; backgrounding destroys
  its `SurfaceTexture`, the Viewfinder sets `surface = null`, and the camera session is left
  holding a dead preview surface. The Viewfinder's `LaunchedEffect` only (re)starts the camera
  when a preview exists, so it never reconfigures to "encoder-only" — the session goes
  abandoned/fragile → the stream stutters or stalls.
- **Activity-scoped ownership is not robust.** `engine` is a `MainActivity` field and
  `Camera2Controller` lives in the Viewfinder composition. A minimize keeps them, but an
  Activity destruction under memory pressure (process still alive) would tear the stream down.

Good news already in place: the foreground service is declared `camera|microphone` with the
right permissions and is started on go-live, so background camera capture is *permitted*;
`Camera2Controller` already supports reconfiguring a session's output targets on the open
device. We just have to relocate ownership and drive the encoder-only reconfigure.

## Decisions (locked during brainstorm)

- **Service-owned pipeline:** an app/process-scoped holder owns camera + encoders + engine;
  the foreground service keeps the process alive so it survives Activity destruction. The
  Activity binds to it for preview + controls.
- **Notification stays informational** — no Stop action (stopping requires reopening the app).
- On background, **reconfigure the camera to encoder-only** (keep streaming); re-add preview on
  foreground (a brief blip per transition is accepted).

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ LivePipeline  (app/process-scoped singleton; survives Activity│
│               destruction; kept alive by the foreground svc)  │
│   • engine: CameraStreamEngine   (+ its native streamer)      │
│   • camera: Camera2Controller                                 │
│   • media lifecycle: the startMedia/stopMedia closures        │
│     (VideoEncoder + AudioEncoder + NativeRecorder + FGS)       │
└───────────────▲───────────────────────────────▲──────────────┘
                │ engine + state                 │ attach/detach preview Surface
        ┌───────┴────────┐               ┌───────┴────────┐
        │ StreamViewModel│               │   Viewfinder    │
        │ (reads engine) │               │ (preview only)  │
        └────────────────┘               └─────────────────┘
```

- **`LivePipeline`** (new): a single instance held by the `Application` (or an `object`
  initialized with the app context). It constructs the engine + camera controller + media
  closures **once** — the logic currently in `MainActivity.onCreate`'s `engine = run { … }`
  block and the Viewfinder's `remember { Camera2Controller(context) }` move here.
- **`StreamViewModel`** takes `LivePipeline.engine` (instead of the Activity creating a fresh
  engine). On Activity recreation a new ViewModel reconnects to the same engine and its
  `StateFlow`s — the live stream and its UI state are intact.
- **`Viewfinder`** uses `LivePipeline.camera` and only manages the **preview surface**: attach
  on surface-available, detach on surface-destroyed. It no longer owns the controller lifecycle.

## Component behavior

### Preview attach/detach → camera targets
The camera's target set is derived from "encoder surface (if streaming) + preview surface (if
attached)":
- **Streaming + preview visible:** `[preview, encoderSurface]`.
- **Streaming + backgrounded (preview gone):** `[encoderSurface]` — reconfigure to encoder-only;
  capture/encode/egress continue.
- **Not streaming + preview visible:** `[preview]` (idle viewfinder).
- A small pure helper computes the target list from `(previewSurface?, encoderSurface?)` so the
  selection is unit-testable; `LivePipeline`/Viewfinder calls `camera.start(config, targets, hdr)`
  whenever either input changes (the controller already coalesces redundant starts and drops
  invalid surfaces).

### Lifecycle
- **Go-live:** start FGS (already happens in `startMedia`) + the engine; the pipeline is now
  "live".
- **Background/foreground:** preview detaches/attaches → target reconfigure only. Nothing stops.
- **Activity onDestroy:** **do not** dispose the pipeline while live; only release the preview.
  Dispose the pipeline only when idle (not streaming). (The camera is never stopped on
  composition dispose while streaming — replaces today's `onDispose { controller.stop() }`.)
- **Stop:** engine.stop() tears down media + native session; FGS stops; camera returns to
  idle preview (or closes if the Activity is also gone).

### Notification
Unchanged — informational title/text only.

## Edge cases / error handling
- **Activity recreated mid-stream:** ViewModel re-binds to `LivePipeline.engine`; Viewfinder
  re-attaches preview → camera reconfigures to `[preview, encoderSurface]`; UI shows Live with
  the running elapsed timer and stats.
- **Process killed (swiped from recents / OOM):** the stream ends — acceptable and unavoidable;
  matches Moblin. The FGS makes this unlikely while backgrounded.
- **Camera reconfigure blip:** each background/foreground transition rebuilds the session once;
  the encoder surface persists so egress is continuous (a frame or two may repeat/drop at the
  seam — acceptable).
- **HDR:** the encoder-only and `[preview, encoder]` configs both carry the active dynamic range
  (existing `hdr` flag path in `Camera2Controller`).

## Testing
- **Unit (host/JVM):** the pure target-selection helper — `(preview, encoder, streaming)` →
  expected target list across all four combinations; and any holder state logic that can be
  isolated from Android.
- **On-device (manual — the real gate):** go live; press Home / switch apps and confirm the
  stream **keeps running** (watch the receiver: continuous, no stall) for a sustained period;
  return to the app and confirm preview reattaches and stats resume; rotate/recreate the
  Activity mid-stream and confirm reconnection. Document in a smoke-test note.

## Out of scope (later Moblin-parity sub-projects / future)
OBS-websocket remote; network bonding/SRT; a notification Stop action; picture-in-picture;
running with the screen off indefinitely beyond what the FGS already permits; multi-camera.
