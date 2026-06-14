# M2-C Local Recording (native fMP4 writer) — Implementation Plan

**Spec:** docs/superpowers/specs/2026-06-15-local-recording-design.md
**Branch:** feat/m2c-recording
**Discipline:** strict TDD, native-first, commit per task.

## Design notes (locked from spec + code read)

- Timescale = 1000 (ms) for both tracks. PTS arrive in ms.
- Reuse `flv.cpp`: `AnnexBToAvcc`, `SplitSpsPps`, `BuildAvcC`, `BuildAsc`; `hevc.cpp`: `SplitHevcParams`, `BuildHvcC`.
- Codec int: 0=AVC, 1=HEVC (matches `Codec` enum / `VideoCodecType.nativeFlag`).
- Box helper: write a placeholder 4-byte size of 0, remember the offset, backpatch after the body.
- Init segment = `ftyp` + `moov`. `moov` = `mvhd` + video `trak` + audio `trak` + `mvex`(2×`trex`).
- Empty sample tables in `stbl` (stts/stsc/stsz/stco all count 0) — required for fMP4.
- Per fragment = `moof`(`mfhd` seq + per-track `traf`) + `mdat`. `traf` = `tfhd`(tf_flags=0x020000 default-base-is-moof) + `tfdt`(version1, baseMediaDecodeTime) + `trun`.
- `trun` flags: 0x000001 data-offset, 0x000100 sample-duration, 0x000200 sample-size, 0x000400 sample-flags. data_offset patched to point at first byte of that track's samples within mdat, relative to start of the enclosing moof.
- Sample flags: sync = 0x02000000 (sample_depends_on=2). non-sync = 0x01010000 (depends_on=1 + is_non_sync). Audio all sync.
- Fragment boundary: a video keyframe AND >= 2000ms since current fragment start opens a new fragment. First fragment opens at first keyframe. Frames before first keyframe dropped. First video sample of each fragment is a keyframe ⇒ sync.
- Sample duration: next_pts - this_pts within a fragment; last sample of a fragment uses default (1000/fps, min 1). Audio same.
- Buffering: accumulate current-fragment video+audio in memory; on boundary keyframe flush previous fragment then open new. `Stop()` flushes final fragment.
- `WriteVideoConfig`/`WriteAudioConfig` stash decoder records; init segment (ftyp+moov) written lazily right before first moof.

## Box layout (see spec). Tasks:

### Task 1 — mp4_box.{h,cpp} + tests (TDD)
BoxWriter over Bytes&: `Begin(tag)->offset` (placeholder size+tag), `End(offset)` backpatch, `FullBox(tag,ver,flags)`. Test nesting + sizes. Wire CMakeLists.
Commit: `feat(m2c): mp4 box writer primitive + tests`.

### Task 2 — Init segment (ftyp+moov) AVC + box-walker tests (TDD)
`FragmentedMp4Writer::Start/WriteVideoConfig/WriteAudioConfig`, private `BuildInitSegment()` + test seam `InitSegmentForTest()`. Box-walker asserts: ftyp brands; 2 trak + mvex(2 trex); avc1+avcC==BuildAvcC; mp4a+esds w/ ASC==BuildAsc; empty stts/stsc/stsz/stco.
Commit: `feat(m2c): fMP4 init segment (ftyp+moov, avc) + tests`.

### Task 3 — Fragments single-fragment (TDD)
WriteVideo/WriteAudio/Stop; BuildFragment (moof+mdat). Test 1 frag: trun counts/sizes/sync flags, tfdt=0, mdat size.
Commit: `feat(m2c): fMP4 fragments (moof+mdat) single-fragment + tests`.

### Task 4 — Multi-fragment + HEVC (TDD)
Boundary >=2000ms on key; 2nd seq/tfdt. HEVC hvcC. Durations from pts diffs.
Commit: `test(m2c): multi-fragment boundary + HEVC hvcC path`.

### Task 5 — JNI + NativeRecorder.kt
Mirror streamer JNI/handle/lock.
Commit: `feat(m2c): JNI + NativeRecorder kotlin facade`.

### Task 6 — Settings + ViewModel + UI switch
`recordWhileStreaming` field/default; setter via mutate; StreamConfig field; VideoSettings Switch dimmed-while-live.
Commit: `feat(m2c): recordWhileStreaming setting + UI switch`.

### Task 7 — MainActivity wiring
startMedia gains record Boolean; build path getExternalFilesDir(DIRECTORY_MOVIES)/Recording_<now>.mp4; fan callbacks to streamer+recorder; stopMedia stops+nulls. Fix engine arity + tests.
Commit: `feat(m2c): wire recorder into go-live (MainActivity + engine)`.

### Task 8 — Full verification (ctest + gradle unit + assembleDebug).

## Test commands
- C++: `cd app/src/main/cpp && cmake -S test -B build-test >/dev/null && cmake --build build-test 2>&1 | tail -8 && ctest --test-dir build-test --output-on-failure 2>&1 | tail -15`
- Kotlin: `./gradlew testDebugUnitTest 2>&1 | tail -20`
- APK: `./gradlew assembleDebug 2>&1 | tail -20`
</content>
