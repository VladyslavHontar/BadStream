# Beautiful UI — device verification (Seeker)

**Device:** Solana Seeker `SM02E4060314107` (MediaTek), portrait-native 1200×2670, panel supports 30/60/90/120 Hz. **Date:** 2026-06-14. **Branch:** `feat/beautiful-ui`.

Verified iteratively on the physical device (installing builds + visual checks). Note: **`adb screencap` is unreliable for a landscape-locked app** — it renders the app-logical frame, not what the physical panel shows — so preview-orientation checks were confirmed by eye, not screenshots.

## Results

| Area | Result |
|---|---|
| Launch (no crash) with new theme + DataStore | ✅ |
| Landscape-locked + full-screen immersive (system bars hidden) | ✅ |
| Glass `ControlRail` in the right letterbox (timer · zoom chips · flip · Go Live · gear) | ✅ |
| **Camera preview** upright + wide 16:9, undistorted (back camera) | ✅ after fix |
| Settings open: preview shrinks left, panel slides in (native `AnimatedContent`) | ✅ |
| Settings close: animates out (no blank gap, rail crossfades back) | ✅ |
| Settings list + sub-screens scroll | ✅ |
| HDR row absent (Seeker camera has no HLG10) | ✅ expected |
| UI motion smoothness (high refresh rate requested) | ✅ "much better" |
| **Quality picker functional** (res/fps/bitrate → encoder + health target) | ✅ wired |
| **Settings persist** across full close/reopen (DataStore) | ✅ confirmed by user |

## Fixes made during device verification
- **Preview orientation** (`CameraPreview.applyPreviewTransform`): adopted the canonical AOSP `configureTransform` — `display.rotation`-based rotation (`90*(rotation-2)`, =270° on this device where `display.rotation=ROTATION_90`, `sensorOrientation=90`) with `setRectToRect` against a swapped buffer rect, so the 90° rotation does **not** stretch (16:9 stays 16:9). Display-only `TextureView.setTransform`; the MediaCodec encoder surface is untouched. `sensorOrientation` plumbed `CameraInfo→CameraConfig→CameraPreview`.
- **Settings transition crash** (`invalid weight; must be > 0`): the old dual-weight layout made the settings box `weight(0)` on the first open frame. Replaced with `animateDpAsState(rightWidth)` (preview keeps `weight(1f)`) + `AnimatedContent` crossfade.
- **Smoothness**: `MainActivity` requests the panel's highest refresh rate (`preferredDisplayModeId`).

## Live HUD (go-live) — user-verifiable follow-up
The real-stats HUD (bitrate / connection-health / audio meter / elapsed timer + the Go-Live→Stop morph) is fully wired and unit-tested (`BitrateMeter`/`deriveHealth`/`rms16`/engine sampling; 72 unit tests). A full on-device go-live pass (watching the meters move while streaming to a local `ffmpeg -listen` or Twitch) is a quick user check — recipe: `adb reverse tcp:1935 tcp:1935`, host `ffmpeg -y -listen 1 -i rtmp://0.0.0.0:1935/app/test -c copy /tmp/out.flv` (macOS has no `timeout` → background + `pkill -f "listen 1 -i rtmp"`), Settings→Destination `rtmp://127.0.0.1/app` key `test`, Go Live.

## Known carry-forwards (non-blocking)
- `FakeStreamEngine` lives in `src/main` (ships in release) — move to `src/test`.
- `StreamViewModel` ctor typed `StreamEngine` then triple-cast to `VideoStreamEngine` — could type it directly.
- Front-camera preview orientation (sensor 270°) not calibrated; back camera is correct.
- Quality picker changes apply at next go-live (settings dimmed while live — Moblin pattern); no mid-stream reconfig.
