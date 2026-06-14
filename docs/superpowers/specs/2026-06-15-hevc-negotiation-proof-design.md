# Real HEVC-over-the-wire Negotiation Proof — Design (M2-D)

**Date:** 2026-06-15
**Milestone:** M2 "Usable streaming" — sub-project D
**Status:** Approved (ready for implementation plan)

## Goal

Replace the hardcoded/fragile `ServerAdvertisesHevc` heuristic with a real parse of the
server's enhanced-RTMP codec advertisement, and prove HEVC negotiation end-to-end against a
real server.

## Problem

`rtmp_client.cpp`'s `ServerAdvertisesHevc(payload)` is a raw byte-substring scan:
```cpp
std::string s(payload.begin(), payload.end());
return s.find("hvc1") != std::string::npos;
```
This is not AMF0 parsing — it would false-positive on `"hvc1"` appearing anywhere in the
connect `_result` (an echoed field, an unrelated string), and it doesn't actually read the
codec-advertisement structures. The negotiation *decision* is otherwise correct
(`negotiatedCodec_ = (requested == Hevc && serverHevc) ? Hevc : Avc`), but it's gated on this
unreliable signal. We've never proven the path against a server that returns a genuine
advertisement.

## Enhanced-RTMP facts (Veovera)

In the `connect` `_result`, a server advertises the video codecs it supports via either:
- **v1:** `fourCcList` — an AMF0 **strict array** (`0x0A`) of 4-byte FourCC strings, e.g.
  `["hvc1","avc1"]`.
- **v2:** `videoFourCcInfoMap` — an AMF0 **object/ECMA-array** keyed by FourCC (`{"hvc1": <caps>,
  "avc1": <caps>}`).

HEVC is FourCC **`"hvc1"`**. A spec-correct client checks the advertised video set (from either
shape) for `"hvc1"`; absence of both ⇒ legacy server ⇒ AVC.

## Decisions (locked during brainstorm)

- Replace the substring scan with a **real AMF0 walk** of the connect `_result`, reading
  `fourCcList` (v1) and `videoFourCcInfoMap` keys (v2).
- Keep the existing `negotiatedCodec_` decision unchanged.
- **Verify both ways:** host unit tests with captured `_result` blobs **and** a live smoke
  against local **SRS** (Docker), with **MediaMTX** as the AVC-fallback sanity check.

## Design

### AMF0 advertisement parser
Add a focused helper (in `amf0.{h,cpp}`) used by `RtmpClient`:

```cpp
// True if the connect _result advertises the given video FourCC ("hvc1") in either the v1
// fourCcList (strict array of strings) or the v2 videoFourCcInfoMap (object/ECMA keyed by
// FourCC). Walks the AMF0 command values (skipping name/txn) and descends into objects.
bool VideoCodecAdvertised(const Bytes& resultPayload, const std::string& fourCc);
```

This needs AMF0 reader primitives the current `Amf0Reader` lacks:
- enumerate **object / ECMA-array** properties (key → value), and
- read a **strict array** (`0x0A`) of values.

Add these to `Amf0Reader` (or as standalone helpers), reusing the existing bounds-checked
decode style and the existing `SkipValue` for descending/skipping non-matching values. The
parser descends into each top-level object value of the command; when it finds a property named
`fourCcList` (strict array) it checks each string entry, and when it finds `videoFourCcInfoMap`
(object/ECMA) it checks each property **key**, for an exact `fourCc` match.

`RtmpClient::OnBytes`'s connect-`_result` branch calls
`VideoCodecAdvertised(payload, "hvc1")` in place of `ServerAdvertisesHevc`; the
`negotiatedCodec_` line is unchanged. `ServerAdvertisesHevc` is removed.

## Components

| File | Change |
|---|---|
| `cpp/core/amf0.{h,cpp}` | Add object/ECMA-array key enumeration + strict-array reading; add `VideoCodecAdvertised(payload, fourCc)`. |
| `cpp/core/rtmp_client.cpp` | Replace `ServerAdvertisesHevc` call with `VideoCodecAdvertised(payload, "hvc1")`; delete the old substring function. |
| `cpp/test/test_codec_negotiation.cpp` | Strengthen: real v1/v2/legacy fixtures + a false-positive guard. |
| `cpp/test/test_amf0.cpp` | Add unit tests for the new AMF0 enumeration/strict-array primitives. |
| `docs/superpowers/M2D_SRS_SMOKE.md` | NEW: the live-smoke recipe + results. |

## Testing

**Host unit tests** (the core deliverable):
- `_result` carrying v1 `fourCcList: ["hvc1","avc1"]` → `VideoCodecAdvertised(payload,"hvc1")`
  true → with `requested==Hevc`, negotiates HEVC.
- `_result` carrying v2 `videoFourCcInfoMap: {"hvc1": {...}, "avc1": {...}}` → true → HEVC.
- legacy `_result` (no enhanced fields) → false → AVC fallback.
- **False-positive guard:** a `_result` where `"hvc1"` appears as a substring in an unrelated
  string field (e.g. a `code` or `description`) but NOT in the codec list → **false** → AVC.
  (This is the case the old substring scan got wrong.)
- AMF0 primitive tests: strict-array read, object/ECMA key enumeration, bounds safety on
  truncated input.

**Live smoke** (`docs/superpowers/M2D_SRS_SMOKE.md`):
- Run **SRS** locally (`docker run --rm -p 1935:1935 ossrs/srs:5`), `adb reverse tcp:1935
  tcp:1935`, set the app URL to `rtmp://127.0.0.1/live` + a key, force/allow HEVC, go live, and
  confirm `negotiatedCodec()` surfaces HEVC and the stream plays. Use **MediaMTX**
  (`mediamtx`) as the AVC-fallback sanity check (no HEVC advertised → AVC). Document the
  observed `_result` and the negotiated codec.

## Out of scope
v2 `capsEx` bitmask handling; `audioFourCcList`/audio negotiation; AV1/VP9 FourCCs; any change
to how the client *requests* codecs (the outbound `fourCcList` send is already correct).
