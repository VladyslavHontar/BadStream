# Background Streaming — On-Device Smoke Test

**Branch:** feat/background-streaming
**Date:** 2026-06-15
**Status:** Manual acceptance gate — run this on a physical device before merging.

---

## Prerequisites

- A physical Android device (camera + microphone permissions granted to the app).
- A local RTMP receiver running, e.g. MediaMTX or `ffmpeg -listen 1 -i rtmp://localhost/live/test`.
- USB debugging enabled; run `adb reverse tcp:1935 tcp:1935` so the device can reach the listener.
  (Alternatively, use Twitch or another public RTMP endpoint for a real-network test.)

---

## Test 1 — Background while streaming (core gate)

1. Launch the app. Confirm the camera preview is visible.
2. Enter your RTMP URL and tap **Go Live**. Confirm the stream starts (timer ticking, stats updating).
3. Press the **Home** button (or use the app-switcher to switch to another app).
4. On the receiver, observe the stream for **60+ seconds** — it must continue with no stall, freeze, or disconnect. Audio and video must keep flowing.
5. Return to the app.
6. Confirm: the preview reattaches cleanly, the elapsed timer reflects the real live duration, stats resume updating, no crash.

**Pass criteria:** stream is continuous (no gap > 1–2 frames at foreground/background transitions is acceptable) and the app is stable on return.

---

## Test 2 — Activity recreation mid-stream (rotation / system kill)

1. While live (from Test 1 or a fresh go-live), rotate the device to trigger an Activity recreation.
2. Confirm: the stream does **not** stop. On the receiver, at most a brief blip (one session reconfigure) is acceptable.
3. Confirm the UI reconnects: the Live indicator is shown, the timer continues from where it was, stats resume.
4. Rotate back; confirm again.

**Pass criteria:** stream survives rotation; UI reconnects without a crash.

---

## Test 3 — Stop from app

1. While live, tap **Stop** in the app.
2. Confirm: the stream ends cleanly on the receiver. The foreground-service notification clears. The camera returns to idle preview.

**Pass criteria:** clean shutdown; no ANR; notification gone.

---

## Test 4 — Idle backgrounding (not streaming)

1. Do not go live. Press Home.
2. Return to the app. Confirm: preview reattaches, camera is working, no crash.

**Pass criteria:** idle background/foreground works normally.

---

## Notes

- A brief camera session reconfigure blink at background/foreground transition is accepted (one
  session rebuild per transition; the encoder surface is persistent so egress is continuous).
- Process kill (swipe from recents) ends the stream — this is expected and acceptable (matches
  Moblin behavior). The foreground service makes this unlikely while backgrounded.
- No Stop action in the notification (out of scope for this milestone).
