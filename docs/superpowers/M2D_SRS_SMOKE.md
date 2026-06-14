# M2-D Live Smoke Test: SRS HEVC Negotiation + MediaMTX AVC Fallback

**Status:** Manual acceptance step — to be run by the developer on a real device or emulator.

## Purpose

Verify that `VideoCodecAdvertised` correctly detects HEVC support in a real server's connect
`_result`, and that `negotiatedCodec()` surfaces `Hevc` after a live connect. Also verify AVC
fallback when the server does not advertise HEVC (MediaMTX).

---

## Prerequisites

- Docker installed and running.
- Android device connected via USB (or emulator — note: emulator camera may not support HEVC
  encode; use a physical device for full end-to-end validation).
- `adb` in PATH.
- PlohoyStream APK installed with HEVC requested (default in debug build).

---

## Scenario A: SRS (HEVC advertised — expect `negotiatedCodec = HEVC`)

SRS 5+ sends `fourCcList: ["hvc1", "avc1"]` in the connect `_result` when the client requests
enhanced-RTMP codecs.

### Steps

1. **Start SRS:**
   ```bash
   docker run --rm -p 1935:1935 ossrs/srs:5
   ```
   Wait for the log line: `server started, listen at 1935`.

2. **Tunnel the port to the device:**
   ```bash
   adb reverse tcp:1935 tcp:1935
   ```
   This maps `127.0.0.1:1935` on the device to port 1935 on your host.

3. **Configure the app:**
   - RTMP URL: `rtmp://127.0.0.1/live`
   - Stream key: `test` (or any key)
   - Codec: HEVC (ensure HEVC is requested in settings)

4. **Go live.**

5. **Verify negotiation:**
   - In logcat: filter tag `PlohoyStream` or `RtmpClient`.
   - Expect log line: `negotiatedCodec=HEVC` (or equivalent).
   - If no such tag exists yet, inspect the `negotiatedCodec()` accessor via a UI label or
     add a temporary log line in `stream_session.cpp` after `Begin()`.

6. **Verify stream plays:**
   ```bash
   ffplay rtmp://127.0.0.1/live/test
   ```
   Expect HEVC (H.265) video. ffplay will print the codec in its title bar.

### Expected `_result` structure from SRS 5

```
_result (txn=1)
  null
  {object}
    fmsVer: "FMS/3,5,3,824"
    capabilities: 127
    mode: 1
    fourCcList: [strict array]
      "hvc1"
      "avc1"
    ...
```

### Expected outcome

```
negotiatedCodec() == Codec::Hevc
```

---

## Scenario B: MediaMTX (no HEVC advertisement — expect `negotiatedCodec = AVC`)

MediaMTX does not send `fourCcList` or `videoFourCcInfoMap` in its connect `_result` — it is a
legacy-style server for this check.

### Steps

1. **Download and start MediaMTX:**
   ```bash
   # Download from https://github.com/bluenviron/mediamtx/releases
   ./mediamtx
   ```
   Default RTMP port: 1935.

2. **Tunnel:**
   ```bash
   adb reverse tcp:1935 tcp:1935
   ```

3. **Configure the app:**
   - RTMP URL: `rtmp://127.0.0.1/live` (or `rtmp://127.0.0.1/mystream`)
   - Stream key: `test`
   - Codec: HEVC requested

4. **Go live.**

5. **Verify negotiation:**
   - Expect log: `negotiatedCodec=AVC` (fallback because no fourCcList in the _result).

6. **Verify stream plays:**
   ```bash
   ffplay rtmp://127.0.0.1/live/test
   ```
   Expect H.264 / AVC video.

### Expected outcome

```
negotiatedCodec() == Codec::Avc
```

---

## Acceptance criteria

| Check | Expected |
|---|---|
| SRS connect `_result` contains `fourCcList` | Yes (inspect raw logcat or Wireshark) |
| `VideoCodecAdvertised(payload, "hvc1")` returns true (SRS) | Yes |
| `negotiatedCodec()` after SRS connect | `Hevc` |
| Stream plays on SRS | Yes, H.265 |
| `VideoCodecAdvertised(payload, "hvc1")` returns false (MediaMTX) | Yes |
| `negotiatedCodec()` after MediaMTX connect | `Avc` |
| Stream plays on MediaMTX | Yes, H.264 |

---

## Notes

- The emulator's camera does not support HEVC encode on most AVD images; use a physical
  Pixel 6+ for end-to-end HEVC encode + publish.
- If the device doesn't support HEVC encode, the codec falls back silently to AVC in
  `stream_session.cpp`; this smoke test focuses on the *negotiation* layer, not encode.
- SRS `docker run` image `ossrs/srs:5` supports enhanced-RTMP out of the box; no extra
  config file is needed for fourCcList advertisement.
- MediaMTX version 1.x does not advertise fourCcList; this makes it a reliable AVC-fallback
  sanity server.

---

*This document is the M2-D manual acceptance step. Automated host unit tests cover the AMF0
parse correctness (including the false-positive guard). This recipe covers the live wire.*
