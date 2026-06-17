# Dual-Camera Independent Capture — Design

**Date:** 2026-06-17
**Status:** Approved (design); pending implementation plan
**Branch context:** `feat/dual-camera-capture`

## Problem

Dual-camera PiP currently runs both cameras through CameraX's `ConcurrentCamera`, which binds the
front+back pair as **one atomic unit**: `bindToLifecycle(listOf(primary, secondary))`. On the Seeker
the back "lenses" (0.8×/1×/1.8×) are *separate physical cameras*, so switching the back lens means
binding a different back camera — and CameraX offers no way to rebind just the back. The only move is
`unbindAll()` + rebind the whole pair, which tears down the **front** camera too. The PiP video blinks
out and back on every back-lens switch.

The root issue is conceptual: a scene should consume **independent** video inputs, and switching one
input must not disturb the other. CameraX's concurrent API couples them.

## Hard hardware constraint (must stay phone-agnostic)

`CameraManager.getConcurrentCameraIds()` is the ground truth that **every** API is bound by. On the
Seeker (`SM02E4060314107`) it returns exactly one combo: `{{0, 1}}`.

| id | facing | focal  | role        |
|----|--------|--------|-------------|
| 0  | BACK   | 5.24mm | main (1×)   |
| 1  | FRONT  | 3.74mm | front       |
| 2  | BACK   | 2.22mm | ultrawide (0.8×) |
| 3  | BACK   | 7.07mm | tele (1.8×) |

So on this device the ultrawide and tele **cannot be open at the same time as the front camera under
any API** — not even with a blink. There is no rewrite that changes this; it is a HAL guarantee.

Therefore the design must be **capability-driven at runtime**, never hardcoded: it reads each device's
certified concurrent combos and delivers the best achievable behavior per phone. On a phone whose
driver certifies `{ultrawide, front}`, ultrawide lights up automatically and switches blink-free; on
the Seeker it does not, and we degrade gracefully.

## Goals

- A back-lens/zoom change in dual mode never disturbs the front PiP, **where the hardware permits.**
- Eliminate the blink wherever possible: zoom changes never blink (no session change); physical-sensor
  changes never blink on phones that certify the new pair (only the back device restarts, front stays
  live).
- Never thrash on an impossible combo (no failed concurrent bind + fallback churn).
- Phone-agnostic: one capability model, no device-specific branches.
- Single-camera mode is untouched and behavior-identical (HDR/60fps/exposure/lens/zoom on CameraX).

## Non-goals

- Making ultrawide+front work on hardware that forbids it (impossible).
- Time-slicing a single back sensor between lenses (stuttery, half-framerate — rejected).
- Moving single-camera mode off CameraX (we keep its proven feature support).

## Architecture: hybrid (single on CameraX, dual on independent Camera2)

The GL compositor (`EgressSurfaceProcessor` + `GlRenderer`) remains the consumer of two textures and is
unchanged in its rendering/scene/rounded-corner/mirror logic. What changes is **who feeds it** and
**who owns the outputs**, which now differs by mode:

- **Single mode (unchanged):** CameraX binds one camera → `CameraEffect` → processor renders to the
  CameraX-owned preview `SurfaceOutput` + encoder `Surface`. Full HDR-HLG10 / FPS_60 feature-group
  negotiation, manual exposure (Camera2 interop), physical-lens selection, ViewPort crop — exactly as
  today.
- **Dual mode (new):** No CameraX. A new `DualCameraSession` opens back + front as **independent
  Camera2 `CameraDevice`s**, each driving one of the processor's two input `SurfaceTexture`s. The
  processor runs in a **standalone** mode: it renders directly to the UI preview `Surface` and the
  encoder `Surface` (no CameraX `SurfaceOutput`). The GL thread, EGL context, OES textures, and all
  scene/compositing logic are shared between modes.

`DisplayTransform` (pure, already unit-tested) computes the upright orientation for **both** Camera2
sources in dual, since neither goes through CameraX's transform anymore.

### Why A1 (two independent devices)

- **A1 (chosen):** Two `CameraDevice` + `CaptureSession`, managed by `DualCameraSession`. Switching the
  back closes/reopens only the back device; front untouched. Standard Android concurrent-camera
  workflow, gated by `getConcurrentCameraIds()`. Clean ownership; matches the independent-inputs goal.
- **A2 (rejected):** CameraX front + Camera2 back. Mixing a CameraX binding with a raw Camera2 open of
  a second camera fights for the concurrent slot; CameraX doesn't know about our open. Fragile.
- **A3 (rejected):** One manager that always reopens both. Reintroduces coupling → the blink returns.

## Components

### `ConcurrentCameraCapabilities` (pure, unit-tested)
Computed once at startup from `getConcurrentCameraIds()` + each camera's characteristics.

- `concurrentPairs: Set<Pair<CameraId, CameraId>>` — every certified simultaneous combo, normalized.
- `backLenses: List<{ id, focalRatio (≈0.8/1/1.8×), zoomRange }>` — selectable back sensors (the chips).
- `dualOptions(frontId, currentBackId): Map<Chip, DualClass>` where `DualClass` ∈:
  - **REAL** — a back sensor that pairs concurrently with the front → switch the back device for real.
  - **ZOOM** — not a concurrent sensor, but its focal ≥ the open back sensor's → reachable by digital
    zoom on the open sensor (no device change).
  - **UNAVAILABLE** — wider than the open sensor (can't zoom wider) and not concurrent → tap-to-exit-dual.

Pure: fed parsed structs (no Android camera objects), exactly like `Scene`/`DisplayTransform`. This is
the single source of truth the UI and `DualCameraSession` both read — identical logic on every phone,
only the input data differs.

### `DualCameraSession` (Android/Camera2, device-verified)
Owns two independent Camera2 devices/sessions feeding the processor's two input `SurfaceTexture`s.

- `start(primaryFacing, scene)` — open both devices (back + front), create per-device capture sessions
  at concurrent-safe resolutions (≤720p guarantee; ~1280×720 primary, ~640×360 secondary as today).
- `switchBack(newBackId)` — close only the back device, open `newBackId`, rebuild only the back session;
  front device/session keep streaming. Called only when `{newBackId, frontId}` ∈ `concurrentPairs`, so
  the open is never rejected. The back source holds its last GL frame during the ~0.3–0.5s reopen.
- `setZoom(ratio)` — digital zoom on the back source (clamped to its range). No device touch.
- `swapPrimary()` — relabel which open source is primary vs secondary in the scene. **No camera reopen**
  (both already open) → instant, blink-free.
- `stop()` — close both devices.
- Preview-gone / resume handling for the streaming-while-backgrounded case (render to encoder only when
  the preview surface is absent).

### `EgressSurfaceProcessor` (extended)
Gains a mode flag: **CameraX-driven (single)** vs **standalone (dual)**. In standalone mode it does not
expect `onInputSurface`/`SurfaceOutput` from CameraX; instead it exposes its two input
`SurfaceTexture`s to `DualCameraSession` and renders to directly-registered output `Surface`s (UI
preview + encoder). All existing compositing (scene layers, rounded corners, front-mirror, freeze) is
reused.

### Controller / Viewfinder wiring
`CameraXController` keeps the single path. Dual toggling, lens-chip classification, swap, zoom, and the
tap-to-exit-dual offer are routed through the capability model and `DualCameraSession`.

## Data flow

- **Single:** Camera → CameraX `CameraEffect` → processor → {preview SurfaceOutput, encoder}.
- **Dual:** back Camera2 → SurfaceTexture A; front Camera2 → SurfaceTexture B → processor composite →
  {UI preview Surface, encoder Surface}. The primary frame drives the composite; the secondary updates
  its texture/transform on its own frames (as today).

## UX

- **Lens chips in dual:** the row stays visible. REAL/ZOOM chips behave like normal selectable chips
  (tap switches sensor or sets zoom; active one highlights; pinch-zoom updates the highlight to the
  nearest chip). UNAVAILABLE chips render greyed/dimmed with a subtle marker, still tappable to trigger
  the offer below. Nothing silently disappears when PiP turns on; identical UI on every phone, only the
  per-chip class differs.
- **Tap-to-exit-dual:** tapping an UNAVAILABLE chip shows a one-line confirm
  ("Ultra-wide needs the full screen — drop PiP?") with Confirm/Cancel. Confirm → turn dual off
  (persist the PiP layout, as today) and switch single-cam to that lens. Cancel → no change. Explicit
  offer, not a silent flip.
- **Swap:** single-tap on the PiP swaps which camera is the big view — now just relabeling primary vs
  secondary in the scene with both sources already open → instant and blink-free. Back lens chips
  always control the back camera regardless of slot; enabled in both slots.
- **Zoom:** pinch on the preview and the chips both drive digital zoom on the back source, clamped to
  that sensor's real range. Front PiP has no zoom (matches today).

## Transitions & lifecycle

- **Single → dual:** `provider.unbindAll()`; switch processor to standalone (register preview + encoder
  Surfaces on the GL thread); `DualCameraSession.start(...)`. Reuse the existing
  `awaitCamerasFreeThenBind` gate so CameraX's async close finishes before the Camera2 opens
  (else `MAX_CAMERAS_IN_USE`). If an open fails (device claims concurrency but rejects) → tear down,
  revert to single, surface via the existing `onFailed` path.
- **Dual → single:** `DualCameraSession.stop()`; return processor to CameraX-effect mode; re-bind the
  single camera via the existing path. Persist PiP layout (as today).
- **Backgrounding while streaming:** keep both Camera2 sessions alive rendering to the encoder only
  (drop the preview output); re-register the preview Surface on resume. Backgrounded + not streaming →
  stop both devices.
- **Streaming is mode-agnostic:** the encoder Surface is just another GL output, fed identically in
  both modes. Go-live / reconnect / OBS logic is untouched (downstream of the compositor).
- **Threading:** one GL thread owns EGL + textures across both modes; Camera2 callbacks post frames to
  it like the current `onFrameAvailable`. Mode switches serialize on that thread so we never render
  against half-torn-down state.

## Testing

- **Pure unit tests (JUnit, no Android — existing pattern):**
  - `ConcurrentCameraCapabilities`: assert `concurrentPairs` and `dualOptions` classification for
    (a) Seeker `{0,1}` only → `1×`=native, `1.8×`=ZOOM, `0.8×`=UNAVAILABLE; (b) a phone certifying
    `{ultrawide, front}` → `0.8×`=REAL; (c) a no-concurrency phone → dual unavailable.
  - `DisplayTransform`: extend for both Camera2 sources (primary now uses our transform), across
    sensor/display rotations and front-mirror.
  - Any new pure layout/zoom-clamp helpers.
- **On-device verification (adb + logcat with explicit markers):** `DualCameraSession` open/switch/close,
  processor standalone rendering, mode transitions. Markers: open/close per device id, the resolved
  `DualClass` of a tapped chip, "front session kept alive across back switch."
- **Device matrix:** primary target Seeker — confirm blink-free `1×↔1.8×` zoom, instant swap, `0.8×`
  exit-dual offer, front never drops on a back change. Capability logic makes other phones correct
  without device-specific code; spot-check a second device when available.
- **Regression guard:** single-camera mode behavior-identical (HDR/60fps/exposure/lens/zoom) since its
  CameraX path is untouched — verified on device.

## Risks

- **Concurrent open rejected despite certification:** some HALs are flaky. Mitigation: the `onFailed`
  revert-to-single path; only attempt pairs in `concurrentPairs`.
- **Resolution limits in concurrent mode:** cap per-camera capture at the 720p guarantee.
- **Processor standalone-mode regressions:** keep the rendering core identical; only the input/output
  ownership changes. Cover with on-device markers and the single-mode regression check.
