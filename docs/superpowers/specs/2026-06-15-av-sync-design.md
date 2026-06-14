# A/V Sync — Design (M2-B)

**Date:** 2026-06-15
**Milestone:** M2 "Usable streaming" — sub-project B
**Status:** Approved (ready for implementation plan)

## Goal

Put audio and video timestamps on one shared monotonic clock so they don't drift,
eliminating the constant/growing lip-sync offset.

## Problem

- **Video PTS** = `MediaCodec.BufferInfo.presentationTimeUs` for the surface-input encoder
  (`VideoEncoder.kt`: `onFrame(bytes, key, info.presentationTimeUs / 1000)`). For a Camera2
  surface this is the camera **`SENSOR_TIMESTAMP`**, whose domain is `CLOCK_MONOTONIC`
  (`System.nanoTime`) when `SENSOR_INFO_TIMESTAMP_SOURCE == UNKNOWN`, or `CLOCK_BOOTTIME`
  (`SystemClock.elapsedRealtimeNanos`) when `== REALTIME`.
- **Audio PTS** = `System.nanoTime()` stamped at encode-queue time
  (`AudioEncoder.kt`: `codec.queueInputBuffer(idx, 0, read, System.nanoTime() / 1000, 0)`),
  carried through AAC and emitted as `info.presentationTimeUs / 1000`.
- The native `StreamSession::run()` rebase subtracts the **first sample's** timestamp
  (`baseMs`) from **both** streams. Since the two streams sit on different epochs (and
  possibly different clocks), one shared subtraction cannot align them → a constant lip-sync
  offset (monotonic-vs-monotonic) or a growing drift (boottime-vs-monotonic across doze).

Moblin avoids this because both its audio and video come from the same `CMSampleBuffer`
capture clock with a single shared `basePresentationTimeStamp`. We mimic that: one clock,
one shared epoch.

## Decisions (locked during brainstorm)

- Standardize both streams on **one monotonic timeline** anchored to a shared `t0` captured
  **once** when media starts.
- Do the normalization **in the Kotlin engine layer** (one place — pit of success); the
  native `baseMs` rebase stays as a harmless safety net.
- **No camera-characteristics plumbing required** — select the video epoch **empirically**
  from the first video frame (see below). This is self-contained and robust.

## Design

### Shared epoch capture
When media starts (`CameraStreamEngine.startMedia` path), capture **both** clocks at the
same instant and pass them to the encoders:

```
val nanoT0 = System.nanoTime()                       // audio clock epoch
val bootT0 = SystemClock.elapsedRealtimeNanos()      // candidate video epoch (sensor REALTIME)
```

Because both are read at the same wall-clock instant, the relative offset between an
audio sample (nanoTime domain) and a video sample (whichever domain) is preserved as long
as each stream is rebased against the epoch read on **its own** clock.

### Audio normalization
`AudioEncoder` emits `ptsMs = (System.nanoTime() - nanoT0) / 1_000_000`. (Currently it emits
absolute `nanoTime/1000`; the change is subtracting `nanoT0` and using ms.) Capture-time
stamping is unchanged (queue-time `nanoTime`); improving to `AudioRecord.getTimestamp` is
out of scope.

### Video normalization — empirical epoch selection
The video surface PTS is in the sensor-clock domain, which is either the `nanoTime` domain or
the `elapsedRealtimeNanos` (boottime) domain. Rather than querying
`SENSOR_INFO_TIMESTAMP_SOURCE` (which requires plumbing `CameraCharacteristics` into
`startMedia`), pick the epoch **empirically on the first frame**:

```
// firstPtsNanos = first frame's info.presentationTimeUs * 1000
videoEpochNanos = if (abs(firstPtsNanos - nanoT0) <= abs(firstPtsNanos - bootT0)) nanoT0 else bootT0
```

Then every frame emits `ptsMs = (framePtsNanos - videoEpochNanos) / 1_000_000`.

**Why this is robust:** `nanoT0` (monotonic) and `bootT0` (boottime) differ by exactly the
device's accumulated deep-sleep time. The first frame's sensor PTS is tiny-distance from the
epoch on its own clock and (potentially huge-distance) from the other. So when the two clocks
differ, the choice is unambiguous; when they barely differ (no sleep since boot), either
choice yields the same result. No characteristic query needed.

### Result
Both streams now emit ms on one shared timeline with a common zero, so audio and video are
aligned end-to-end. The native `baseMs` rebase still runs and now just shifts an
already-aligned pair near zero — a safety net, not the thing holding sync.

## Components

| File | Change |
|---|---|
| `stream/VideoEncoder.kt` | Accept `nanoT0`/`bootT0` epochs; on first frame pick `videoEpochNanos`; emit `(framePtsNanos - videoEpochNanos)/1e6` ms. |
| `stream/AudioEncoder.kt` | Accept `nanoT0`; emit `(nanoTime - nanoT0)/1e6` ms (rebased, not absolute). |
| `MainActivity.kt` `startMedia` | Capture `nanoT0`/`bootT0` once; pass to both encoders. |
| (optional) a small pure helper | `fun chooseVideoEpoch(firstPtsNanos, nanoT0, bootT0): Long` extracted for host testing. |

The native `stream_session.cpp` `baseMs` logic is unchanged (verified still correct as a
safety net).

## Error handling / edge cases
- First frame arrives before any audio: fine — each stream rebases independently.
- Clock wrap (ms is 32-bit on the wire): unchanged from today; the native rebase keeps the
  stream starting near zero, far from wrap.
- A very long pre-roll between epoch capture and first frame: the relative offset is still
  preserved because both epochs were captured together.

## Testing
- **Host-testable unit:** `chooseVideoEpoch` (the empirical selection) and the rebase math —
  pure functions, JVM unit tests covering: sensor==monotonic (pick nanoT0), sensor==boottime
  with large sleep gap (pick bootT0), and near-equal epochs (either, result within tolerance).
- **On-device (manual):** lip-sync check while streaming to `ffmpeg`/Twitch — your acceptance
  step (timestamp math can't prove perceptual sync).

## Out of scope
`AudioRecord.getTimestamp` capture-latency removal; resampling/clock-skew correction over
multi-hour streams; any change to the native rebase.
