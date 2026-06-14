# Reconnect & Network Resilience — Design

**Date:** 2026-06-14
**Milestone:** M2 "Usable streaming" — sub-project A (first of four)
**Status:** Approved (ready for implementation plan)

## Goal

Make a live stream survive a flaky mobile connection: detect drops promptly, keep
the RTMP control channel serviced so strict servers (Twitch/YouTube) don't silently
kill us, tear down cleanly, and automatically reconnect when the connection blips —
without the user touching anything.

## Problem (current behavior)

Two gaps, both required for real resilience:

1. **Drops are not recovered.** A connection drop becomes native `SessionState::Error`,
   which the Kotlin poll loop maps to a terminal `StreamState.Error` and stops. The
   stream is dead until the user manually goes live again. There is **no**
   `StreamState.Reconnecting` — only an unused `reconnecting` boolean param on the
   `LiveStatusCluster` composable.

2. **Drops are often not even detected — and we get silently killed.**
   - `RtmpClient::Send` ignores the `Transport::Write` failure bool, so a write to a
     dead socket does not trip an error.
   - Once `Publishing` is reached, the egress thread only *writes*; it never reads
     inbound. So we never answer the server's **User-Control Ping**, never send
     **Acknowledgements**, never reply to **Window-Acknowledgement-Size**. Strict
     servers drop a publisher that goes silent on the control channel.
   - Teardown is a bare TCP close — no `FCUnpublish`/`deleteStream`/`closeStream`, so
     the ingest slot can stay "live" briefly and block an immediate reconnect with
     "stream already publishing".

## Decisions (locked during brainstorm)

- **Scope:** full package — native hardening **and** Kotlin auto-reconnect.
- **Retry policy:** flat **5-second** delay, retry **indefinitely** while the stream is
  toggled on (Moblin-style). Simple and proven.
- **Reconnect strategy:** **full media-pipeline restart each attempt** (Moblin-style).
  Tear down encoders + native session, rebuild from scratch. The camera preview visibly
  blips during reconnect — accepted for simplicity. (A fresh encoder emits fresh CSD +
  keyframe for free, so no CSD-caching is needed.)
- **Transient vs terminal:** a **transport/socket/write drop** is transient → reconnect.
  A **server rejection** (`_error`, auth/bad-key, "already publishing") is terminal →
  `StreamState.Error`, no retry. Retrying a bad stream key forever is pointless.

## Architecture

Native services the protocol; Kotlin owns the retry loop. This matches Moblin
(protocol servicing in `RtmpConnection`, reconnect orchestration in the app/model layer)
and fits our existing layering.

```
┌─────────────────────────────────────────────────────────────┐
│ CameraStreamEngine (Kotlin)                                   │
│   • owns endpoint + config + media pipeline + StreamState     │
│   • reconnect loop: on Dropped → Reconnecting → wait 5s →     │
│     full restart → Live ;  on Rejected → Error (terminal)     │
└───────────────┬─────────────────────────────────────────────┘
                │ start / stop / poll state + stop-reason (JNI)
┌───────────────▼─────────────────────────────────────────────┐
│ StreamSession (C++) — ONE connect attempt                     │
│   • handshake → connect → publish → run                       │
│   • run loop now ALSO reads inbound + services control channel │
│   • Stop() sends graceful FCUnpublish/deleteStream/closeStream │
│   • exposes stop-reason: Dropped (transient) vs Rejected (term)│
└──────────────────────────────────────────────────────────────┘
```

The native session stays a single-attempt unit. "Reconnect" = the Kotlin engine
rebuilds the whole pipeline on a timer. No reconnect logic lives in C++.

## Component design

### C1. Propagate write failures (`rtmp_client.{h,cpp}`, `tcp_transport`)
`RtmpClient::Send` currently discards `Transport::Write`'s bool. Make `Send` return
success/failure and have callers treat a `false` write as a transport drop. The
publishing loop in `stream_session.cpp` must end the session with a **Dropped** reason
when a send fails (today it relies on `recv <= 0`, which a write-only loop never sees).

### C2. Service the control channel while publishing (`stream_session.cpp`, `rtmp_client.cpp`)
Today the run loop reads inbound only until `Publishing`, then writes only. Change the
publish loop to, each iteration:
1. **Non-blocking read** of any available inbound bytes (single thread — no locking;
   reads and writes on the same fd from the one egress thread).
2. Feed them to `OnBytes`, which gains handling for control/user-control messages:
   - **User-Control Ping (type 0x04, event 6) → PingResponse (event 7)** echoing the
     timestamp.
   - **Window-Acknowledgement-Size (0x05) →** adopt the server's window and send our own
     Window-Ack-Size back.
   - **Set-Peer-Bandwidth (0x06) →** reply with a matching Window-Ack-Size.
   - **Acknowledgement (0x03) →** track server-acked bytes (informational).
   - **Send our own Acknowledgement** once we have received a full window's worth of
     bytes (track running received-byte count).

This is the single most important fix for not getting silently dropped by Twitch.

### C3. Graceful teardown (`stream_session.cpp`, `rtmp_client.cpp`)
On `Stop()`, before closing the socket and while still `Publishing`, send the publisher
unpublish sequence: **`FCUnpublish(streamKey)` → `deleteStream(streamId)` →
`closeStream`**, then close. Bounded/best-effort (don't hang teardown on a dead socket).

### C4. Stop-reason over JNI (`stream_session.{h,cpp}`, `native-lib.cpp`, `NativeRtmpStreamer.kt`)
The native session distinguishes how it ended:
- **`Dropped`** — transport closed / write failed / read returned ≤ 0 mid-publish.
- **`Rejected`** — server returned `_error` / `level == "error"` (auth, bad name,
  already-publishing), or connect was refused.

Expose this as a small int reason code alongside the existing state int (e.g. a
`nativeStopReason()` JNI call, or fold it into the state enum). Kotlin reads it to
decide reconnect vs terminal.

### K1. `StreamState.Reconnecting` (`StreamState.kt`)
Add a `Reconnecting` member to the sealed state (current members: `Idle`, `Connecting`,
`Live`, `Stopping`, `Error`). It drives the existing
`LiveStatusCluster(reconnecting = …)` UI pill — no new UI components needed, just wire
the boolean from this state.

### K2. Reconnect loop (`CameraStreamEngine.kt`)
The engine gains a reconnect orchestration around the existing start/stop machinery:

- Track user intent: `userWantsLive` (set true on `start()`, false on `stop()`).
- The poll loop maps the native state + stop-reason:
  - native ended with **Dropped** while `userWantsLive` → set `StreamState.Reconnecting`,
    tear down media + session (`stopMedia` + native stop), **wait 5 s**, then full
    restart (the same path `start()` uses), loop.
  - native ended with **Rejected** → `StreamState.Error(reason)`, exit loop, clear intent.
  - user `stop()` → always wins: cancel any pending reconnect wait, terminal teardown.
- The 5 s wait must be cancellable (a `stop()` during the wait aborts it immediately).
- The reconnect delay uses the engine's injected dispatcher so unit tests can drive a
  virtual clock (consistent with the existing `tickerDispatcher` pattern).

### K3. UX wiring (`StreamScreen` / `Viewfinder` / `LiveStatusCluster`)
- `Reconnecting` → pulsing `RECONNECTING` pill (existing `reconnecting` param).
- Foreground service keeps running across reconnects (we are still "streaming").
- Elapsed timer keeps counting (session duration, not connection duration).
- Camera preview blips on each attempt (accepted — Option B).

## Error handling

| Situation | Outcome |
|---|---|
| Socket drop / write failure mid-stream | `Dropped` → `Reconnecting`, retry every 5 s forever |
| Server `_error` / bad key / already-publishing | `Rejected` → terminal `Error`, no retry |
| Connect never completes (no `_result`) | connect timeout → `Dropped` → reconnect |
| User taps Stop during reconnect wait | wait cancelled, terminal teardown to `Idle` |
| Teardown on a dead socket | best-effort graceful sequence, bounded, then close |

## Testing strategy

**Native host tests** (`app/src/main/cpp/test/`, fake transport):
- Write failure mid-publish → session ends with `Dropped`.
- Inbound User-Control Ping → a correct PingResponse is written.
- Inbound Window-Ack-Size → our Window-Ack-Size is echoed.
- Received-byte window exceeded → an Acknowledgement is emitted.
- `Stop()` while publishing → `FCUnpublish`+`deleteStream`+`closeStream` byte sequence is
  written before close.
- Server `_error` `_result` → session ends with `Rejected` (not `Dropped`).

**Kotlin unit tests** (fake engine + virtual clock):
- Dropped while live → `Reconnecting`, and after 5 s a restart is attempted → `Live`.
- Rejected → terminal `Error`, no restart attempted.
- `stop()` during the reconnect wait → no restart, ends `Idle`.
- Repeated drops → repeated reconnects (loop does not give up).

**Live smoke test:** stream to local `ffmpeg -listen` or MediaMTX via
`adb reverse tcp:1935 tcp:1935`; toggle the device network off/on and confirm the pill
goes `RECONNECTING` then back to `LIVE` and the server sees a fresh publish.

## Carry-forward cleanups to fold in (optional, only if touched)
- Move `FakeStreamEngine` out of `src/main` into `src/test` (it ships in release today).
- Untangle `StreamViewModel`'s `StreamEngine`→`VideoStreamEngine` triple-cast.

These are not required by this sub-project; include only if the work naturally touches
those files.

## Out of scope (later M2 sub-projects)
A/V sync, local recording, real HEVC-over-the-wire negotiation proof.
