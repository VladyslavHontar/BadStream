# SRT Single-Link Egress + ABR (Phase A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Mux encoded H.264/HEVC + AAC into MPEG-TS and send over a single libsrt LIVE caller to an SRT/BELABOX relay, with adaptive bitrate. Selected by `srt://` URL scheme; RTMP path unchanged.

**Architecture:** New `SrtSession` (C++) parallel to `StreamSession`, reusing the `MediaQueue`/`MediaItem` model: `TsMuxer` → `SrtLink` (libsrt). ABR from `srt_bstats` → encoder. Build order is **host-testable-first**: TS muxer + ABR + endpoint parsing (no libsrt) before the libsrt NDK build and the device-gated session.

**Tech Stack:** C++17 (NDK core + GoogleTest host tests), prebuilt libsrt (`ENABLE_ENCRYPTION=OFF`), Kotlin/JNI, MediaCodec runtime bitrate. **Spec:** `docs/superpowers/specs/2026-06-15-srt-egress-design.md`. **Research:** `docs/superpowers/SRT_SRTLA_RESEARCH.md`.

**Test commands:** C++ host — `cd app/src/main/cpp && cmake -S test -B build-test && cmake --build build-test && ctest --test-dir build-test --output-on-failure`. Kotlin — `./gradlew testDebugUnitTest`. App — `./gradlew assembleDebug`.

---

## Phase 1 — MPEG-TS muxer (C++, host-testable, libsrt-INDEPENDENT) — the correctness core

### Task 1.1: TsMuxer — PSI + single video track
**Files:** Create `app/src/main/cpp/core/ts_muxer.{h,cpp}`; test `app/src/main/cpp/test/test_ts_muxer.cpp`; register in `core/CMakeLists.txt` + `test/CMakeLists.txt`.

- [ ] **Step 1: Write failing tests** asserting TS fundamentals over the muxer's output bytes. Use a small parser in the test (walk 188-byte packets). Cover:
  - Output length is a multiple of 188; every packet starts with sync byte `0x47`.
  - A `PAT` packet (PID 0x0000) exists, declares program 1 → a PMT PID.
  - A `PMT` packet exists; for an AVC config it lists video `stream_type 0x1B` on the video PID and sets PCR_PID = video PID.
  - After feeding one keyframe access unit, a PES packet on the video PID exists with `packet_start_code_prefix 00 00 01`, stream_id `0xE0` (video), and a PTS in the PES header equal to `ptsMs * 90`.
  - Continuity counters on the video PID increment by 1 (mod 16) across its packets.
  Write these as concrete byte assertions (see test_flv.cpp / test_stream_session.cpp ParseMessages for the byte-walking style; add a tiny `ParseTsPackets` helper in the test).

- [ ] **Step 2: Run red** — `cmake -S test -B build-test && cmake --build build-test 2>&1 | tail` → FAIL (no TsMuxer).

- [ ] **Step 3: Implement** `ts_muxer.{h,cpp}`. API:

```cpp
class TsMuxer {
public:
    void SetVideo(VideoCodecKind kind);      // Avc(0x1B) or Hevc(0x24)
    void SetAudio(int sampleRate, int channels);  // AAC-ADTS (stream_type 0x0F)
    // Append an access unit; returns the produced TS bytes (188-byte packets).
    Bytes WriteVideo(const Bytes& annexb, bool keyframe, uint32_t ptsMs, uint32_t dtsMs);
    Bytes WriteAudio(const Bytes& aac, uint32_t ptsMs);   // raw AAC -> wrap in ADTS
private:
    // PAT/PMT emitted before the first packet and refreshed ~every 100ms of media time.
    // Per-PID continuity counters; PES packetization; PTS/DTS at 90kHz (ms*90);
    // PCR on the video PID's adaptation field (PCR = (pts - preroll)*... in 27MHz/300 base).
};
```
Implementation notes: standard TS — 4-byte TS header (sync, TEI/PUSI/priority, 13-bit PID, scrambling/adaptation/CC), adaptation field for PCR + stuffing to fill 188; PES header (start code, stream_id, PES packet length (0 allowed for video), flags, PTS/DTS 5-byte each with the 0010/0011 markers). Wrap AAC in a 7-byte ADTS header (profile AAC-LC, sampling-frequency index from `sampleRate`, channel config). Reuse Annex-B start-code scanning from existing helpers where useful. Keep video params (SPS/PPS or VPS/SPS/PPS) in-band on the first keyframe.

- [ ] **Step 4: Run green** + register in CMake; all existing C++ tests still pass.
- [ ] **Step 5: Commit** `feat(srt): MPEG-TS muxer — PSI + AVC video PES (host-tested)`

### Task 1.2: Audio track + PCR/DTS edge cases + ffprobe cross-check
- [ ] **Step 1:** Add tests: audio PES on the audio PID with ADTS framing + correct PTS; PMT lists both streams; PCR appears at the start and at a bounded interval (<100ms); for B-frame-free streams DTS==PTS, and when dtsMs<ptsMs the PES carries both DTS and PTS.
- [ ] **Step 2:** Run red → implement audio + PCR interval + DTS handling → green.
- [ ] **Step 3: ffprobe cross-check (key correctness gate).** Add a test (or a scripted check) that writes a muxed `.ts` to a temp file and, IF `ffprobe` is on PATH, runs `ffprobe -v error -show_entries stream=codec_name,codec_type -of csv` and asserts it reports the expected `h264`+`aac` (and `-show_packets` timestamps are monotonic). If `ffprobe` is absent, skip with a logged note (the byte-level asserts still gate). Document the manual `ffprobe`/`ffmpeg -i out.ts` check in the smoke doc regardless.
- [ ] **Step 4: Commit** `feat(srt): TS muxer audio (ADTS) + PCR/DTS + ffprobe cross-check`

### Task 1.3: HEVC video
- [ ] Add HEVC: PMT `stream_type 0x24`, in-band VPS+SPS+PPS on the first keyframe; test with an HEVC config fixture. Commit `feat(srt): TS muxer HEVC support`.

---

## Phase 2 — ABR control law (C++, host-testable, no libsrt)

### Task 2.1: Pure ABR controller
**Files:** Create `app/src/main/cpp/core/abr.{h,cpp}`; test `test/test_abr.cpp`; register in CMake.

- [ ] **Step 1: Write failing tests** for a pure controller:

```cpp
struct AbrStats { double rttMs; int sndBufPkts; int inflight; double lossPct; };
struct AbrConfig { int minBps; int targetBps; int maxBps; };
// returns the next target encoder bitrate (bps), given current stats + current bitrate.
int AbrNextBitrate(const AbrStats& s, const AbrConfig& cfg, int currentBps);
```
Cases: healthy (low RTT, empty send buffer, no loss) → climbs toward `maxBps` (bounded step); congested (rising send buffer / loss) → drops bitrate (toward `minBps`); clamps to [min,max]; no oscillation/thrash (bounded per-step delta); stable input → stable output.

- [ ] **Step 2:** Run red → **Step 3:** implement a BELABOX-style law (decrease fast on buffer/loss, increase slowly when clear, clamp + rate-limit) → **Step 4:** green.
- [ ] **Step 5: Commit** `feat(srt): adaptive-bitrate control law (host-tested)`

---

## Phase 3 — Endpoint parsing (Kotlin, host-testable)

### Task 3.1: `srt://` scheme in endpoint parsing
**Files:** `app/src/main/java/com/example/plohoystream/stream/RtmpEndpoint.kt` (or a new `Endpoint` abstraction); test `app/src/test/java/.../stream/RtmpEndpointTest.kt`.

- [ ] **Step 1: Write failing tests:** parsing `srt://host:port?streamid=foo&latency=3000` yields scheme=SRT, host, port, streamid, latency; `rtmp://…`/`rtmps://` still parse as RTMP (existing behavior unchanged). A scheme accessor distinguishes them.
- [ ] **Step 2:** Run red → **Step 3:** extend parsing to recognize the `srt://` scheme + query params, exposing a `scheme` (enum RTMP/SRT) + SRT fields, without breaking existing RTMP cases → **Step 4:** green (all existing RtmpEndpoint tests pass).
- [ ] **Step 5: Commit** `feat(srt): parse srt:// endpoints (scheme + streamid + latency)`

---

## Phase 4 — libsrt prebuilt + CMake wiring

### Task 4.1: Build & vendor libsrt for all ABIs
**Files:** `app/src/main/cpp/third_party/srt/{include/, libs/<abi>/libsrt.so}`; `core/CMakeLists.txt`.

> ENVIRONMENT TASK — runs the Haivision build once. If the NDK build can't run in this sandbox, STOP and report BLOCKED with the recipe so the user runs it in their dev environment; the rest of Phase 4+ depends on it.

- [ ] **Step 1:** Clone `https://github.com/Haivision/srt` to a temp dir. For each ABI in `arm64-v8a armeabi-v7a x86 x86_64`, run the Android build per `scripts/build-android/` with `ENABLE_ENCRYPTION=OFF` and the project NDK (find it under the Android SDK `ndk/` matching the app's `ndkVersion`/`compileSdk`). Produce `libsrt.so` per ABI.
- [ ] **Step 2:** Copy headers (`srt/srt.h`, `srt/*.h`, `version.h`) into `cpp/third_party/srt/include/` and each `libsrt.so` into `cpp/third_party/srt/libs/<abi>/`.
- [ ] **Step 3:** In `core/CMakeLists.txt`, add the include dir and an imported target per ABI:

```cmake
add_library(srt SHARED IMPORTED)
set_target_properties(srt PROPERTIES IMPORTED_LOCATION
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/srt/libs/${ANDROID_ABI}/libsrt.so)
target_include_directories(plohoystream_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/third_party/srt/include)
# link srt into the JNI lib (native-lib) target; ensure libsrt.so is packaged via jniLibs or imported SHARED.
```
Ensure `libsrt.so` is packaged into the APK (imported SHARED libs are included; verify after assembleDebug).

- [ ] **Step 4:** Verify: `./gradlew assembleDebug` builds and packages `libsrt.so` for all ABIs (`unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libsrt`).
- [ ] **Step 5: Commit** `build(srt): vendor prebuilt libsrt (.so per ABI, encryption off) + CMake`

> NOTE: the host GoogleTest build does NOT link libsrt (it tests TsMuxer/Abr only). `SrtLink`/`SrtSession` (Phase 5) compile in the NDK build, not the host test build — keep them out of `core_tests`' sources, or guard SRT code so the host build excludes it.

---

## Phase 5 — SrtLink + SrtSession (NDK; device-gated)

### Task 5.1: `SrtLink` (libsrt LIVE caller)
**Files:** `cpp/core/srt_link.{h,cpp}`.
- [ ] Implement: `Startup()` (ref-counted `srt_startup`), `Connect(host, port, streamid, latencyMs)` (create socket, `srt_setsockflag` TRANSTYPE=SRTT_LIVE / LATENCY / PAYLOADSIZE=1316 / STREAMID, `srt_connect`), `Send(const uint8_t*, size)` (`srt_sendmsg2` in ≤1316 chunks), `Stats(AbrStats&)` (`srt_bstats`), `Close()`, `connected()`. No host test (needs libsrt); keep it NDK-only. Commit `feat(srt): SrtLink libsrt LIVE caller`.

### Task 5.2: `SrtSession` (egress thread, reuses MediaQueue)
**Files:** `cpp/core/srt_session.{h,cpp}` (model after `stream_session.{h,cpp}`).
- [ ] Implement the egress thread: connect `SrtLink`; on success Live; drain `MediaQueue`; per `MediaItem` → `TsMuxer.Write*` → `SrtLink.Send`; same timestamp-rebase; bytesSent/queueDepth/state with Dropped/Rejected reasons (so the Kotlin reconnect loop applies); periodic `srt_bstats` → `AbrNextBitrate` → store latest target for the JNI/Kotlin ABR callback. `Stop()` closes the link. Commit `feat(srt): SrtSession (TS mux + libsrt egress, reuses MediaQueue)`.

---

## Phase 6 — JNI + Kotlin selection + ABR encoder hook + UI

### Task 6.1: JNI + `NativeSrtStreamer`
- [ ] JNI in `native-lib.cpp` for SrtSession (create/start/state/bytesSent/queueDepth/sendVideoConfig/sendVideo/sendAudioConfig/sendAudio/stop + a `nativeSrtTargetBitrate` poll or callback). Kotlin `NativeSrtStreamer` implementing the existing `RtmpStreamer` (egress) interface. Commit `feat(srt): JNI + NativeSrtStreamer facade`.

### Task 6.2: Scheme-based streamer selection + SRT settings
- [ ] `CameraStreamEngine`/`startMedia` (in `LivePipeline`) picks `NativeSrtStreamer` when the endpoint scheme is SRT, else `NativeRtmpStreamer`. Add SRT settings to the `Settings` trait (latency, streamid, abrEnabled, abrMin/Target/Max) — auto-persist. Commit `feat(srt): select SRT egress by scheme + SRT settings`.

### Task 6.3: ABR → encoder runtime bitrate
- [ ] `VideoEncoder.setTargetBitrate(bps)` → `MediaCodec.setParameters(Bundle{ PARAMETER_KEY_VIDEO_BITRATE })`. The engine polls the session's ABR target (or receives the callback) while live and applies it (clamped, rate-limited). Small unit test for the apply/clamp logic if isolatable. Commit `feat(srt): apply adaptive bitrate to the encoder at runtime`.

### Task 6.4: UI
- [ ] Destination settings already accept a URL — ensure `srt://` is accepted; add SRT fields (latency, streamid, ABR toggle + min/target/max) to the Destination (or a new SRT) settings screen, dimmed-while-live. Commit `feat(srt): SRT destination settings UI`.

---

## Phase 7 — Verify + smoke

- [ ] **Task 7.1:** Full host C++ suite + `./gradlew testDebugUnitTest assembleDebug` green; `libsrt.so` packaged. Commit any fixes.
- [ ] **Task 7.2:** Write `docs/superpowers/SRT_SMOKE.md`: run a local SRT relay (`srt-live-transmit srt://:8890 file://con` or MediaMTX SRT, or a BELABOX `srtla_rec`+SRT); set the app destination to `srt://<host>:<port>?streamid=…`; go live; confirm the relay receives + plays (ffprobe/ffplay) the TS; throttle the link and confirm ABR drops bitrate then recovers; confirm a link wobble triggers reconnect. Mark as the user's manual acceptance gate. Commit.

---

## Notes for the implementer
- **Host-testable first:** Phases 1–3 (TsMuxer, Abr, endpoint) need NO libsrt and are the bulk of the correctness risk — do them first, fully TDD. Keep TsMuxer/Abr OUT of any libsrt include path so they build in `core_tests`.
- **TS timestamp correctness is the make-or-break** — gate it with byte asserts AND ffprobe before any networking.
- libsrt is NDK-only: guard SrtLink/SrtSession so the host GoogleTest build doesn't try to link libsrt.
- Reuse the existing `MediaQueue`/`MediaItem` and the timestamp-rebase approach; mirror `StreamSession`'s Dropped/Rejected so the existing Kotlin reconnect loop works for SRT too.
- No bonding/multi-interface here (Phase B/C).
