# M1-B.3 — Encode + JNI + Native Egress (Go Live for real) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make "Go Live" actually publish the phone's camera + microphone to Twitch over RTMP — the first end-to-end stream through every layer.

**Architecture:** Camera2 renders into both the preview `SurfaceView` and a `MediaCodec` H.264 encoder input `Surface`. `MediaCodec` emits Annex-B NAL units; `AudioRecord`→`MediaCodec` emits raw AAC. Both cross JNI (as `byte[]`) into a native `StreamSession` that owns a `TcpTransport` + the proven `RtmpClient`, running a dedicated egress thread with a bounded drop-oldest queue. A foreground service (camera|microphone) keeps the pipeline alive. The clean Kotlin seams (`RtmpStreamer`, `StreamEngine`) are interface-driven so orchestration is unit-tested with fakes; the Android media glue is verified on-device.

**Tech Stack:** Kotlin + Jetpack Compose, Camera2, MediaCodec (surface-input H.264 + AAC), AudioRecord, foreground service; C++17 NDK core (existing `plohoystream_core`), JNI.

---

## Background: the seams this plan connects (read first)

These already exist and are **proven** (M1-A: 46 host tests + ffmpeg end-to-end; M1-B.2b: on-device):

- **C++ `RtmpClient`** (`app/src/main/cpp/core/rtmp_client.h`):
  - `RtmpClient(Transport& t, StreamParams p)`, `Begin()`, `OnBytes(const Bytes&)`, `state()`, `streamId()`.
  - `SendVideoConfig(const Bytes& sps, const Bytes& pps)`, `SendVideo(const Bytes& annexb, bool key, uint32_t ptsMs, uint32_t dtsMs)`.
  - `SendAudioConfig(int sampleRate, int channels)`, `SendAudio(const Bytes& aacRaw, uint32_t ptsMs)`.
  - `RtmpState { Idle, HandshakeSent, ConnectSent, CreateStreamSent, PublishSent, Publishing, Error }`.
  - `StreamParams { host, app, streamKey, tcUrl; port=1935; width=1280,height=720,sampleRate=44100; fps=30 }`.
- **`TcpTransport`** (`core/tcp_transport.h`): `Connect(host,port)`, `Write`, `Read` (blocking), `Close`, `connected()`. SIGPIPE-suppressed.
- **`flv.h`**: `SplitSpsPps(const Bytes& annexb, Bytes& sps, Bytes& pps)` — scans an Annex-B blob for SPS(nal type 7)/PPS(type 8). Reuse this for codec-config; do NOT re-parse NALs in Kotlin.
- **The proven driver loop** is `harness/main.cpp`: `Begin()` → loop `t.Read()`→`OnBytes()` until `state()==Publishing|Error` → `SendVideoConfig` → per-frame `SendVideo`. `StreamSession` (Task 2) is this loop moved onto a background thread fed by a queue.
- **Kotlin `StreamEngine`** (`stream/StreamEngine.kt`): `val state: StateFlow<StreamState>`, `start(config: StreamConfig)`, `stop()`. `MainActivity` currently injects `FakeStreamEngine`; Task 9 swaps in `CameraStreamEngine`.
- **`StreamConfig`** (`stream/StreamConfig.kt`): `rtmpUrl`, `streamKey`.
- **Native lib** is `plohoystream` (SHARED, links `plohoystream_core`), loaded via `System.loadLibrary("plohoystream")`. JNI lives in `app/src/main/cpp/native-lib.cpp`.

**Encoder defaults for this slice (fixed, auto-selected — no settings UI yet):** 1920×1080, 30 fps, 6 Mbps H.264 (baseline/high auto), 2 s keyframe interval; audio 44100 Hz stereo AAC-LC 128 kbps. These flow into `StreamParams` (used by `onMetaData`).

**Known simplifications carried from M1-A (documented, deferred to M2):** no inbound RTMP servicing during publish (no window-ack/ping responses), no graceful `deleteStream`/FCUnpublish teardown, no auto-reconnect. The acceptance gate here is "Twitch dashboard goes live and shows the camera for a short test," matching the proven harness behavior.

---

## File Structure

**Create (C++):**
- `app/src/main/cpp/core/media_queue.h` — bounded, thread-safe, drop-oldest media queue (header-only).
- `app/src/main/cpp/core/stream_session.h` / `.cpp` — owns transport+client+egress thread; public thread-safe API.
- `app/src/main/cpp/test/test_media_queue.cpp` — GoogleTest.
- `app/src/main/cpp/test/test_stream_session.cpp` — GoogleTest with `StubTransport`.

**Modify (C++):**
- `app/src/main/cpp/core/CMakeLists.txt` — add `stream_session.cpp`.
- `app/src/main/cpp/test/CMakeLists.txt` — add the two new test files.
- `app/src/main/cpp/native-lib.cpp` — add the JNI bridge.

**Create (Kotlin main):**
- `stream/RtmpEndpoint.kt` — pure URL→params parsing.
- `stream/RtmpStreamer.kt` — interface (the JNI seam).
- `stream/NativeRtmpStreamer.kt` — `external fun` impl + `System.loadLibrary`.
- `stream/VideoEncoder.kt` — MediaCodec H.264 surface-input wrapper.
- `stream/AudioEncoder.kt` — AudioRecord + MediaCodec AAC wrapper.
- `stream/CameraStreamEngine.kt` — `StreamEngine` impl wiring everything.
- `service/StreamForegroundService.kt` — camera|microphone FGS keep-alive.

**Modify (Kotlin main):**
- `stream/StreamEngine.kt` — add `VideoStreamEngine` sub-interface exposing `encoderSurface`.
- `stream/StreamViewModel.kt` — re-expose `encoderSurface`.
- `camera/CameraController.kt` + `camera/Camera2Controller.kt` — accept multiple target surfaces.
- `ui/StreamScreen.kt` — feed `[preview, encoder]` surfaces to the camera.
- `MainActivity.kt` — inject `CameraStreamEngine`.
- `app/src/main/AndroidManifest.xml` — INTERNET, RECORD_AUDIO, FGS perms + service.

**Create (Kotlin test):**
- `stream/RtmpEndpointTest.kt`, `stream/FakeRtmpStreamer.kt`, `stream/CameraStreamEngineTest.kt`.

---

## Task 1: RTMP endpoint parsing (pure Kotlin, TDD)

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/stream/RtmpEndpoint.kt`
- Test: `app/src/test/java/com/example/plohoystream/stream/RtmpEndpointTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.example.plohoystream.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class RtmpEndpointTest {
    @Test fun parsesHostAppAndKey() {
        val e = RtmpEndpoint.parse("rtmp://live.twitch.tv/app", "live_123_abc")
        assertEquals("live.twitch.tv", e.host)
        assertEquals("app", e.app)
        assertEquals(1935, e.port)
        assertEquals("live_123_abc", e.streamKey)
        assertEquals("rtmp://live.twitch.tv/app", e.tcUrl)
    }

    @Test fun parsesExplicitPort() {
        val e = RtmpEndpoint.parse("rtmp://127.0.0.1:1936/live", "k")
        assertEquals("127.0.0.1", e.host)
        assertEquals(1936, e.port)
        assertEquals("live", e.app)
    }

    @Test fun trimsTrailingSlashOnApp() {
        val e = RtmpEndpoint.parse("rtmp://a.b/app/", "k")
        assertEquals("app", e.app)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonRtmpScheme() {
        RtmpEndpoint.parse("https://x/y", "k")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.plohoystream.stream.RtmpEndpointTest'`
Expected: FAIL — `Unresolved reference 'RtmpEndpoint'`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.example.plohoystream.stream

/** Egress endpoint parsed from an `rtmp://host[:port]/app` URL plus a stream key. */
data class RtmpEndpoint(
    val host: String,
    val port: Int,
    val app: String,
    val streamKey: String,
) {
    val tcUrl: String get() = "rtmp://$host:$port/$app".let {
        // Twitch et al. accept tcUrl without the default port; keep it canonical without :1935.
        if (port == 1935) "rtmp://$host/$app" else it
    }

    companion object {
        fun parse(url: String, streamKey: String): RtmpEndpoint {
            require(url.startsWith("rtmp://")) { "Only rtmp:// URLs are supported: $url" }
            val rest = url.removePrefix("rtmp://").trim('/')
            val slash = rest.indexOf('/')
            require(slash > 0) { "URL must include an app path: $url" }
            val authority = rest.substring(0, slash)
            val app = rest.substring(slash + 1).trim('/')
            require(app.isNotEmpty()) { "URL must include an app path: $url" }
            val host: String
            val port: Int
            val colon = authority.indexOf(':')
            if (colon >= 0) {
                host = authority.substring(0, colon)
                port = authority.substring(colon + 1).toIntOrNull()
                    ?: throw IllegalArgumentException("Bad port in $url")
            } else {
                host = authority; port = 1935
            }
            require(host.isNotEmpty()) { "URL must include a host: $url" }
            return RtmpEndpoint(host, port, app, streamKey)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.plohoystream.stream.RtmpEndpointTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/stream/RtmpEndpoint.kt app/src/test/java/com/example/plohoystream/stream/RtmpEndpointTest.kt
git commit -m "feat(m1b3): parse rtmp:// endpoint into host/port/app/key (TDD)"
```

---

## Task 2: Native bounded media queue + StreamSession (C++, GoogleTest, TDD)

**Files:**
- Create: `app/src/main/cpp/core/media_queue.h`, `core/stream_session.h`, `core/stream_session.cpp`
- Create: `app/src/main/cpp/test/test_media_queue.cpp`, `test/test_stream_session.cpp`
- Modify: `core/CMakeLists.txt`, `test/CMakeLists.txt`

### 2a: Bounded drop-oldest queue

- [ ] **Step 1: Write the failing test** — `test/test_media_queue.cpp`

```cpp
#include <gtest/gtest.h>
#include "media_queue.h"
using namespace ps;

TEST(MediaQueue, PushPopFifo) {
    MediaQueue q(8);
    q.Push(MediaItem{MediaItem::Video, {1}, false, 0, 0});
    q.Push(MediaItem{MediaItem::Video, {2}, false, 1, 1});
    MediaItem out;
    ASSERT_TRUE(q.Pop(out)); EXPECT_EQ(out.data[0], 1);
    ASSERT_TRUE(q.Pop(out)); EXPECT_EQ(out.data[0], 2);
}

TEST(MediaQueue, DropsOldestWhenFull) {
    MediaQueue q(2);
    q.Push(MediaItem{MediaItem::Video, {1}, false, 0, 0});
    q.Push(MediaItem{MediaItem::Video, {2}, false, 0, 0});
    q.Push(MediaItem{MediaItem::Video, {3}, false, 0, 0}); // evicts {1}
    EXPECT_EQ(q.dropped(), 1u);
    MediaItem out;
    ASSERT_TRUE(q.Pop(out)); EXPECT_EQ(out.data[0], 2);
    ASSERT_TRUE(q.Pop(out)); EXPECT_EQ(out.data[0], 3);
}

TEST(MediaQueue, CloseUnblocksPop) {
    MediaQueue q(2);
    q.Close();
    MediaItem out;
    EXPECT_FALSE(q.Pop(out)); // closed + empty -> false, no hang
}
```

- [ ] **Step 2: Run to verify it fails** — see Task 2 build commands below; expect compile failure (`media_queue.h` missing).

- [ ] **Step 3: Implement** — `core/media_queue.h`

```cpp
#pragma once
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <vector>
namespace ps {

struct MediaItem {
    enum Kind { Video, Audio, VideoConfig, AudioConfig };
    Kind kind;
    std::vector<uint8_t> data;   // annexb (video) / raw aac (audio) / csd (video config)
    bool keyframe = false;       // video only
    uint32_t ptsMs = 0;
    uint32_t dtsMs = 0;
    int sampleRate = 0;          // audio config only
    int channels = 0;            // audio config only
};

// Thread-safe bounded queue. When full, Push evicts the oldest item (drop-oldest keeps
// latency bounded on a slow uplink). Pop blocks until an item is available or Close().
class MediaQueue {
public:
    explicit MediaQueue(size_t capacity) : cap_(capacity) {}

    void Push(MediaItem item) {
        std::unique_lock<std::mutex> lk(m_);
        if (q_.size() >= cap_) { q_.pop_front(); ++dropped_; }
        q_.push_back(std::move(item));
        cv_.notify_one();
    }

    bool Pop(MediaItem& out) {
        std::unique_lock<std::mutex> lk(m_);
        cv_.wait(lk, [&] { return closed_ || !q_.empty(); });
        if (q_.empty()) return false;     // closed + drained
        out = std::move(q_.front());
        q_.pop_front();
        return true;
    }

    void Close() {
        std::unique_lock<std::mutex> lk(m_);
        closed_ = true;
        cv_.notify_all();
    }

    uint64_t dropped() const {
        std::unique_lock<std::mutex> lk(m_);
        return dropped_;
    }

private:
    mutable std::mutex m_;
    std::condition_variable cv_;
    std::deque<MediaItem> q_;
    size_t cap_;
    bool closed_ = false;
    uint64_t dropped_ = 0;
};
}
```

### 2b: StreamSession

- [ ] **Step 4: Write the failing test** — `test/test_stream_session.cpp`

Uses the existing `StubTransport` (`core/stub_transport.h`): `FeedIncoming(bytes)` queues server→client bytes returned by `Read`; `written()` returns everything `Write`-n; `clear()` resets writes.

**Critical sequencing for this test (differs from `test_rtmp_client.cpp`):** that file drives the client with direct `c.OnBytes(...)` calls (helpers `MakePublishStart()` / `ForcePublishing()`); `StreamSession` instead pulls bytes via `transport->Read()` on its egress thread. So `PreloadPublishHandshake(stub)` must `FeedIncoming` the **exact same canned byte sequence** those helpers use (S0S1S2 → connect `_result` → createStream `_result` → onStatus publish-start) **all before `Start()`**. Reason: `StreamSession::run()` treats a `Read()` returning `≤ 0` as fatal (correct for a real blocking socket), and `StubTransport::Read` returns `0` once drained — so if the bytes aren't all queued up front, the session errors before reaching Publishing. With everything preloaded (≪ 8192 bytes), a single `Read` delivers them, one `OnBytes` advances the client straight to Publishing, and the loop exits before a second (empty) read. Extract the canned bytes by copying the body of `MakePublishStart`/`ForcePublishing` from `test_rtmp_client.cpp` and routing them through `FeedIncoming` instead of `OnBytes`.

```cpp
#include <gtest/gtest.h>
#include <thread>
#include <chrono>
#include "stream_session.h"
#include "stub_transport.h"
using namespace ps;

// Minimal happy-path: once the stub feeds a server sequence that drives RtmpClient to
// Publishing, the session reports Live and SendVideo produces socket writes.
TEST(StreamSession, ReachesLiveAndWritesVideo) {
    // The factory hands the session an owned StubTransport; keep a raw pointer to inspect
    // writes after the move. Preload ALL canned publish-handshake bytes before Start().
    auto owned = std::make_unique<StubTransport>();
    StubTransport* stub = owned.get();
    PreloadPublishHandshake(*stub);  // <-- FeedIncoming the bytes copied from test_rtmp_client.cpp

    StreamParams p; p.host = "h"; p.app = "app"; p.streamKey = "k"; p.tcUrl = "rtmp://h/app";
    StreamSession s(p, [t = std::move(owned)]() mutable { return std::move(t); });
    s.Start();

    // spin until Live or timeout
    for (int i = 0; i < 200 && s.state() != SessionState::Live; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    ASSERT_EQ(s.state(), SessionState::Live);

    size_t before = stub->written().size();
    // Single csd blob with an SPS (nal type 7) and PPS (type 8) so SplitSpsPps finds both.
    s.SendVideoConfig({0,0,0,1, 0x67,0x42,0x00,0x1e, 0,0,0,1, 0x68,0xce,0x3c,0x80});
    s.SendVideo({0,0,0,1, 0x65, 0x88}, true, 0, 0);   // fake IDR annexb
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    EXPECT_GT(stub->written().size(), before);

    s.Stop();
    EXPECT_EQ(s.state(), SessionState::Idle);
}
```

> **Implementer note:** `StreamSession`'s constructor takes a transport factory so the test can inject `StubTransport` while production injects a `TcpTransport`. If wiring a reference-returning factory is awkward with the existing `StubTransport`, use `std::function<std::unique_ptr<Transport>()>` and have the test return a `std::unique_ptr<StubTransport>` whose written-bytes you inspect via a raw pointer captured before the move. Keep the seam; adjust the mechanics to the real stub.

- [ ] **Step 5: Run to verify it fails** — compile failure (`stream_session.h` missing).

- [ ] **Step 6: Implement** — `core/stream_session.h`

```cpp
#pragma once
#include <atomic>
#include <functional>
#include <memory>
#include <thread>
#include "media_queue.h"
#include "rtmp_client.h"
#include "transport.h"
namespace ps {

enum class SessionState { Idle, Connecting, Live, Error };

// Owns a transport + RtmpClient on a dedicated egress thread. Public methods are
// thread-safe (enqueue onto MediaQueue / atomics). The egress thread runs the proven
// harness loop: Begin -> read/OnBytes until Publishing -> drain queue -> Send*.
class StreamSession {
public:
    using TransportFactory = std::function<std::unique_ptr<Transport>()>;

    StreamSession(StreamParams params, TransportFactory factory)
        : params_(std::move(params)), factory_(std::move(factory)), queue_(256) {}
    ~StreamSession() { Stop(); }

    void Start();   // spawns egress thread; returns immediately
    void Stop();    // closes queue, joins thread, closes transport

    SessionState state() const { return state_.load(); }

    void SendVideoConfig(const Bytes& csd);  // raw SPS+PPS annexb blob; split natively
    void SendVideo(const Bytes& annexb, bool key, uint32_t ptsMs, uint32_t dtsMs);
    void SendAudioConfig(int sampleRate, int channels);
    void SendAudio(const Bytes& aacRaw, uint32_t ptsMs);

private:
    void run();     // egress thread body

    StreamParams params_;
    TransportFactory factory_;
    MediaQueue queue_;
    std::thread thread_;
    std::atomic<SessionState> state_{SessionState::Idle};
    std::atomic<bool> running_{false};
};
}
```

- [ ] **Step 7: Implement** — `core/stream_session.cpp`

```cpp
#include "stream_session.h"
#include "flv.h"
namespace ps {

void StreamSession::Start() {
    if (running_.exchange(true)) return;
    state_ = SessionState::Connecting;
    thread_ = std::thread([this] { run(); });
}

void StreamSession::Stop() {
    if (!running_.exchange(false)) { if (thread_.joinable()) thread_.join(); return; }
    queue_.Close();
    if (thread_.joinable()) thread_.join();
    state_ = SessionState::Idle;
}

void StreamSession::SendVideoConfig(const Bytes& csd) {
    queue_.Push(MediaItem{MediaItem::VideoConfig, csd, false, 0, 0});
}
void StreamSession::SendVideo(const Bytes& annexb, bool key, uint32_t pts, uint32_t dts) {
    MediaItem it{MediaItem::Video, annexb, key, pts, dts}; queue_.Push(std::move(it));
}
void StreamSession::SendAudioConfig(int sampleRate, int channels) {
    MediaItem it{MediaItem::AudioConfig, {}, false, 0, 0}; it.sampleRate = sampleRate; it.channels = channels;
    queue_.Push(std::move(it));
}
void StreamSession::SendAudio(const Bytes& aac, uint32_t pts) {
    queue_.Push(MediaItem{MediaItem::Audio, aac, false, pts, 0});
}

void StreamSession::run() {
    auto transport = factory_();
    if (!transport || !transport->Connect(params_.host, params_.port)) {
        state_ = SessionState::Error; running_ = false; return;
    }
    RtmpClient client(*transport, params_);
    client.Begin();

    uint8_t buf[8192];
    while (running_.load() &&
           client.state() != RtmpState::Publishing &&
           client.state() != RtmpState::Error) {
        int n = transport->Read(buf, sizeof(buf));
        if (n <= 0) { state_ = SessionState::Error; running_ = false; return; }
        client.OnBytes(Bytes(buf, buf + n));
    }
    if (client.state() != RtmpState::Publishing) {
        state_ = SessionState::Error; running_ = false; return;
    }
    state_ = SessionState::Live;

    MediaItem item;
    while (queue_.Pop(item)) {
        switch (item.kind) {
            case MediaItem::VideoConfig: {
                Bytes sps, pps; SplitSpsPps(item.data, sps, pps);
                if (!sps.empty() && !pps.empty()) client.SendVideoConfig(sps, pps);
                break;
            }
            case MediaItem::Video:
                client.SendVideo(item.data, item.keyframe, item.ptsMs, item.dtsMs);
                break;
            case MediaItem::AudioConfig:
                client.SendAudioConfig(item.sampleRate, item.channels);
                break;
            case MediaItem::Audio:
                client.SendAudio(item.data, item.ptsMs);
                break;
        }
        if (!transport->connected()) { state_ = SessionState::Error; break; }
    }
    transport->Close();
}
}
```

- [ ] **Step 8: Wire CMake** — append to `core/CMakeLists.txt` source list: `stream_session.cpp`. Append to `test/CMakeLists.txt` the two new test files (mirror how existing tests are added there).

- [ ] **Step 9: Build + run host tests**

```bash
cd app/src/main/cpp && cmake -S test -B build-test && cmake --build build-test && ctest --test-dir build-test --output-on-failure
```
Expected: all existing 46 tests still pass + new MediaQueue (3) and StreamSession (1) tests pass.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/cpp/core/media_queue.h app/src/main/cpp/core/stream_session.* app/src/main/cpp/core/CMakeLists.txt app/src/main/cpp/test/test_media_queue.cpp app/src/main/cpp/test/test_stream_session.cpp app/src/main/cpp/test/CMakeLists.txt
git commit -m "feat(m1b3): native StreamSession + bounded media queue (egress thread, TDD)"
```

---

## Task 3: JNI bridge

**Files:**
- Modify: `app/src/main/cpp/native-lib.cpp`

- [ ] **Step 1: Implement the bridge** (append below the existing `stringFromJNI`)

Handle-based: `nativeCreate` returns a `jlong` owning pointer; all other calls take it back. `byte[]` is copied into a `Bytes` via `GetByteArrayRegion`.

```cpp
#include <jni.h>
#include <vector>
#include "core/stream_session.h"
#include "core/tcp_transport.h"
using namespace ps;

namespace {
std::vector<uint8_t> ToBytes(JNIEnv* env, jbyteArray arr) {
    if (!arr) return {};
    jsize n = env->GetArrayLength(arr);
    std::vector<uint8_t> out(static_cast<size_t>(n));
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte*>(out.data()));
    return out;
}
std::string ToStr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}
StreamSession* Self(jlong h) { return reinterpret_cast<StreamSession*>(h); }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeCreate(
        JNIEnv* env, jobject, jstring host, jstring app, jstring key, jstring tcUrl,
        jint port, jint width, jint height, jint fps, jint sampleRate) {
    StreamParams p;
    p.host = ToStr(env, host); p.app = ToStr(env, app);
    p.streamKey = ToStr(env, key); p.tcUrl = ToStr(env, tcUrl);
    p.port = static_cast<uint16_t>(port);
    p.width = width; p.height = height; p.fps = static_cast<double>(fps);
    p.sampleRate = sampleRate;
    auto* s = new StreamSession(p, [] { return std::unique_ptr<Transport>(new TcpTransport()); });
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeStart(JNIEnv*, jobject, jlong h) {
    if (h) Self(h)->Start();
}

JNIEXPORT jint JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeState(JNIEnv*, jobject, jlong h) {
    return h ? static_cast<jint>(Self(h)->state()) : 0; // 0=Idle,1=Connecting,2=Live,3=Error
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeSendVideoConfig(
        JNIEnv* env, jobject, jlong h, jbyteArray csd) {
    if (h) Self(h)->SendVideoConfig(ToBytes(env, csd));
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeSendVideo(
        JNIEnv* env, jobject, jlong h, jbyteArray annexb, jboolean key, jlong pts, jlong dts) {
    if (h) Self(h)->SendVideo(ToBytes(env, annexb), key,
                              static_cast<uint32_t>(pts), static_cast<uint32_t>(dts));
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeSendAudioConfig(
        JNIEnv*, jobject, jlong h, jint sampleRate, jint channels) {
    if (h) Self(h)->SendAudioConfig(sampleRate, channels);
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeSendAudio(
        JNIEnv* env, jobject, jlong h, jbyteArray aac, jlong pts) {
    if (h) Self(h)->SendAudio(ToBytes(env, aac), static_cast<uint32_t>(pts));
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeStop(JNIEnv*, jobject, jlong h) {
    if (h) Self(h)->Stop();
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeDestroy(JNIEnv*, jobject, jlong h) {
    delete Self(h);
}

} // extern "C"
```

> The existing `stringFromJNI` references `MainActivity`; leave it. Keep `#include <jni.h>` once.

- [ ] **Step 2: Verify it compiles for Android**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (native compiles for all ABIs; JNI symbol names will be validated at runtime in Task 4+).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/cpp/native-lib.cpp
git commit -m "feat(m1b3): JNI bridge for StreamSession (handle-based create/start/send*/stop)"
```

---

## Task 4: Kotlin RtmpStreamer seam (interface + native impl + fake)

**Files:**
- Create: `stream/RtmpStreamer.kt`, `stream/NativeRtmpStreamer.kt`
- Create (test): `stream/FakeRtmpStreamer.kt`

- [ ] **Step 1: Interface** — `stream/RtmpStreamer.kt`

```kotlin
package com.example.plohoystream.stream

/** The egress seam the engine drives. Native impl crosses JNI; fake impl backs unit tests. */
interface RtmpStreamer {
    /** Native session state: 0=Idle, 1=Connecting, 2=Live, 3=Error. */
    fun start(endpoint: RtmpEndpoint, width: Int, height: Int, fps: Int, sampleRate: Int)
    fun state(): Int
    fun sendVideoConfig(csd: ByteArray)
    fun sendVideo(annexb: ByteArray, keyframe: Boolean, ptsMs: Long, dtsMs: Long)
    fun sendAudioConfig(sampleRate: Int, channels: Int)
    fun sendAudio(aac: ByteArray, ptsMs: Long)
    fun stop()
}
```

- [ ] **Step 2: Native impl** — `stream/NativeRtmpStreamer.kt`

```kotlin
package com.example.plohoystream.stream

/** JNI-backed [RtmpStreamer]. One instance == one native StreamSession handle. */
class NativeRtmpStreamer : RtmpStreamer {
    private var handle: Long = 0L

    override fun start(endpoint: RtmpEndpoint, width: Int, height: Int, fps: Int, sampleRate: Int) {
        if (handle == 0L) {
            handle = nativeCreate(
                endpoint.host, endpoint.app, endpoint.streamKey, endpoint.tcUrl,
                endpoint.port, width, height, fps, sampleRate,
            )
        }
        nativeStart(handle)
    }

    override fun state(): Int = if (handle != 0L) nativeState(handle) else 0
    override fun sendVideoConfig(csd: ByteArray) { if (handle != 0L) nativeSendVideoConfig(handle, csd) }
    override fun sendVideo(annexb: ByteArray, keyframe: Boolean, ptsMs: Long, dtsMs: Long) {
        if (handle != 0L) nativeSendVideo(handle, annexb, keyframe, ptsMs, dtsMs)
    }
    override fun sendAudioConfig(sampleRate: Int, channels: Int) {
        if (handle != 0L) nativeSendAudioConfig(handle, sampleRate, channels)
    }
    override fun sendAudio(aac: ByteArray, ptsMs: Long) { if (handle != 0L) nativeSendAudio(handle, aac, ptsMs) }

    override fun stop() {
        if (handle != 0L) {
            nativeStop(handle)
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(
        host: String, app: String, key: String, tcUrl: String,
        port: Int, width: Int, height: Int, fps: Int, sampleRate: Int,
    ): Long
    private external fun nativeStart(handle: Long)
    private external fun nativeState(handle: Long): Int
    private external fun nativeSendVideoConfig(handle: Long, csd: ByteArray)
    private external fun nativeSendVideo(handle: Long, annexb: ByteArray, keyframe: Boolean, ptsMs: Long, dtsMs: Long)
    private external fun nativeSendAudioConfig(handle: Long, sampleRate: Int, channels: Int)
    private external fun nativeSendAudio(handle: Long, aac: ByteArray, ptsMs: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeDestroy(handle: Long)

    companion object {
        init { System.loadLibrary("plohoystream") }
    }
}
```

- [ ] **Step 3: Fake** — `app/src/test/java/com/example/plohoystream/stream/FakeRtmpStreamer.kt`

```kotlin
package com.example.plohoystream.stream

/** In-memory [RtmpStreamer] for engine unit tests. Drives state manually. */
class FakeRtmpStreamer : RtmpStreamer {
    var started = false; private set
    var stopped = false; private set
    var videoConfigCount = 0; private set
    var videoCount = 0; private set
    var audioConfigCount = 0; private set
    var audioCount = 0; private set
    private var state = 0

    fun emitState(s: Int) { state = s }

    override fun start(endpoint: RtmpEndpoint, width: Int, height: Int, fps: Int, sampleRate: Int) {
        started = true; state = 1 // Connecting
    }
    override fun state(): Int = state
    override fun sendVideoConfig(csd: ByteArray) { videoConfigCount++ }
    override fun sendVideo(annexb: ByteArray, keyframe: Boolean, ptsMs: Long, dtsMs: Long) { videoCount++ }
    override fun sendAudioConfig(sampleRate: Int, channels: Int) { audioConfigCount++ }
    override fun sendAudio(aac: ByteArray, ptsMs: Long) { audioCount++ }
    override fun stop() { stopped = true; state = 0 }
}
```

- [ ] **Step 4: Build (compile check; no behavior yet)**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.plohoystream.stream.RtmpEndpointTest'` (proves the new files compile alongside existing tests).
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/stream/RtmpStreamer.kt app/src/main/java/com/example/plohoystream/stream/NativeRtmpStreamer.kt app/src/test/java/com/example/plohoystream/stream/FakeRtmpStreamer.kt
git commit -m "feat(m1b3): RtmpStreamer seam (interface + JNI impl + fake)"
```

---

## Task 5: VideoEncoder (MediaCodec H.264, surface input)

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/stream/VideoEncoder.kt`

Verified on-device (MediaCodec is not host-JVM testable). The encoder exposes an input `Surface` for the camera and pushes encoded output to callbacks.

- [ ] **Step 1: Implement**

```kotlin
package com.example.plohoystream.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * Surface-input H.264 encoder. Camera2 renders into [inputSurface]; encoded Annex-B output
 * is delivered via callbacks: [onConfig] once (SPS+PPS csd blob), then [onFrame] per frame.
 * Timestamps are milliseconds derived from the surface frame PTS.
 */
class VideoEncoder(
    width: Int,
    height: Int,
    fps: Int,
    bitRate: Int,
    private val onConfig: (csd: ByteArray) -> Unit,
    private val onFrame: (annexb: ByteArray, keyframe: Boolean, ptsMs: Long) -> Unit,
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    val inputSurface: Surface
    private val thread = HandlerThread("VideoEnc").apply { start() }
    private val handler = Handler(thread.looper)

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, index: Int) {} // surface input
            override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val buf = c.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset); buf.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        onConfig(bytes)
                    } else {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        onFrame(bytes, key, info.presentationTimeUs / 1000)
                    }
                }
                c.releaseOutputBuffer(index, false)
            }
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {}
            override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {}
        }, handler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    fun start() = codec.start()

    fun stop() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        inputSurface.release()
        thread.quitSafely()
    }
}
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/stream/VideoEncoder.kt
git commit -m "feat(m1b3): MediaCodec H.264 surface-input VideoEncoder"
```

---

## Task 6: AudioEncoder (AudioRecord → MediaCodec AAC)

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/stream/AudioEncoder.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.example.plohoystream.stream

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import kotlin.concurrent.thread

/**
 * Microphone → AAC-LC encoder. Emits raw AAC frames (no ADTS) via [onFrame]; the ASC is
 * built natively from [sampleRate]/[channels] (RtmpClient.SendAudioConfig), so no codec-config
 * blob is forwarded here. Permission RECORD_AUDIO is gated by the UI before construction.
 */
class AudioEncoder(
    private val sampleRate: Int = 44100,
    private val channels: Int = 2,
    bitRate: Int = 128_000,
    private val onFrame: (aac: ByteArray, ptsMs: Long) -> Unit,
) {
    private val channelMask =
        if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
    private val minBuf = AudioRecord.getMinBufferSize(
        sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT,
    ).coerceAtLeast(4096)

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private lateinit var record: AudioRecord
    @Volatile private var running = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO gated by UI
    fun start() {
        record = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate, channelMask,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * 2,
        )
        codec.start()
        record.startRecording()
        running = true
        thread(name = "AudioEnc-feed") { feedLoop() }
        thread(name = "AudioEnc-drain") { drainLoop() }
    }

    private fun feedLoop() {
        val pcm = ByteArray(minBuf)
        while (running) {
            val read = record.read(pcm, 0, pcm.size)
            if (read <= 0) continue
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val ib = codec.getInputBuffer(idx) ?: continue
                ib.clear(); ib.put(pcm, 0, read)
                val ptsUs = System.nanoTime() / 1000
                codec.queueInputBuffer(idx, 0, read, ptsUs, 0)
            }
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = codec.dequeueOutputBuffer(info, 10_000)
            if (idx >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val ob = codec.getOutputBuffer(idx)
                    if (ob != null) {
                        val bytes = ByteArray(info.size)
                        ob.position(info.offset); ob.get(bytes)
                        onFrame(bytes, info.presentationTimeUs / 1000)
                    }
                }
                codec.releaseOutputBuffer(idx, false)
            }
        }
    }

    fun audioConfig(): Pair<Int, Int> = sampleRate to channels

    fun stop() {
        running = false
        runCatching { record.stop(); record.release() }
        runCatching { codec.stop(); codec.release() }
    }
}
```

> **Note on audio PTS:** this slice timestamps audio off `System.nanoTime()` and video off the surface PTS, so A/V sync is approximate. Tightening to a shared monotonic clock is an M2 polish item; for the first-stream acceptance gate, approximate sync is acceptable. Document this in the commit.

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/stream/AudioEncoder.kt
git commit -m "feat(m1b3): AudioRecord->MediaCodec AAC AudioEncoder (approx A/V sync, M2 tightens)"
```

---

## Task 7: Multi-surface camera (preview + encoder)

**Files:**
- Modify: `camera/CameraController.kt`, `camera/Camera2Controller.kt`

The camera must render into the preview surface AND the encoder input surface simultaneously.

- [ ] **Step 1: Widen the interface** — `camera/CameraController.kt`

Change `start` to take a list of targets:

```kotlin
package com.example.plohoystream.camera

import android.view.Surface

interface CameraController {
    /** Open [config]'s camera and stream into every surface in [targets] (preview, encoder, …). */
    fun start(config: CameraConfig, targets: List<Surface>)
    fun stop()
    fun setZoom(ratio: Float)
    fun setLens(lens: CameraLens) = setZoom(lens.zoomRatio)
}
```

- [ ] **Step 2: Update Camera2Controller** — in `camera/Camera2Controller.kt`:
  - Replace the single `surface: Surface?` field with `targets: List<Surface> = emptyList()`.
  - `start(config, targets)` stores `this.targets = targets`.
  - `configureSession`: build `targets.map { OutputConfiguration(it) }` for the `SessionConfiguration`, and `targets.forEach { builder.addTarget(it) }` on the request builder. Guard `if (targets.isEmpty()) return`.
  - `closeSession` clears `targets`.

```kotlin
// field
private var targets: List<Surface> = emptyList()

// in start { ... }
this.targets = targets

// configureSession
val outputs = targets.map { OutputConfiguration(it) }
if (outputs.isEmpty()) return
val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
    targets.forEach { addTarget(it) }
    set(CaptureRequest.CONTROL_AF_MODE, CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
    set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom)
}
requestBuilder = builder
val sessionConfig = SessionConfiguration(
    SessionConfiguration.SESSION_REGULAR, outputs, executor, /* same StateCallback */ ...)
camera.createCaptureSession(sessionConfig)
```

> Keep everything else (HandlerThread, zoom, deviceCallback, idempotent restart) identical. The only change is single-surface → list-of-surfaces.

- [ ] **Step 3: Build to verify it compiles** (callers in `StreamScreen` break — fixed in Task 9, but compile the module to catch signature errors now)

Run: `./gradlew :app:assembleDebug 2>&1 | tail -20`
Expected: failure ONLY in `StreamScreen.kt` at the `controller.start(...)` call (now needs a list). That confirms the signature changed; Task 9 updates the caller. (If you prefer green-at-every-step, do Task 7 + Task 9 as one commit.)

- [ ] **Step 4: Commit** (together with Task 9, or stage now and finish in Task 9)

```bash
git add app/src/main/java/com/example/plohoystream/camera/CameraController.kt app/src/main/java/com/example/plohoystream/camera/Camera2Controller.kt
git commit -m "feat(m1b3): camera renders into multiple surfaces (preview + encoder)"
```

---

## Task 8: CameraStreamEngine (orchestration) — TDD the pure parts

**Files:**
- Modify: `stream/StreamEngine.kt` (add sub-interface)
- Create: `stream/CameraStreamEngine.kt`
- Create (test): `stream/CameraStreamEngineTest.kt`

### 8a: VideoStreamEngine sub-interface

- [ ] **Step 1:** append to `stream/StreamEngine.kt`:

```kotlin
import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/**
 * A [StreamEngine] that also produces an encoder input [Surface] when live, so the camera
 * can render into the H.264 encoder. Null when not streaming.
 */
interface VideoStreamEngine : StreamEngine {
    val encoderSurface: StateFlow<Surface?>
}
```

### 8b: The engine

The engine owns: `RtmpStreamer`, `VideoEncoder`, `AudioEncoder`, a polling job mapping native state → `StreamState`, and the `encoderSurface` flow. It is constructed with factories so tests inject fakes and skip Android media classes.

- [ ] **Step 2: Write the failing test** — `app/src/test/java/com/example/plohoystream/stream/CameraStreamEngineTest.kt`

```kotlin
package com.example.plohoystream.stream

import com.example.plohoystream.MainDispatcherRule
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CameraStreamEngineTest {
    @get:Rule val mainRule = MainDispatcherRule()

    private fun engine(streamer: FakeRtmpStreamer) = CameraStreamEngine(
        streamerFactory = { streamer },
        // media factories return no-op doubles so no Android classes are touched in unit tests
        startMedia = {},
        stopMedia = {},
        pollIntervalMs = 100,
    )

    @Test fun start_movesToConnecting_thenLiveWhenNativeReports() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://live.twitch.tv/app", "key"))
        runCurrent()
        assertTrue(e.state.value is StreamState.Connecting)
        streamer.emitState(2) // Live
        advanceTimeBy(150); runCurrent()
        assertEquals(StreamState.Live, e.state.value)
    }

    @Test fun start_withBadUrl_goesToError() = runTest {
        val e = engine(FakeRtmpStreamer())
        e.start(StreamConfig("not-a-url", "key"))
        runCurrent()
        assertTrue(e.state.value is StreamState.Error)
    }

    @Test fun nativeError_surfacesAsError() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        streamer.emitState(3) // Error
        advanceTimeBy(150); runCurrent()
        assertTrue(e.state.value is StreamState.Error)
    }

    @Test fun stop_returnsToIdle_andStopsStreamer() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        e.stop(); runCurrent()
        assertEquals(StreamState.Idle, e.state.value)
        assertTrue(streamer.stopped)
    }
}
```

- [ ] **Step 3: Run to verify it fails** — `Unresolved reference 'CameraStreamEngine'`.

- [ ] **Step 4: Implement** — `stream/CameraStreamEngine.kt`

The Android media wiring (encoders, surface) is injected via `startMedia`/`stopMedia` lambdas so the unit test exercises only the state machine. The real assembly (in Task 9's `MainActivity`) passes lambdas that build the encoders and publish the encoder surface.

```kotlin
package com.example.plohoystream.stream

import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Real [StreamEngine]: parses the endpoint, starts the native session via [RtmpStreamer],
 * and polls native state into [state]. Android media setup is injected via [startMedia]/
 * [stopMedia] so the orchestration is unit-tested with fakes.
 */
class CameraStreamEngine(
    private val streamerFactory: () -> RtmpStreamer,
    private val startMedia: (RtmpStreamer) -> Unit,
    private val stopMedia: () -> Unit,
    private val pollIntervalMs: Long = 250,
    private val width: Int = 1920,
    private val height: Int = 1080,
    private val fps: Int = 30,
    private val sampleRate: Int = 44100,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : VideoStreamEngine {

    private val _state = MutableStateFlow<StreamState>(StreamState.Idle)
    override val state: StateFlow<StreamState> = _state.asStateFlow()

    private val _encoderSurface = MutableStateFlow<Surface?>(null)
    override val encoderSurface: StateFlow<Surface?> = _encoderSurface.asStateFlow()

    private var streamer: RtmpStreamer? = null
    private var pollJob: Job? = null

    // The media lambda may publish the encoder surface back through this hook.
    internal fun publishEncoderSurface(s: Surface?) { _encoderSurface.value = s }

    override fun start(config: StreamConfig) {
        val endpoint = runCatching { RtmpEndpoint.parse(config.rtmpUrl, config.streamKey) }
            .getOrElse { _state.value = StreamState.Error(it.message ?: "Bad URL"); return }

        _state.value = StreamState.Connecting
        val s = streamerFactory().also { streamer = it }
        s.start(endpoint, width, height, fps, sampleRate)
        startMedia(s)

        pollJob = scope.launch {
            while (true) {
                when (s.state()) {
                    2 -> _state.value = StreamState.Live
                    3 -> { _state.value = StreamState.Error("Stream rejected"); break }
                    0 -> { _state.value = StreamState.Idle; break }
                }
                delay(pollIntervalMs)
            }
        }
    }

    override fun stop() {
        _state.value = StreamState.Stopping
        pollJob?.cancel(); pollJob = null
        stopMedia()
        _encoderSurface.value = null
        streamer?.stop(); streamer = null
        _state.value = StreamState.Idle
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'com.example.plohoystream.stream.CameraStreamEngineTest'`
Expected: PASS (4 tests). (Note: the test's `startMedia`/`stopMedia` are `{}` no-ops; the `Surface` import is unused in the test path but compiles.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/stream/StreamEngine.kt app/src/main/java/com/example/plohoystream/stream/CameraStreamEngine.kt app/src/test/java/com/example/plohoystream/stream/CameraStreamEngineTest.kt
git commit -m "feat(m1b3): CameraStreamEngine state machine over RtmpStreamer (TDD)"
```

---

## Task 9: Wire it together (ViewModel + Viewfinder + MainActivity)

**Files:**
- Modify: `stream/StreamViewModel.kt`, `ui/StreamScreen.kt`, `MainActivity.kt`

- [ ] **Step 1: ViewModel re-exposes the encoder surface** — `stream/StreamViewModel.kt`

Add a passthrough so the Viewfinder can collect it without knowing the concrete engine:

```kotlin
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// inside StreamViewModel:
val encoderSurface: StateFlow<Surface?> =
    (engine as? VideoStreamEngine)?.encoderSurface
        ?: MutableStateFlow<Surface?>(null) // FakeStreamEngine path: never produces one
```

> If `engine` is `private val` already, reuse it; otherwise read it once in `init`. Keep the existing `uiState`, `setUrl`, `setKey`, `goLive`, `stop` untouched.

- [ ] **Step 2: Viewfinder feeds both surfaces to the camera** — `ui/StreamScreen.kt`

In `Viewfinder`, collect the encoder surface and build the target list:

```kotlin
val encoderSurface by viewModel.encoderSurface.collectAsStateWithLifecycle()

// replace the single-surface LaunchedEffect with:
LaunchedEffect(config, surface, encoderSurface) {
    val c = config; val preview = surface
    if (c != null && preview != null) {
        val targets = listOfNotNull(preview, encoderSurface)
        controller.start(c, targets)
        controller.setZoom(zoom)
    }
}
```

> When `goLive` fires, the engine's `startMedia` lambda (Task 9 Step 3) publishes the encoder surface → `encoderSurface` becomes non-null → this effect re-runs → camera restarts rendering into preview + encoder. On `stop`, it goes null → camera restarts preview-only.

- [ ] **Step 3: MainActivity assembles the real engine** — `MainActivity.kt`

Replace `FakeStreamEngine()` with a `CameraStreamEngine` whose media lambdas build the encoders and publish the encoder surface. RECORD_AUDIO is requested alongside CAMERA in Step 4.

```kotlin
val engine: StreamEngine = run {
    var video: VideoEncoder? = null
    var audio: AudioEncoder? = null
    lateinit var eng: CameraStreamEngine
    eng = CameraStreamEngine(
        streamerFactory = { NativeRtmpStreamer() },
        startMedia = { streamer ->
            val v = VideoEncoder(
                width = 1920, height = 1080, fps = 30, bitRate = 6_000_000,
                onConfig = { csd -> streamer.sendVideoConfig(csd) },
                onFrame = { annexb, key, pts -> streamer.sendVideo(annexb, key, pts, pts) },
            )
            val a = AudioEncoder(
                sampleRate = 44100, channels = 2,
                onFrame = { aac, pts -> streamer.sendAudio(aac, pts) },
            )
            streamer.sendAudioConfig(44100, 2)
            v.start(); a.start()
            video = v; audio = a
            eng.publishEncoderSurface(v.inputSurface)
        },
        stopMedia = {
            video?.stop(); audio?.stop(); video = null; audio = null
        },
    )
    eng
}
```

> `publishEncoderSurface` is `internal` on the engine; `MainActivity` is in the same module, so this is legal. Encoder `start()` must precede camera rendering — order is: create encoder (makes input surface) → publish surface → camera restarts into it. The camera tolerates a surface that already has a started encoder.

- [ ] **Step 4: Manifest — permissions + service** — `app/src/main/AndroidManifest.xml`

Add above `<application>`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-feature android:name="android.hardware.microphone" android:required="true" />
```

And request RECORD_AUDIO in the UI: change `StreamScreen`'s permission gate to request BOTH camera and mic via `ActivityResultContracts.RequestMultiplePermissions()`, treating `granted` as true only when both are present.

- [ ] **Step 5: Build + install + smoke (no real stream yet)**

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" -s emulator-5554 shell pm grant com.example.plohoystream android.permission.CAMERA
"$ADB" -s emulator-5554 shell pm grant com.example.plohoystream android.permission.RECORD_AUDIO
"$ADB" -s emulator-5554 shell am start -n com.example.plohoystream/.MainActivity
```
Expected: app launches, preview renders, no crash. (Going live needs a real ingest — Task 11.)

- [ ] **Step 6: Run full unit suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all prior tests + RtmpEndpoint (4) + CameraStreamEngine (4) pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/stream/StreamViewModel.kt app/src/main/java/com/example/plohoystream/ui/StreamScreen.kt app/src/main/java/com/example/plohoystream/MainActivity.kt app/src/main/AndroidManifest.xml
git commit -m "feat(m1b3): wire CameraStreamEngine end-to-end (encoder surface, mic perm, internet)"
```

---

## Task 10: Foreground service (camera|microphone keep-alive)

**Files:**
- Create: `app/src/main/java/com/example/plohoystream/service/StreamForegroundService.kt`
- Modify: `AndroidManifest.xml` (register service), `MainActivity.kt` or `StreamViewModel` (start/stop with stream)

A streaming app must run a foreground service while live so the OS permits continued camera+mic use and doesn't kill the process. For this slice the service is a keep-alive + notification; the pipeline runs in-process.

- [ ] **Step 1: Service** — `service/StreamForegroundService.kt`

```kotlin
package com.example.plohoystream.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Keeps the process foregrounded (camera|microphone) while streaming. */
class StreamForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "stream"
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(channelId, "Streaming", NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = Notification.Builder(this, channelId)
            .setContentTitle("PlohoyStream")
            .setContentText("Live")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .build()
        startForeground(
            1, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        return START_NOT_STICKY
    }

    companion object {
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, StreamForegroundService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, StreamForegroundService::class.java))
    }
}
```

- [ ] **Step 2: Register in manifest** — inside `<application>`:

```xml
<service
    android:name=".service.StreamForegroundService"
    android:exported="false"
    android:foregroundServiceType="camera|microphone" />
```

- [ ] **Step 3: Start/stop with the stream** — in `MainActivity`, wrap the engine's media lambdas (or the ViewModel's goLive/stop) to also call `StreamForegroundService.start(context)` on go-live and `.stop(context)` on stop. Simplest: call `StreamForegroundService.start(this)` just before `streamer.start(...)` happens — i.e., in the `startMedia` lambda (capture `applicationContext`), and `.stop(applicationContext)` in `stopMedia`.

```kotlin
// in startMedia lambda, first line:
StreamForegroundService.start(applicationContext)
// in stopMedia lambda, last line:
StreamForegroundService.stop(applicationContext)
```

- [ ] **Step 4: Build + install + verify the FGS starts**

```bash
./gradlew :app:installDebug
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" -s emulator-5554 shell dumpsys activity services com.example.plohoystream | grep -i foreground
```
Expected: after tapping Go Live (with a reachable ingest), a foreground service with camera|microphone type is listed. (Full go-live verified in Task 11.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/service/StreamForegroundService.kt app/src/main/AndroidManifest.xml app/src/main/java/com/example/plohoystream/MainActivity.kt
git commit -m "feat(m1b3): foreground service (camera|microphone) for live streaming"
```

---

## Task 11: End-to-end acceptance — stream to a real ingest

**Files:** none (verification).

Two options. Do (A) first (offline, deterministic), then (B) (the real goal).

- [ ] **Step 1 (A): Local ingest on the dev machine.** Start an RTMP sink and point the app at the host machine (emulator reaches the host at `10.0.2.2`):

```bash
# Terminal 1 — accept one RTMP publish and save it:
ffmpeg -y -listen 1 -i rtmp://0.0.0.0:1935/app/test -c copy /tmp/plohoy_out.flv
```
In the app: RTMP URL `rtmp://10.0.2.2/app`, key `test`, tap **Go Live**. Expected: status → Connecting → ● LIVE; ffmpeg prints incoming video+audio; after stopping, `ffprobe /tmp/plohoy_out.flv` shows an H.264 video stream and an AAC audio stream of nonzero duration.

- [ ] **Step 2 (B): Twitch.** RTMP URL `rtmp://live.twitch.tv/app` (or the nearest ingest from https://help.twitch.tv/s/twitch-ingest-recommendation), key from the Twitch dashboard. Run on a **physical device** (the emulator's virtual camera + uplink make this unreliable). Tap **Go Live**. Expected: the Twitch dashboard shows the channel live with the phone camera + mic within ~10–20 s.

- [ ] **Step 3:** Capture logcat during a live test to confirm no dropped-frame storm / encoder errors:

```bash
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" logcat -d | grep -iE 'plohoy|mediacodec|rtmp|fatal' | tail -60
```

- [ ] **Step 4 (docs):** Update `app/src/main/cpp/SMOKE_TEST.md` (or create `docs/superpowers/M1B3_SMOKE_TEST.md`) with the exact ffmpeg + Twitch steps above so the acceptance path is reproducible. Commit.

```bash
git add docs/superpowers/M1B3_SMOKE_TEST.md
git commit -m "docs(m1b3): end-to-end smoke test (local ffmpeg ingest + Twitch)"
```

---

## Self-Review

**Spec coverage (M1 first-stream slice — capture → encode → JNI → native egress → live):**
- Camera → encoder input surface → H.264 NALs → JNI → native egress → RTMP: Tasks 5, 7, 9 (video); Task 2/3 (native + JNI); Task 11 (live).
- Microphone → AAC → JNI → native egress: Tasks 6, 9, 3.
- Native egress thread + bounded queue reusing the proven `RtmpClient`: Task 2.
- Foreground service (camera|microphone): Task 10.
- `FakeStreamEngine` → `CameraStreamEngine` swap: Task 9.
- Auto-selected fixed settings (1080p30 / 6 Mbps / 44.1k stereo): encoder configs in Tasks 5/6/9.

**Placeholder scan:** No "TBD"/"add error handling" gaps. The one deliberately codebase-dependent spot — the `StreamSession` host test's `PreloadPublishHandshake` helper — is explicitly instructed to be copied from the existing `test_rtmp_client.cpp` (which already drives the client to Publishing). Deferred items (inbound RTMP servicing, graceful teardown, reconnect, tight A/V sync, HDR/HEVC) are named and assigned to M2/M1-C, consistent with M1-A's documented open items.

**Type consistency:** `RtmpEndpoint(host,port,app,streamKey,tcUrl)`, `RtmpStreamer.{start,state,sendVideoConfig,sendVideo,sendAudioConfig,sendAudio,stop}`, native `nativeXxx` signatures, `StreamSession.{Start,Stop,state,SendVideoConfig(csd),SendVideo,SendAudioConfig,SendAudio}`, `SessionState{Idle=0,Connecting=1,Live=2,Error=3}` (matches the Kotlin poll mapping), `MediaItem{kind,data,keyframe,ptsMs,dtsMs,sampleRate,channels}`, `VideoEncoder(onConfig,onFrame,inputSurface)`, `AudioEncoder(onFrame,audioConfig)`, `VideoStreamEngine.encoderSurface`, `CameraController.start(config, targets: List<Surface>)` are consistent across tasks. The native enum ordinal (0..3) matches `nativeState`'s contract and `CameraStreamEngine`'s `when`.

**Android-API notes:** `startForeground(id, n, type)` with FGS types requires the matching `FOREGROUND_SERVICE_*` perms (Task 9 manifest) — present. `Notification.Builder(context, channelId)` + channel creation is required (minSdk 35). MediaCodec async callbacks run on the provided `Handler` thread — encoder callbacks must not block; they only copy bytes and enqueue across JNI (fast). `GetByteArrayRegion` copies (safe w.r.t. JNI threading) rather than `GetByteArrayElements` pinning.

**Threading sanity:** encoder callback threads call `streamer.sendVideo/...` → JNI → `MediaQueue.Push` (mutex-guarded) — safe. The egress thread is the sole `RtmpClient` user — no shared-client races. `Stop()` closes the queue to unblock the egress thread's `Pop`, then joins.

---

## Execution Handoff

This plan makes **Go Live** actually publish camera + mic to Twitch — the first complete vertical slice through every layer (Compose → Camera2 → MediaCodec → JNI → C++ RtmpClient → TCP → ingest). After this:
- **M1-C** — codec/`Muxer` seam + HEVC enhanced-RTMP + HDR/HLG10 (the user wants HEVC+HDR; main platform Twitch, real HDR via SRT/own-server).
- **M2** — settings UI (res/bitrate/fps), auto-reconnect, graceful RTMP teardown, inbound ack/ping servicing, tight A/V sync, local recording.
- A dedicated **Moblin-fidelity UI** milestone for the viewfinder/overlays.
