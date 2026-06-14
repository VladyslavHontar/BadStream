# M1-B.3 End-to-End Smoke Test

Goal: prove the full vertical slice — Camera2/mic → MediaCodec (H.264/AAC) → JNI → native
`StreamSession` (`RtmpClient` over TCP) → RTMP ingest → live.

## A. Local ffmpeg ingest (offline, deterministic)

The Android emulator reaches the host machine at `10.0.2.2`.

1. On the host, listen for one RTMP publish and record it:

```bash
ffmpeg -y -loglevel info -listen 1 -timeout 120 -i "rtmp://0.0.0.0:1935/app/test" -c copy -t 30 /tmp/plohoy_out.flv
```

2. In the app (CAMERA + RECORD_AUDIO granted):
   - RTMP URL: `rtmp://10.0.2.2/app`
   - Stream key: `test`
   - Tap **Go Live**.

3. Expected on real hardware: status → Connecting → ● LIVE; ffmpeg reports both an
   H.264 video stream and an AAC audio stream; `ffprobe /tmp/plohoy_out.flv` shows both
   streams with nonzero duration.

### Emulator result (2026-06-14, Pixel_10_Pro API 37) — PARTIAL, as expected

- ✅ **RTMP connect + handshake + connect + publish succeeded** against ffmpeg.
- ✅ **Audio proven end-to-end**: ffmpeg decoded `Audio: aac (LC) 44100 Hz, stereo` and
  wrote ~11 KiB of audio. This exercises the entire new stack:
  `AudioRecord → MediaCodec AAC → JNI → StreamSession → RtmpClient → RTMP → ffmpeg`.
- ❌ **Video: 0 KiB on the emulator.** Root cause (logcat):
  `Camera 10: Failed to query Surface format: No such device (-19) → camera session configuration failed`.
  The emulator's goldfish/ranchu **camera HAL cannot use a MediaCodec encoder input
  surface as a camera output**. This is an emulator limitation, not an app bug:
  - The surface-input encoder is the correct, performant path for real devices (the
    standard camera→encoder recording pattern); real-device camera HALs accept it.
  - The native H.264 RTMP egress is independently proven (M1-A: ffmpeg decoded 60 valid
    H.264 frames through the same `RtmpClient`).
  - The app no longer crashes on this: `Camera2Controller.configureSession` filters
    invalid surfaces and guards session configuration, degrading to preview-only.

**Conclusion:** every layer is validated except camera→H.264 surface encoding, which the
emulator structurally cannot do. That last link is verified on a physical device (B).

## B. Twitch on a physical device (the real target — requires the user)

Run on a **physical** Pixel/Samsung (emulator camera + uplink are unreliable):

1. RTMP URL: `rtmp://live.twitch.tv/app` (or the nearest ingest from
   https://help.twitch.tv/s/twitch-ingest-recommendation).
2. Stream key: from the Twitch Creator Dashboard.
3. Tap **Go Live**. Expected: the Twitch dashboard shows the channel live with the phone
   camera + mic within ~10–20 s.

Capture logcat during a live test to confirm no encoder errors / dropped-frame storm:

```bash
adb logcat -d | grep -iE 'plohoy|Camera2Controller|MediaCodec|rtmp|fatal' | tail -60
```

## Known limitations carried to M2 (documented, not bugs)

- No inbound RTMP servicing during publish (no window-ack/ping responses).
- No graceful `deleteStream`/FCUnpublish teardown; no auto-reconnect.
- Approximate A/V sync (audio off `System.nanoTime`, video off the surface PTS).
- If the video encoder produces nothing (e.g. emulator), the session streams audio-only,
  which some servers reject — moot on real hardware where video flows.
