# M1-B — Android Capture → Encode → Live (H.264)

**Project:** PlohoyStream — a personal Android clone of [Moblin](https://github.com/eerimoq/moblin) (iOS IRL streaming).
**Status:** Design approved in brainstorming; pending spec review.
**Date:** 2026-06-14

## Context: where M1-B sits

M1 (first stream) was split into **M1-A** (C++ RTMP egress core) and **M1-B** (this — the Android half). M1-A is **done, merged, and proven end-to-end** against a real RTMP server (ffmpeg decoded 60 valid H.264 frames); it exposes `RtmpClient` with `Begin()/OnBytes()/state()/streamId()` and a media-send API (`SendVideoConfig/SendVideo/SendAudioConfig/SendAudio`) over a `Transport`, all in `app/src/main/cpp/core/`.

M1-B feeds that core from a real Android camera. It rides the existing **H.264 / RTMP** path untouched — no codec or muxer changes.

**Downstream (not M1-B):**
- **M1-C** — HEVC + HDR. Introduces the codec-tagged-frame + `Muxer` seam in the core (FLV-H264 / FLV-HEVC-enhanced), MediaCodec HEVC/Main10 + HLG color management. The `Muxer` abstraction is *deliberately deferred to M1-C* — M1-B is single-codec and would gain nothing from it (consistent with deferring `Muxer` from M1-A "until a second muxer exists").
- **M2** — manual resolution/bitrate settings, adaptive bitrate, auto-reconnect, local recording.
- **M3** — SRT egress (MPEG-TS muxer); the unrestricted HDR path to your own server.
- **M4+** — scenes/overlays, screen capture, SRTLA bonding, chat, etc.

## Goal

Open the app → see a high-quality camera preview → tap **Go Live** → stream watchable, A/V-synced 1080p H.264 to Twitch (or any RTMP ingest), with **live zoom and lens switching**, surviving backgrounding. Tap **Stop** to end cleanly.

**Done when:** on a Pixel/Samsung device, the app goes live on Twitch with auto-selected best-lens 1080p30, pinch-zoom and lens buttons work mid-stream without dropping the stream, audio and video stay in sync, and the foreground service keeps streaming when the app is backgrounded.

## Scope

### In
- Compose single-screen UI: full-screen preview + overlaid controls (Go Live/Stop, status, zoom, lens buttons, front/back flip) + RTMP URL/key entry (persisted).
- Runtime permissions: camera, microphone, notifications; foreground-service camera/microphone types.
- `CameraController` (Camera2): per-device capability query, capture session to **two surfaces** (preview + encoder input), live zoom/lens/flip reconfiguration.
- `CameraCapabilities`: auto-select best back lens (prefer logical multi-camera), best resolution (1080p target), stabilization (OIS+EIS), continuous 3A, encoder params; detect-but-don't-use HDR capability.
- `VideoEncoder` (MediaCodec H.264, surface-input) → encoded AUs; `AudioEncoder` (AudioRecord → MediaCodec AAC) → encoded AUs.
- JNI `NativeSession` + a native `StreamSession` (one egress thread + bounded frame queue) that owns `RtmpClient`.
- `StreamEngine` (Kotlin interface) + `CameraStreamEngine` (real) + `FakeStreamEngine` (tests/preview); live-control methods (`setZoom`, `selectLens`, `flipCamera`).
- `StreamingService` (foreground, type `camera|microphone`) hosting the pipeline.
- State machine: `Idle → Connecting → Live → Stopping → Idle`, plus `Error(reason)`.
- Bounded-queue backpressure: drop oldest non-keyframe on overflow, logged.

### Out (deferred — explicitly NOT M1-B)
- HEVC, HDR, 10-bit, color spaces beyond sRGB — **M1-C**.
- Manual resolution/bitrate/fps menus, adaptive bitrate, auto-reconnect, local recording — **M2**.
- SRT / MPEG-TS, scenes/overlays, screen capture, external cameras, chat — later.

Single auto-selected config (best lens / 1080p30 / ~6 Mbps / High profile / no B-frames / 2s keyframes). No manual quality menus.

## Architecture

Dependencies point inward; the UI depends on `StreamEngine`, never on Camera2/MediaCodec/JNI.

```
Compose UI (StreamScreen)
  └─> StreamViewModel  (StreamState + config; no framework objects)
        └─> StreamEngine  (interface: start(config)/stop()/setZoom/selectLens/flipCamera; state: StateFlow)
              ├─ FakeStreamEngine    (in-memory state → UI build/preview/tests)
              └─ CameraStreamEngine  (real)
                    ├─ CameraController   (Camera2: capability query + session → preview + encoder surfaces; live zoom/lens)
                    ├─ VideoEncoder       (MediaCodec H.264, surface-input)  ─┐
                    ├─ AudioEncoder       (AudioRecord → MediaCodec AAC)      ─┤ encoded AUs
                    └─ NativeSession(JNI) ──────────────────────────────────┘
                          └─> C++ StreamSession (egress thread + bounded queue)
                                └─> RtmpClient → TcpTransport   [existing, proven]
   StreamingService (foreground, camera|microphone) hosts CameraStreamEngine
```

### Key seams (and the one deliberately omitted)
- **`StreamEngine` (Kotlin)** — UI/ViewModel depend on it; `FakeStreamEngine` enables building/testing the whole UI and state machine with no camera or network. High value.
- **Native `StreamSession` threading boundary** — `RtmpClient` stays single-threaded and lock-free; one egress thread is its sole caller. Android encoder threads only enqueue. The single cross-thread object is the bounded frame queue.
- **`CameraCapabilities`** — pure selection logic over `CameraCharacteristics`, unit-testable against fakes; isolates device-quirk decisions from session wiring.
- **Deliberately NOT in M1-B: the codec/`Muxer` seam.** M1-B is H.264-only; introducing it now would be speculative. It lands in M1-C with HEVC as the real second muxer.

## Data flow

**Video (we never touch pixels — camera→GPU encoder path):**
1. `CameraController` opens the chosen (logical) camera; capture session targets the Activity preview `Surface` **and** `VideoEncoder`'s MediaCodec input `Surface`.
2. MediaCodec (H.264, surface-input) emits encoded AUs on its output callback. First buffer (`BUFFER_FLAG_CODEC_CONFIG`) = SPS+PPS (Annex-B).
3. Config buffer → `NativeSession.pushVideoConfig(annexB)` → native `SplitSpsPps` → `RtmpClient.SendVideoConfig`. Each frame → `pushVideo(bytes, ptsUs, isKeyframe)` → enqueued.
4. Egress thread dequeues → `RtmpClient.SendVideo(annexB, keyframe, ptsMs, dtsMs)` (core converts Annex-B→AVCC). No B-frames → dts=pts, cts=0.

**Audio:** `AudioRecord` PCM → MediaCodec AAC. csd-0 (the `AudioSpecificConfig`) → `pushAudioConfig(sampleRate, channels)` → `RtmpClient.SendAudioConfig`; each AAC frame → `pushAudio(bytes, ptsMs)`.

**A/V sync:** both encoders timestamp from one shared monotonic clock base captured at stream start, so PTS share an origin.

**Egress thread loop** (sole toucher of `RtmpClient`):
```
connect → Begin()
loop until stopped:
    if socket readable (poll ~10ms): Read → OnBytes        // handshake + server keepalive
    while queue non-empty and state==Publishing: pop → SendVideo/SendAudio
    if state==Error: surface reason; break
```
Frames arriving before `Publishing` are dropped — media is never sent in the wrong protocol phase (the state-guard the M1-A review wanted, enforced at the queue boundary, not inside `RtmpClient`).

**Live camera controls** (independent of start/stop, applied on `CameraController`):
- `setZoom(ratio)` → updates the repeating request's `CONTROL_ZOOM_RATIO`. Instant, smooth, no rebuild.
- `selectLens(ratio)` → on a logical camera, snaps zoom ratio (OS swaps physical sensor, seamless); otherwise rebuilds the Camera2 session on a different physical camera using the **same encoder surface** (brief preview hitch, stream uninterrupted).
- `flipCamera()` → true front/back switch (session rebuild on same encoder surface); momentary freeze, stream continues.

**State surfacing:** native session holds an atomic state; `CameraStreamEngine` exposes it as `StateFlow<StreamState>` via lightweight polling (simpler/robust vs native→JVM callbacks for M1-B).

## Capability / quality layer (`CameraCapabilities`)

Auto-selection for the H.264 SDR pipeline (the "best output per device" goal):
- **Best back sensor** — prefer the device's **logical multi-camera**; otherwise the primary main back lens (not ultrawide/depth/mono aux).
- **Resolution** — target 1920×1080 from the encoder's supported sizes (nearest ≤1080p fallback); capture may run higher, encoder Surface scales.
- **Stabilization** — OIS (`LENS_OPTICAL_STABILIZATION_MODE`) + standard EIS (`CONTROL_VIDEO_STABILIZATION_MODE`) where present. (Vendor EIS — Samsung Super Steady, Pixel HAL — is not accessible to third-party apps; stated honestly.)
- **3A** — continuous-video AF, auto-exposure, auto-white-balance, antibanding.
- **Zoom/lens** — read zoom range (min/max `CONTROL_ZOOM_RATIO`) and the available lens ratios (e.g. 0.6×/1×/2×/3×) for the lens buttons.
- **Encoder** — H.264 High profile if supported (else Main/Baseline), ~6 Mbps @ 1080p30, 2s keyframe interval, B-frames off, CBR.
- **HDR detection only** — record `DynamicRangeProfiles`/HLG10 capability for M1-C; M1-B stays sRGB/8-bit.

Output: one immutable `CameraConfig` consumed by `CameraController` + `VideoEncoder`. Selection logic is the testable core; session wiring is integration-tested by running.

## UI, permissions, service

- **UI (Compose, one screen):** full-screen preview + overlays — Go Live/Stop, status pill, pinch-to-zoom + lens buttons (.5/1/2/3×) + front/back flip, and a settings affordance for RTMP **URL + stream key** (persisted via DataStore). No resolution/bitrate menus.
- **Permissions:** `CAMERA`, `RECORD_AUDIO`, `INTERNET`, `POST_NOTIFICATIONS` (13+), `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CAMERA` + `FOREGROUND_SERVICE_MICROPHONE`. Pre-flight: explain → request → handle denial gracefully (never crash/silently fail).
- **`StreamingService`** (foreground, type `camera|microphone`): hosts the engine + camera, persistent "Streaming…" notification with Stop action. Activity binds and supplies the preview Surface; backgrounding drops preview, keeps encoding/streaming.

## Error handling

- Permission denied → never reach `Connecting`; UI explains and re-prompts.
- Camera/encoder init failure → `Error(reason)` before any network attempt.
- Connect/handshake/publish rejected → `RtmpClient` reaches `Error`; egress thread surfaces it; UI shows reason; service stops.
- Mid-stream socket failure → `Error`, clean teardown (no auto-reconnect in M1-B; that's M2).
- Queue overflow → drop oldest non-keyframe, log; never unbounded memory, never a fake-healthy "Live".
- `StreamState.Error` carries a human-readable reason. No silent failures.

## Testing strategy

TDD (red→green) for deterministic logic:
- **`CameraCapabilities`** — best-lens/resolution/zoom-range/lens-ratio selection against fake `CameraCharacteristics`.
- **`StreamEngine` state machine** — via `FakeStreamEngine`: legal transitions, errors land in `Error`.
- **`StreamViewModel`** — input handling + state rendering against `FakeStreamEngine`.
- **PTS/timestamp conversion** (µs→ms, shared base).
- **Native `StreamSession`** — push canned frames → `StubTransport`; assert the egress thread drains the queue and produces expected RTMP bytes; assert pre-`Publishing` frames are dropped and overflow drops oldest non-keyframe. (Reuses M1-A `StubTransport`.)

Integration-tested by running (NOT unit-tested — low value to mock the OS): Camera2 session, MediaCodec, JNI marshalling. Acceptance gate: app → preview → Go Live → watchable on Twitch, zoom/lens switching works mid-stream, A/V in sync, survives backgrounding.

## Build sequence

1. **Kotlin shell, no hardware:** Compose `StreamScreen` + `StreamViewModel` + `StreamEngine`/`FakeStreamEngine`; TDD the ViewModel/state machine. Runs with no camera/network.
2. **Capability layer:** `CameraCapabilities` TDD against fake characteristics → `CameraConfig`.
3. **Preview:** `CameraController` opens the selected camera → on-screen preview (no encoding yet). Proves capability picks + live zoom/lens/flip visually.
4. **Encode:** `VideoEncoder` (MediaCodec surface-input) + `AudioEncoder`; log encoded AUs; verify on device.
5. **Native session + JNI:** `StreamSession` (egress thread + bounded queue) owning `RtmpClient`; TDD against `StubTransport`; add `TcpTransport` non-blocking/`poll()` read; JNI bridge.
6. **Foreground service + wire-up:** `StreamingService`, permissions flow; connect encoders → JNI → core. **Go live for real.**

Each step is independently demonstrable; live happens at step 6.

## Risks / open questions

- **Mid-stream camera switch hitch** — physical-lens/front-back switches rebuild the Camera2 session; aim to minimize the visible freeze (keep encoder + egress running throughout). Logical-camera zoom avoids it for in-lens-family changes.
- **Encoder surface vs varying lens resolutions** — fix the encoder at 1080p; rely on camera→surface scaling so lens switches don't disturb the encoder.
- **MediaCodec device variance** — codec-specific data delivery, color formats; pin to a safe profile, validate on Pixel + Samsung.
- **Backpressure tuning** — bounded-queue size and drop policy need a sane default (e.g. ~1–2s of frames) verified under a constrained network.
- **A/V sync** — shared monotonic base must be set correctly at start; the manual smoke test catches drift.
- **Logical-camera availability** — not all devices expose a good one; lens buttons fall back to discrete physical-camera switches there.
