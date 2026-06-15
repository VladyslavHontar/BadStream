# CameraX 1.6.1 Migration + Capability-Driven Capture — Design

**Date:** 2026-06-15
**Branch:** `feature/camerax-migration`
**Status:** Approved design, pre-implementation

## Problem

Two user-facing defects on the current Camera2 stack:

1. **60fps doesn't work.** Selecting 1080p60 still previews/streams at 30. The test device
   (Solana Seeker) exposes **no** 60fps range in `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`
   on any of its 7 cameras — normal-session 60fps is impossible there. Camera2 also can't
   answer the *combinatorial* question "can I do 60fps **and** HDR together?" without
   attempting a session and catching failure.
2. **Codec/quality changes feel inert.** The menu (`VideoQuality.Presets`, codec chips) is
   static and not tied to what the device can actually capture/encode, so choices either
   silently downgrade or appear to do nothing.

The desired end state: the capture stack adapts to **real device capability** — offering
60fps, HDR, 4K, and stabilization only where the hardware truly supports them — and unlocks
premium combos (notably **60fps + HDR**) on capable phones (Pixel/Samsung and any other
capable device) without a hardcoded make/model list.

## Decision

Migrate the capture stack from **Camera2** to **CameraX 1.6.1** (latest stable, May 2026)
and drive the menu from device capability. CameraX gives us two things Camera2 cannot
cleanly provide:

- **Feature Groups** (`SessionConfig` + `GroupableFeature` + `CameraInfo.isSessionConfigSupported()`):
  an authoritative combinatorial query and guaranteed feature combination (60fps + HDR +
  stabilization + 4K), with graceful fallback.
- **First-class dual concurrent camera + composition** (deferred to a later sub-project) —
  the `SurfaceProcessor`/`CameraEffect` seam we build now is exactly where future front+back
  **scene composition** will live.

Gating is **pure capability query** — no make/model allowlist. `isSessionConfigSupported()`
auto-enables a combo on any capable device and disables it on a "premium" phone that lacks it.

This is **one combined sub-project**: migration + capability-driven menu + Feature Groups,
on one branch. Dual-cam composition is explicitly **out of scope** (architected-for, not built).

### Why not stay on Camera2

We would have to hand-build both the combinatorial capability probing *and* (for the future
feature) the dual-cam compositor. CameraX 1.6.1 provides both. The 2023 "write your own GL
compositor" guidance is outdated — CameraX 1.5+ composes front+back into PiP/side-by-side
itself and feeds `VideoCapture`, and 1.6 made `CameraEffect` usable *inside* Feature Groups.

## Architecture & Seams

Keep the existing `CameraController` interface (`start/stop/setZoom/setLens`) and the
`CameraCapabilities`/`CameraModels` boundary so the streaming engine, `LivePipeline`, and the
`CameraStreamEngineTest` fakes are unaffected.

| Concern | Today (Camera2) | After (CameraX 1.6.1) |
|---|---|---|
| `CameraController` impl | `Camera2Controller` (manual open/session/reconnect) | **`CameraXController`** — `ProcessCameraProvider` + `bindToLifecycle` |
| Capability source | `CameraEnumerator` reads `CameraCharacteristics` | **`DeviceCapabilities`** — camera via `isSessionConfigSupported()` ∩ encoder via `MediaCodecList` |
| Menu | static `VideoQuality.Presets` | **generated** from `DeviceCapabilities` at camera init |
| Frames → preview **and** MediaCodec | MediaCodec surface added as 2nd Camera2 target | **one `SurfaceProcessor`** (`CameraEffect`) fans frames to preview + MediaCodec input surface |

The `SurfaceProcessor` is the keystone: CameraX won't accept a raw second Surface like
Camera2 did. A `CameraEffect` wrapping a GL `SurfaceProcessor` receives the camera frame and
renders to N outputs (preview + our encoder surface). It is Feature-Group-compatible in 1.6
and is the future home of the dual-cam compositor.

CameraX's `bindToLifecycle` owns device open/recovery, so most of `Camera2Controller`'s
manual lifecycle (concurrent-open guarding, stale-`onOpened` rejection, bounded reopen) is
**deleted**.

## Capability Detection & Dynamic Menu

A combo is offered iff the **camera can capture it AND the encoder can encode it**.

**Source A — Camera (Feature Groups).** For each candidate `(resolution, fps, hdr,
stabilization)`, build a `SessionConfig` with matching `GroupableFeature`s and call
`cameraInfo.isSessionConfigSupported(it)`. Available groupable features: `FPS_60`,
`HDR_HLG10`, `UHD_RECORDING` (4K), `PREVIEW_STABILIZATION`, `IMAGE_ULTRA_HDR`.

**Source B — Encoder (`MediaCodecList`).** Codec is an encoder + go-live-negotiation
property, **not** a camera property (this is why "changing codec did nothing"):
- **AVC** — always (baseline).
- **HEVC** — only if a HEVC encoder exists.
- **HEVC Main10** — **required for HDR**; absent → HDR not offerable regardless of camera.
- Each codec's `CodecCapabilities` caps max resolution/fps/bitrate — folded into the menu.

**Menu generation.** Replace fixed `VideoQuality.Presets` with the achievable set computed at
camera init. `1080p60` appears only where real; `4K` only with `UHD_RECORDING`; HDR only when
camera HDR **and** HEVC Main10 both exist.

**Presentation.**
- Resolution/fps: filter to achievable combos (no dead chips).
- HDR: an explicit toggle — show it, but **disable with a one-line reason** when the chosen
  resolution/fps can't pair with it (a vanishing toggle is confusing).
- Codec chips (Auto/HEVC/AVC): reflect only what `MediaCodecList` offers.
- Bitrate: coupled to the chosen combo, clamped to the encoder's reported max.

`DeviceCapabilities` and its menu-generation logic are pure/injectable for unit testing.

## Capture → Egress Pipeline

`CameraXController`:
1. `ProcessCameraProvider.getInstance(context)`; bind on a lifecycle we control.
2. Build a `SessionConfig` with a **Preview** use case (on-screen `PreviewView`/Surface) and a
   **`CameraEffect`** (GL `SurfaceProcessor`) whose outputs are the preview `SurfaceOutput`
   **and** the MediaCodec input surface (our native egress).
3. Apply the selected combo via **`requiredFeatureGroup`** (the menu already pre-verified it
   with `isSessionConfigSupported`, so binding is guaranteed; on the rare bind failure, fall
   back to the next-lower combo and log).
4. **60fps:** `FPS_60` groupable feature; encoder `KEY_FRAME_RATE=60`; RTMP `onMetaData`
   fps=60 — downstream already plumbed, camera now actually delivers 60.
5. **HDR:** `HDR_HLG10` groupable feature + HEVC Main10 encoder + HLG dynamic-range metadata
   on the MediaCodec format (the M2 HDR negotiation path already exists downstream).
6. **Zoom/lens:** `CameraControl.setZoomRatio` replaces manual `CONTROL_ZOOM_RATIO`; the
   `CameraLens` → zoom-ratio mapping is preserved; logical multi-camera lens switching via
   CameraX zoom/selector.

No mid-stream reconfig — quality is applied at go-live, as today.

## Lifecycle, Background Streaming, Reconnect, Parity

- **Background streaming.** CameraX is lifecycle-bound. We drive a **custom
  `LifecycleRegistry`** tied to the streaming session (under the foreground service), not the
  Activity, so capture keeps feeding the encoder while backgrounded. When idle (preview only),
  bind to the Composable/Activity lifecycle. Replaces the `CameraTargets` mechanism while
  preserving the process-scoped `LivePipeline` singleton.
- **Reconnect/recovery.** CameraX handles device disconnect/reopen; drop manual `MAX_REOPEN`.
  Surface camera-unavailable to the UI via `CameraInfo.getCameraState()`.
- **Preserved parity:** front/back flip, OIS, sensor orientation, no-mid-stream-reconfig,
  HDR-while-live constraints, lens/zoom UI, OBS/SRT/RTMP egress behavior — all unchanged from
  the user's perspective.

## Testing & Rollout

- **Seam preserved** → existing `CameraStreamEngineTest` and engine/`LivePipeline` fakes
  unaffected.
- **TDD the pure logic:** `DeviceCapabilities` menu generation (intersection of fake camera
  caps + fake encoder caps) is unit-tested with fakes — no device required. Codec gating,
  HDR-requires-Main10, fps/resolution intersection, bitrate clamping.
- **On-device smoke (manual gate):** preview smooth; 1080p60 actually 60 on a capable device;
  HDR path; stream to Twitch (RTMP) and SRT relay; background streaming continues; zoom/lens;
  front/back flip; disconnect recovery.
- **Rollout / risk control:** biggest blast radius is the egress surface re-plumb
  (`SurfaceProcessor`) and the background-lifecycle change. Build `CameraXController` behind
  the existing interface and **keep `Camera2Controller` in-tree until parity is confirmed on
  device** (swap at the controller factory; delete Camera2 only after smoke passes).
  Subagent-driven build with review checkpoints, per project convention.
- **Gradle:** add `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`,
  `camera-view` at **1.6.1**. `minSdk 35` covers all required APIs.

## Out of Scope (architected-for, not built)

- Dual concurrent front+back camera + scene composition (PiP/side-by-side). The
  `SurfaceProcessor` seam is the designated insertion point.
- High-speed/slow-mo capture (`HighSpeedVideoSessionConfig`) — the only path to 60fps on
  devices like the Seeker that lack normal-session 60fps. Noted as a future follow-on.
- Preview stabilization and Ultra HDR toggles — trivial to add later via the same Feature
  Group mechanism once core migration lands.
