# M2-D: Real HEVC Negotiation Proof — Implementation Plan

**Date:** 2026-06-15
**Branch:** feat/m2d-hevc-proof
**Spec:** docs/superpowers/specs/2026-06-15-hevc-negotiation-proof-design.md

## Goal

Replace the fragile `ServerAdvertisesHevc` byte-substring scan with real AMF0 parsing of
the enhanced-RTMP `fourCcList` (v1) and `videoFourCcInfoMap` (v2) codec advertisements in
the connect `_result`. Prove correctness with host unit tests.

## Tasks

### Task 1 — Add AMF0 reader primitives + `VideoCodecAdvertised` in amf0.{h,cpp}

**File:** `app/src/main/cpp/core/amf0.h`
**File:** `app/src/main/cpp/core/amf0.cpp`

New `Amf0Reader` methods (header):
```cpp
// Enumerate key->value pairs of an object (0x03) or ECMA-array (0x08).
// For each pair, calls visitor(key, reader_at_value_marker). Visitor returns true to
// continue, false to stop. Stops at 0x00 0x00 0x09 object-end sentinel.
void ForEachProperty(std::function<bool(const std::string&, Amf0Reader&)> visitor);

// Read a strict array (0x0A), calling visitor(reader_at_value_marker) for each element.
// count is read from the 4-byte header.
void ForEachArrayElement(std::function<bool(Amf0Reader&)> visitor);
```

These need private helpers exposed (or expanded `Amf0Reader` interface):
- `size_t pos() const { return i_; }`
- `void set_pos(size_t i) { i_ = i; }`
- `uint32_t u32()` — read big-endian U32, bounds-checked.

New free function (header):
```cpp
// True if the connect _result AMF0 payload advertises the given video FourCC ("hvc1")
// in fourCcList (v1 strict array) or videoFourCcInfoMap (v2 object/ECMA) property.
bool VideoCodecAdvertised(const Bytes& resultPayload, const std::string& fourCc);
```

**Implementation strategy:**
- `ForEachProperty`: skip leading marker byte (0x03 or 0x08; for ECMA also skip 4-byte count),
  then loop: read U16 key length + key bytes; if key-length == 0 stop (object-end, skip 0x09);
  call visitor. Visitor can call SkipValue() itself to advance past the value.
- `ForEachArrayElement`: expect/consume 0x0A marker, read U32 count, call visitor count times.
- `VideoCodecAdvertised`: construct Amf0Reader on payload; skip "_result" string and txn number
  (using ReadString/ReadNumber); then loop: for each remaining value (via SkipValue with peeking
  logic), when it's an object (0x03) or ECMA-array (0x08):
  - ForEachProperty: if key=="fourCcList" and value marker==0x0A: ForEachArrayElement checking
    each string entry equals fourCc.
  - ForEachProperty: if key=="videoFourCcInfoMap" and value marker==0x03 or 0x08: ForEachProperty
    again, check each KEY equals fourCc.

### Task 2 — Replace `ServerAdvertisesHevc` call in rtmp_client.cpp

**File:** `app/src/main/cpp/core/rtmp_client.cpp`

Changes:
1. DELETE lines 7-13 (`static bool ServerAdvertisesHevc(...) { ... }`).
2. In `OnBytes`, line 108: replace `ServerAdvertisesHevc(payload)` with
   `VideoCodecAdvertised(payload, "hvc1")`.
   No other change to the `negotiatedCodec_` decision logic.

### Task 3 — Strengthen test_codec_negotiation.cpp

**File:** `app/src/main/cpp/test/test_codec_negotiation.cpp`

Replace `MakeConnectResult(bool serverHevc)` with three factories:

```cpp
// v1: fourCcList as strict array of strings
static Bytes MakeConnectResultV1(bool hevc) { ... }

// v2: videoFourCcInfoMap as ECMA-array keyed by FourCC (value = empty object)
static Bytes MakeConnectResultV2(bool hevc) { ... }

// legacy: no codec advertisement field at all
static Bytes MakeConnectResultLegacy() { ... }

// false-positive guard: "hvc1" appears in code/description string but NOT in fourCcList
static Bytes MakeConnectResultFalsePositive() { ... }
```

Tests:
- `TEST(Connect, AdvertisesHevcFourCc)` — keep as-is (tests BuildConnectCommand outbound)
- `TEST(Connect, AvcRequestOmitsFourCc)` — keep as-is
- `TEST(Connect, NegotiatesHevcWhenServerAdvertisesV1)` — feeds MakeConnectResultV1(true)
- `TEST(Connect, NegotiatesHevcWhenServerAdvertisesV2)` — feeds MakeConnectResultV2(true)
- `TEST(Connect, FallsBackToAvcLegacyServer)` — feeds MakeConnectResultLegacy()
- `TEST(Connect, FallsBackToAvcWhenServerLacksHevc)` — feeds MakeConnectResultV1(false)
- `TEST(Connect, FalsePositiveGuard_SubstringInStringFieldNotCodecList)` — feeds
  MakeConnectResultFalsePositive(), expects Avc (this FAILS against old substring code)
- `TEST(VideoCodecAdvertised, V1TrueForHvc1)` — unit test VideoCodecAdvertised directly
- `TEST(VideoCodecAdvertised, V2TrueForHvc1)` — unit test VideoCodecAdvertised directly
- `TEST(VideoCodecAdvertised, LegacyFalse)` — unit test VideoCodecAdvertised directly
- `TEST(VideoCodecAdvertised, FalsePositiveGuard)` — unit test VideoCodecAdvertised directly

### Task 4 — Add AMF0 primitive tests in test_amf0.cpp

**File:** `app/src/main/cpp/test/test_amf0.cpp`

New tests:
- `TEST(Amf0Decode, StrictArrayOfStrings)` — encode with StrictArrayBegin(2)+String+"hvc1"+
  String+"avc1", then use ForEachArrayElement to read strings; expect ["hvc1","avc1"].
- `TEST(Amf0Decode, ObjectPropertyEnumeration)` — build object {level:"status", code:"ok"},
  ForEachProperty; expect both key-value pairs visited in order.
- `TEST(Amf0Decode, EcmaArrayPropertyEnumeration)` — build ECMA-array {hvc1: null, avc1: null},
  ForEachProperty; expect both keys visited.
- `TEST(Amf0Decode, ForEachPropertyTruncatedSafe)` — truncated object input; ForEachProperty
  should not crash or overread.
- `TEST(Amf0Decode, ForEachArrayElementTruncatedSafe)` — truncated strict array; no crash.

### Task 5 — Write M2D_SRS_SMOKE.md

**File:** `docs/superpowers/M2D_SRS_SMOKE.md`

Document the live smoke recipe per spec (Docker SRS, adb reverse, MediaMTX AVC fallback).
Mark as user's manual acceptance step.

## Build & test command

```
cd app/src/main/cpp && cmake -S test -B build-test >/dev/null && cmake --build build-test 2>&1 | tail -8 && ctest --test-dir build-test --output-on-failure 2>&1 | tail -15
```

## Commit sequence

1. `feat(m2d): add AMF0 object/array enumeration + VideoCodecAdvertised` (amf0.h + amf0.cpp)
2. `feat(m2d): replace ServerAdvertisesHevc with VideoCodecAdvertised` (rtmp_client.cpp)
3. `test(m2d): strengthen codec negotiation tests (v1/v2/legacy/false-positive)` (test_codec_negotiation.cpp)
4. `test(m2d): add AMF0 primitive unit tests` (test_amf0.cpp)
5. `docs(m2d): SRS live smoke recipe` (M2D_SRS_SMOKE.md)
