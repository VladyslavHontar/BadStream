# M1-C — HEVC + HDR device verification (Task 14)

**Device:** Solana Seeker (`SM02E4060314107`, MediaTek), screen 1200×2670, USB-attached.
**Date:** 2026-06-14. **Branch:** `feat/m1c-hevc-hdr`.
**Host:** ffmpeg/ffprobe 8.1 (`/opt/homebrew/bin`).

## Device capability probe

- **HEVC encoder present:** `c2.mtk.hevc.encoder` (`video/hevc`), max 3840×2160, VBR/CBR/CQ. So `CodecCapabilities.hevc().encoder == true` and the app **requests HEVC by default** (auto-best codec).
- **Camera HLG10 NOT exposed:** `dumpsys media.camera` reports `Dynamic Range Profile: 0x1` (STANDARD only; HLG10 would be `0x2`). The standard Camera2 `REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES` does not advertise HLG10 (the MediaTek camera exposes only its proprietary vendor HDR — vhdr/hdr10p photo modes — not the AOSP HLG10 profile used by `OutputConfiguration.dynamicRangeProfile`).
  → `supportsHdr == false`, `hdrAvailable == false`.

## Results

| # | Check | Result |
|---|-------|--------|
| 1 | App launches after permission grant | ✅ No crash (logcat clean) |
| 2 | HDR toggle visibility | ✅ Correctly **absent** — UI hierarchy dump shows no HDR `Switch` (only zoom chips, RTMP URL/key fields, Go Live). Matches `hdrAvailable == false`. |
| 3 | Bad-URL validation | ✅ Percent-encoded URL rejected: "Error: Only rtmp:// URLs are supported" |
| 4 | **AVC fallback** (request HEVC → vanilla server) | ✅ See below |
| 5 | HEVC over the wire | ⏳ Requires enhanced-RTMP server (user / Twitch) — see "Not verifiable on Seeker" |
| 6 | HLG10 HDR capture | ⛔ Not possible on Seeker (camera lacks HLG10 via the standard API) |

### Check 4 — AVC fallback (the key M1-C on-device proof)

Recipe: `adb reverse tcp:1935 tcp:1935`; host `ffmpeg -y -listen 1 -i rtmp://0.0.0.0:1935/app/test -c copy /tmp/m1c_fallback.flv`; app URL `rtmp://127.0.0.1/app`, key `test`, Go Live.

The app reached **● LIVE**; ffmpeg ingested ~10 MB with no demux errors (the single trailing "Error during demuxing" is the abrupt disconnect on Stop — the file probes cleanly).

`ffprobe /tmp/m1c_fallback.flv`:
```
video: h264 (High) 1920x1080 yuv420p  bt709/bt709
audio: aac (LC)
```

This proves, on real hardware:
- The **enhanced connect** (connect command carrying `fourCcList ["hvc1","avc1"]`) does **not** break a vanilla (non-enhanced) RTMP server — it connects normally.
- The app requested HEVC, the server's `_result` lacked `hvc1`, so `ServerAdvertisesHevc` returned false and the codec **negotiated down to AVC**.
- The **negotiate-before-encoder** engine then built an H.264 encoder at Live and streamed clean 1080p30 H.264 High + AAC — byte-compatible with the M1-B.3 baseline.

## Not verifiable on the Seeker (hand-off to the user / future device)

- **HEVC over the wire (`hevc (Main)`):** needs a server that advertises HEVC in its connect `_result` — i.e. **Twitch enhanced broadcasting** or a local enhanced-RTMP server (SRS / nginx-rtmp-enhanced). `ffmpeg -listen` is a vanilla server and always triggers the AVC fallback above. When testing against Twitch, also **pin `ServerAdvertisesHevc`** against the real `_result` capture (currently a raw `"hvc1"` byte-scan; tighten to a proper AMF0 walk if Twitch's reply structure requires it).
- **HLG10 / Main10 HDR (`hevc (Main 10)` + `arib-std-b67`/`bt2020`):** the Seeker camera does not expose HLG10 via the standard Camera2 API, so the HDR toggle never appears and HDR capture can't be exercised here. The HDR **encode** path (Main10 + BT2020/HLG/full-range keys, hvcC) is covered by the C++ host unit tests and `VideoEncoder` config; on-device HDR capture needs a phone whose camera advertises `DynamicRangeProfiles.HLG10` (e.g. recent Pixel / Galaxy flagships).

## Known limitations carried forward (documented, not bugs)

- `cameraHdr` uses a device-global `any { supportsHdr }`; on a device where only one camera supports HLG10, requesting HDR on the other camera would mismatch. Not triggerable on the Seeker (no camera HLG10). Refine to per-active-camera in a later milestone.
- Runtime HEVC/Main10 **encoder-configure failure** (capability advertised but `configure()` throws) is not caught for a clean tier-drop — a proper fix needs mid-stream re-negotiation, deferred to **M2**. Not reachable on the Seeker (HDR never requested).
- HDR preview shares the session dynamic range (washed-out preview while HDR-streaming); true HDR preview deferred to the UI milestone.
