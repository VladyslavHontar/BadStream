# SRT Single-Link Egress + Adaptive Bitrate — Design (Phase A)

**Date:** 2026-06-15
**Milestone:** Moblin-parity — sub-project 3 (network bonding), **Phase A of 3** (A: SRT egress → B: SRTLA bonding → C: Android multi-interface)
**Status:** Approved (ready for implementation plan)
**Research:** `docs/superpowers/SRT_SRTLA_RESEARCH.md`

## Goal

Add an SRT egress path alongside RTMP: mux the encoded H.264/HEVC + AAC into MPEG-TS and send
it over a single SRT (libsrt) LIVE caller connection to an SRT/BELABOX relay, with adaptive
bitrate driven by SRT link stats. Independently shippable (single-link SRT beats RTMP for IRL);
the foundation for SRTLA bonding (Phase B).

## Decisions (locked during brainstorm)

- **libsrt = prebuilt `.so` per ABI**, built once with Haivision's `build-android` script,
  **`ENABLE_ENCRYPTION=OFF`** (BELABOX relays don't require SRT encryption); commit the `.so` +
  headers per ABI into the repo.
- **Include adaptive bitrate (ABR)** in Phase A: `srt_bstats` → a BELABOX-style algorithm →
  runtime encoder bitrate change.
- **Own MPEG-TS muxer** in C++ (host-testable); built and tested BEFORE the libsrt integration.
- SRT selected by **URL scheme `srt://`**; RTMP (`rtmp://`/`rtmps://`) stays the default path.

## Architecture

```
Kotlin (CameraStreamEngine / NativeRtmpStreamer-equivalent)
  endpoint scheme srt:// ─► NativeSrtStreamer (JNI)  ─►  C++ SrtSession
                                                            ├─ MediaQueue (REUSED; same MediaItem)
                                                            ├─ TsMuxer  (NEW: AAC+H264/HEVC → MPEG-TS, 188B)
                                                            └─ SrtLink  (NEW: libsrt LIVE caller)
                                                                  • srt_connect(host,port; streamid,latency)
                                                                  • srt_sendmsg2 (1316B = 7×188)
                                                                  • srt_bstats → ABR → JNI → encoder
  endpoint scheme rtmp:// ─► NativeRtmpStreamer ─► StreamSession (EXISTING, unchanged)
```

`SrtSession` mirrors `StreamSession`'s shape (a dedicated egress thread draining the existing
`MediaQueue` of `MediaItem`s; same start/stop/SendVideoConfig/SendVideo/SendAudioConfig/SendAudio
API) so the Kotlin engine drives either identically. The difference is the body: instead of FLV
tags over TCP/RTMP, it feeds samples to `TsMuxer` and the resulting TS packets to `SrtLink`.

## Components

### C1. `TsMuxer` (`cpp/core/ts_muxer.{h,cpp}`) — NEW, host-testable
Produces a single-program MPEG-TS stream from encoded access units:
- **PSI:** `PAT` (program 1 → PMT PID) + `PMT` (video stream_type 0x1B AVC / 0x24 HEVC, audio
  0x0F AAC-ADTS; PCR PID = video PID), emitted periodically (e.g. every ~100 ms / before the
  first frame and refreshed).
- **PES:** packetize video (Annex-B → with AUD, or as-is) and audio (AAC → **ADTS-framed**),
  with PTS/DTS (90 kHz) from the sample ms timestamps (×90). Video carries PCR (PTS − a small
  preroll) in the adaptation field on PCR-PID packets.
- **TS packets:** 188-byte packets, 4-byte header (sync 0x47, PID, continuity counter,
  payload-unit-start, adaptation control), adaptation field for PCR/stuffing; pack output for
  1316-byte SRT payloads (7 packets) — the muxer emits a byte stream; the caller slices 1316.
- Codec config: AVC SPS/PPS / HEVC VPS+SPS+PPS go into the PES stream as the first NALs of the
  first keyframe (in-band), reusing existing `flv.cpp`/`hevc.cpp` NAL helpers where useful
  (Annex-B handling) — TS uses Annex-B in-band params, not avcC/hvcC.
- **CRITICAL (biggest risk):** PCR/PTS/DTS correctness. Continuity counters per PID. Unit-test
  the output and verify with `ffprobe`/`ffmpeg` before any networking.

### C2. libsrt prebuilt + `SrtLink` (`cpp/core/srt_link.{h,cpp}`) — NEW
- **Prebuilt libsrt:** build with `github.com/Haivision/srt` `scripts/build-android` (NDK,
  `ENABLE_ENCRYPTION=OFF`), producing `libsrt.so` for arm64-v8a / armeabi-v7a / x86 / x86_64;
  commit under `cpp/third_party/srt/{include,libs/<abi>/libsrt.so}`; reference from CMake
  (`add_library(srt SHARED IMPORTED)` per ABI + `target_link_libraries`). Headers `srt/srt.h`.
- **`SrtLink`:** wraps a LIVE caller — `srt_startup` (once), create socket,
  `srt_setsockflag(SRTO_TRANSTYPE=SRTT_LIVE, SRTO_LATENCY=<cfg, default 2000-5000ms>,
  SRTO_PAYLOADSIZE=1316, SRTO_STREAMID=<cfg>)`, `srt_connect(host,port)`, send via
  `srt_sendmsg2` (1316-byte chunks), poll `srt_bstats` for ABR, `srt_close`/`srt_cleanup`.
  Exposes a connection state + stats snapshot.

### C3. `SrtSession` (`cpp/core/srt_session.{h,cpp}`) — NEW
Egress thread: connect `SrtLink`; on success → Live; drain `MediaQueue`; for each `MediaItem`
feed `TsMuxer`, send produced TS bytes (sliced to 1316) via `SrtLink`; surface bytesSent /
queue depth / SRT stats; teardown on stop or fatal SRT error (→ Dropped/Rejected like the RTMP
session, so the existing Kotlin reconnect loop applies). Reuses the existing timestamp-rebase
approach.

### C4. ABR (`cpp/core/abr.{h,cpp}` + JNI callback) — NEW, host-testable
A pure BELABOX-style controller: input `srt_bstats` (RTT, send-buffer level / `pktSndBuf`,
in-flight, loss) + the configured target/min/max bitrate → output a target encoder bitrate.
`SrtSession` polls stats ~every 1 s, runs the controller, and when the target changes calls a
JNI callback → Kotlin → `VideoEncoder` runtime bitrate change
(`MediaCodec.setParameters(PARAMETER_KEY_VIDEO_BITRATE)`). The control law is unit-testable
(pure function over stats); the encoder hook is the only Android part.

### C5. JNI + Kotlin selection
- `NativeSrtStreamer` (Kotlin, JNI facade mirroring `NativeRtmpStreamer`) → `SrtSession`.
- `RtmpStreamer` interface already abstracts the egress; add the SRT impl behind the same
  interface (or a shared `Streamer` interface). The engine/`startMedia` picks the impl from the
  endpoint scheme: `srt://` → `NativeSrtStreamer`, else `NativeRtmpStreamer`.
- `RtmpEndpoint`/endpoint parsing extends to accept `srt://host:port?streamid=…&latency=…` and
  expose scheme + SRT params. Settings: the existing destination URL field accepts `srt://…`;
  add SRT-specific settings (latency, streamid, ABR on/off + min/target/max) to the trait.
- `VideoEncoder` gains a `setTargetBitrate(bps)` that calls `MediaCodec.setParameters`.

## Error handling
- libsrt connect failure / broken link → `SrtSession` ends with a transient reason → existing
  Kotlin reconnect loop retries (same as RTMP Dropped). Auth/streamid rejection by the relay →
  terminal (Rejected).
- ABR never drives the encoder below the configured min or above max; rate-limited changes
  (avoid thrashing); MediaCodec bitrate change is best-effort (ignore if the codec rejects).
- `srt_startup`/`srt_cleanup` reference-counted so multiple sessions over a process lifetime are
  safe.

## Testing
- **Host C++ unit tests** (the core value):
  - `TsMuxer`: feed known SPS/PPS + a keyframe + a few frames + AAC; assert valid TS — sync
    bytes, PAT/PMT contents (PIDs, stream types), PES headers, PTS/DTS (90 kHz from ms),
    PCR presence on the PCR PID, continuity counters increment per PID, 188-byte alignment.
    **Cross-check the muxed output with `ffprobe`/`ffmpeg` if available in the harness** (decode
    a captured `.ts` and confirm stream/codec/timestamps) — this is the key correctness gate.
  - `Abr`: the pure control law over crafted `srt_bstats` inputs (rising RTT/buffer → lower
    bitrate; healthy → climb toward target/max; clamp to min/max; no thrash).
  - Endpoint parsing for `srt://` (scheme, host, port, streamid, latency).
- **Host build of libsrt** verified by linking a tiny C++ test that calls `srt_startup`.
- **On-device (manual — the real gate):** stream `srt://` to a BELABOX/SRT relay (or local
  `srt-live-transmit` / MediaMTX SRT) via the device; confirm it connects, plays through the
  relay, survives a brief link wobble (ABR drops bitrate then recovers). Document in a smoke note.

## Out of scope (Phase B/C and later)
SRTLA bonding / multiple links (Phase B); Android cellular+WiFi simultaneous binding
(Phase C); SRT encryption (passphrase); per-link priorities/UI; RIST/WHIP; SRT *ingest*
(listener) mode.
