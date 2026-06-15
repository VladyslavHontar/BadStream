# Background Streaming Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the stream running when the app is minimized/backgrounded (even if the Activity is destroyed while the process lives) by moving pipeline ownership out of the Activity into a process-scoped holder and keeping the camera feeding the encoder when the preview is gone.

**Architecture:** A process-scoped `LivePipeline` singleton owns the `CameraStreamEngine`, the `Camera2Controller`, and the persistent settings store. The foreground service (already `camera|microphone`, started on go-live) keeps the process alive. The Activity/ViewModel read the engine from the holder and reconnect on recreation; the Viewfinder only attaches/detaches its preview surface, and the camera reconfigures to encoder-only when the preview is gone.

**Tech Stack:** Kotlin, Jetpack Compose, Camera2, AndroidX lifecycle, kotlinx-coroutines; JUnit unit tests. No C++ changes.

**Spec:** `docs/superpowers/specs/2026-06-15-background-streaming-design.md`

**Test command:** `./gradlew testDebugUnitTest` (Kotlin unit tests) and `./gradlew assembleDebug` (compile the app). The real acceptance is on-device (manual) — see Task 6.

---

## File Structure

- Create: `app/src/main/java/com/example/plohoystream/camera/CameraTargets.kt` — pure target-selection helper.
- Create: `app/src/test/java/com/example/plohoystream/camera/CameraTargetsTest.kt` — its unit test.
- Create: `app/src/main/java/com/example/plohoystream/stream/LivePipeline.kt` — process-scoped pipeline holder.
- Modify: `app/src/main/java/com/example/plohoystream/MainActivity.kt` — use `LivePipeline`; drop dispose-while-live.
- Modify: `app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt` — use `LivePipeline.camera`; preview attach/detach via the helper; no stop-while-live on dispose.

---

## Task 1: Pure camera-target selection helper

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/camera/CameraTargets.kt`
- Test: `app/src/test/java/com/example/plohoystream/camera/CameraTargetsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/example/plohoystream/camera/CameraTargetsTest.kt`:

```kotlin
package com.example.plohoystream.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraTargetsTest {
    // Generic over the surface type so it can be tested without android.view.Surface.
    @Test fun previewAndEncoder_bothPresent_previewFirst() {
        assertEquals(listOf("p", "e"), CameraTargets.select("p", "e"))
    }

    @Test fun backgrounded_encoderOnly_keepsStreaming() {
        assertEquals(listOf("e"), CameraTargets.select(null, "e"))
    }

    @Test fun idlePreview_only() {
        assertEquals(listOf("p"), CameraTargets.select("p", null))
    }

    @Test fun nothing_empty() {
        assertEquals(emptyList<String>(), CameraTargets.select(null, null))
    }
}
```

- [ ] **Step 2: Run it red**

Run: `./gradlew testDebugUnitTest --tests "*CameraTargetsTest*"`
Expected: FAIL to compile (`CameraTargets` does not exist).

- [ ] **Step 3: Implement**

Create `app/src/main/java/com/example/plohoystream/camera/CameraTargets.kt`:

```kotlin
package com.example.plohoystream.camera

/**
 * Camera output targets for the capture session, in order: preview (when the UI is attached)
 * first, then the encoder input surface (when streaming).
 *
 * The key background-streaming invariant: when the preview is gone (app backgrounded) but the
 * encoder is present, the result is `[encoder]` — capture keeps feeding the encoder, so the
 * stream never stops. Generic over the surface type so the selection logic is unit-testable
 * without `android.view.Surface`.
 */
object CameraTargets {
    fun <T> select(preview: T?, encoder: T?): List<T> = listOfNotNull(preview, encoder)
}
```

- [ ] **Step 4: Run it green**

Run: `./gradlew testDebugUnitTest --tests "*CameraTargetsTest*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/camera/CameraTargets.kt app/src/test/java/com/example/plohoystream/camera/CameraTargetsTest.kt
git commit -m "feat(bg): pure camera target-selection helper (encoder-only when preview gone)"
```

---

## Task 2: `LivePipeline` process-scoped holder

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/stream/LivePipeline.kt`
- Reference (read, do not yet change): `MainActivity.kt` (the `engine = run { … }` block and `DataStoreSettingsStore` creation), `camera/Camera2Controller.kt`, `camera/CameraEnumerator.kt`, `media/CodecCapabilities.kt`, `stream/CodecSelector.kt`, `data/DataStoreSettingsStore.kt`.

**Context:** This holder takes over what `MainActivity.onCreate` builds today, so it survives Activity destruction. CRITICAL: it must also own the settings `store` — `DataStoreSettingsStore` creates a `DataStore` for `settings.json`, and constructing a second one for the same file (which a recreated Activity would do) throws *"There are multiple DataStores active for the same file."* Owning it in the singleton fixes that latent crash that background/Activity-recreation would otherwise trigger.

- [ ] **Step 1: Implement the holder**

Create `app/src/main/java/com/example/plohoystream/stream/LivePipeline.kt`. Move the engine + camera + store construction here verbatim from `MainActivity` (adapting `this`/`appCtx` to the passed application context). Use the EXACT same `startMedia`/`stopMedia` closure bodies currently in `MainActivity` (including the M2-B epoch capture and M2-C recorder fan-out and `StreamForegroundService.start/stop`).

```kotlin
package com.example.plohoystream.stream

import android.content.Context
import android.os.SystemClock
import android.os.Environment
import com.example.plohoystream.camera.Camera2Controller
import com.example.plohoystream.camera.CameraEnumerator
import com.example.plohoystream.data.DataStoreSettingsStore
import com.example.plohoystream.data.SettingsStore
import com.example.plohoystream.media.CodecCapabilities
import com.example.plohoystream.service.StreamForegroundService

/**
 * Process-scoped owner of the live pipeline (engine + camera + settings store). Survives
 * Activity destruction; the foreground service keeps the process alive while streaming, so the
 * stream continues when the UI is backgrounded or the Activity is recreated. Initialized once
 * with the application context.
 */
object LivePipeline {
    @Volatile private var initialized = false

    lateinit var engine: CameraStreamEngine
        private set
    lateinit var camera: Camera2Controller
        private set
    lateinit var store: SettingsStore
        private set
    var hdrAvailable: Boolean = false
        private set

    fun ensureInit(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appCtx = context.applicationContext
            camera = Camera2Controller(appCtx)
            store = DataStoreSettingsStore(appCtx)

            val cameras = CameraEnumerator.enumerate(appCtx)
            val anyCameraHdr = cameras.any { it.supportsHdr }
            val hevcCaps = CodecCapabilities.hevc()
            hdrAvailable = CodecSelector.hdrAvailable(hevcCaps.main10, anyCameraHdr)

            engine = run {
                var video: VideoEncoder? = null
                var audio: AudioEncoder? = null
                var recorder: NativeRecorder? = null
                lateinit var eng: CameraStreamEngine
                eng = CameraStreamEngine(
                    streamerFactory = { NativeRtmpStreamer() },
                    hevcEncoder = hevcCaps.encoder,
                    hevcMain10 = hevcCaps.main10,
                    cameraHdr = anyCameraHdr,
                    startMedia = { streamer, fmt, quality, record ->
                        StreamForegroundService.start(appCtx)
                        val nanoT0 = System.nanoTime()
                        val bootT0 = SystemClock.elapsedRealtimeNanos()
                        val rec: NativeRecorder? = if (record) {
                            val dir = appCtx.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                            val path = "${dir?.absolutePath}/Recording_${System.currentTimeMillis()}.mp4"
                            NativeRecorder().also {
                                it.start(path, fmt.codec.nativeFlag, quality.width, quality.height, quality.fps, 44100, 2)
                                it.writeAudioConfig(44100, 2)
                            }
                        } else null
                        val v = VideoEncoder(
                            width = quality.width, height = quality.height, fps = quality.fps,
                            bitRate = quality.videoBitrate,
                            format = fmt,
                            nanoT0 = nanoT0,
                            bootT0 = bootT0,
                            onConfig = { csd -> streamer.sendVideoConfig(csd); rec?.writeVideoConfig(csd) },
                            onFrame = { annexb, key, pts ->
                                streamer.sendVideo(annexb, key, pts, pts); rec?.writeVideo(annexb, key, pts)
                            },
                        )
                        val a = AudioEncoder(
                            sampleRate = 44100, channels = 2, bitRate = quality.audioBitrate,
                            nanoT0 = nanoT0,
                            onFrame = { aac, pts -> streamer.sendAudio(aac, pts); rec?.writeAudio(aac, pts) },
                            onLevel = { lvl -> eng.publishAudioLevel(lvl) },
                        )
                        streamer.sendAudioConfig(44100, 2)
                        v.start(); a.start()
                        video = v; audio = a; recorder = rec
                        eng.publishEncoderSurface(v.inputSurface)
                    },
                    stopMedia = {
                        video?.stop(); audio?.stop(); video = null; audio = null
                        recorder?.stop(); recorder = null
                        StreamForegroundService.stop(appCtx)
                    },
                )
                eng
            }
            initialized = true
        }
    }
}
```

> If `import com.example.plohoystream.stream.CodecSelector` is needed (same package — `CodecSelector` is in `stream`), no import line is required. Verify the actual package of `CodecSelector`, `VideoEncoder`, `AudioEncoder`, `NativeRecorder`, `NativeRtmpStreamer`, `CameraStreamEngine` (all `com.example.plohoystream.stream`) — no imports needed for same-package types; add imports only for `camera`/`data`/`media`/`service` types as shown.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/stream/LivePipeline.kt
git commit -m "feat(bg): LivePipeline process-scoped holder (engine + camera + settings store)"
```

---

## Task 3: `MainActivity` reads from `LivePipeline`; no dispose-while-live

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/MainActivity.kt`

- [ ] **Step 1: Replace the engine/store construction with the holder**

In `MainActivity.onCreate`, replace the whole `val appCtx = …`-through-`val store = DataStoreSettingsStore(applicationContext)` region (the camera enumeration, `hdrAvailable`, the `engine = run { … }` block, and the `store` creation) with:

```kotlin
        LivePipeline.ensureInit(applicationContext)
```

Remove the `private lateinit var engine: StreamEngine` field. Update `setContent` to use the holder:

```kotlin
        setContent {
            PlohoyTheme {
                val vm: StreamViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        StreamViewModel(LivePipeline.engine, LivePipeline.hdrAvailable, LivePipeline.store) as T
                })
                StreamScreen(vm)
            }
        }
```

- [ ] **Step 2: Drop the dispose-while-live in `onDestroy`**

Replace `onDestroy`:

```kotlin
    override fun onDestroy() {
        super.onDestroy()
        // Do NOT tear down the pipeline here: while streaming it must survive Activity
        // destruction (the foreground service keeps the process alive). The pipeline is a
        // process-scoped singleton; user-initiated Stop tears it down via the engine.
    }
```

Remove now-unused imports (`AudioEncoder`, `CameraEnumerator`, `CodecCapabilities`, `CodecSelector`, `NativeRecorder`, `NativeRtmpStreamer`, `VideoEncoder`, `DataStoreSettingsStore`, `StreamForegroundService`, `Environment`, `SystemClock`, `StreamEngine` — whichever are no longer referenced in `MainActivity`). Keep `LivePipeline`, `StreamViewModel`, `StreamScreen`, `PlohoyTheme`, the window/insets imports.

- [ ] **Step 3: Compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL (no unused-import errors; warnings are fine).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/MainActivity.kt
git commit -m "feat(bg): MainActivity uses LivePipeline; keep pipeline alive across Activity destroy"
```

---

## Task 4: Viewfinder attaches/detaches preview; camera survives background

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt`

**Context:** Today the Viewfinder owns the controller (`remember { Camera2Controller(context) }`), starts it only when a preview exists, and stops it on dispose. Change it to use `LivePipeline.camera`, drive targets via `CameraTargets.select(preview, encoder)` (so a null preview while streaming → encoder-only), and never stop the camera on dispose while streaming.

- [ ] **Step 1: Use the shared controller and target helper**

In `Viewfinder.kt`:
- Replace `val controller = remember { Camera2Controller(context) }` with `val controller = LivePipeline.camera`.
- Replace the camera `LaunchedEffect` so it reconfigures whenever the preview surface OR encoder surface changes, using the helper. The encoder surface comes from `encoderSurface` (the engine's StateFlow, already collected). Example shape:

```kotlin
    LaunchedEffect(config, surface, encoderSurface, activeHdr) {
        val c = config ?: return@LaunchedEffect
        val targets = CameraTargets.select(surface, encoderSurface)
        if (targets.isEmpty()) {
            controller.stop()           // idle + no preview (e.g. backgrounded while not live)
        } else {
            controller.start(c, targets, hdr = activeHdr)
            controller.setZoom(zoom)
        }
    }
```

(`CameraTargets.select` is generic; here `T = Surface`. Import `com.example.plohoystream.camera.CameraTargets`.)

- [ ] **Step 2: Don't stop the camera on dispose while live**

Replace the existing `DisposableEffect(Unit) { onDispose { controller.stop() } }` with one that only releases the preview and never stops a live capture:

```kotlin
    DisposableEffect(Unit) {
        onDispose {
            // Detach preview only. If streaming, the camera must keep feeding the encoder, so
            // reconfigure to whatever targets remain (encoder-only) rather than stopping. If
            // idle (no encoder surface), stop the camera.
            val enc = encoderSurface
            val targets = CameraTargets.select<Surface>(null, enc)
            if (targets.isEmpty()) controller.stop()
            else config?.let { controller.start(it, targets, hdr = activeHdr) }
        }
    }
```

> Note: `encoderSurface`, `config`, `activeHdr` are read at dispose time. `encoderSurface`/`activeHdr` are `by collectAsStateWithLifecycle()` values; capturing them in the `onDispose` lambda reads their latest values. Verify they're in scope where `DisposableEffect` is declared; if not, hoist the `DisposableEffect` below their declarations.

- [ ] **Step 3: Remove the now-unused `Camera2Controller` import** if present (the type is no longer constructed here; `LivePipeline.camera` provides it). Keep `CameraControls`, `CameraCapabilities`, etc.

- [ ] **Step 4: Compile**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/ui/viewfinder/Viewfinder.kt
git commit -m "feat(bg): preview attach/detach via target helper; camera survives background"
```

---

## Task 5: Full unit suite + APK build

**Files:** none (verification).

- [ ] **Step 1: Run all unit tests**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all existing tests + the 4 new `CameraTargetsTest` pass. (No test relied on `MainActivity` building the engine; `StreamViewModelTest`/`CameraStreamEngineTest` use fakes and are unaffected.)

- [ ] **Step 2: Build the APK**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (native + Kotlin compile and package).

- [ ] **Step 3: Commit any incidental fixes** (if needed; otherwise skip).

---

## Task 6: On-device acceptance (manual — the real gate)

Document the recipe in `docs/superpowers/BACKGROUND_SMOKE.md` (create it) and mark it as the user's manual acceptance step; do NOT attempt to automate:

- Go live (to local `ffmpeg -listen`/MediaMTX via `adb reverse tcp:1935 tcp:1935`, or Twitch).
- Press Home / switch to another app. Confirm on the receiver that the stream **continues with no stall** for a sustained period (e.g. 60+ s). Audio + video keep flowing.
- Return to the app: preview reattaches, stats/timer resume, no crash.
- Rotate the device / trigger Activity recreation mid-stream: the UI reconnects to the live pipeline (still Live), preview returns.
- Stop from the app: stream ends cleanly, foreground-service notification clears.

- [ ] **Step 1: Write `docs/superpowers/BACKGROUND_SMOKE.md`** with the above steps.
- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/BACKGROUND_SMOKE.md
git commit -m "docs(bg): on-device background-streaming smoke-test recipe"
```

---

## Notes for the implementer
- No C++ changes. No new dependencies.
- Do not add a notification Stop action (explicitly out of scope).
- Preserve the exact `startMedia`/`stopMedia` closure bodies when moving them into `LivePipeline` (they encode M2-B A/V-sync epochs and M2-C recording — do not regress them).
- `Camera2Controller.start` already coalesces redundant starts and drops invalid surfaces, so calling it on every surface change is safe.
