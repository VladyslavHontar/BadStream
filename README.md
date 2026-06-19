<div align="center">

# PlohoyStream

**A live-streaming studio in your pocket — Android, camera to RTMP/SRT, built from the socket up.**

<!-- Hero / app icon. Drop a landscape screenshot of the viewfinder here. -->
<p>
  <img src="docs/images/hero.png" alt="PlohoyStream viewfinder" width="720">
</p>

<!-- Badges — swap in real ones once CI / release tags exist -->
![Platform](https://img.shields.io/badge/platform-Android%2015%2B-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Kotlin%20%2B%20C%2B%2B-7F52FF?logo=kotlin&logoColor=white)
![UI](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4)
![Transport](https://img.shields.io/badge/RTMP%20%C2%B7%20RTMPS%20%C2%B7%20SRT-FF6F00)

</div>

---

## What it is

PlohoyStream turns an Android phone into a self-contained live-streaming camera. Point it at a
scene, pick a destination, and go live to any RTMP, RTMPS, or SRT ingest — Twitch, YouTube, a
private OBS/SRT relay, whatever speaks the protocol. The networking stack (RTMP handshake, FLV
muxing, MPEG-TS, SRT) is written from scratch in native C/C++ rather than wrapped around a
black-box library, so the streaming path is fully under the app's control.

It draws inspiration from the [Moblin](https://github.com/eerimoq/moblin) project on iOS, and aims
to bring the same "real broadcast tool, not a toy" feel to Android.

> **Project status:** active development. Currently validated on a single reference device; the
> camera and capability logic are written to be phone-agnostic, but the device matrix is still small.

<!-- A second screenshot — e.g. the settings panel or the go-live state — fits well here. -->
<p align="center">
  <img src="docs/images/settings.png" alt="Settings panel" width="360">
  &nbsp;&nbsp;
  <img src="docs/images/live.png" alt="Live state" width="360">
</p>

---

## Features

### Streaming
- **RTMP / RTMPS and SRT egress** — paste a destination URL + stream key and go live. SRT URLs
  carry `streamid` and `latency` parameters (`srt://host:port?streamid=…&latency=…`).
- **Native, self-built transport** — RTMP chunking, AMF0, handshake, FLV; MPEG-TS muxing; SRT via
  the bundled `libsrt`. No reliance on a high-level RTMP SDK.
- **H.264 (AVC) and H.265 (HEVC)** with automatic codec negotiation, plus a manual force-codec
  escape hatch for compatibility debugging.
- **HDR (HLG10)** capture-to-encode path where the device supports it, with graceful SDR fallback.
- **Adaptive bitrate (ABR)** — the native layer adjusts to changing link conditions.
- **Quality presets** — 720p30, 1080p30, 1080p60, with configurable video/audio bitrate.
- **Foreground-service streaming** — keep broadcasting while the app is backgrounded; reconnects
  survive the screen turning off.
- **Local recording** — capture an MP4 alongside (or instead of) the live stream.

### Camera
- **Lens & zoom control** — switch between ultra-wide / main / tele physical lenses and pinch- or
  chip-driven digital zoom, clamped to each sensor's real range.
- **Manual exposure** controls.
- **Dual-camera Picture-in-Picture** — composite a second camera (e.g. front + back) into one feed
  through a shared GLES compositor. The PiP window is **draggable, resizable, corner-snapping**, and
  tap-to-swap which camera is the main view — switching is blink-free because both cameras run as
  independent inputs.
- **Capability-driven** — reads each device's certified concurrent-camera combinations at runtime
  and only offers lens/PiP combinations the hardware actually supports, degrading gracefully instead
  of failing on unsupported pairs.

### OBS integration
- **OBS WebSocket remote** — connect to a running OBS instance to list and switch scenes, and start
  or stop the OBS stream from the phone.
- **Auto "Be Right Back" switching** — when the link health degrades, automatically flip OBS to a
  BRB scene so viewers see a placeholder instead of a frozen feed, then switch back on recovery.

### Interface
- **Jetpack Compose + Material 3**, landscape-first viewfinder with a glass-surface control rail.
- Live health indicator, audio level meter, on-screen lens/zoom chips, exposure panel, and a
  prominent go-live control.

<!-- Show off the dual-camera PiP here — a screenshot of the draggable PiP window is great. -->
<p align="center">
  <img src="docs/images/dual-pip.png" alt="Dual-camera picture-in-picture" width="720">
</p>

---

## Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  UI  (Jetpack Compose, Material 3)                             │
│  Viewfinder · ControlRail · Settings · PiP overlay            │
├──────────────────────────────────────────────────────────────┤
│  Capture                                                       │
│  CameraX (single cam)   │   Camera2 (independent dual cams)    │
│            └──────────┬──┴──────────┘                          │
│                  GLES compositor (scene, PiP, mirror, corners) │
├──────────────────────────────────────────────────────────────┤
│  Encode   MediaCodec  →  AVC / HEVC · SDR / HLG10              │
├──────────────────────────────────────────────────────────────┤
│  Native core  (C/C++, JNI)                                     │
│  RTMP (handshake · chunk · AMF0 · FLV) · MPEG-TS · SRT(libsrt) │
│  MP4 recorder · ABR · HEVC bitstream handling                  │
└──────────────────────────────────────────────────────────────┘
```

- **Pure, unit-tested models** keep the tricky logic free of Android types and easy to test:
  scene geometry (`Scene`), display/sensor orientation (`DisplayTransform`), concurrent-camera
  capabilities (`ConcurrentCameraCapabilities`), and the OBS BRB decision (`BrbSwitch`).
- **The GL compositor is the single consumer of video inputs.** Single-camera mode feeds it via
  CameraX; dual-camera mode feeds it two independent Camera2 devices. Switching one input never
  disturbs the other.
- **The native layer owns the wire.** Encoded frames cross JNI into a C++ session that handles
  muxing and transport directly.

### Project layout

```
app/src/main/
├── java/com/example/plohoystream/
│   ├── camera/        Capture, GL compositor, dual-camera session, capability model
│   │   └── scene/     Pure scene + display-transform models
│   ├── stream/        Encoders, endpoints, codec selection, stream engine + ViewModel
│   │   └── obs/       OBS WebSocket remote + BRB auto-switch
│   ├── ui/            Compose screens, viewfinder, settings, theme
│   ├── data/          DataStore-backed settings
│   └── service/       Foreground streaming service
└── cpp/
    ├── core/          RTMP, SRT, TS/MP4 muxers, ABR, codec helpers
    ├── test/          Native unit tests
    └── third_party/   Prebuilt libsrt (arm64-v8a, armeabi-v7a, x86, x86_64)
```

---

## Building

**Requirements**
- Android Studio (latest stable) with the Android NDK and CMake 3.22.1
- A device or emulator running **Android 15 (API 35)** or newer
- `minSdk 35`, `targetSdk 36`

**Build & install**

```bash
# Debug build to a connected device
./gradlew :app:installDebug

# Assemble an APK
./gradlew :app:assembleDebug
```

**Tests**

```bash
# JVM unit tests (pure models: scene, transform, capabilities, BRB, codec)
./gradlew :app:testDebugUnitTest

# Native C++ tests are built/run via the cpp/test CMake target
```

The bundled `libsrt.so` is packaged per-ABI from `app/src/main/cpp/third_party/srt/libs`.

---

## Usage

1. Launch the app and grant **camera** and **microphone** permissions.
2. Open **Settings → Destination** and enter your ingest URL and stream key
   (`rtmp://…`, `rtmps://…`, or `srt://…`).
3. Pick a **video quality** preset and codec (or leave on Auto).
4. (Optional) Configure the **OBS WebSocket** connection for remote scene control.
5. Frame your shot — choose a lens, adjust zoom/exposure, and enable **dual-camera PiP** if you want
   a second angle.
6. Tap **Go Live**.

<!-- A short GIF of going live or dragging the PiP would shine here. -->

---

## Permissions

| Permission | Why |
|---|---|
| `CAMERA` | Capture video |
| `RECORD_AUDIO` | Capture audio |
| `INTERNET` | Stream to the destination |
| `FOREGROUND_SERVICE` (`camera`, `microphone`) | Keep streaming while backgrounded |
| `POST_NOTIFICATIONS` | Show the live/foreground notification |

---

## Acknowledgements

- [Moblin](https://github.com/eerimoq/moblin) — inspiration for the broadcast-tool approach and the
  OBS BRB auto-switch behavior.
- [libsrt](https://github.com/Haivision/srt) — the SRT transport library.

---

<div align="center">
<sub>Built with Kotlin, Jetpack Compose, and a hand-rolled native streaming core.</sub>
</div>
