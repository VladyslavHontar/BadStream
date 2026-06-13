# M1 — First Stream (RTMP vertical slice)

**Project:** PlohoyStream — a personal Android clone of [Moblin](https://github.com/eerimoq/moblin) (iOS IRL streaming).
**Status:** Design approved in brainstorming; pending spec review.
**Date:** 2026-06-13

## Goal

Get from "launch app" to "I am live on Twitch/YouTube" through the narrowest possible
path that still exercises every architectural layer. M1 is a thin vertical slice, not a
feature set. When it works, the spine the whole roadmap hangs off of is proven.

**Done when:** with the back camera at fixed settings, tapping **Go Live** produces a
watchable H.264/AAC stream on a real Twitch (or YouTube) ingest, and tapping **Stop**
cleanly ends it.

## Scope

### In
- Single Compose screen: camera preview, RTMP URL + stream key fields, Go Live / Stop, a status line.
- Foreground service that owns the stream (survives the app being backgrounded; posts the required notification).
- Runtime permissions: camera + microphone.
- Capture: back camera preview + frames via CameraX/Camera2.
- Encode: MediaCodec → H.264 (video) + AAC (audio), with PTS.
- JNI boundary: hand encoded access units to the C++ core.
- C++ egress core: `FlvMuxer` → `RtmpClient` (handshake + AMF0 connect/publish) → `Transport` (TCP).
- A minimal, honest state machine: `Idle → Connecting → Live → Stopping → Idle`, plus `Error(reason)`.

### Out (deferred to later milestones — listed so they are explicitly *not* M1)
- Front/back switch, resolution/bitrate/fps/audio selection, orientation handling — **M2**.
- Auto-reconnect, local recording — **M2**.
- SRT egress, MPEG-TS muxer, the `Muxer` interface — **M3**.
- Scenes, overlays, screen capture, feed-in — **M4/M5**.
- SRTLA bonding — **M6**.
- Chat, OBS remote, BLE remote — **M7+**.

A single hardcoded resolution/bitrate/fps (e.g. 720p / 2.5 Mbps / 30fps) is fine for M1.

## Architecture

Dependencies point inward. The UI depends on a `StreamEngine` abstraction; it never
knows about JNI, MediaCodec, or sockets.

```
Compose UI  ──>  StreamViewModel  ──>  StreamEngine (Kotlin interface)
                                            │
                          ┌─────────────────┴───────────────────┐
                   FakeStreamEngine                     JniStreamEngine
                   (tests / @Preview)                          │
                                              CameraX/Camera2  →  MediaCodec
                                                                  │  (encoded AUs + PTS)
                                                                 JNI
                                                                  │
                                              C++:  FlvMuxer  →  RtmpClient  →  Transport
                                                                                  ├── TcpTransport
                                                                                  └── StubTransport (tests)
```

### Seams (and one deliberately omitted)

These interfaces earn their place; we add no others in M1.

- **`Transport` (C++)** — `connect(host, port)`, `write(span<byte>)`, `close()`.
  - `TcpTransport` (real), `StubTransport` (captures written bytes for assertions).
  - **Why:** makes the entire RTMP/FLV layer testable with zero sockets, and is the exact
    insertion point for SRT (M3) and SRTLA (M6). Mirrors the `TcpTransport`/`StubTransport`
    pattern the user already validated in their Rust work.
- **`StreamEngine` (Kotlin)** — `start(config)`, `stop()`, `state: StateFlow<StreamState>`.
  - `JniStreamEngine` (real), `FakeStreamEngine` (drives state transitions in-memory).
  - **Why:** the ViewModel and Compose previews are built and tested against this with no
    camera or network.

- **Deliberately NOT abstracted in M1: the muxer.** `FlvMuxer` is a concrete class. A
  `Muxer` interface only earns its keep when MPEG-TS arrives for SRT in M3. Adding it now
  would be speculative abstraction. We introduce it in M3, not before.

## Components

### Kotlin / Android
- **`MainActivity` + Compose `StreamScreen`** — preview surface, inputs, Go Live/Stop, status text. Stateless; renders `StreamState`.
- **`StreamViewModel`** — holds config (URL/key), exposes `StreamState`, delegates to `StreamEngine`. Owns no Android framework objects.
- **`StreamEngine` / `JniStreamEngine`** — orchestrates the capture→encode→JNI flow; owns the foreground service handle.
- **`CameraSource`** — CameraX/Camera2 wrapper: preview + a frame stream into the encoder.
- **`VideoEncoder` / `AudioEncoder`** — MediaCodec wrappers emitting encoded access units `(ByteArray, ptsUs, isKeyframe)`.
- **`StreamingService`** — foreground service hosting the engine while live.

### C++ (NDK core, `app/src/main/cpp`)
- **`Transport`** (interface) + **`TcpTransport`**, **`StubTransport`**.
- **`FlvMuxer`** — wraps encoded video/audio access units into FLV tags (AVC sequence header from SPS/PPS, AAC sequence header from ASC, then media tags).
- **`RtmpClient`** — C0/C1/C2 handshake, AMF0 `connect`/`createStream`/`publish`, chunking, writes FLV payloads via a `Transport`.
- **JNI bridge** — `nativeStart/nativeStop/nativePushVideo/nativePushAudio`; marshals access units across the boundary.

## Data flow (happy path)

1. User enters URL + key, taps **Go Live**.
2. ViewModel → `StreamEngine.start(config)`; state → `Connecting`. Foreground service starts.
3. `RtmpClient.connect()` via `TcpTransport`: TCP → RTMP handshake → AMF0 `connect` → `createStream` → `publish`.
4. On publish success: state → `Live`. Encoders start; `FlvMuxer` emits the AVC/AAC sequence headers first.
5. Each encoded AU crosses JNI → `FlvMuxer` → FLV tag → `RtmpClient` → chunked → `Transport.write`.
6. **Stop:** encoders stop, `RtmpClient` sends `deleteStream`/closes, `Transport.close()`, service stops; state → `Idle`.

## Error handling (M1-minimal, honest)

No auto-reconnect in M1 (that's M2) — but failures must surface, never hang.
- Connect failure (DNS/TCP/handshake/rejected publish) → `Error(reason)`, service stops, UI shows it.
- Mid-stream socket write failure → `Error(reason)`, clean teardown.
- Permission denied → never reach `Connecting`; UI prompts/explains.
- Camera/encoder init failure → `Error(reason)` before any network attempt.

`StreamState.Error` carries a human-readable reason. No silent failures, no fake "Live".

## Testing strategy

TDD (red → green, test unchanged) for the deterministic logic:
- **`FlvMuxer`** — given known SPS/PPS + an AU, assert exact FLV tag bytes.
- **`RtmpClient`** — drive against `StubTransport`; assert the handshake + AMF0 `connect`/`publish` byte sequences; assert it surfaces a rejected publish as an error.
- **`StreamEngine` state machine** — via `FakeStreamEngine` / a test double of the transport: assert legal transitions and that errors land in `Error`.
- **`StreamViewModel`** — against `FakeStreamEngine`: input handling + state rendering.

Integration-tested by going live (NOT unit-tested — low value to mock the OS):
- Camera2/MediaCodec glue and the JNI marshalling. Verified by an actual watchable stream
  on a real ingest. A short manual smoke test (point at a clock, confirm A/V sync and that
  the stream is watchable) is the acceptance gate.

## Build sequence

1. **C++ core, offline first (TDD):** `Transport` + `StubTransport`; `FlvMuxer`; `RtmpClient` handshake + AMF0. All red→green against `StubTransport`, no device needed.
2. **`TcpTransport`** + a tiny host-side harness: push a canned H.264/AAC sample to a real ingest, confirm watchable. (Proves the protocol before any Android wiring.)
3. **Kotlin shell:** Compose screen + ViewModel + `StreamEngine`/`FakeStreamEngine`; TDD the ViewModel/state machine. Runs with no camera.
4. **Capture + encode:** `CameraSource` + MediaCodec encoders; preview on screen; encoded AUs logged.
5. **Wire JNI:** encoded AUs → C++ core; foreground service. **Go live for real.**

Each step is independently demonstrable; live happens at step 5, but the protocol is already proven at step 2.

## Risks / open questions

- **MediaCodec quirks** (codec-specific data delivery, color formats, B-frames) vary by device — pin to baseline/main profile, no B-frames for M1.
- **RTMP AMF0 edge cases** per ingest (Twitch vs YouTube handshake/`connect` params) — target Twitch first, keep `connect` params configurable.
- **A/V sync** — get PTS bases right early; the smoke test catches drift.
- **minSdk 36** is very high (Android 16) and limits test devices — confirm the scaffold's `minSdk` is intentional or lower it in M1.
```
