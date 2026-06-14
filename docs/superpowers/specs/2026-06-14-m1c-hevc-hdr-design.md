# M1-C — HEVC + HDR Design

**Status:** Approved (brainstormed 2026-06-14). Builds on M1-B.3 (working H.264/AAC RTMP egress, real-device verified).

**Goal:** Add HEVC (H.265) encode and full HDR (HLG10 / Main10) capture+encode, sent over enhanced-RTMP, with the best codec auto-selected and a simple HDR toggle. Auto-fallback to H.264/SDR.

## Scope decisions (from brainstorming)

- **HEVC + full HDR** in this milestone (10-bit Main10 + HLG/BT2020 capture + HEVC-over-enhanced-RTMP egress). Accepted caveat: Twitch/RTMP HDR *display* support is poor, so true HDR display likely needs SRT/own-server (M3); M1-C proves correct HDR capture→encode→egress.
- **Control:** codec auto-best (HEVC if device+server support, else H.264); HDR a single on/off toggle, offered only when the device supports HLG10 capture + HEVC Main10 encode; defaults OFF. Minimal UI (polished controls are the next milestone).
- **Codec seam:** strategy pattern in the C++ FLV layer (approach A). NOT a full MPEG-TS/SRT muxer (that's M3).
- **Codec negotiation:** negotiate the codec at the enhanced-RTMP connect handshake **before** creating the encoder (no optimistic-then-restart).

## Architecture & data flow

```
HDR toggle ─▶ CodecSelector ─▶ requested VideoFormat ─▶ native.start(requestedCodec)
native: connect (advertise fourCcList) ─▶ settle codec at _result ─▶ publish ─▶ Live
engine poll-to-Live ─▶ read negotiated codec ─▶ build VideoEncoder + set camera dynamic range
camera (HLG10 or SDR) ─▶ encoder input surface ─▶ NALUs ─▶ JNI ─▶ Avc/HevcCodec builds
  FLV/enhanced-RTMP tags ─▶ RtmpClient ─▶ egress
```

## Component 1 — C++ codec seam (FLV layer)

`VideoCodec` strategy producing the full FLV video-message payload:

```cpp
class VideoCodec {
public:
    virtual ~VideoCodec() = default;
    virtual Bytes SequenceHeader(const Bytes& csd) = 0;                  // from encoder CSD
    virtual Bytes Frame(const Bytes& annexb, bool key, uint32_t cts) = 0;
    virtual const char* FourCc() const = 0;                             // "avc1" / "hvc1"
};
```

- **`AvcCodec`** — today's legacy FLV tags (`0x17/0x27`, `0x00/0x01`, `avcC`). Pure refactor of the existing, tested `FlvVideoSeqHeader`/`FlvVideoFrame`/`BuildAvcC` path; byte-for-byte identical output (regression guard).
- **`HevcCodec`** — enhanced-RTMP "ex-video" tags:
  - SequenceStart: `byte0 = IsExHeader(0x80) | FrameType(key=0x10) | PacketType(SequenceStart=0)` then `"hvc1"` (4 bytes) then `hvcC`.
  - CodedFrames: `byte0 = 0x80 | FrameType(key 0x10 / inter 0x20) | PacketType(CodedFrames=1)` then `"hvc1"` then `[3-byte cts][length-prefixed NALUs]`.
- New `flv` helpers: `SplitHevcParams(csd, vps, sps, pps)` (HEVC NAL type = `(nal[0] >> 1) & 0x3F`: VPS=32, SPS=33, PPS=34) and `BuildHvcC(vps, sps, pps)`.
- `RtmpClient` holds the active `VideoCodec` and dispatches `SendVideoConfig`/`SendVideo` through it.
- **Enhanced connect** advertises `fourCcList` (e.g. `["hvc1","avc1"]`). On the connect `_result`, the FSM determines server HEVC support; the active codec is the intersection of requested + supported. The negotiated codec is exposed to the JNI/engine layer (e.g. via a query on `RtmpClient`/`StreamSession`).

> Exact enhanced-RTMP signaling (the `_result` capability fields / `fourCcList` placement) is pinned against the Veovera enhanced-RTMP spec + Twitch during implementation; the negotiated-codec *behavior* is fixed.

## Component 2 — Capability detection & selection (Kotlin)

- **`CodecCapabilities`** — query `MediaCodecList` for a `video/hevc` encoder and whether it supports `HEVCProfileMain` and `HEVCProfileMain10`.
- **`CameraEnumerator`** — read `REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES` (API 33+) per camera; record HLG10 support on `CameraInfo`/`CameraConfig` (`supportsHdr`).
- **`CodecSelector`** (pure, TDD):
  ```
  inputs: hevcEncoder?, hevcMain10?, cameraHdr?, hdrToggleOn
  → HDR on + hevcMain10 + cameraHdr → HEVC / Main10 / HLG10
  → else hevcEncoder                → HEVC / Main   / SDR
  → else                            → AVC  / —      / SDR
  output VideoFormat { codec, profile, dynamicRange, mime, bitrate }
  ```
  The HDR toggle is only offered when `hevcMain10 && cameraHdr`.

## Component 3 — HDR capture + HEVC/Main10 encode (Kotlin)

- **`VideoEncoder`** parameterized by `VideoFormat`:
  - HEVC: `createEncoderByType("video/hevc")`, `KEY_PROFILE = HEVCProfileMain`/`…Main10`.
  - HDR adds `KEY_COLOR_STANDARD = BT2020`, `KEY_COLOR_TRANSFER = HLG`, `KEY_COLOR_RANGE`. Input stays `COLOR_FormatSurface` (camera feeds 10-bit through the surface).
  - HEVC codec-config output = VPS+SPS+PPS Annex-B → crosses JNI as the CSD blob → native `BuildHvcC`.
- **`Camera2Controller`** — when HDR, set the encoder `OutputConfiguration`'s dynamic-range profile to HLG10 and configure the session for HDR. **Known limitation:** a Camera2 session shares dynamic range, so the preview surface also receives HLG10; on an SDR `TextureView` the preview may look washed-out/over-bright while streaming HDR. A true HDR *preview* is deferred to the UI milestone; M1-C verifies HDR via the recorded stream, not the on-screen preview.

## Component 4 — Wiring & fallback (negotiate-before-encoder)

- `engine.start(config)`: compute requested `VideoFormat` (CodecSelector), `native.start(requestedCodec)`. State → Connecting. No encoder yet.
- Native connects + negotiates the codec at `_result`, proceeds to Publishing, exposing the negotiated codec.
- The engine's poll-to-Live loop, on Live: read the negotiated codec, recompute the real `VideoFormat` (e.g. server lacked HEVC → AVC/SDR; HDR off), then build `VideoEncoder` + set camera dynamic range + start audio + publish the encoder surface. (Removes the current pre-Publishing frame pre-buffering.)
- **Fallback, both automatic:** (1) device-side — if the HEVC/Main10 encoder can't be created/configured, the selector drops a tier before going live; (2) server-side — handled by the negotiation above (no restart).
- **JNI:** `nativeCreate` gains a `codec` param; native exposes the negotiated codec (new query or via state). `nativeSendVideoConfig` CSD is split per the active codec (avcC source vs VPS/SPS/PPS).
- **HDR toggle:** a minimal Compose switch in the viewfinder, shown only when supported, wired into `StreamConfig`/engine.

## Testing

- **C++ host (GoogleTest):** `HevcCodec` SequenceHeader/Frame bytes (ex-video header + `hvc1` + `hvcC` / length-prefixed NALUs + cts); `SplitHevcParams`/`BuildHvcC`; `AvcCodec` regression (identical to current output); enhanced-connect `fourCcList`; FSM codec selection from `_result` with/without HEVC support (StubTransport).
- **Kotlin unit:** `CodecSelector` (all tiers + fallback, TDD); `CodecCapabilities`/HDR-detection pure parts.
- **Device (Seeker):** (1) HEVC-SDR → ffmpeg, ffprobe shows `hevc (Main)`; (2) HDR on → ffprobe shows `hevc (Main 10)` + `bt2020`/`arib-std-b67 (HLG)`; (3) AVC fallback against the HEVC-unaware ffmpeg server → streams H.264; (4) Twitch enhanced-broadcasting (user).

## Deferred (not in M1-C)

- MPEG-TS / SRT muxer and HDR-over-SRT (M3).
- True HDR on-screen preview (UI milestone / later).
- Polished codec/HDR settings UI (next milestone).
- Mid-stream codec re-negotiation / reconnect (M2).
- Tight A/V sync (M2).

## Known limitations carried in

- HDR preview may look wrong while HDR-streaming (shared session dynamic range).
- HDR display on Twitch/RTMP is unreliable; correct egress is verified via the recorded stream / ffprobe, with real HDR display expected via SRT (M3).
