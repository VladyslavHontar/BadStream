# Beautiful UI — Design Spec

**Status:** Approved (brainstormed 2026-06-14). Follows M1-C (HEVC + HDR, merged). Next milestone in the roadmap: M1-C → **Beautiful UI** → M2.

**Goal:** Turn the functional-but-bare viewfinder into a comfortable, modern, *beautiful* streaming UI — a landscape, full-screen, glass-over-camera experience with physics-based motion, semantic-color live stats, and a Moblin-style settings panel — without over-complicating the UX.

## Scope (decided in brainstorming)

- **Surfaces:** the live **viewfinder** + a **settings panel**. No home screen, no multi-screen app shell.
- **Orientation:** **landscape-locked**, **full-screen immersive** (system bars hidden, sticky) so it's hard to accidentally exit. Portrait is out of scope.
- **Controls depth:** "Lean + quality picker" — destination, codec (auto/HEVC/AVC), HDR toggle, and a **quality picker** (resolution / fps / bitrate), plus existing zoom/lens/flip. No tap-to-focus/exposure/torch in this milestone (deferred).
- **Real stats:** the live HUD shows **real** bitrate, connection health, and audio level — so this milestone includes the native→Kotlin **stats plumbing** (M2-flavored groundwork), not fake meters.
- **Settings UX** mirrors **Moblin** (`eerimoq/moblin`, MIT) from a UX perspective, collapsed to our single-destination/single-camera feature set.

## Visual language

**Cinematic Glass + semantic color + iOS-spring motion.**

- The **camera feed is always the hero** and is **never covered** by controls.
- **Controls are neutral white frosted glass.** Color is reserved for *data that is changing* — never decoration.
- **Semantic color:**
  - `liveRed` — the LIVE indicator and the Stop button (the one critical state worth red).
  - Connection health — `healthGood` (green) → `healthWarn` (amber) → `healthBad` (red), by value.
  - Audio meter — green → amber (loud) → red (clipping), by value.
- **Motion:** fluid, physics-based springs shared across the whole app (one spec, sourced from Material 3 Expressive's `MotionScheme`). The signature move is the **preview-shrink ⇄ settings** transition.

### Design-system foundation

- New `ui/theme/` package: `Color.kt` (semantic tokens above + dark surfaces + glass tints), `Type.kt` (expressive type scale), `Motion.kt` (the shared spring specs), `GlassSurface.kt` (reusable frosted-panel composable: translucent dark scrim + hairline border + subtle elevation).
- **Material 3 Expressive enabled** — bump the Compose BOM / `androidx.compose.material3` to the 1.4.x line that ships the expressive components, shape system, and `MotionScheme`. (Current: BOM `2025.06.00`. Verify the exact BOM/version that exposes expressive APIs at implementation time and bump to it.)
- **Glass, honestly:** the control rail sits in the **letterbox black bars** (no camera behind it), so its "glass" is a translucent panel + hairline — cheap and reliable, no real-time blur needed. Any element *over* the video (the LIVE pill) uses a translucent scrim. **True gaussian backdrop-blur of the live camera surface is NOT promised** — it's genuinely hard on Android (camera is a separate `TextureView`/`SurfaceView` surface); the scrim look is the committed baseline, `RenderEffect` blur is a stretch experiment only.

## Layout (landscape · iOS-Camera pattern)

- **Camera preview fills the left**, fit-to-height 16:9, leaving a **letterbox bar on the right**.
- **The right letterbox bar is the control rail** — nothing covers the video:
  - **Top of rail (status):** LIVE pill (red pulsing dot + "LIVE"), large readable **timer**, **connection health dot + bitrate**, **audio meter**, **zoom chips**.
  - **Bottom of rail (actions):** small circular **flip** · the primary **Go Live / Stop** button · **gear** (settings). Controls are small (explicit feedback: earlier mockups were too big).
- **Settings does not overlay the preview.** Opening settings (or any "major" surface) **shrinks the preview to the left** and the settings panel fills the freed space — one coordinated spring.

## State flow

The right rail/HUD adapts per connection state; the video is never covered.

| State | Rail / HUD | Behavior |
|---|---|---|
| **Setup** (idle) | Neutral white Go Live ring, gear, flip, zoom chips. No color. | Gear → preview shrinks, settings panel springs in. |
| **Connecting** | Go Live ring morphs into a pulsing progress ring; "Connecting…". | The ring itself animates (no separate spinner). |
| **Live** | Red pulsing LIVE + timer; green→amber→red health dot + bitrate; audio meter; white Stop. | Stream-stopping settings rows are **dimmed** (Moblin pattern). |
| **Reconnecting** | LIVE pill turns amber "Reconnecting…"; health dot red; timer keeps running. | Presented honestly; actual auto-reconnect logic is M2. |
| **Error** | Quiet **inline** message in the rail (e.g. "Connection refused") + white "Try again". No modal dialog. | Retry → Connecting. |

## Motion language

- **Preview shrink ⇄ settings** — the signature coordinated spring (medium-bouncy): preview springs left as the panel slides in.
- **Go Live → Stop** — the white ring **morphs** to the rounded-square Stop via shape interpolation, not a swap.
- **Color shifts** — health dot / audio meter / LIVE animate color over ~300ms; never snap.
- **LIVE dot** — gentle ~1.4s pulse. **Press feedback** — springy scale-down on every control. **Rail reveal** — controls fade/slide in when the camera opens.
- All driven by one shared spring spec for physical consistency.

## Settings (Moblin UX, scoped to us)

Grouped list that **pushes sub-screens** (iOS Settings style), rendered inside the right split panel as a small navigation stack (root → sub-screen → back). Patterns copied from Moblin: **settings that would stop the stream are dimmed while live** (with an amber info banner), the **stream key is masked**, **example-URL hints**, and **footer help text** on codec/bitrate (Moblin's wording is good and user-tested).

**IA (root → sub-screens):**

1. **Destination** — Server URL (`rtmp://…`, with example hints) · **Stream key** (masked). *We split key out of the URL — cleaner than Moblin's combined field for a single-platform user.*
2. **Video** — Resolution picker · Frame-rate picker · **Codec** (Auto = current negotiation / HEVC / AVC) · **Bitrate** · footer help.
3. **Audio** — Bitrate (default 128 kbps). Codec fixed to AAC (no picker yet).
4. **Camera** — Lens/camera select · Zoom · Flip/mirror · **HDR toggle** (shown only when supported — reflects per-device availability, e.g. "HDR unavailable on this device").
5. **About & Reset** — version + reset-to-defaults.

**Explicitly skipped (YAGNI vs Moblin):** stream *profiles* list, the create wizard, scenes, OAuth platform integrations, SRT/RIST/WHIP, bonding/Moblink, widgets/chat, all external-device integrations, the show-all/advanced split.

## Real-stats plumbing (honest signals)

- **Bitrate (actual egress):** native `RtmpClient`/`StreamSession` increments an `std::atomic<uint64_t> bytesSent_` on each socket write; JNI exposes it (`nativeBytesSent`); the engine's existing ~250ms poll computes a smoothed (EMA) kbps from the byte delta over the time delta.
- **Connection health:** native exposes the **egress backpressure signal** — the StreamSession queue depth / pending bytes (`nativeQueueDepth` or pending-bytes). Kotlin derives `Good/Warn/Bad` from queue trend + actual-vs-target bitrate. Real backpressure, not a guess. (Counting truly *dropped* frames would need a bounded-drop queue policy — out of scope here; the queue-depth signal is the committed health source.)
- **Audio level:** `AudioEncoder` computes peak/RMS from the PCM it already reads from `AudioRecord`, normalized 0..1, exposed as a `StateFlow<Float>`. Pure Kotlin, no native.
- **Timer:** monotonic start stamped when state → Live; UI formats elapsed.

## Architecture / components

- **Decompose** the current single `ui/StreamScreen.kt` into focused, previewable composables:
  - `Viewfinder` (orchestrates preview + rail + settings split), `CameraPreview` (existing, reused), `ControlRail`, `LiveStatusCluster`, `HealthIndicator`, `AudioMeter`, `ZoomChips`, `GoLiveButton` (morphs Go Live↔Stop), `SettingsPanel` + sub-screens (`DestinationSettings`, `VideoSettings`, `AudioSettings`, `CameraSettings`, `AboutSettings`), `PermissionGate` (restyled).
  - Each composable is small, single-responsibility, and has `@Preview`s for its states.
- **State:**
  - Extend `StreamUiState` with: `bitrateKbps`, `health: ConnectionHealth`, `audioLevel: Float`, `elapsed`, settings-mirror fields, and a `settingsRoute` for the panel's nav.
  - Extend `StreamConfig` with: `resolution`, `fps`, `videoBitrate`, `audioBitrate`, `codecOverride` (Auto/HEVC/AVC). Settings **apply at go-live** (changing them while live is disabled), so **no mid-stream encoder reconfig** is needed.
  - `codecOverride` feeds the existing `CodecSelector` (Auto = today's behavior; HEVC/AVC forces the requested codec).
- **Manifest/Activity:** `MainActivity` locked to `landscape`; sticky-immersive via `WindowInsetsControllerCompat` (hide system bars, swipe-to-reveal-transient).

## Testing

- **Unit (pure logic):** health derivation from queue/bitrate, bitrate EMA smoothing, elapsed-timer formatting, settings→`StreamConfig` mapping, codec-override→`CodecSelector` selection, audio-level normalization.
- **Compose `@Preview`s** for every component state (setup / connecting / live / reconnecting / error; settings root + each sub-screen; health good/warn/bad; audio quiet/loud/clipping).
- **Device-verified** on the Seeker: landscape lock, immersive, the preview-shrink settings transition, real bitrate/health/audio while streaming to a local server or Twitch.

## Deferred / non-goals

- True gaussian **backdrop blur** of the live camera (scrim baseline ships; blur is a stretch experiment).
- **Tap-to-focus / exposure / torch / grid**, stream **profiles**, multiple destinations, SRT, scenes, chat/overlays — all later milestones.
- Real **auto-reconnect** logic and **dropped-frame counting / adaptive bitrate** — M2 (the Reconnecting state is *presented* here, the logic lands in M2).
- True HDR preview (still constrained by shared-session dynamic range; the Seeker camera lacks HLG10 anyway).

## Known constraints carried in

- Backdrop-blur-of-live-camera is hard on Android → glass = scrim, not blur (above).
- Settings that change the encoder only take effect at the next go-live (hence dimmed-while-live), matching Moblin.
- On the Seeker specifically, the HDR toggle won't appear (camera exposes no HLG10) — the Camera settings screen states this rather than offering a dead toggle.
