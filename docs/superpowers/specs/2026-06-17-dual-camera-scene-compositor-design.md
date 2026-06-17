# Dual-Camera Scene Compositor — Design

**Date:** 2026-06-17
**Status:** Approved
**Supersedes:** the ad-hoc dual-camera PiP path (`PipLayout` + hand-rolled rotate/crop in `GlRenderer.renderComposite`).

## Problem

Single-camera mode renders both the back and front cameras perfectly, in dual ("PiP")
mode the secondary (front) camera comes out squeezed or sideways. Root cause:

- **Single-cam / dual primary** flow through CameraX's effect pipeline
  (`Preview` → `CameraEffect` → `SurfaceOutput`). CameraX computes a **device-correct
  display transform** via `SurfaceOutput.updateTransformMatrix(...)` that bakes in sensor
  orientation, front-camera mirroring, and the 16:9 `ViewPort` crop. The renderer just
  applies it (`EgressSurfaceProcessor.onFrameAvailable`).
- **Dual secondary** bypasses all of that: it's fed through a raw `SurfaceProvider`
  (`provideSecondarySurface`) with no effect, no `SurfaceOutput`, no `ViewPort`. Only the
  bare `SurfaceTexture` transform is available, so orientation/crop were hand-rolled in GL
  (`renderComposite`) — the source of the recurring distortion.

The capture setup is already correct. The dual path is the wrong shape.

## Approach

Model everything as a **local scene compositor** (OBS/Moblin-style):

- A **scene** is an ordered list of **layers**; each layer is a camera **source** placed in
  a **rect**.
- The compositor renders the same scene to **both** the preview and the encoder, so what
  you see is what you stream.
- Single-cam is the trivial 1-layer scene (source fills the frame). Dual is 2 layers
  (base + PiP). Toggling dual adds/removes a layer; dragging/resizing the PiP mutates a
  layer's rect; both take effect on-stream immediately.

**Invariant:** every layer's source must obtain the *same* device-correct transform that
single-cam gets today. (Chosen approach: **A — derived + uniform**. The primary keeps
CameraX's transform unchanged; the secondary gets a derived, unit-tested transform. Both
sit behind a uniform source/scene/compositor.)

**Zero-regression rule:** the 1-layer (single-cam) case keeps using today's full-frame
`render()` path. The multi-layer compositor only runs in dual mode. Output is functionally
identical; the scene model is the conceptual frame.

## Data Model

All pure data + math, unit-tested. Replaces `PipLayout`.

- **`SourceId`** — enum `PRIMARY` | `SECONDARY` (the two camera slots).
- **`NormRect`** — `{ left, top, right, bottom }` in normalized `0..1`, top-left origin.
  Helpers: `width`, `height`, `center`, `clampInside`, `snapToCorner(margin)`,
  `resizedTo(size, anchor)`.
- **`SceneLayer`** — `{ source: SourceId, rect: NormRect, z: Int }`.
- **`Scene`** — ordered `List<SceneLayer>` (base first / lowest z, PiP on top). Pure ops:
  - `moveLayer(source, newCenter)` — re-center a layer's rect, clamped inside `0..1`.
  - `resizeLayer(source, size, anchor)` — clamp to `[MIN_PIP, MAX_PIP]`.
  - `snapToCorner(source, margin)` — snap a layer to the nearest corner.
  - `swapSources()` — exchange the `source` field between the base layer and the PiP layer
    (rects stay put; the big view and the PiP swap which camera they show).
  - `pipLayer()` / `baseLayer()` accessors.

Defaults: single-cam scene = `[ Layer(PRIMARY, full, z=0) ]`. Dual scene =
`[ Layer(PRIMARY, full, z=0), Layer(SECONDARY, defaultPipRect, z=1) ]`.

## Capture & Per-Source Transforms

Each `SourceId` resolves at runtime to an OES texture id + a **device-correct tex
transform** + a **source aspect**:

- **PRIMARY** — unchanged. Transform = CameraX `SurfaceOutput.updateTransformMatrix(...)`.
  Source aspect = the primary output aspect (16:9 after `ViewPort`).
- **SECONDARY** — fed into its OES texture via `provideSecondarySurface` as today, but the
  transform is no longer guessed. New pure function:

  ```
  displayTransform(sensorDeg: Int, displayDeg: Int, isFront: Boolean): FloatArray /*4x4*/
  ```

  Computes the net rotation (sensor orientation vs. current display rotation) and the
  front-camera mirror, expressed as a texture-coordinate matrix about center (0.5, 0.5).
  The secondary's final tex matrix = `displayTransform ∘ rawSurfaceTextureTransform`.

  `sensorDeg` is read from the secondary camera's `CameraCharacteristics.SENSOR_ORIENTATION`
  at bind time (ground truth). `displayDeg` from the current display rotation. The app is
  landscape-locked, so this is effectively computed once per (re)bind.

### Swap and the concurrent-resolution constraint

Concurrent capture hard-caps the secondary slot low (~320×240) while the primary gets the
high-res slot (~960×720+). So "swap which camera is big" cannot be a purely visual
compositor change — the low-res slot would fill the screen and look bad.

**Swap performs a real concurrent rebind** with the facings exchanged (the high-res slot
follows the big view), wrapped in the **existing freeze-blur transition**
(`beginTransition()`): the user sees a brief blur, not a glitch. After the rebind the scene
references the swapped cameras (`Scene.swapSources()`).

Drag and resize are **pure compositor edits** — fully live, no rebind.

## Compositor / Renderer

Generalize `renderComposite` into a z-ordered layer loop in `GlRenderer`:

- **`drawLayer(textureId, texTransform, rect, sourceAspect, fill = COVER)`** — places the
  source into `rect` via `uTransMatrix`, applies `texTransform` to `uTexMatrix`, and applies
  **cover-crop** (the existing `cropX`/`cropY` math, generalized) from `sourceAspect` vs the
  rect's pixel aspect, about texture center.
- **`renderScene(layers, sources, surface)`** — make current, set viewport, `drawLayer` per
  layer in z-order, set presentation time, swap once.

The base layer (full rect, matching aspect) cover-crops to a no-op. The bespoke 90°-rotate
block is removed: orientation lives entirely in the per-source `texTransform`.

`render()` (single full-frame draw) is retained for the 1-layer single-cam case.

## Interaction UI

`Scene` state is hoisted in `Viewfinder`. New `PipOverlay` composable over the PiP region:

- **Drag** — `detectDragGestures` updates the PiP layer's center via `Scene.moveLayer`;
  **corner-snap** on release (`Scene.snapToCorner`, nearest corner, fixed margin).
- **Resize** — a corner handle plus S/M/L preset chips; free resize clamped to
  `[MIN_PIP, MAX_PIP]` via `Scene.resizeLayer`. Box aspect is free (cover-crop handles any
  aspect).
- **Swap** — a small swap button on the PiP → `controller.swapDual()` (§ Swap).

Edit flow: gesture → mutate hoisted `Scene` → `controller.setScene(scene)` →
`processor.setScene(...)` (`@Volatile`, read on the GL thread) → next frame composites the
new layout, on preview and encoder alike.

## Error Handling

- Concurrent bind failure → fall back to single mode (existing behavior).
- Swap rebind failure → revert the scene to the pre-swap state and keep the pre-swap
  binding (do not leave a half-swapped scene).
- Teardown / producer-disconnect races → already guarded by the try/catch around
  `updateTexImage` in `onFrameAvailable` / `provideSecondarySurface`.

## Testing

Pure unit tests are the backbone (TDD):

- **`displayTransform()`** — canonical `(sensorDeg × displayDeg × facing)` combinations map
  to the expected texture matrices (e.g. front sensor 270° at display 90° → upright +
  mirrored). This is the ground-truth core that was previously missing.
- **`Scene`** — `moveLayer`/`resizeLayer` clamping, `snapToCorner`, `swapSources`,
  default scenes.
- **cover-crop** — `cropX`/`cropY` for representative source-aspect vs rect-aspect cases.

GL output (EGL/GLES) is verified on-device manually — it cannot be unit-tested. Manual
checks: front PiP upright + undistorted; drag/resize live on preview *and* in the encoded
stream (confirm via the SRT/OBS output); swap shows a brief blur then the swapped layout at
correct resolution.

## File Structure

- **Create**
  - `camera/scene/Scene.kt` — `SourceId`, `NormRect`, `SceneLayer`, `Scene` + edit ops.
  - `camera/scene/DisplayTransform.kt` — `displayTransform(...)` + matrix-compose helper.
  - `ui/viewfinder/PipOverlay.kt` — drag/resize/swap gesture surface.
- **Modify**
  - `GlRenderer.kt` — add `drawLayer` / `renderScene`; keep `render()`; drop the bespoke
    rotate/crop block from the old `renderComposite`.
  - `EgressSurfaceProcessor.kt` — hold a `Scene` + the secondary `sensorDeg`; replace the
    `dualMode`/`pipLayout` fields and the composite branch with the scene render loop;
    `setScene(...)`.
  - `CameraXController.kt` — `startDual` reads + passes the secondary `SENSOR_ORIENTATION`;
    add `swapDual()` (transition + rebind with swapped facing) and `setScene(...)`.
  - `Viewfinder.kt` — hoist `Scene` state; wire `PipOverlay`; route edits to the controller.
- **Remove / fold**
  - `PipLayout.kt` → folded into `camera/scene/Scene.kt`.
