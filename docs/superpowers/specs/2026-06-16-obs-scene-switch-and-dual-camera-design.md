# OBS Scene Switching + Dual-Camera PiP — Design Spec

Date: 2026-06-16
Status: Approved (pending spec review)

## Summary

Two independent features for the live viewfinder:

1. **OBS scene switcher on the viewfinder** — surface the already-wired OBS scene
   switching on the live screen so the user no longer has to open
   Settings → OBS Remote to change scenes. Pure UI change.
2. **Dual-camera PiP (phone-composited)** — run the front and back cameras at the
   same time and composite them on-device into the single existing encoded stream
   (Moblin-style). One stream leaves the phone. The PiP is draggable with size
   presets and a primary/secondary swap.

These ship as separate, independent slices and can be built/merged in either order.

## Decisions (locked during brainstorming)

- **Who composites the two cameras:** the phone. One composited stream leaves the
  device to OBS/Twitch/any destination. OBS does *not* place the two feeds.
  (Matches how Moblin works.)
- **Dual-cam layout + control:** draggable floating PiP over a full-screen main
  feed; drag to reposition, release snaps to the nearest corner; tap cycles size
  (S/M/L); a button swaps which camera is the main vs the PiP.
- **OBS scene switcher placement:** a tap-to-expand `Scene: <current> ⌄` chip in
  the right-side `ControlRail`; expand shows the scene list, tapping a scene
  switches and collapses. Shown only when the OBS WebSocket is connected.

## Current architecture (as found)

- **Capture is single-camera.** `CameraXController` (`camera/CameraXController.kt`)
  binds exactly one `Preview` use case via `ProcessCameraProvider.bindToLifecycle`,
  with one `EgressSurfaceProcessor` attached as a `CameraEffect` (PREVIEW |
  VIDEO_CAPTURE).
- **One GL input texture.** `EgressSurfaceProcessor` creates a single
  `SurfaceTexture` backed by `GlRenderer`'s one external OES texture.
  `GlRenderer` is a passthrough shader (one sampler) that also does the
  freeze-blur transition and an auto-ISO luma meter. It renders the same frame to
  each registered output surface (on-screen preview + MediaCodec encoder input).
- **Downstream is layout-agnostic.** The encoder surface, SRT/RTMP streamers, and
  preview all just consume whatever `GlRenderer` draws. Compositing therefore has
  a single natural seam: `GlRenderer.render()`.
- **OBS control already exists end-to-end.** `ObsRemote` /
  `ObsWebSocketController` expose `connected`, `scenes`, `currentScene`,
  `obsStreaming`, and `switchScene(name)`. `StreamViewModel` mirrors these into
  `StreamUiState` (`obsConnected`, `obsScenes`, `obsCurrentScene`, `obsStreaming`)
  and exposes `obsSwitchScene(name)`. Only the *settings* screen
  (`ui/settings/ObsSettings.kt`) consumes the scene list today.

---

## Feature 1 — OBS scene switcher on the viewfinder

### Architecture

Pure UI. No ViewModel, no OBS-layer, no capture changes. We render the existing
`uiState.obs*` data and call the existing `obsSwitchScene`.

### Components

- **New** `ui/viewfinder/ObsSceneChip.kt`
  - Collapsed: a glass chip showing `Scene: <currentScene> ⌄`.
  - Expanded: an in-rail vertical list of `obsScenes`; the current scene is marked
    (green dot, matching the existing settings list). Tapping a scene calls
    `onSwitchScene(name)` and collapses.
  - Styling mirrors `LensButtons` / `glassSurface()` (translucent dark scrim,
    hairline, rounded corners; `OnSurfaceWhite`/`OnSurfaceMuted` text).
  - Holds only local `expanded` state; all data is passed in.
- **Changed** `ui/viewfinder/ControlRail.kt`
  - New params: `obsConnected: Boolean`, `obsScenes: List<String>`,
    `obsCurrentScene: String?`, `onSwitchScene: (String) -> Unit`.
  - Renders `ObsSceneChip` in the top cluster (below `LensButtons`) **only when
    `obsConnected && obsScenes.isNotEmpty()`**.
- **Changed** `ui/viewfinder/Viewfinder.kt`
  - At the existing `ControlRail` call site, pass `ui.obsConnected`,
    `ui.obsScenes`, `ui.obsCurrentScene`, and `viewModel::obsSwitchScene`.

### Data flow

OBS WS → `ObsWebSocketController` → `StreamViewModel` flows → `uiState.obs*`
(already live) → `ObsSceneChip`. Tap → `viewModel.obsSwitchScene(name)` → existing
path. The chip's "current scene" tracks `obsCurrentScene`, so scene changes made
elsewhere (OBS itself, the settings screen) reflect in the chip too.

### Error handling

- Disconnected or empty scene list → chip is absent; connecting still lives in
  Settings → OBS Remote.
- Switch failures are already handled inside the OBS layer (unchanged).

### Testing

- State/interaction test: when expanded, selecting a scene invokes `onSwitchScene`
  with that name; gating logic hides the chip when disconnected or scene list
  empty. (No new ViewModel surface to test — the API already exists and is
  covered.)

---

## Feature 2 — Dual-camera PiP (phone-composited)

### Architecture

Add a concurrent-camera capture path that runs front + back simultaneously and
composites them into the **one** existing encoder/preview surface. Downstream
(encoder, SRT/RTMP, preview routing) is unchanged. Three layers change:

1. **Capture** — `CameraXController` gains a dual mode using
   `ProcessCameraProvider.bindToConcurrentCamera(listOf(frontCfg, backCfg))`.
   Capability is gated by `getAvailableConcurrentCameraInfos()`. Both
   `SingleCameraConfig`s attach the **same** `EgressSurfaceProcessor` instance, so
   one shared GL context receives **two** `SurfaceRequest`s.
2. **Compositor** — `EgressSurfaceProcessor` manages up to two input
   `SurfaceTexture`s (primary + secondary), each with its own transform matrix.
   `GlRenderer` becomes a compositor: draw the primary full-frame, then draw the
   secondary into a PiP viewport rect. One composited frame still goes to every
   output surface; the freeze-blur transition logic is preserved.
3. **Layout / UX** — a `PipLayout` value (normalized x/y, size preset, which
   camera is primary) is the single source of truth. A draggable Compose overlay
   over the preview mutates it (drag to move; release snaps to nearest corner; tap
   cycles size S/M/L; a swap button flips primary/secondary). The **same**
   `PipLayout` drives the GL viewport, so preview and stream match exactly.

### Components

- **Changed** `camera/CameraModels.kt` (and/or `camera/CameraTargets.kt`)
  - `enum class CameraMode { SINGLE, DUAL_PIP }`.
  - `data class PipLayout(normX: Float, normY: Float, size: PipSize, primary: Facing)`
    with `enum class PipSize { S, M, L }`.
  - Pure layout math: clamp-in-bounds, nearest-corner snap, `size → rect`
    (normalized PiP rectangle from `PipSize` + canvas aspect), and a `swap()` that
    flips `primary`.
- **New** `camera/ConcurrentCameraProbe.kt` (sibling to `CameraXComboProbe`)
  - Wraps `getAvailableConcurrentCameraInfos()`; reports whether a front+back
    combo is available and the resolution ceiling for dual capture.
- **Changed** `camera/CameraXController.kt`
  - Dual-bind path (`bindToConcurrentCamera`), wiring both cameras' effects to one
    `EgressSurfaceProcessor`, swap handling, and pushing `PipLayout` to the
    processor/renderer. Single-camera path is unchanged.
- **Changed** `camera/EgressSurfaceProcessor.kt`
  - Track up to two input `SurfaceTexture`s keyed by their `SurfaceRequest`
    (primary vs secondary), each with its own transform matrix; clear/replace on
    rebind.
- **Changed** `camera/GlRenderer.kt`
  - Second external OES texture + a composite pass: primary full-frame, secondary
    into the PiP rect from `PipLayout`. PiP is a plain rectangle initially.
- **Changed** `stream/StreamViewModel.kt` + `stream/StreamUiState.kt`
  - State: `cameraMode`, `pipLayout`, `dualSupported`.
  - Actions: toggle dual mode, swap primary/secondary, update `pipLayout`
    (position/size from the overlay).
- **Changed** `ui/viewfinder/ControlRail.kt`
  - A dual-cam toggle button, hidden when `!dualSupported`.
- **Changed** `ui/viewfinder/Viewfinder.kt`
  - A draggable PiP overlay positioned over the preview, bound to `pipLayout`
    (drag → update position; release → corner-snap; tap → cycle size). A swap
    affordance (button or double-tap on the PiP).

### Data flow

Two cameras → two `SurfaceTexture`s on one GL context → `GlRenderer` composites
per tick → on-screen preview surface + MediaCodec encoder surface (both unchanged)
→ SRT/RTMP. `PipLayout` flows VM → overlay (touch) and VM → compositor (viewport)
in lock-step, so what the user sees is what is encoded.

### Device reality & error handling

- Concurrent camera is device-gated and resolution-limited (commonly ≤720p per
  camera; many devices don't support it at all). If
  `getAvailableConcurrentCameraInfos()` yields no front+back combo, the dual
  toggle is hidden and `dualSupported = false`.
- Concurrent mode generally disallows HDR-HLG10 / 60fps combos. Reuse the existing
  "retry bind without the feature group" fallback; dual mode requests a
  conservative per-camera resolution within the reported ceiling.
- If a dual bind throws, fall back to single-camera capture and surface an error
  in `uiState` (existing error pattern).
- When the user enables dual mode, show the resolution ceiling so the quality drop
  is not a surprise.

### Testing

- Pure-function tests for `PipLayout`: corner snap, clamp-in-bounds, size cycle,
  primary swap, and `size → rect` — mirrors `CameraControlsTest`.
- `ConcurrentCameraProbe` test with faked concurrent infos:
  supported / unsupported / front-only.
- `EgressSurfaceProcessor` two-input bookkeeping test: add / replace / clear
  inputs and per-input transform association.
- ViewModel test: toggling dual mode when `!dualSupported` is a no-op; swap flips
  `pipLayout.primary`; layout updates propagate to `uiState`.
- GL rendering is validated on-device (consistent with the existing GL code, which
  is not unit-tested).

## Scope calls (YAGNI)

- No side-by-side / split-screen layout — draggable PiP only.
- PiP is a plain rectangle initially; rounded corners / border is a fast follow.
- Dual-cam audio is unchanged (single mic); no per-camera audio.
- OBS scene switcher does not add scene *editing* — it only switches among scenes
  OBS already defines.

## Build order

Feature 1 (scene switcher) and Feature 2 (dual-cam) are independent. Feature 1 is
small and low-risk; Feature 2 is the substantial pipeline change. Either can land
first.
