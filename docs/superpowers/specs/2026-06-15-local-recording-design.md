# Local Recording (native fragmented MP4) — Design (M2-C)

**Date:** 2026-06-15
**Milestone:** M2 "Usable streaming" — sub-project C
**Status:** Approved (ready for implementation plan)

## Goal

Record the live stream to a single, crash-safe `.mp4` on disk while streaming, with **no
second encoder** — mimicking Moblin's fragmented-MP4 recorder as closely as Android allows.

## Why a native fragmented-MP4 writer

Moblin records via `AVAssetWriter` with `outputFileTypeProfile = .mpeg4AppleHLS` +
`preferredOutputSegmentInterval = 2s` — **fragmented MP4** (fMP4) written incrementally to one
`.mp4` file (init `ftyp`+`moov`, then 2-second `moof`+`mdat` fragments). A crash loses only the
in-progress ≤2 s fragment. Android's `MediaMuxer` **cannot** produce fMP4 (no segment-interval
API), and the only platform alternative (rotating `MediaMuxer` into separate segment files +
concatenating) mimics the outcome but not Moblin's single-file shape and adds fragile
concat/seam handling. So we write the fMP4 box layer ourselves in C++, which:
- gives **byte-shape parity** with Moblin (single fMP4 file, per-fragment flush, same crash
  profile),
- **maximally reuses existing helpers** — `flv.cpp`'s `AnnexBToAvcc`, `BuildAvcC`,
  `SplitSpsPps`, `BuildAsc`, and `hevc.cpp`'s `hvcC` builder already produce exactly the MP4
  sample formats / decoder-config records; only the ISO-BMFF boxes are new,
- is **fully host-testable** in the existing C++ GoogleTest harness (box output is pure bytes),
- fits the project's "mux/egress in the C++/NDK core" architecture.

## Decisions (locked during brainstorm)

- **Native fragmented-MP4 writer** (Strategy b), single `.mp4`, per-fragment flush.
- **No re-encode** — tap the already-encoded `onConfig`/`onFrame` callbacks (deviation from
  Moblin, which re-encodes; accepted to avoid a second `MediaCodec`).
- **Trigger:** a `Settings.recordWhileStreaming` toggle (auto-persists via the Settings trait)
  + a Settings UI switch; default **off**. When on, each go-live also records.
- **Location:** app-specific external `Movies/` dir (`getExternalFilesDir(DIRECTORY_MOVIES)`),
  no runtime permission.
- **HDR metadata** (`colr`/`mdcv`/`clli`) **deferred** — the recorded bitstream is HDR but the
  MP4 HDR signaling boxes are a later add (not relevant on current test hardware).

## Architecture

```
MainActivity.startMedia (recordWhileStreaming?)
   ├─ onConfig(csd)  ─┬─▶ streamer.sendVideoConfig (RTMP, existing)
   │                  └─▶ recorder.writeVideoConfig (NEW)
   ├─ onFrame(annexb,key,ptsMs) ─┬─▶ streamer.sendVideo (existing)
   │                             └─▶ recorder.writeVideo (NEW)
   └─ audio onFrame(aac,ptsMs)  ─┬─▶ streamer.sendAudio (existing)
                                 └─▶ recorder.writeAudio (NEW)

Kotlin: NativeRecorder (JNI facade, mirrors NativeRtmpStreamer style)
   └─ C++: FragmentedMp4Writer (app/src/main/cpp/core/mp4_writer.{h,cpp})
            reuses flv.cpp / hevc.cpp helpers + new ISO-BMFF box writing
```

The recorder is a **native** writer (same rationale as the RTMP core), wrapped by a Kotlin
`NativeRecorder` JNI facade, tapped alongside the streamer in `startMedia`.

## fMP4 structure

- **Init segment (written once at start):** `ftyp` (major brand `iso5`/`mp42`, compatible
  brands incl. `iso6`/`dash`) + **`moov`** containing `mvhd`, a **video `trak`** (`tkhd`,
  `mdia`→`minf`→`stbl` with an empty `stts/stsc/stsz/stco` and an `stsd` holding `avc1`+`avcC`
  or `hvc1`+`hvcC`), an **audio `trak`** (`mp4a`+`esds` from `BuildAsc`), and **`mvex`** with a
  `trex` per track (fragmented-MP4 requires empty sample tables in `stbl` and `trex` defaults).
- **Per fragment:** `moof` (`mfhd` sequence number, one `traf` per track with `tfhd`
  default-base-is-moof, `tfdt` `baseMediaDecodeTime`, `trun` carrying per-sample size +
  duration + the keyframe/non-sync sample flags) followed by `mdat` with the AVCC/AAC sample
  bytes. Flush (`fsync`-or-`flush`) after each fragment write.

**Timescale:** use a 1000 (millisecond) timescale for both tracks for simplicity (PTS already
arrive in ms); per-sample duration = next-PTS − this-PTS (last sample of a fragment uses the
running average or a default frame duration).

**Fragment boundary:** start a new fragment when a **video keyframe** arrives **and** ≥ ~2 s
has elapsed since the current fragment started. The first fragment starts at the first video
keyframe. Audio samples are bucketed into the current fragment by PTS. (Pass-through means we
align to the live encoder's existing IDRs; the encoder already emits periodic keyframes.)

**Codec config:** for AVC, `SplitSpsPps` → `BuildAvcC`; for HEVC, the `hvcC` builder (single
`csd-0` VPS+SPS+PPS blob). The negotiated codec is known at record start (same as the stream).

## Components

| File | Change |
|---|---|
| `cpp/core/mp4_writer.{h,cpp}` | NEW: `FragmentedMp4Writer` — `Start(path, codec, width, height, fps, sampleRate, channels)`, `WriteVideoConfig(csd)`, `WriteVideo(annexb, key, ptsMs)`, `WriteAudioConfig(sr, ch)`, `WriteAudio(aac, ptsMs)`, `Stop()`. Reuses flv/hevc helpers; new ISO-BMFF box writers. |
| `cpp/core/mp4_box.{h,cpp}` (or in mp4_writer) | Box-writing helpers (`ftyp/moov/trak/mvex/moof/traf/trun/mdat`). |
| `cpp/native-lib.cpp` | JNI `nativeRecorderCreate/Start/WriteVideoConfig/WriteVideo/WriteAudioConfig/WriteAudio/Stop/Destroy`. |
| `stream/NativeRecorder.kt` | JNI facade (mirrors `NativeRtmpStreamer` lock/handle style). |
| `stream/Settings.kt` | Add `recordWhileStreaming: Boolean = false` (auto-persists via DataStore trait). |
| `MainActivity.kt` `startMedia`/`stopMedia` | When `recordWhileStreaming`, create recorder, build output path, fan callbacks out to it; stop+null it in `stopMedia`. |
| `ui/settings/*` | A "Record while streaming" switch (in Video settings or a Recording row), dimmed-while-live per the existing Moblin-style pattern; reads `ui.settings.recordWhileStreaming`, writes via `viewModel.setRecordWhileStreaming`. |
| `stream/StreamViewModel.kt` | `setRecordWhileStreaming(on)` via the existing `mutate` path. |
| `cpp/test/test_mp4_writer.cpp` | NEW: host tests. |
| `cpp/test/CMakeLists.txt` | Register the new test + add `mp4_writer.cpp` to the core build. |

## Crash-safety

Each `moof`+`mdat` is flushed to disk as written, so every completed ≤2 s fragment is intact
and playable; an app/OS kill loses only the in-progress fragment. The init `moov` carries no
final duration — standard fMP4/DASH players tolerate this. (A clean `Stop()` simply finishes
the last fragment and closes the file; no rewrite of `moov` is required for fMP4.)

## Testing

- **Host C++ unit tests** (`test_mp4_writer.cpp`): drive the writer with known SPS/PPS + a few
  AVCC frames + AAC, then **parse the output bytes** and assert: `ftyp` present with expected
  brands; `moov` has two `trak`s and a `mvex`/`trex` per track; `avcC`/`esds` payloads match the
  flv-helper output; each fragment is a well-formed `moof`+`mdat` with the right `trun` sample
  count, sizes, durations, and the first video sample of each fragment flagged as a sync
  sample; the first fragment begins on a keyframe. A small box-walker in the test validates
  box nesting + lengths. HEVC path covered with a `hvcC` fixture.
- **On-device (manual):** enable the toggle, stream, stop, pull the `.mp4`, confirm it plays
  and `ffprobe` reports the expected tracks/duration — your acceptance step.

## Out of scope (later)
Replay buffer; independent recording bitrate/codec (a second encoder, closer to Moblin);
HDR metadata boxes (`colr`/`mdcv`/`clli`); pause/resume; MediaStore publish to the gallery.
