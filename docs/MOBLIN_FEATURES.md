# Moblin Feature Catalogue

**Source**: https://github.com/eerimoq/moblin (iOS/Swift, commit at clone date 2026-06-15)  
**Primary files consulted**: `Moblin/Various/Settings/Settings.swift`, `SettingsStream.swift`, `SettingsScene.swift`, `SettingsChat.swift`, `SettingsAudio.swift`, `SettingsRemoteControl.swift`, `SettingsMoblink.swift`, `SettingsMacros.swift`, `SettingsIngests.swift`, and the `Moblin/View/Settings/**` UI views.

---

## Status vs PlohoyStream (Gap Analysis Header)

**PlohoyStream currently has:**
- RTMP/RTMPS egress (HEVC/AVC enhanced-RTMP + HDR via Camera2 10-bit)
- Camera2 capture with multi-lens zoom, front/back flip, manual focus/exposure/WB
- Landscape glass UI (Compose)
- Persistent settings (destination / video / audio / camera)
- Real-stats HUD (bitrate, FPS, dropped frames, network round-trip)
- Auto-reconnect and network resilience
- A/V sync (shared monotonic clock, rebased PTS)
- Local recording (native fragmented MP4, Camera2 path)

**PlohoyStream does NOT yet have** (use the section tags below to identify gaps):
- SRT/SRTLA egress and bonding across multiple network interfaces
- OBS WebSocket remote control
- Background/minimized streaming (audio continues, video freezes)
- Scenes, overlays, and widget system (chat overlay, text, alerts, browser, map, etc.)
- Chat integration (Twitch, Kick, YouTube, in-app moderation)

---

## Section Index with Gap Tags

| # | Section | PlohoyStream status |
|---|---------|---------------------|
| 1 | Streaming destinations & protocols | ⚠️ Partial |
| 2 | Network & bonding | ❌ Not yet |
| 3 | Video encoding | ⚠️ Partial |
| 4 | Audio | ⚠️ Partial |
| 5 | Camera & lenses | ⚠️ Partial |
| 6 | Scenes & widgets | ❌ Not yet |
| 7 | Video effects & LUTs | ❌ Not yet |
| 8 | Recording & replay | ⚠️ Partial |
| 9 | OBS WebSocket integration | ❌ Not yet |
| 10 | Remote control (Moblin assistant / web) | ❌ Not yet |
| 11 | Moblink (bonding relay) | ❌ Not yet |
| 12 | Ingests (RTMP/SRT/RIST/WHIP/RTSP servers) | ❌ Not yet |
| 13 | Chat & moderation | ❌ Not yet |
| 14 | Chat bot & TTS | ❌ Not yet |
| 15 | Macros & automation | ❌ Not yet |
| 16 | Game controllers | ❌ Not yet |
| 17 | Apple Watch companion | ❌ Not yet |
| 18 | External devices (DJI, GoPro, gimbal, Tesla, sensors) | ❌ Not yet |
| 19 | Display & UI preferences | ⚠️ Partial |
| 20 | Deep links / import-export / cosmetics | ❌ Not yet |

---

## 1. Streaming Destinations & Protocols ⚠️ Partial

PlohoyStream has RTMP/RTMPS. Missing: SRT, SRTLA, RIST, WHIP.

### Per-stream settings (`SettingsStream.swift`)

| Setting/Feature | Description |
|---|---|
| **Stream name** | Human-readable label for a saved stream configuration. Multiple streams can be stored and activated one at a time. |
| **URL** | Destination endpoint. Supports `rtmp://`, `rtmps://`, `srt://`, `srtla://`, `rist://`, and `whip://` scheme prefixes. |
| **Stream key** | Embedded in the URL or set separately depending on protocol. |
| **Protocol — RTMP/RTMPS** | Push stream over RTMP (plain) or RTMPS (TLS-encrypted). Widely supported by Twitch, YouTube, Kick, and Facebook. (`SettingsStreamProtocol.rtmp / .rtmps`) |
| **Protocol — SRT** | Secure Reliable Transport. Low-latency protocol with forward error correction. Configurable latency (default 3000 ms). (`SettingsStreamProtocol.srt`) |
| **Protocol — SRTLA** | SRT Link Aggregation. Bundles multiple network paths (cellular, WiFi, Ethernet) into one SRT stream. (`SettingsStreamProtocol.srtla`) |
| **Protocol — RIST** | Reliable Internet Stream Transport. Alternative low-latency bonding protocol. (`SettingsStreamProtocol.rist`) |
| **Protocol — WHIP (WebRTC)** | WebRTC Ingest Protocol for ultra-low-latency delivery. Supports Opus audio only. (`SettingsStreamProtocol.whip`) |
| **Multi-streaming** | Send the same stream simultaneously to additional RTMP destinations directly from the device. Each extra destination increases bandwidth and thermal load. (`SettingsStreamMultiStreaming`, `SettingsMacros.swift`) |
| **Twitch channel** | Associates a stream with a Twitch channel name to enable viewer count, chat, alerts, and authenticated actions. |
| **Kick channel** | Associates a stream with a Kick channel for chat and viewer count. |
| **YouTube** | OAuth-authenticated YouTube integration for chat and scheduled live broadcasts. |
| **SOOP channel** | Basic integration with SOOP (afreecaTV) for chat. |
| **Open Streaming Platform** | OSP/self-hosted platform support via URL. |
| **Real-time IRL** | Optional push of GPS location to a realtime IRL tracking server (configurable base URL + push key). |
| **Go live notification — Discord webhook** | Posts a message to a Discord channel when the stream goes live. Configurable webhook URL and message text. |
| **Background streaming** | Keeps the stream alive when the app is backgrounded. Video becomes a frozen frame (iOS limitation); audio continues. Optional Picture-in-Picture mode to keep camera active. |
| **Portrait mode (per-stream)** | Rotates the UI to portrait while keeping video in landscape. Video vertical offset is adjustable. |
| **NTP pool address** | Custom NTP server for timecode synchronisation (default: `time.apple.com`). |
| **Timecodes** | Embeds timecodes in the stream (used for post-production sync). |
| **Estimated viewer delay** | Sets the estimated end-to-end viewer delay (seconds). Used to time on-screen alerts. |
| **Stream wizard** | Step-by-step setup wizard that auto-configures URL, codec, and platform settings for common services. |

---

## 2. Network & Bonding ❌ Not yet

| Setting/Feature | Description |
|---|---|
| **SRT(LA) — Latency** | Buffer depth in milliseconds (default 3000 ms). Higher values tolerate more network jitter at cost of delay. |
| **SRT(LA) — Adaptive bitrate** | Automatically reduces video bitrate when the link degrades. Three algorithms available: `Belabox`, `FastIRL`, and `SlowIRL`. (`SettingsStreamSrtAdaptiveBitrateAlgorithm`) |
| **SRT(LA) — Connection priorities** | Per-interface priority weighting so the encoder prefers WiFi over cellular when both are available. |
| **SRT(LA) — Max bandwidth follows input** | (Official libSRT implementation) Automatically caps the allowed overhead bandwidth to match the stream bitrate. |
| **SRT(LA) — Overhead bandwidth** | Percentage of extra bandwidth reserved for SRT retransmissions (5–100 %, default 25 %). |
| **SRT(LA) — Big packets** | Packs 7 MPEG-TS packets per SRT packet (vs. 6). Improves efficiency but can fail on some Android hotspots. |
| **SRT(LA) — DNS lookup strategy** | Choose System, IPv4, or IPv6 resolution. Affects carrier compatibility (e.g., T-Mobile vs. IRLToolkit). |
| **SRT(LA) — Implementation** | Toggle between the official libSRT 1.5.3 and Moblin's own more energy-efficient custom implementation. |
| **Adaptive encoder resolution** | Downscales the encoded resolution when bitrate is severely constrained to reduce encoder load. Threshold configurable. (`SettingsStream.adaptiveEncoderResolution`) |
| **Network interface names** | Human-readable labels for each detected network interface (e.g., "Cellular modem", "Hotel WiFi") shown in the bonding status panel. |
| **WiFi Aware** | Peer-to-peer WiFi Direct link between two devices (sender/receiver roles) for ultra-low-latency local bonding without a router. (`SettingsWiFiAware`) |
| **Bonding statistics panel** | On-screen HUD showing per-interface upload speed, RTT, and packet loss for all active SRTLA/RIST connections. |

---

## 3. Video Encoding ⚠️ Partial

PlohoyStream has H.264/HEVC, resolution, FPS, bitrate. Missing: B-frames, VBR (iOS 26+), many resolution presets.

| Setting/Feature | Description |
|---|---|
| **Resolution** | Output video resolution. Options range from 426×240 up to 4032×3024 (4K ProRes-class), including 16:9, 4:3, and square crop variants. (`SettingsStreamResolution`) |
| **FPS** | Target frame rate. Standard values; 120 FPS is noted as experimental. |
| **Low light boost (LLB)** | Allows built-in cameras to automatically drop below the selected FPS to gather more light when the scene is dark (iOS 18+). |
| **Codec — H.264/AVC** | Industry-standard codec, universally compatible with RTMP platforms. Supports Baseline, Main, and High profiles. |
| **Codec — H.265/HEVC** | Better compression at equivalent quality. Required for HDR (HLG/Apple Log) streams. RTMP requires enhanced-RTMP for HEVC. |
| **H.264 profile** | Choose Baseline, Main, or High profile to trade compatibility for compression efficiency. |
| **Rate control** | ABR (average bitrate), CBR (constant bitrate), or VBR (variable bitrate, iOS 26+). |
| **Bitrate** | Target encode bitrate in bps. Selected from a configurable list of presets. |
| **Bitrate presets** | User-editable named presets (e.g., "4 Mbps", "8 Mbps") shared across all streams. |
| **Key frame interval** | Maximum seconds between I-frames (0 = automatic). Lower values aid seek and error recovery. |
| **B-frames** | Enables bi-directional predicted frames in H.264 for better compression at the cost of encoder latency. |
| **Color space** | sRGB (standard), P3 D65 (wide-gamut), HLG BT.2020 (HDR), or Apple Log (log-gamma for grading). (`SettingsColorSpace`) |
| **Video stabilization** | Off / Standard / Cinematic / Cinematic Extended Enhanced — uses AVFoundation's built-in EIS. |
| **Mirror front camera on stream** | Toggle whether the front camera is mirrored in the outgoing stream (default: mirrored). |
| **Fixed horizon** | Locks the horizon level so the image stays level when the device tilts (uses gyro). |
| **Adaptive encoder resolution threshold** | Sensitivity multiplier for the adaptive-resolution downscale trigger. |

---

## 4. Audio ⚠️ Partial

PlohoyStream has AAC, stereo, basic gain. Missing: Opus, noise reduction, multi-mic routing, channel mapping.

| Setting/Feature | Description |
|---|---|
| **Audio codec — AAC** | Default codec. Universal compatibility with RTMP, SRT, RIST. |
| **Audio codec — Opus** | Required for WHIP (WebRTC). Better quality at low bitrates; not supported by all RTMP targets. |
| **Audio bitrate** | Encode bitrate in kbps (32–320 kbps slider, step 32). 128 kbps recommended minimum. |
| **Mic selection** | Choose built-in mic position (Bottom, Front, Back, Top), external/wired, Bluetooth, or network audio sources (RTMP/SRTLA ingest). |
| **Auto-switch to external mic** | When an external mic connects, automatically routes audio to it. (`SettingsMics.autoSwitch`) |
| **Microphone input gain** | Hardware gain control for external mics that support it (slider, 0–1). |
| **Output gain** | Software amplification of the final mix, 0–24 dB. |
| **Stereo mic preference** | Prefer stereo mic mode when the device supports it. |
| **Bluetooth output only** | Routes audio output exclusively over Bluetooth, improving compatibility with certain BT speakers during recording. |
| **Output-to-input channel mapping** | Maps which stereo output channel feeds which encoder input channel (e.g., reroute right channel to both inputs). (`SettingsAudioOutputToInputChannelsMap`) |
| **Noise reduction** | Built-in noise suppression video effect (listed under video effects but applied in the audio pipeline). |
| **Recording audio bitrate** | Separate audio bitrate for local recordings, independent of the live stream bitrate. |
| **Prefer stereo mic** | System hint to use a stereo microphone array when available. |

---

## 5. Camera & Lenses ⚠️ Partial

PlohoyStream has front/back flip, multi-lens, manual focus/exposure/WB. Missing: triple low-energy, screen capture source, external USB-C, media player source.

| Setting/Feature | Description |
|---|---|
| **Back camera** | Main rear sensor. Lens selected via the zoom/lens picker. |
| **Front camera** | Selfie camera. Mirror-on-screen is default for natural framing. |
| **Back triple (low energy)** | Uses the wide-dual-tele triple-camera combination in a single low-energy session (no switching penalty). Available on supported iPhone Pro models. |
| **Back dual (low energy)** | Wide + ultra-wide dual-camera low-energy session. |
| **Back wide dual (low energy)** | Wide + tele dual-camera low-energy session. |
| **External camera** | USB-C UVC cameras on iPad, or cameras detected via AVFoundation as external. |
| **Screen capture** | Captures the device's own screen as the video source (uses Broadcast Upload Extension). |
| **Tap to focus** | Tap the preview to set manual focus point. Long-press returns to autofocus. |
| **Zoom presets (back)** | Named zoom levels for rear cameras (e.g., 0.5×, 1×, 2×, 5×). Selectable from the bottom bar. |
| **Zoom presets (front)** | Named zoom levels for the front camera. |
| **Switch-to-back / switch-to-front zoom** | Preset zoom applied automatically when flipping cameras. |
| **Zoom animation speed** | Speed of the smooth zoom animation between presets. |
| **Pinch-to-zoom** | Gesture zoom on the preview. |
| **Manual focus** | Lock and adjust focus distance with a slider. |
| **Manual exposure bias** | Adjust EV offset (overexpose / underexpose). |
| **Manual ISO** | Direct ISO control (on supported cameras). |
| **Manual white balance** | Lock and adjust colour temperature. |
| **Lens selection** | Explicitly choose ultra-wide, wide, 2×, or tele lens on multi-lens devices. |
| **Camera controls** | Native iOS camera controls panel (Apple's hardware-level UI overlay, iOS 18+). |
| **DJI camera (Bluetooth)** | Bluetooth remote-start of live streaming on DJI Osmo Action 3/4, Osmo Pocket 3. Appears as a video/audio source in Moblin scenes. (`SettingsDjiDevices`) |
| **GoPro (WiFi)** | Connects to a GoPro camera over WiFi, launches live stream, and ingests video back into Moblin via RTMP. (`SettingsGoPro`) |
| **Media player** | Plays a local video file as a virtual camera/mic source in a scene. Multiple named player instances supported. (`SettingsMediaPlayers`) |
| **Selfie stick integration** | Detects button presses on a selfie stick / remote shutter to trigger scene switches or snapshots. (`SettingsSelfieStick`) |
| **Grid overlay** | Shows a rule-of-thirds or other composition grid on the local preview only (not encoded). |
| **Face / privacy** | ML-powered face detection to blur faces, text regions, or the background. Blur strength and pixellate strength adjustable. (`SettingsFace`) |
| **Beauty filter** | Skin-smoothing and face-shaping post-process applied to the encoded video. Adjustable radius, strength, and shape parameters. (`SettingsBeauty`) |
| **Reactions** | Triggers iOS 17 built-in camera reactions (fireworks, balloons, hearts, confetti, lasers, rain, sparkle, glasses). |

---

## 6. Scenes & Widgets ❌ Not yet

| Setting/Feature | Description |
|---|---|
| **Scenes** | Named presets that bundle a camera source, a set of widgets, and optional video effects. Switch scenes instantly during a live stream. (`SettingsScene`) |
| **Scene enable/disable** | Individual scenes can be temporarily disabled so they don't appear in the selector. |
| **Scene switch transition** | Animated blur, freeze, or blur-and-zoom transition when switching scenes. Force-transition option ignores the default. |
| **Camera source per scene** | Each scene can have its own camera (back, front, triple, external, RTMP ingest, SRT ingest, screen capture, media player, etc.). (`SettingsSceneCameraPosition`) |
| **Auto scene switcher** | Cycles through a list of scenes on a timed schedule (configurable per-scene dwell time). (`SettingsAutoSceneSwitcher`) |
| **Disconnect protection** | Automatically switches to a fallback scene when the stream connection drops, and returns to the live scene when it recovers. |
| **Widget — Text** | Overlay a formatted text string. Supports a format-token system: `{shortTime}`, `{time}`, `{date}`, `{fullDate}`, `{speed}`, `{altitude}`, `{distance}`, `{country}`, `{countryFlag}`, `{state}`, `{city}`, `{heading}`, `{subtitles}`, `{heartRate}`, `{gForce}`, `{timer}`, `{stopwatch}`, `{lapTimes}`, `{checkbox}`, `{rating}`, `{muted}`, `{browserTitle}`, `{bitrateAndTotal}`, `{debugOverlay}`, and more. Font, weight, design, colour, background, border, and corner radius all configurable. |
| **Widget — Browser** | Renders a URL in a WebView overlay on the stream. Custom CSS stylesheet supported. Optionally interactive for the local streamer. |
| **Widget — Image** | Displays a static image file at a configurable position and size. |
| **Widget — Slideshow** | Cycles through multiple images on a timer. |
| **Widget — Video source** | Embeds a second camera or ingest source as a picture-in-picture overlay (multi-cam). |
| **Widget — Alerts** | Shows animated event notifications (Twitch follows, subs, raids, bits; Kick subs). Configurable image, sound, text colour, font, placement relative to detected face or scene. |
| **Widget — Chat** | In-stream scrolling chat overlay (reads from the same Twitch/Kick/YouTube feed). |
| **Widget — Chat emote combo** | Highlights when multiple chat users react with the same emote in quick succession. |
| **Widget — Map** | Live GPS map tile overlay showing the streamer's current location. |
| **Widget — QR code** | Generates and displays a QR code from a configurable URL string. |
| **Widget — Scene** | Embeds another scene's output as a sub-widget (scene-within-scene composition). |
| **Widget — Scoreboard** | Overlay scoreboard for Padel, Golf, Basketball, Volleyball, Hockey, Football, Tennis, and generic formats. Players are configured globally and shared across instances. |
| **Widget — VTuber** | Virtual avatar overlay driven by face tracking (basic VTuber). |
| **Widget — PNGTuber** | Static-image avatar that swaps between idle/talking frames based on audio level. |
| **Widget — Wheel of luck** | Animated spinning wheel for giveaways or decisions, triggered from the quick-button panel. |
| **Widget — Bingo card** | Interactive bingo card overlay. |
| **Widget — Crop** | Crops a region of the video frame and stretches it to fill the widget area. |
| **Widget — Pomodoro timer** | Displays a Pomodoro focus/break cycle timer on stream. |
| **Widget — Snapshot** | Displays the last captured snapshot image as an overlay. |
| **Widget layout** | Each widget has configurable X/Y position, width, height, and layer order within a scene. |

---

## 7. Video Effects & LUTs ❌ Not yet

| Setting/Feature | Description |
|---|---|
| **Grayscale** | Converts the video to black and white. |
| **Sepia** | Applies a warm brown tone. |
| **Movie (4:3 bars)** | Paints black pillarboxes on left/right to simulate 4:3 aspect ratio. |
| **Anamorphic lens (2.35:1 bars)** | Paints black letterboxes top/bottom to simulate widescreen cinema. |
| **Noise reduction** | Apple's built-in AVFoundation noise reduction post-process. |
| **Triple** | Shows the centre third of the image repeated three times side by side (experimental). |
| **Pixellate** | Pixellates the full frame at a configurable strength. |
| **Whirlpool** | Applies a rotating swirl distortion; angle configurable. |
| **Pinch / squeeze** | Barrel/pincushion distortion; scale factor configurable. |
| **Remove background** | ML-powered background removal, replaces background with transparency or a solid colour. |
| **Dewarp 360** | Remaps a 360° equirectangular source to a standard perspective output. |
| **Shape** | Applies a shape mask (e.g., circle crop) with corner radius and border controls. |
| **Opacity** | Global video layer opacity control (0–1). |
| **Mask** | Applies a custom vector mask to hide parts of the frame. |
| **LUT (3D Look-Up Table)** | Applies a colour grade via a 3D LUT. Bundled LUTs included; custom PNG (Hald CLUT) and `.cube` LUTs can be imported. Especially useful with Apple Log colour space. |
| **CRT effect** | Simulates a CRT scanline look (quick button). |
| **Blur faces** | Detects and blurs human faces in real time. Strength adjustable. |
| **Blur text** | Detects and blurs on-screen text. |
| **Blur background** | Blurs everything except detected foreground subjects. |

---

## 8. Recording & Replay ⚠️ Partial

PlohoyStream has fragmented MP4 recording. Missing: replay buffer, stinger transitions, auto-start/stop, Discord upload.

| Setting/Feature | Description |
|---|---|
| **Local recording** | Records to a fragmented MP4 file (VFR, crash-resilient). Stored in the app's Documents folder or a user-chosen folder. (`SettingsStreamRecording`) |
| **Recording path** | Optional custom output folder (security-scoped bookmark to a user-chosen directory or external drive). |
| **Recording — override resolution** | Encode the recording at a different resolution from the live stream (costs extra encoder resources). |
| **Recording — video codec** | H.264 or H.265/HEVC independently of the stream codec. |
| **Recording — video bitrate** | Up to 50 Mbps; 0 = automatic. |
| **Recording — key frame interval** | Independent key frame interval for recordings. |
| **Recording — audio bitrate** | Separate audio bitrate for the recorded file. |
| **Clean recordings** | Strips all widget overlays from the recorded file (records the raw camera feed). |
| **Auto-start recording on go live** | Automatically begins recording when the stream is started. |
| **Auto-stop recording on stream end** | Automatically stops the recording when the live stream is terminated. |
| **Replay buffer** | Stores the last N seconds of video in a ring buffer. Trigger "Instant Replay" (quick button or macro) to save a clip. Start delay and clip length configurable. (`SettingsReplay`) |
| **Replay speed** | Slow-motion factor applied when saving a replay clip (e.g., 0.5×, 0.25×). |
| **Replay transition — type** | Fade or stinger animation played when the replay clip interrupts or follows the live feed. |
| **Replay transition — stinger video** | Custom HEVC+alpha `.mov` video file used as an in/out stinger clip. |
| **Replay post-trigger delay** | Seconds to wait after the trigger before cutting back to live. |
| **Snapshot** | Manual or chat-bot-triggered still frame capture. Saved locally and optionally uploaded to Discord via webhook. |
| **Discord snapshot webhook** | Automatically POSTs captured snapshots to a Discord channel. Optionally only when live. |

---

## 9. OBS WebSocket Integration ❌ Not yet

Source: `StreamObsRemoteControlSettingsView.swift`, `ModelObs.swift`

| Setting/Feature | Description |
|---|---|
| **OBS WebSocket URL** | WebSocket address of the OBS instance to control (e.g., `ws://192.168.1.5:4455`). |
| **OBS WebSocket password** | Authentication password for the obs-websocket plugin. |
| **See current OBS scene** | Displays which scene is currently active in OBS on Moblin's status panel. |
| **Start / stop OBS stream** | Sends start/stop stream commands to OBS. |
| **Start / stop OBS recording** | Sends start/stop recording commands to OBS. |
| **Switch OBS scene** | Changes the active scene in OBS remotely (from quick buttons or macros). |
| **OBS snapshot** | Requests a screenshot of a specific OBS source. |
| **OBS audio levels** | Displays per-source audio levels from OBS in the Moblin HUD. |
| **OBS audio sync** | Sets the audio synchronisation offset for an OBS source. |
| **Mute / unmute OBS audio inputs** | Toggles the mute state of OBS audio sources. |
| **Main scene / BRB scene** | Configure which OBS scene is "normal" and which is "BRB". Moblin automatically switches OBS to BRB when the stream appears broken, and back when it recovers. |
| **BRB on video source broken** | Triggers the OBS BRB scene when the SRTLA/RTMP ingest source feeding OBS disconnects (home-server use case). |
| **OBS source name** | The name of the OBS Source that receives Moblin's video (for snapshot and level display). |
| **Streaming directly to OBS** | Toggle indicating Moblin pushes directly to OBS (enables relevant OBS sync features). |

---

## 10. Remote Control (Moblin Assistant / Web) ❌ Not yet

Source: `SettingsRemoteControl.swift`, `RemoteControlSettingsView.swift`

| Setting/Feature | Description |
|---|---|
| **Remote control — Streamer (server) mode** | Moblin on the streaming device opens a WebSocket server so an "assistant" app/device can observe and control it. |
| **Remote control — Assistant (client) mode** | A second Moblin instance or the web remote connects to the streamer's device and sees live status (bitrate, scene, logs). |
| **Remote control — relay** | Routes the control connection through Moblin's hosted relay server (`wss://moblin.mys-lang.org/…`) so the assistant doesn't need to be on the same LAN. Bridge ID is a shared secret. |
| **Remote control — password** | Shared password to authenticate the assistant connection. |
| **Remote control — preview FPS** | How many frames per second the low-res stream preview is sent to the assistant (default 1 FPS). |
| **Remote control — reliable chat & events** | Uses a more reliable delivery path for chat and event data to the assistant. |
| **Remote control — web** | Built-in HTTP server that exposes a local web UI for browser-based remote control. Port configurable (default 80). |
| **Assistant capabilities** | Change scene, change mic, change bitrate, change zoom, view logs, trigger macros, view stream stats. |
| **Multiple streamers** | An assistant can pair with multiple named streamers and switch between them. |

---

## 11. Moblink (Bonding Relay) ❌ Not yet

Source: `SettingsMoblink.swift`, `MoblinkSettingsView.swift`

| Setting/Feature | Description |
|---|---|
| **Moblink — Streamer mode** | The streaming device runs a Moblink server (TCP, configurable port, default 7777) that accepts additional relay connections from phones acting as extra uplinks. |
| **Moblink — Relay mode** | A second iPhone/iPad joins the Moblink server and contributes its cellular or WiFi connection as an extra bonding path, without running the full Moblin app. Relay can discover the streamer automatically on the local network. |
| **Moblink password** | Shared secret (default "1234") to authenticate relay devices. |
| **Manual relay URL** | Override automatic discovery with an explicit server URL for cross-network setups. |

---

## 12. Ingests (Server-side Sources) ❌ Not yet

Source: `SettingsMacros.swift` (Ingests section), `ModelRtmpServer.swift`, `ModelSrtlaServer.swift`, `ModelRistServer.swift`

| Setting/Feature | Description |
|---|---|
| **RTMP server** | Moblin acts as an RTMP ingest server (default port 1935). External encoders (e.g., OBS) push to it; the received stream appears as a virtual camera/mic source in scenes. Multiple named stream keys supported. |
| **RTMP server — latency** | Buffer depth (ms) for the RTMP ingest to smooth network jitter. |
| **SRTLA server** | Moblin accepts incoming SRT or SRTLA connections (SRT port and SRTLA port configurable). Received video is available as a camera source. |
| **RIST server** | Receives a RIST stream on a configurable port as a virtual camera source. Per-stream virtual destination port and latency. |
| **WHIP server** | WebRTC WHIP ingest server (default port 8310). Accepts WebRTC pushes as camera sources. |
| **SRT client** | Moblin connects outward to an SRT server and pulls a stream as a camera source. |
| **RTSP client** | Pulls an RTSP stream (RTP/RTSP/TCP or RTP/UDP) as a camera source. |
| **WHEP client** | Pulls a WebRTC stream via the WHEP egress protocol as a camera source. |
| **Talkback** | Plays the audio track of a chosen network ingest in the device's speaker so the operator can hear what is being sent. Route selection by mic/source name. |
| **WiFi Aware** | Peer-to-peer WiFi Direct between two Moblin devices for a direct local relay path. (`SettingsWiFiAware`) |

---

## 13. Chat & Moderation ❌ Not yet

Source: `SettingsChat.swift`, `Moblin/View/Settings/Chat/`

| Setting/Feature | Description |
|---|---|
| **Chat enabled** | Global toggle for in-app chat reception and overlay. |
| **Chat — Twitch** | Connects to Twitch IRC chat. Supports authenticated write (send messages), announcements, `/me` styling, reply threads, ban/timeout/delete actions (with login). |
| **Chat — Kick** | Connects to Kick chat. Supports ban, timeout, delete, and reply. |
| **Chat — YouTube** | Pulls YouTube Live chat messages. |
| **Chat — SOOP** | Basic SOOP (afreecaTV) chat pull. |
| **Chat font size** | Overlay font size in points. |
| **Chat colours** | Configurable username colour, message colour, background colour, shadow colour. |
| **Chat bold username / message** | Independent bold toggle for username and message text. |
| **Animated emotes** | Toggle animated GIF emotes from BTTV, FFZ, and 7TV (Twitch/Kick). |
| **Chat timestamp** | Optionally shows a timestamp on each message. |
| **Chat height / width** | Fractional portion of the screen occupied by the chat overlay (0–1). |
| **Maximum message age** | Automatically removes messages older than N seconds from the overlay. |
| **Filters** | Rules matching on username and/or message prefix; matched messages can trigger TTS, show on stream, print (cat printer), or feed the chat bot. |
| **Nicknames** | Map Twitch/Kick usernames to display nicknames in the chat overlay. |
| **Show deleted messages** | Grey out or hide deleted messages (moderation action). |
| **Background chat** | Keep chat connected when the app is backgrounded. |
| **Highlight events** | Tags special events (subs, raids, bits) visually in the chat overlay. |
| **Moderation — ban/timeout** | Send ban or timeout commands to Twitch/Kick from within Moblin. |
| **Twitch alerts** | Follow, subscribe, gift-subscription, resubscription, reward, raid, cheer (with minimum bits threshold). |
| **Kick alerts** | Subscriptions, gifted subscriptions, rewards, hosts, bans, kicks (with minimum kicks threshold). |

---

## 14. Chat Bot & Text-to-Speech ❌ Not yet

Source: `SettingsChat.swift` (bot section), `ChatBotCommand.swift`, `ChatTextToSpeech.swift`

| Setting/Feature | Description |
|---|---|
| **Chat bot** | Built-in rule engine that responds to viewer messages. Triggered by message prefix matching. |
| **Bot — commands** | Toggle chat TTS on/off, trigger scene switch, trigger zoom, trigger alerts, let chat take a snapshot, turn filters on/off, run a macro, fax (cat printer), and more. (`ChatBotCommand.swift`) |
| **Bot — aliases** | Map a custom command string (e.g., `!myalias`) to another command (e.g., `!moblin`). |
| **Bot — cooldown** | Minimum seconds between bot responses per command to prevent spam. |
| **Bot — subscriber/moderator only** | Restrict who can invoke bot commands. |
| **Low battery warning** | Bot periodically posts a low-battery message to chat when device battery is below threshold. |
| **Gemini AI personality** | Optional Google Gemini API integration for AI-driven chat bot responses. Configurable API key, model, and persona. |
| **Simple poll** | Viewers vote by typing 1, 2, or 3 in chat. Result shown on overlay. |
| **Text-to-speech (TTS)** | Reads incoming chat messages aloud using on-device speech synthesis. |
| **TTS — say username** | Optionally reads the username before the message. |
| **TTS — subscribers only** | Restrict TTS to paying subscribers. |
| **TTS — filter** | Skip messages matching certain patterns from TTS. Filter mentions toggle. |
| **TTS — language detection** | Per-message language detection so voices match the message language. |
| **TTS — voice selection** | Per-language voice selection. |
| **TTS — rate & volume** | Playback speed (rate) and volume sliders. |
| **TTS — pause between messages** | Gap in seconds between spoken messages. |
| **TTS.Monster** | Optional third-party TTS.Monster service integration for additional voices. |
| **Skip current TTS** | Quick button / Watch button to interrupt and skip the currently playing TTS message. |
| **Pause TTS** | Quick button to pause TTS without disabling it. |

---

## 15. Macros & Automation ❌ Not yet

Source: `SettingsMacros.swift`

| Setting/Feature | Description |
|---|---|
| **Macros** | Named sequences of actions executed in order, optionally with delays between steps. |
| **Macro actions** | Scene switch, zoom to X, enable/disable filters, trigger a reaction, enable/disable scenes, start/stop recording, take snapshot, mute/unmute, torch on/off, enable/disable auto scene switcher, DJI device control, move gimbal to preset, delay, and run another macro (nesting). |
| **Macro repeat mode** | Off (run once), Count (repeat N times), or Forever (loop until manually stopped). |
| **Macro — close panel on run** | Optionally dismiss the quick-button panel when the macro is triggered. |
| **Macro triggering** | Via quick buttons, game controllers, Apple Watch, chat bot commands, or remote control assistant. |

---

## 16. Game Controllers ❌ Not yet

Source: `SettingsGameController.swift`, `ModelGameController.swift`

| Setting/Feature | Description |
|---|---|
| **Game controller pairing** | Connect any MFi or standard iOS game controller (PlayStation, Xbox, etc.) via Bluetooth. |
| **Button mapping** | Assign stream actions to controller buttons: zoom in/out, switch scene, toggle torch, toggle mute, take snapshot, start/stop record, trigger macro, etc. |
| **Multiple controllers** | Multiple controllers can be configured simultaneously. |

---

## 17. Apple Watch Companion ❌ Not yet

Source: `Moblin Watch/`, `WatchSettingsView.swift`, README

| Setting/Feature | Description |
|---|---|
| **Stream preview** | Low-res live preview of the stream on the Watch face. |
| **Audio level** | Current mic audio level displayed on Watch. |
| **Bitrate display** | Current upload bitrate shown on Watch. |
| **Thermal state** | iPhone thermal state indicator on Watch. |
| **Chat on Watch** | Last 50 chat messages scrollable on Watch. |
| **Watch control — zoom** | Adjust zoom level using the Digital Crown or preset buttons. |
| **Watch control — scene switch** | Switch Moblin scenes from the Watch. |
| **Watch control — go live** | Start/stop the stream from the Watch. |
| **Watch control — record** | Start/stop recording from the Watch. |
| **Watch control — mute** | Toggle mic mute from the Watch. |
| **Watch control — skip TTS** | Skip the current TTS message. |
| **Watch face complication** | Watch face widget showing stream status. |
| **Watch as remote control assistant** | Watch acts as an external remote control assistant (limited feature set in this mode). |
| **Watch chat display settings** | Font size, colour, and layout for the Watch chat view. |

---

## 18. External Devices ❌ Not yet

| Setting/Feature | Description |
|---|---|
| **DJI camera (Bluetooth controller)** | Moblin pairs with a DJI Osmo Action (OA3, OA4) or Osmo Pocket 3 over Bluetooth, sends QR codes to configure its network settings, and then triggers the camera to start live streaming to Moblin's RTMP server. Video is ingested and re-encoded. |
| **GoPro (WiFi)** | Generates a GoPro-compatible QR code (or sends commands directly) to configure the camera's WiFi hotspot and RTMP push URL. Moblin ingests the received RTMP feed. Supports Hero 12 and 13. |
| **Gimbal (DockKit)** | Support for DockKit-compatible motorised gimbal/phone holder. Pan, tilt, and zoom tracking. Named preset positions configurable. |
| **Tesla integration** | Reads vehicle status (battery, charge state, location) and can display it on stream via text widgets. (`SettingsTesla`) |
| **Workout device (heart rate monitor)** | Connects to a BLE heart rate monitor. Heart rate fed into text widget via `{heartRate}` token. |
| **Cycling power device** | Connects to a BLE cycling power meter. Power data can be displayed in text widgets. |
| **Black Shark cooler** | Controls a Black Shark phone-cooling fan accessory; optional RGB light colour and brightness. |
| **Cat printer** | Connects to a BLE cat printer to print chat messages, faxes (images), and snapshots. Optional meow sound. |

---

## 19. Display & UI Preferences ⚠️ Partial

PlohoyStream has landscape UI and glass theme. Missing: portrait mode, quick buttons, verbose status overlays, external display.

| Setting/Feature | Description |
|---|---|
| **Quick buttons** | Customisable row of on-screen shortcut buttons (torch, mute, bitrate picker, scene switch, record, OBS, remote, draw overlay, poll, snapshot, replay, macros, etc.). Configurable layout (one/two column, scroll, show name). |
| **Big buttons** | Larger quick-button targets for easier touch in the field. |
| **Big audio level meter** | Expanded audio VU meter. |
| **Vertical buttons** | Arrange quick buttons vertically instead of horizontally. |
| **Local overlays** | Toggle visibility of individual status elements in the streamer's preview (chat, viewers, uptime, speed, audio level, zoom, mic, audio bar, cameras, OBS status, ingest status, bonding, game controller, location, remote control, browser widgets, events, system monitor, etc.). |
| **Network interface name labels** | Custom labels for each network adapter shown in the bonding HUD. |
| **Low bitrate warning** | Shows a warning toast when upload bitrate drops significantly below target. |
| **Recording confirmations** | Requires a confirmation tap before starting or stopping a recording. |
| **Vibrate on events** | Vibrates the device on connect-fail, low-bitrate warning, low-battery, and thermal events. |
| **Portrait mode** | Rotates the entire UI to portrait orientation. |
| **External display content** | When mirroring to an external monitor, choose: Stream (with overlays), Clean stream (no overlays), Chat only, or Mirror (same as phone). |
| **Stream button colour** | Customise the colour of the go-live button. |
| **Scene numeric input** | Switch scenes by typing a scene number on a numeric keypad. |
| **Stealth mode** | Turns off the phone screen while keeping the stream running (`ModelStealthMode`). |
| **Black screen button** | Quick button that makes the phone screen black without stopping the stream. |
| **Lock screen button** | Quick button that locks the UI to prevent accidental touches. |
| **Icon / cosmetics** | Choose the Moblin app icon from several variants; additional icons purchasable in-app to support development. |
| **Theme** | Dark mode always; no light mode. |

---

## 20. Deep Links, Import/Export & Misc ❌ Not yet

| Setting/Feature | Description |
|---|---|
| **Deep links (`moblin://`)** | Custom URL scheme for zero-touch configuration. A single URL can pre-configure stream URL, codec, OBS credentials, quick buttons, chat settings, and more. Used by service providers (BELABOX, IRLToolkit, etc.) for onboarding. |
| **Deep link creator** | Built-in tool to generate `moblin://` URLs from the current settings without manual JSON editing. (`SettingsDeepLinkCreator`) |
| **Import/export settings** | Export the full settings database as a JSON/zip file and import it on another device. (`ModelSettingsImportExport.swift`) |
| **Settings reset** | Factory-reset all settings to defaults. |
| **Streaming history** | Log of past stream sessions with timestamps and connection quality. |
| **Localization** | UI translated into English, French, German, Spanish, Polish, Simplified Chinese, Swedish, and more. |
| **Web browser (in-app)** | Private in-app browser visible only to the streamer (not encoded). Bookmarks supported. (`WebBrowserSettings`) |
| **Draw overlay** | Free-hand drawing tool on the preview for real-time annotations. Not encoded by default (quick button). |
| **Speech to text** | On-device transcription fed into the `{subtitles}` text widget token for live captioning. (`SpeechToText.swift`) |
| **Saved WiFi networks** | Store WiFi SSID/password pairs so Moblin can auto-join networks (used by GoPro setup). |
| **Keyboard** | Configurable on-screen keyboard behaviour and shortcuts. |
| **Debug settings** | Advanced toggles for logging, raw SRT stats, force-override codec, and other diagnostic options. |

---

## 5 Biggest Feature Gaps for PlohoyStream (Brainstorm Primer)

1. **SRT/SRTLA + adaptive bitrate + bonding** — Moblin's headline differentiator. The entire bonding stack (SRTLA link aggregation, connection priorities, Belabox/FastIRL/SlowIRL adaptive-bitrate algorithms) is absent from PlohoyStream. Most serious IRL streamers use SRTLA via BELABOX or IRLToolkit rather than RTMP. This is the single largest gap.

2. **Scene & widget system** — Moblin's scene graph (multiple camera sources + layered widgets: text, browser, alerts, map, chat overlay, scoreboard, video PiP) turns it from an encoder into a full production tool. PlohoyStream currently has no overlay/composition layer at all.

3. **Chat integration + TTS + chat bot** — Live interaction is central to IRL streaming culture. Twitch/Kick/YouTube chat with in-app moderation, alert widgets, TTS readback, and a command bot (with Gemini AI option) are heavily used. PlohoyStream has none of this.

4. **OBS WebSocket remote control** — Lets Moblin act as a wireless field camera feeding a home OBS instance, with automatic BRB scene switching on disconnect. This use-case (Moblin-on-phone → RTMP/SRT → OBS-at-home → platform) is extremely common and requires the WebSocket control layer to work properly.

5. **Remote control assistant + Apple Watch** — The assistant/relay system lets a second person (or the Watch) monitor and control the stream remotely. For solo IRL streamers the Watch integration (go live, scene switch, bitrate check, chat glance) is particularly high value and requires a tight iOS companion app that PlohoyStream has no equivalent of.
