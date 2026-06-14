# Reconnect & Network Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a live stream survive a flaky mobile connection — detect drops promptly, keep the RTMP control channel serviced so strict servers don't silently kill us, tear down gracefully, and auto-reconnect on transient drops.

**Architecture:** Native `StreamSession` stays a single connect-attempt unit, hardened to (a) detect write failures, (b) service the inbound control channel while publishing on its existing single egress thread (non-blocking), and (c) send a graceful unpublish sequence on stop. The Kotlin `CameraStreamEngine` owns the retry loop: on a transient **Dropped** it goes `Reconnecting`, waits 5 s, and rebuilds the whole pipeline; on a terminal **Rejected** it goes to `Error`. The existing `RECONNECTING` UI pill is wired to a new `StreamState.Reconnecting`.

**Tech Stack:** C++17 (NDK core, GoogleTest host tests), Kotlin/Coroutines (engine + ViewModel, JUnit + kotlinx-coroutines-test), Jetpack Compose (UI).

**Spec:** `docs/superpowers/specs/2026-06-14-reconnect-resilience-design.md`

**Test commands:**
- C++ host tests: `cd app/src/main/cpp && cmake -S test -B build-test >/dev/null && cmake --build build-test 2>&1 | tail -5 && ctest --test-dir build-test --output-on-failure`
- Kotlin unit tests: `./gradlew testDebugUnitTest`

---

## File Structure

**Native (C++):**
- `app/src/main/cpp/core/transport.h` — add `ReadNonBlocking` to the `Transport` interface.
- `app/src/main/cpp/core/tcp_transport.{h,cpp}` — implement `ReadNonBlocking` (recv MSG_DONTWAIT).
- `app/src/main/cpp/core/stub_transport.h` — implement `ReadNonBlocking`; add `SetWriteFails`.
- `app/src/main/cpp/core/media_queue.h` — add `PopTimeout`.
- `app/src/main/cpp/core/rtmp_client.{h,cpp}` — `writeOk()`, control-channel servicing in `OnBytes`, `SendUnpublish()`.
- `app/src/main/cpp/core/stream_session.{h,cpp}` — `SessionState` gains `Dropped`/`Rejected` (replacing `Error`); non-blocking publishing loop; graceful teardown; `Stop()` defers socket close while Live.
- `app/src/main/cpp/native-lib.cpp` — update the `nativeState` comment (0=Idle,1=Connecting,2=Live,3=Dropped,4=Rejected); no logic change.

**Native tests:**
- `app/src/main/cpp/test/test_rtmp_control.cpp` — NEW: control-channel + writeOk + unpublish unit tests.
- `app/src/main/cpp/test/test_stream_session.cpp` — add drop/reject/teardown tests.
- `app/src/main/cpp/test/CMakeLists.txt` — register `test_rtmp_control.cpp`.

**Kotlin:**
- `app/src/main/java/com/example/plohoystream/stream/StreamState.kt` — add `Reconnecting`.
- `app/src/main/java/com/example/plohoystream/stream/StreamUiState.kt` — `isActive`/`canGoLive` include `Reconnecting`.
- `app/src/main/java/com/example/plohoystream/stream/CameraStreamEngine.kt` — reconnect loop.
- `app/src/main/java/com/example/plohoystream/stream/StreamViewModel.kt` — keep elapsed timer running through `Reconnecting`.
- `app/src/main/java/com/example/plohoystream/ui/viewfinder/ControlRail.kt` — map `Reconnecting` → amber pill.
- `app/src/main/java/com/example/plohoystream/ui/viewfinder/GoLiveButton.kt` — treat `Reconnecting` as active (show Stop).

**Kotlin tests:**
- `app/src/test/java/com/example/plohoystream/stream/CameraStreamEngineTest.kt` — reconnect-loop tests; update the two tests that assumed native `3` == terminal error.
- `app/src/test/java/com/example/plohoystream/stream/StreamUiStateTest.kt` — `Reconnecting` is active / not go-live-able.

---

## Phase 1 — Transport & queue primitives

### Task 1.1: `Transport::ReadNonBlocking` interface + TCP impl

**Files:**
- Modify: `app/src/main/cpp/core/transport.h`
- Modify: `app/src/main/cpp/core/tcp_transport.h:9`, `app/src/main/cpp/core/tcp_transport.cpp:43`
- Modify: `app/src/main/cpp/core/stub_transport.h`
- Test: covered indirectly by Phase 3; this task is a compile-only primitive (no behavior to unit-test on the real socket in host tests).

- [ ] **Step 1: Add the pure-virtual to the interface**

In `transport.h`, after the `Read` declaration (line 12), add:

```cpp
    // Non-blocking read: >0 bytes available now, 0 nothing available right now (not an error),
    // <0 peer closed or error. Used to service the inbound control channel while publishing
    // without blocking the egress thread.
    virtual int ReadNonBlocking(uint8_t* buf, int maxLen) = 0;
```

- [ ] **Step 2: Implement in `TcpTransport`**

In `tcp_transport.h`, after the `Read` declaration (line 9) add:

```cpp
    int  ReadNonBlocking(uint8_t* buf, int maxLen) override;
```

In `tcp_transport.cpp`, add `#include <cerrno>` near the top includes, and after `Read` (line 43) add:

```cpp
int TcpTransport::ReadNonBlocking(uint8_t* buf, int maxLen) {
    if (fd_ < 0) return -1;
    ssize_t n = ::recv(fd_, buf, maxLen, MSG_DONTWAIT);
    if (n > 0) return (int)n;
    if (n == 0) return -1;                                  // peer closed
    if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;  // nothing available now
    return -1;                                              // real error
}
```

- [ ] **Step 3: Implement in `StubTransport`** (host tests)

In `stub_transport.h`, add a write-failure knob, an external write sink (so tests can read written bytes even after the session destroys the transport), and the non-blocking read. Replace the `Write` method and add after `Read`:

```cpp
    bool Write(const std::vector<uint8_t>& d) override {
        if (failWrites_) return false;
        written_.insert(written_.end(), d.begin(), d.end());
        if (sink_) sink_->insert(sink_->end(), d.begin(), d.end());   // mirror to test-owned sink
        return true;
    }
    // Same delivery model as Read: returns queued bytes or 0 when nothing is queued.
    // 0 means "nothing now" (not a drop), so the publish loop keeps running.
    int ReadNonBlocking(uint8_t* buf, int maxLen) override { return Read(buf, maxLen); }
```

And in the test-helpers section add:

```cpp
    void SetWriteFails(bool v) { failWrites_ = v; }
    // Mirror every Write into a test-owned buffer that outlives this transport — lets a test
    // assert on bytes written *during* StreamSession::Stop() (which destroys the transport).
    void SetSink(std::vector<uint8_t>* s) { sink_ = s; }
```

And in the private members add:

```cpp
    bool failWrites_ = false;
    std::vector<uint8_t>* sink_ = nullptr;
```

- [ ] **Step 4: Build the host tests to confirm everything still compiles**

Run: `cd app/src/main/cpp && cmake -S test -B build-test >/dev/null && cmake --build build-test 2>&1 | tail -5`
Expected: builds cleanly (the `BlockingTransport` in `test_stream_session.cpp` will FAIL to compile because it doesn't implement the new pure-virtual — that's fixed in Task 1.2).

- [ ] **Step 5: Fix `BlockingTransport` in `test_stream_session.cpp`**

In `test_stream_session.cpp`, inside `struct BlockingTransport` (after its `Read` override, ~line 85) add:

```cpp
    int ReadNonBlocking(uint8_t*, int) override {
        std::lock_guard<std::mutex> lk(m); return closed ? -1 : 0;
    }
```

Run: `cd app/src/main/cpp && cmake --build build-test 2>&1 | tail -5 && ctest --test-dir build-test --output-on-failure 2>&1 | tail -5`
Expected: builds; all existing tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/cpp/core/transport.h app/src/main/cpp/core/tcp_transport.h app/src/main/cpp/core/tcp_transport.cpp app/src/main/cpp/core/stub_transport.h app/src/main/cpp/test/test_stream_session.cpp
git commit -m "feat(m2a): add Transport::ReadNonBlocking + StubTransport write-fail knob"
```

### Task 1.2: `MediaQueue::PopTimeout`

**Files:**
- Modify: `app/src/main/cpp/core/media_queue.h:40`
- Test: `app/src/main/cpp/test/test_media_queue.cpp`

- [ ] **Step 1: Write the failing test**

Append to `test_media_queue.cpp`:

```cpp
TEST(MediaQueue, PopTimeoutReturnsFalseWhenEmptyAfterTimeout) {
    ps::MediaQueue q(4);
    ps::MediaItem out;
    auto t0 = std::chrono::steady_clock::now();
    bool got = q.PopTimeout(out, 30);
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();
    EXPECT_FALSE(got);
    EXPECT_GE(ms, 25);            // actually waited ~the timeout
}

TEST(MediaQueue, PopTimeoutReturnsItemImmediatelyWhenAvailable) {
    ps::MediaQueue q(4);
    q.Push(ps::MediaItem{ps::MediaItem::Audio, {1,2,3}, false, 0, 0});
    ps::MediaItem out;
    EXPECT_TRUE(q.PopTimeout(out, 1000));
    EXPECT_EQ(out.data.size(), 3u);
}
```

Add `#include <chrono>` at the top of the file if not present.

- [ ] **Step 2: Run to verify it fails**

Run: `cd app/src/main/cpp && cmake --build build-test 2>&1 | tail -5`
Expected: FAIL to compile — `PopTimeout` is not a member of `MediaQueue`.

- [ ] **Step 3: Implement `PopTimeout`**

In `media_queue.h`, add `#include <chrono>` to the includes, and after `Pop` (line 40) add:

```cpp
    // Like Pop but waits at most timeoutMs. Returns false on timeout (queue still open but
    // empty) OR on closed+drained — the caller distinguishes via its own running flag.
    bool PopTimeout(MediaItem& out, int timeoutMs) {
        std::unique_lock<std::mutex> lk(m_);
        cv_.wait_for(lk, std::chrono::milliseconds(timeoutMs), [&] { return closed_ || !q_.empty(); });
        if (q_.empty()) return false;
        out = std::move(q_.front());
        q_.pop_front();
        return true;
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd app/src/main/cpp && cmake --build build-test 2>&1 | tail -3 && ctest --test-dir build-test --output-on-failure 2>&1 | tail -5`
Expected: PASS (new + all existing).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/core/media_queue.h app/src/main/cpp/test/test_media_queue.cpp
git commit -m "feat(m2a): MediaQueue::PopTimeout for non-blocking egress servicing"
```

---

## Phase 2 — RtmpClient hardening (write-failure, control channel, graceful unpublish)

### Task 2.1: `writeOk()` — propagate transport write failures

**Files:**
- Modify: `app/src/main/cpp/core/rtmp_client.h:48,57`
- Test: `app/src/main/cpp/test/test_rtmp_control.cpp` (NEW)
- Modify: `app/src/main/cpp/test/CMakeLists.txt:28`

- [ ] **Step 1: Register the new test file**

In `test/CMakeLists.txt`, add `test_rtmp_control.cpp` to the `add_executable(core_tests ...)` list (after `test_codec_negotiation.cpp`, before the closing `)`).

- [ ] **Step 2: Write the failing test**

Create `app/src/main/cpp/test/test_rtmp_control.cpp`:

```cpp
#include <gtest/gtest.h>
#include "rtmp_client.h"
#include "rtmp_chunk.h"
#include "stub_transport.h"
using namespace ps;

// Drive a fresh RtmpClient past the handshake so OnBytes will process RTMP chunks.
// Returns with handshakeDone_ true and the connect command already sent.
static void DoHandshake(RtmpClient& c, StubTransport& t) {
    c.Begin();                                  // sends C0C1
    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;
    c.OnBytes(s0s1s2);                          // completes handshake -> sends C2 + connect
    t.clear();                                  // forget handshake bytes; assert only what follows
}

// A protocol-control message (csid 2) the server would send us.
static Bytes Control(uint8_t type, const Bytes& payload) {
    return ChunkEncode(2, type, 0, 0, payload, 128);
}
// Search haystack for the contiguous subsequence needle.
static bool Contains(const Bytes& hay, const Bytes& needle) {
    if (needle.empty() || hay.size() < needle.size()) return false;
    for (size_t i = 0; i + needle.size() <= hay.size(); ++i)
        if (std::equal(needle.begin(), needle.end(), hay.begin() + i)) return true;
    return false;
}

TEST(RtmpControl, WriteFailureFlagsNotOk) {
    StubTransport t; RtmpClient c(t, StreamParams{});
    EXPECT_TRUE(c.writeOk());
    t.SetWriteFails(true);
    c.Begin();                  // a Send() now fails
    EXPECT_FALSE(c.writeOk());
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd app/src/main/cpp && cmake -S test -B build-test >/dev/null && cmake --build build-test 2>&1 | tail -8`
Expected: FAIL to compile — `writeOk` is not a member of `RtmpClient`.

- [ ] **Step 4: Implement `writeOk`**

In `rtmp_client.h`: after `uint64_t bytesSent() const ...` (line 48) add:

```cpp
    bool writeOk() const { return writeOk_; }
```

Change the `Send` helper (line 57) from:

```cpp
    void Send(const Bytes& b) { t_.Write(b); bytesSent_ += b.size(); }
```

to:

```cpp
    void Send(const Bytes& b) { if (!t_.Write(b)) writeOk_ = false; bytesSent_ += b.size(); }
```

And in the private members (after `std::atomic<uint64_t> bytesSent_{0};`, line 59) add:

```cpp
    bool writeOk_ = true;          // egress thread only; no atomic needed
```

- [ ] **Step 5: Run to verify it passes**

Run: `cd app/src/main/cpp && cmake --build build-test 2>&1 | tail -3 && ctest --test-dir build-test --output-on-failure -R RtmpControl 2>&1 | tail -5`
Expected: `RtmpControl.WriteFailureFlagsNotOk` PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/cpp/core/rtmp_client.h app/src/main/cpp/test/test_rtmp_control.cpp app/src/main/cpp/test/CMakeLists.txt
git commit -m "feat(m2a): RtmpClient::writeOk propagates transport write failures"
```

### Task 2.2: Control-channel servicing (ping→pong, window-ack echo, set-peer-bw, acknowledgements)

**Files:**
- Modify: `app/src/main/cpp/core/rtmp_client.h` (private members)
- Modify: `app/src/main/cpp/core/rtmp_client.cpp:56` (`OnBytes`)
- Test: `app/src/main/cpp/test/test_rtmp_control.cpp`

- [ ] **Step 1: Write the failing tests**

Append to `test_rtmp_control.cpp`:

```cpp
TEST(RtmpControl, PingRequestGetsPingResponse) {
    StubTransport t; RtmpClient c(t, StreamParams{});
    DoHandshake(c, t);
    // User Control: event 6 (PingRequest) + 4-byte timestamp 0x01020304
    Bytes p = {0x00, 0x06, 0x01, 0x02, 0x03, 0x04};
    c.OnBytes(Control(0x04, p));
    // Expect a PingResponse: event 7 + echoed timestamp, somewhere in the written bytes.
    EXPECT_TRUE(Contains(c_written(t), Bytes{0x00, 0x07, 0x01, 0x02, 0x03, 0x04}));
}

TEST(RtmpControl, WindowAckSizeIsEchoed) {
    StubTransport t; RtmpClient c(t, StreamParams{});
    DoHandshake(c, t);
    Bytes win = {0x00, 0x10, 0x00, 0x00};           // window = 0x00100000
    c.OnBytes(Control(0x05, win));                   // Window Acknowledgement Size
    // We reply with our own Window Ack Size message (type 0x05) carrying the same window.
    EXPECT_TRUE(Contains(c_written(t), win));
}

TEST(RtmpControl, SetPeerBandwidthRepliesWindowAckSize) {
    StubTransport t; RtmpClient c(t, StreamParams{});
    DoHandshake(c, t);
    Bytes pbw = {0x00, 0x20, 0x00, 0x00, 0x02};      // window=0x00200000, limitType=dynamic
    c.OnBytes(Control(0x06, pbw));
    EXPECT_TRUE(Contains(c_written(t), Bytes{0x00, 0x20, 0x00, 0x00}));  // our Window Ack Size
}

TEST(RtmpControl, EmitsAcknowledgementAfterWindowOfBytes) {
    StubTransport t; RtmpClient c(t, StreamParams{});
    DoHandshake(c, t);
    // Tell the client our window is small so an Ack triggers quickly.
    c.OnBytes(Control(0x05, Bytes{0x00, 0x00, 0x00, 0x40}));  // window = 64 bytes
    t.clear();
    // Feed >64 bytes of (any) inbound; the client must emit an Acknowledgement (type 0x03).
    Bytes filler(200, 0x00);
    c.OnBytes(filler);
    // An Acknowledgement message has msg type 0x03 in its chunk header. ChunkEncode(2,0x03,...)
    // emits basic header 0x02 then an 11-byte msg header whose 7th byte (type) is 0x03.
    const Bytes& w = c_written(t);
    bool sawAck = false;
    for (size_t i = 0; i + 12 <= w.size(); ++i)
        if (w[i] == 0x02 && w[i + 7] == 0x03) { sawAck = true; break; }
    EXPECT_TRUE(sawAck);
}
```

Add this helper near the top of the file (after `Contains`):

```cpp
static const Bytes& c_written(StubTransport& t) { return t.written(); }
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd app/src/main/cpp && cmake --build build-test 2>&1 | tail -3 && ctest --test-dir build-test --output-on-failure -R RtmpControl 2>&1 | tail -15`
Expected: the four new tests FAIL (no control replies are written yet).

- [ ] **Step 3: Add control state to the header**

In `rtmp_client.h` private members (after `Codec negotiatedCodec_ = Codec::Avc;`, line 70) add:

```cpp
    // Inbound control-channel accounting (RTMP spec 5.4): we must answer pings, echo the
    // server's window-ack-size, and send our own Acknowledgement once we've received a window.
    uint32_t serverWindow_ = 2500000;   // bytes; overwritten by an inbound Window-Ack-Size
    uint64_t receivedBytes_ = 0;
    uint64_t lastAckBytes_ = 0;
```

- [ ] **Step 4: Implement servicing in `OnBytes`**

In `rtmp_client.cpp`, at the very start of `OnBytes` (line 57, before the `if (!handshakeDone_)` block) add:

```cpp
    receivedBytes_ += d.size();
```

Then replace the inner reader loop (lines 71-109, the `while (reader_.Next(type, payload)) { if (type != 0x14) continue; ... }`) with a version that handles control messages first:

```cpp
    reader_.Feed(d);
    uint8_t type; Bytes payload;
    while (reader_.Next(type, payload)) {
        if (type == 0x05) {                                  // Window Acknowledgement Size
            if (payload.size() >= 4) {
                serverWindow_ = (payload[0]<<24)|(payload[1]<<16)|(payload[2]<<8)|payload[3];
                Bytes w; PutU32BE(w, serverWindow_);
                Send(ChunkEncode(2, 0x05, 0, 0, w, 128));    // echo our own window
            }
            continue;
        }
        if (type == 0x06) {                                  // Set Peer Bandwidth
            if (payload.size() >= 4) {
                serverWindow_ = (payload[0]<<24)|(payload[1]<<16)|(payload[2]<<8)|payload[3];
                Bytes w; PutU32BE(w, serverWindow_);
                Send(ChunkEncode(2, 0x05, 0, 0, w, 128));    // reply with Window Ack Size
            }
            continue;
        }
        if (type == 0x04) {                                  // User Control
            if (payload.size() >= 6 && payload[0] == 0x00 && payload[1] == 0x06) {  // PingRequest
                Bytes pong = {0x00, 0x07,                    // PingResponse event
                              payload[2], payload[3], payload[4], payload[5]};       // echo ts
                Send(ChunkEncode(2, 0x04, 0, 0, pong, 128));
            }
            continue;
        }
        if (type == 0x03) continue;                          // Acknowledgement from server (info)
        if (type != 0x14) continue;                          // only AMF0 commands drive the FSM
        Amf0Reader r(payload.data(), payload.size());
        std::string name = r.ReadString();
        int txn = (int)r.ReadNumber();
        if (name == "_error") {
            state_ = RtmpState::Error;
        } else if (name == "_result" && state_ == RtmpState::ConnectSent) {
            bool serverHevc = ServerAdvertisesHevc(payload);
            negotiatedCodec_ = (requestedCodec_ == Codec::Hevc && serverHevc) ? Codec::Hevc : Codec::Avc;
            codec_ = (negotiatedCodec_ == Codec::Hevc)
                ? std::unique_ptr<VideoCodec>(new HevcCodec())
                : std::unique_ptr<VideoCodec>(new AvcCodec());
            outChunkSize_ = 4096;
            { Bytes cs; PutU32BE(cs, 4096); Send(ChunkEncode(2, 0x01, 0, 0, cs, 128)); }
            { Bytes b; Amf0::String(b,"releaseStream"); Amf0::Number(b,++txn_); Amf0::Null(b); Amf0::String(b,p_.streamKey); sendCommand(b,0); }
            { Bytes b; Amf0::String(b,"FCPublish");     Amf0::Number(b,++txn_); Amf0::Null(b); Amf0::String(b,p_.streamKey); sendCommand(b,0); }
            createStreamTxn_ = ++txn_;
            { Bytes b; Amf0::String(b,"createStream");  Amf0::Number(b,createStreamTxn_); Amf0::Null(b); sendCommand(b,0); }
            state_ = RtmpState::CreateStreamSent;
        } else if (name == "_result" && state_ == RtmpState::CreateStreamSent && txn == createStreamTxn_) {
            r.SkipValue();
            streamId_ = (int)r.ReadNumber();
            { Bytes b; Amf0::String(b,"publish"); Amf0::Number(b,++txn_); Amf0::Null(b);
              Amf0::String(b,p_.streamKey); Amf0::String(b,"live"); sendCommand(b, streamId_); }
            state_ = RtmpState::PublishSent;
        } else if (name == "onStatus" && state_ == RtmpState::PublishSent) {
            if (Amf0::FindStringValue(payload, "code") == "NetStream.Publish.Start") {
                Send(ChunkEncode(8, 0x12, streamId_, 0,
                         BuildOnMetaData(p_.width, p_.height, p_.fps, p_.sampleRate), outChunkSize_));
                state_ = RtmpState::Publishing;
            } else if (Amf0::FindStringValue(payload, "level") == "error") {
                state_ = RtmpState::Error;
            }
        }
    }
    // Send our own Acknowledgement once we've consumed a full window of inbound bytes.
    if (serverWindow_ > 0 && receivedBytes_ - lastAckBytes_ >= serverWindow_) {
        Bytes a; PutU32BE(a, (uint32_t)receivedBytes_);
        Send(ChunkEncode(2, 0x03, 0, 0, a, 128));
        lastAckBytes_ = receivedBytes_;
    }
```

Note: the AMF0-command branch is unchanged from the original — only the control-message branches above it and the trailing Acknowledgement block are new.

- [ ] **Step 5: Run to verify they pass**

Run: `cd app/src/main/cpp && cmake --build build-test 2>&1 | tail -3 && ctest --test-dir build-test --output-on-failure 2>&1 | tail -8`
Expected: all `RtmpControl.*` PASS; existing `test_rtmp_client` / `test_codec_negotiation` still PASS (the command FSM is byte-for-byte unchanged).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/cpp/core/rtmp_client.h app/src/main/cpp/core/rtmp_client.cpp app/src/main/cpp/test/test_rtmp_control.cpp
git commit -m "feat(m2a): service RTMP control channel (ping/pong, window-ack, acknowledgements)"
```

### Task 2.3: `SendUnpublish()` — graceful publisher teardown

**Files:**
- Modify: `app/src/main/cpp/core/rtmp_client.h` (public method)
- Modify: `app/src/main/cpp/core/rtmp_client.cpp` (impl)
- Test: `app/src/main/cpp/test/test_rtmp_control.cpp`

- [ ] **Step 1: Write the failing test**

Append to `test_rtmp_control.cpp`:

```cpp
// AMF0 string body marker for a command name: 0x02 + u16 length + bytes.
static Bytes Amf0Str(const std::string& s) {
    Bytes b = {0x02, (uint8_t)(s.size() >> 8), (uint8_t)(s.size() & 0xFF)};
    b.insert(b.end(), s.begin(), s.end()); return b;
}

TEST(RtmpControl, SendUnpublishWritesFcUnpublishDeleteAndClose) {
    StubTransport t; RtmpClient c(t, StreamParams{ "h","app","streamkey","rtmp://h/app" });
    DoHandshake(c, t);
    c.SendUnpublish();
    const Bytes& w = c_written(t);
    EXPECT_TRUE(Contains(w, Amf0Str("FCUnpublish")));
    EXPECT_TRUE(Contains(w, Amf0Str("deleteStream")));
    EXPECT_TRUE(Contains(w, Amf0Str("closeStream")));
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd app/src/main/cpp && cmake --build build-test 2>&1 | tail -6`
Expected: FAIL to compile — `SendUnpublish` is not a member.

- [ ] **Step 3: Implement**

In `rtmp_client.h`, after `void SendAudio(...)` (line 53) add:

```cpp
    void SendUnpublish();   // graceful publisher teardown: FCUnpublish + deleteStream + closeStream
```

In `rtmp_client.cpp`, after `SendAudio` (line 132) add:

```cpp
void RtmpClient::SendUnpublish() {
    { Bytes b; Amf0::String(b,"FCUnpublish");  Amf0::Number(b,++txn_); Amf0::Null(b); Amf0::String(b,p_.streamKey); sendCommand(b,0); }
    { Bytes b; Amf0::String(b,"deleteStream"); Amf0::Number(b,++txn_); Amf0::Null(b); Amf0::Number(b, streamId_); sendCommand(b,0); }
    { Bytes b; Amf0::String(b,"closeStream");  Amf0::Number(b,++txn_); Amf0::Null(b); sendCommand(b, streamId_); }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd app/src/main/cpp && cmake --build build-test 2>&1 | tail -3 && ctest --test-dir build-test --output-on-failure -R RtmpControl 2>&1 | tail -5`
Expected: all `RtmpControl.*` PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/core/rtmp_client.h app/src/main/cpp/core/rtmp_client.cpp app/src/main/cpp/test/test_rtmp_control.cpp
git commit -m "feat(m2a): RtmpClient::SendUnpublish graceful teardown sequence"
```

---

## Phase 3 — StreamSession: Dropped/Rejected states, non-blocking publish loop, graceful teardown

### Task 3.1: `SessionState` gains `Dropped`/`Rejected` (replacing `Error`)

**Files:**
- Modify: `app/src/main/cpp/core/stream_session.h:11`
- Modify: `app/src/main/cpp/core/stream_session.cpp` (every `SessionState::Error`)
- Modify: `app/src/main/cpp/native-lib.cpp:74` (comment only)

- [ ] **Step 1: Change the enum**

In `stream_session.h:11`, replace:

```cpp
enum class SessionState { Idle, Connecting, Live, Error };
```

with:

```cpp
// Int values cross JNI to Kotlin (nativeState): 0=Idle,1=Connecting,2=Live,3=Dropped,4=Rejected.
// Dropped = transient transport failure (Kotlin reconnects). Rejected = server refused
// (auth/bad-key/already-publishing) → terminal.
enum class SessionState { Idle = 0, Connecting = 1, Live = 2, Dropped = 3, Rejected = 4 };
```

- [ ] **Step 2: Update the run-loop end states (full `run()`/`Stop()` rewrite is Task 3.2)**

This step is folded into Task 3.2, which rewrites `run()` and `Stop()` wholesale. Proceed to 3.2; do not leave `stream_session.cpp` half-edited.

- [ ] **Step 3: Update the JNI comment**

In `native-lib.cpp:74`, change the trailing comment to:

```cpp
    return h ? static_cast<jint>(Self(h)->state()) : 0; // 0=Idle,1=Connecting,2=Live,3=Dropped,4=Rejected
```

(No code change — the cast already forwards the new ints.)

### Task 3.2: Rewrite `StreamSession::run()` + `Stop()` (non-blocking publish, graceful teardown)

**Files:**
- Modify: `app/src/main/cpp/core/stream_session.cpp:15-94`
- Test: `app/src/main/cpp/test/test_stream_session.cpp`

- [ ] **Step 1: Write the failing tests**

Append to `test_stream_session.cpp` (the helpers `PreloadPublishHandshake`, `SessMake*`, and `ParseMessages` already exist in the file):

```cpp
// A _result whose info object carries level=="error" — a server rejection at connect.
static Bytes SessMakeConnectError() {
    Bytes b; Amf0::String(b,"_error"); Amf0::Number(b,1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b,"level"); Amf0::String(b,"error");
    Amf0::Key(b,"code");  Amf0::String(b,"NetConnection.Connect.Rejected");
    Amf0::ObjectEnd(b);
    return SessMakeCommand(b);
}

TEST(StreamSession, ConnectRejectionEndsRejected) {
    auto owned = std::make_unique<StubTransport>();
    StubTransport* stub = owned.get();
    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;
    stub->FeedIncoming(s0s1s2);
    stub->FeedIncoming(SessMakeConnectError());          // server rejects connect
    StreamParams p; p.host="h"; p.app="app"; p.streamKey="k"; p.tcUrl="rtmp://h/app";
    auto held = std::make_shared<std::unique_ptr<StubTransport>>(std::move(owned));
    StreamSession s(p, [held]() mutable -> std::unique_ptr<Transport> { return std::move(*held); });
    s.Start();
    for (int i = 0; i < 200 && s.state() == SessionState::Connecting; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    EXPECT_EQ(s.state(), SessionState::Rejected);
    s.Stop();
}

TEST(StreamSession, MidPublishWriteFailureEndsDropped) {
    auto owned = std::make_unique<StubTransport>();
    StubTransport* stub = owned.get();
    PreloadPublishHandshake(*stub);
    StreamParams p; p.host="h"; p.app="app"; p.streamKey="k"; p.tcUrl="rtmp://h/app";
    auto held = std::make_shared<std::unique_ptr<StubTransport>>(std::move(owned));
    StreamSession s(p, [held]() mutable -> std::unique_ptr<Transport> { return std::move(*held); });
    s.Start();
    for (int i = 0; i < 200 && s.state() != SessionState::Live; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    ASSERT_EQ(s.state(), SessionState::Live);
    stub->SetWriteFails(true);                           // socket goes dead
    s.SendVideoConfig({0,0,0,1, 0x67,0x42,0x00,0x1e});
    s.SendVideo({0,0,0,1, 0x65, 0x88}, true, 0, 0);      // this write fails -> Dropped
    for (int i = 0; i < 200 && s.state() == SessionState::Live; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    EXPECT_EQ(s.state(), SessionState::Dropped);
    s.Stop();
}

TEST(StreamSession, GracefulStopWhilePublishingSendsUnpublish) {
    auto owned = std::make_unique<StubTransport>();
    StubTransport* stub = owned.get();
    PreloadPublishHandshake(*stub);
    std::vector<uint8_t> sink;          // test-owned; outlives the session's transport
    stub->SetSink(&sink);
    StreamParams p; p.host="h"; p.app="app"; p.streamKey="streamkey"; p.tcUrl="rtmp://h/app";
    auto held = std::make_shared<std::unique_ptr<StubTransport>>(std::move(owned));
    StreamSession s(p, [held]() mutable -> std::unique_ptr<Transport> { return std::move(*held); });
    s.Start();
    for (int i = 0; i < 200 && s.state() != SessionState::Live; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    ASSERT_EQ(s.state(), SessionState::Live);
    // The egress thread sends FCUnpublish/deleteStream/closeStream during Stop(), before close.
    s.Stop();
    EXPECT_EQ(s.state(), SessionState::Idle);
    auto contains = [&](const std::string& cmd) {
        Bytes n = {0x02, (uint8_t)(cmd.size()>>8), (uint8_t)(cmd.size()&0xFF)};
        n.insert(n.end(), cmd.begin(), cmd.end());
        for (size_t i = 0; i + n.size() <= sink.size(); ++i)
            if (std::equal(n.begin(), n.end(), sink.begin()+i)) return true;
        return false;
    };
    EXPECT_TRUE(contains("FCUnpublish"));
    EXPECT_TRUE(contains("deleteStream"));
}
```

Note: the assertion reads the test-owned `sink` (mirrored on every `Write`), NOT `stub->written()` — `Stop()` destroys the transport via `transport_.reset()` after the egress thread writes the unpublish sequence, so `stub` would be a dangling pointer by the time `Stop()` returns. The `sink` vector lives in the test frame and survives.

- [ ] **Step 2: Run to verify they fail**

Run: `cd app/src/main/cpp && cmake --build build-test 2>&1 | tail -8`
Expected: FAIL to compile (`SessionState::Rejected`/`Dropped` referenced but `run()` still sets the now-removed `SessionState::Error`).

- [ ] **Step 3: Rewrite `Stop()` and `run()`**

In `stream_session.cpp`, replace `Stop()` (lines 15-23) with:

```cpp
void StreamSession::Stop() {
    if (!running_.exchange(false)) { if (thread_.joinable()) thread_.join(); return; }
    queue_.Close();   // wake PopTimeout so the publish loop exits promptly
    // While publishing the loop is non-blocking and exits on running_=false, then sends the
    // graceful unpublish sequence — so we must NOT close the socket first. During handshake the
    // egress thread is in a BLOCKING Read(); closing the socket interrupts it (ANR fix).
    if (state_.load() != SessionState::Live)
        if (transport_) transport_->Close();
    if (thread_.joinable()) thread_.join();
    transport_.reset();
    state_ = SessionState::Idle;
}
```

Replace `run()` (lines 39-94) with:

```cpp
void StreamSession::run() {
    if (!transport_ || !transport_->Connect(params_.host, params_.port)) {
        state_ = SessionState::Dropped; running_ = false; return;   // couldn't connect -> transient
    }
    RtmpClient client(*transport_, params_);
    client.RequestCodec(requestedCodec_);
    client.Begin();

    // --- handshake/connect phase: blocking reads until Publishing, server error, or drop ---
    uint8_t buf[8192];
    while (running_.load() &&
           client.state() != RtmpState::Publishing &&
           client.state() != RtmpState::Error) {
        int n = transport_->Read(buf, sizeof(buf));
        if (n <= 0) { state_ = SessionState::Dropped; running_ = false; return; }  // socket drop
        client.OnBytes(Bytes(buf, buf + n));
    }
    if (!running_.load()) return;                            // user Stop() during handshake
    if (client.state() == RtmpState::Error) { state_ = SessionState::Rejected; running_ = false; return; }
    if (client.state() != RtmpState::Publishing) { state_ = SessionState::Dropped; running_ = false; return; }

    state_ = SessionState::Live;
    negotiated_.store(client.negotiatedCodec());

    // --- publishing phase: single thread services inbound (non-blocking) + drains the queue ---
    MediaItem item;
    bool haveBase = false; uint32_t baseMs = 0;
    auto rebase = [&](uint32_t ts) -> uint32_t { return ts >= baseMs ? ts - baseMs : 0; };
    SessionState endState = SessionState::Idle;              // Idle => user-stop (Stop sets Idle)
    while (running_.load()) {
        int n = transport_->ReadNonBlocking(buf, sizeof(buf));
        if (n > 0) client.OnBytes(Bytes(buf, buf + n));
        else if (n < 0) { endState = SessionState::Dropped; break; }
        if (client.state() == RtmpState::Error) { endState = SessionState::Rejected; break; }

        if (queue_.PopTimeout(item, 50)) {
            switch (item.kind) {
                case MediaItem::VideoConfig:
                    client.SendVideoConfig(item.data); break;
                case MediaItem::Video:
                    if (!haveBase) { baseMs = item.dtsMs; haveBase = true; }
                    client.SendVideo(item.data, item.keyframe, rebase(item.ptsMs), rebase(item.dtsMs)); break;
                case MediaItem::AudioConfig:
                    client.SendAudioConfig(item.sampleRate, item.channels); break;
                case MediaItem::Audio:
                    if (!haveBase) { baseMs = item.ptsMs; haveBase = true; }
                    client.SendAudio(item.data, rebase(item.ptsMs)); break;
            }
            bytesSent_.store(client.bytesSent());
            queueDepth_.store(static_cast<int>(queue_.size()));
        }
        if (!client.writeOk() || !transport_->connected()) { endState = SessionState::Dropped; break; }
    }

    // --- teardown: graceful unpublish only if the link is still healthy & we were publishing ---
    if (client.state() == RtmpState::Publishing && client.writeOk() && transport_->connected())
        client.SendUnpublish();
    transport_->Close();
    if (endState == SessionState::Dropped || endState == SessionState::Rejected)
        state_ = endState;     // else user-stop: leave state for Stop() to set Idle
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd app/src/main/cpp && cmake --build build-test 2>&1 | tail -3 && ctest --test-dir build-test --output-on-failure 2>&1 | tail -10`
Expected: all `StreamSession.*` PASS — including the pre-existing `ReachesLiveAndWritesVideo`, `StopInterruptsBlockingRead`, `NormalizesTimestampsToStreamStart`, `BytesSentIncreasesAfterLive`, plus the 3 new ones.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/cpp/core/stream_session.h app/src/main/cpp/core/stream_session.cpp app/src/main/cpp/native-lib.cpp app/src/main/cpp/test/test_stream_session.cpp
git commit -m "feat(m2a): StreamSession Dropped/Rejected states, non-blocking publish, graceful teardown"
```

---

## Phase 4 — Kotlin: Reconnecting state + reconnect loop

### Task 4.1: Add `StreamState.Reconnecting` and include it in UI-state predicates

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/stream/StreamState.kt`
- Modify: `app/src/main/java/com/example/plohoystream/stream/StreamUiState.kt:23-26`
- Test: `app/src/test/java/com/example/plohoystream/stream/StreamUiStateTest.kt`

- [ ] **Step 1: Write the failing test**

Append to `StreamUiStateTest.kt`:

```kotlin
@Test fun reconnecting_isActive_andNotGoLiveable() {
    val s = StreamUiState(
        settings = Settings(rtmpUrl = "rtmp://h/app", streamKey = "k"),
        stream = StreamState.Reconnecting,
    )
    assertTrue(s.isActive)
    assertFalse(s.canGoLive)
}
```

Ensure the file imports `org.junit.Assert.assertFalse` and `assertTrue` (add if missing).

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "*StreamUiStateTest*" 2>&1 | tail -15`
Expected: FAIL to compile — `StreamState.Reconnecting` does not exist.

- [ ] **Step 3: Add the state and update predicates**

In `StreamState.kt`, add a member after `Live`:

```kotlin
    data object Reconnecting : StreamState
```

In `StreamUiState.kt`, update `canGoLive` (it already excludes active states; `Reconnecting` is excluded automatically since it's neither `Idle` nor `Error`) and add `Reconnecting` to `isActive` (lines 23-26):

```kotlin
    val isActive: Boolean
        get() = stream is StreamState.Connecting ||
            stream is StreamState.Live ||
            stream is StreamState.Reconnecting ||
            stream is StreamState.Stopping
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "*StreamUiStateTest*" 2>&1 | tail -8`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/stream/StreamState.kt app/src/main/java/com/example/plohoystream/stream/StreamUiState.kt app/src/test/java/com/example/plohoystream/stream/StreamUiStateTest.kt
git commit -m "feat(m2a): StreamState.Reconnecting + UI-state predicates"
```

### Task 4.2: `CameraStreamEngine` reconnect loop

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/stream/CameraStreamEngine.kt`
- Test: `app/src/test/java/com/example/plohoystream/stream/CameraStreamEngineTest.kt`

- [ ] **Step 1: Update the two tests that assumed native `3` == terminal Error, and add reconnect tests**

In `CameraStreamEngineTest.kt`:

Replace `liveThenNativeDrop_surfacesAsError` (lines 44-52) with:

```kotlin
    @Test fun liveThenNativeDrop_entersReconnecting() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        streamer.emitState(2); advanceTimeBy(150); runCurrent()
        assertEquals(StreamState.Live, e.state.value)
        streamer.emitState(3); advanceTimeBy(150); runCurrent()   // 3 == native Dropped
        assertEquals(StreamState.Reconnecting, e.state.value)
        e.stop()
    }
```

Replace `nativeError_surfacesAsError` (lines 61-68) with:

```kotlin
    @Test fun nativeRejected_surfacesAsError() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        streamer.emitState(4)                                     // 4 == native Rejected
        advanceTimeBy(150); runCurrent()
        assertTrue(e.state.value is StreamState.Error)
    }
```

Append three new tests:

```kotlin
    @Test fun drop_thenReconnects_reachesLiveAgain() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        streamer.emitState(2); advanceTimeBy(150); runCurrent()
        assertEquals(StreamState.Live, e.state.value)
        streamer.emitState(3); advanceTimeBy(150); runCurrent()   // drop
        assertEquals(StreamState.Reconnecting, e.state.value)
        advanceTimeBy(5000); runCurrent()                         // 5s backoff elapses -> new attempt
        streamer.emitState(2); advanceTimeBy(150); runCurrent()   // reconnected
        assertEquals(StreamState.Live, e.state.value)
        e.stop()
    }

    @Test fun stop_duringReconnectWait_endsIdle_noReconnect() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        streamer.emitState(2); advanceTimeBy(150); runCurrent()
        streamer.emitState(3); advanceTimeBy(150); runCurrent()
        assertEquals(StreamState.Reconnecting, e.state.value)
        e.stop(); runCurrent()
        assertEquals(StreamState.Idle, e.state.value)
        advanceTimeBy(6000); runCurrent()
        assertEquals(StreamState.Idle, e.state.value)             // did not reconnect
    }

    @Test fun rejected_isTerminal_noReconnect() = runTest {
        val streamer = FakeRtmpStreamer()
        val e = engine(streamer)
        e.start(StreamConfig("rtmp://h/app", "key")); runCurrent()
        streamer.emitState(2); advanceTimeBy(150); runCurrent()
        streamer.emitState(4); advanceTimeBy(150); runCurrent()   // server rejection
        assertTrue(e.state.value is StreamState.Error)
        advanceTimeBy(6000); runCurrent()
        assertTrue(e.state.value is StreamState.Error)            // stays terminal
    }
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "*CameraStreamEngineTest*" 2>&1 | tail -25`
Expected: the new/updated tests FAIL (current engine maps `3` → `Error` and has no reconnect loop; `Reconnecting` assertions fail).

- [ ] **Step 3: Rewrite the engine's start/stop with a reconnect loop**

In `CameraStreamEngine.kt`, add a reconnect-delay field to the constructor (after `pollIntervalMs`, line 27):

```kotlin
    private val reconnectDelayMs: Long = 5000,
```

Add a private member near `mediaStarted` (line 61):

```kotlin
    @Volatile private var userWantsLive = false
```

Add a small private enum at the bottom of the file (inside the class, before the closing brace):

```kotlin
    private enum class Outcome { Dropped, Rejected }
```

Replace `start()` (lines 69-117) with:

```kotlin
    override fun start(config: StreamConfig) {
        val endpoint = runCatching { RtmpEndpoint.parse(config.rtmpUrl, config.streamKey) }
            .getOrElse { _state.value = StreamState.Error(it.message ?: "Bad URL"); return }

        userWantsLive = true
        val quality = config.quality
        val requested = resolveRequest(
            config.codecOverride, hevcEncoder, hevcMain10, cameraHdr, config.hdrEnabled,
        )

        pollJob = scope.launch {
            // Reconnect loop: each iteration is one full connect attempt with a fresh streamer +
            // media pipeline (Moblin-style full restart). A transient Dropped → wait 5s → retry
            // forever while the user wants to be live; a server Rejected is terminal. A user
            // stop() cancels this job (interrupting the backoff delay) and tears down itself.
            while (userWantsLive) {
                mediaStarted = false
                _state.value = StreamState.Connecting
                val s = streamerFactory().also { streamer = it }
                s.start(endpoint, requested.codec, width, height, fps, sampleRate)

                val outcome = runSession(s, requested, quality)

                // Per-attempt teardown (mirror of stop()'s media/flow cleanup, minus job cancel).
                if (mediaStarted) stopMedia()
                mediaStarted = false
                _encoderSurface.value = null
                _activeHdr.value = false
                _bitrateKbps.value = 0
                _health.value = ConnectionHealth.Good
                _audioLevel.value = 0f
                s.stop(); streamer = null

                when (outcome) {
                    Outcome.Rejected -> { userWantsLive = false; _state.value = StreamState.Error("Stream rejected") }
                    Outcome.Dropped -> {
                        if (!userWantsLive) break
                        _state.value = StreamState.Reconnecting
                        delay(reconnectDelayMs)      // cancellable: stop() aborts the wait
                    }
                }
            }
        }
    }

    /** Polls native state for one connect attempt; returns why it ended. */
    private suspend fun runSession(s: RtmpStreamer, requested: VideoFormat, quality: VideoQuality): Outcome {
        while (userWantsLive) {
            when (s.state()) {
                2 -> {
                    if (!mediaStarted) {
                        mediaStarted = true
                        val negotiated = s.negotiatedCodec()
                        val actual = if (negotiated == VideoCodecType.HEVC) requested
                                     else VideoFormat(VideoCodecType.AVC, main10 = false, DynamicRange.SDR)
                        startMedia(s, actual, quality)
                        _activeHdr.value = actual.dynamicRange == DynamicRange.HLG10
                    }
                    _state.value = StreamState.Live
                    val kbps = bitrateMeter.update(s.bytesSent(), System.currentTimeMillis())
                    _bitrateKbps.value = kbps
                    _health.value = deriveHealth(
                        queueDepth = s.queueDepth(),
                        queueCapacity = queueCapacity,
                        actualKbps = kbps,
                        targetKbps = quality.videoBitrate / 1000,
                    )
                }
                3 -> return Outcome.Dropped       // native Dropped (transient)
                4 -> return Outcome.Rejected      // native Rejected (terminal)
                // 0 (Idle) / 1 (Connecting) -> keep polling
            }
            delay(pollIntervalMs)
        }
        return Outcome.Dropped                    // userWantsLive cleared mid-poll (user stop)
    }
```

Replace `stop()` (lines 119-131) with:

```kotlin
    override fun stop() {
        userWantsLive = false
        _state.value = StreamState.Stopping
        pollJob?.cancel(); pollJob = null        // also interrupts a pending reconnect delay()
        if (mediaStarted) stopMedia()
        mediaStarted = false
        _encoderSurface.value = null
        _activeHdr.value = false
        _bitrateKbps.value = 0
        _health.value = ConnectionHealth.Good
        _audioLevel.value = 0f
        streamer?.stop(); streamer = null
        _state.value = StreamState.Idle
    }
```

Note: `bitrateMeter` accumulates across attempts; that's acceptable (the EMA self-corrects). If a reviewer prefers a clean meter per attempt, reset it at the top of the loop — not required for correctness.

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "*CameraStreamEngineTest*" 2>&1 | tail -15`
Expected: all `CameraStreamEngineTest` PASS (the unchanged HDR/quality/forceAvc/stats tests still pass — `runSession` preserves that logic verbatim).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/stream/CameraStreamEngine.kt app/src/test/java/com/example/plohoystream/stream/CameraStreamEngineTest.kt
git commit -m "feat(m2a): CameraStreamEngine auto-reconnect loop (Dropped->Reconnecting->retry)"
```

---

## Phase 5 — UI wiring

### Task 5.1: `Reconnecting` propagates to UI state; elapsed timer is not reset

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/stream/FakeStreamEngine.kt` (add `emitReconnecting`)
- Test: `app/src/test/java/com/example/plohoystream/stream/StreamViewModelTest.kt`
- Inspect (no change expected): `app/src/main/java/com/example/plohoystream/stream/StreamViewModel.kt:44-48`

**Why no "timer keeps counting" assertion:** the VM's elapsed string is computed from `System.currentTimeMillis()` (real wall clock), not virtual test time, so a unit test cannot deterministically observe it advancing. The reset is gated on `Idle`/`Error` only and `Reconnecting` is deliberately absent — this task tests that `Reconnecting` propagates cleanly and guards the reset-gating by code inspection.

- [ ] **Step 1: Add the `emitReconnecting` test helper**

In `FakeStreamEngine.kt`, after `fun emitIdle() { _state.value = StreamState.Idle }` (line 39) add:

```kotlin
    fun emitReconnecting() { _state.value = StreamState.Reconnecting }
```

- [ ] **Step 2: Write the failing test**

Append to `StreamViewModelTest.kt`:

```kotlin
@Test fun reconnecting_propagatesToUiState_andIsActive() = runTest {
    val engine = FakeStreamEngine()
    val vm = StreamViewModel(engine, store = FakeSettingsStore())
    advanceUntilIdle()
    engine.start(StreamConfig("rtmp://h/app", "k"))
    engine.emitLive()
    advanceUntilIdle()
    engine.emitReconnecting()
    advanceUntilIdle()
    assertEquals(StreamState.Reconnecting, vm.uiState.value.stream)
    assertTrue(vm.uiState.value.isActive)
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "*StreamViewModelTest*" 2>&1 | tail -15`
Expected: FAIL to compile before Step 1's helper exists, or assertion-meaningful once it does. (`StreamState.Reconnecting` and `isActive`-includes-`Reconnecting` come from Task 4.1.)

- [ ] **Step 4: Confirm the reset-gating is correct (no code change expected)**

Verify `StreamViewModel.kt:45-46` reads:

```kotlin
                if (s is StreamState.Live && liveStartMs == 0L) liveStartMs = System.currentTimeMillis()
                if (s is StreamState.Idle || s is StreamState.Error) liveStartMs = 0L
```

`Reconnecting` is intentionally absent from the reset, so the elapsed anchor survives across a reconnect. No change needed.

- [ ] **Step 5: Run to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "*StreamViewModelTest*" 2>&1 | tail -8`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/stream/FakeStreamEngine.kt app/src/test/java/com/example/plohoystream/stream/StreamViewModelTest.kt
git commit -m "test(m2a): Reconnecting propagates to UI state; keep elapsed anchor"
```

### Task 5.2: Wire `Reconnecting` into `ControlRail` + `GoLiveButton`

**Files:**
- Modify: `app/src/main/java/com/example/plohoystream/ui/viewfinder/ControlRail.kt:56,63`
- Modify: `app/src/main/java/com/example/plohoystream/ui/viewfinder/GoLiveButton.kt:37`
- Test: visual (preview) + full unit-test suite must stay green. No new unit test (pure Compose wiring).

- [ ] **Step 1: Map `Reconnecting` to the amber pill in `ControlRail`**

In `ControlRail.kt`, replace line 56:

```kotlin
    val live = state is StreamState.Live
```

with:

```kotlin
    val reconnecting = state is StreamState.Reconnecting
    val live = state is StreamState.Live || reconnecting
```

And replace line 63:

```kotlin
            LiveStatusCluster(live = live, elapsed = elapsed)
```

with:

```kotlin
            LiveStatusCluster(live = live, elapsed = elapsed, reconnecting = reconnecting)
```

(The `if (live)` block at line 64 then also keeps the health/audio meters visible during a reconnect, which is fine — they read their last values.)

Add a preview at the end of the file:

```kotlin
@Preview(name = "reconnecting", widthDp = 240, heightDp = 360, showBackground = true, backgroundColor = 0xFF000000)
@Composable private fun RailReconnectingPreview() = PlohoyTheme {
    ControlRail(StreamState.Reconnecting, "01:30", ConnectionHealth.Warn, 0, 0f, emptyList(), 1f, false, null, {}, {}, {}, {}, {})
}
```

- [ ] **Step 2: Treat `Reconnecting` as active in `GoLiveButton` (so the Stop control shows)**

In `GoLiveButton.kt`, replace line 37:

```kotlin
    val active = state is StreamState.Live || state is StreamState.Stopping
```

with:

```kotlin
    val active = state is StreamState.Live || state is StreamState.Stopping || state is StreamState.Reconnecting
```

- [ ] **Step 3: Build the app + run the full unit suite**

Run: `./gradlew assembleDebug testDebugUnitTest 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL; all unit tests PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/plohoystream/ui/viewfinder/ControlRail.kt app/src/main/java/com/example/plohoystream/ui/viewfinder/GoLiveButton.kt
git commit -m "feat(m2a): wire Reconnecting into ControlRail pill + GoLiveButton (show Stop)"
```

---

## Phase 6 — Full verification

### Task 6.1: Whole-suite green (C++ + Kotlin) + APK build

**Files:** none (verification only).

- [ ] **Step 1: Run all C++ host tests**

Run: `cd app/src/main/cpp && cmake -S test -B build-test >/dev/null && cmake --build build-test 2>&1 | tail -3 && ctest --test-dir build-test --output-on-failure 2>&1 | tail -12`
Expected: 100% tests passed (the prior 70 + the new control/queue/session tests).

- [ ] **Step 2: Run all Kotlin unit tests + build the APK**

Run: `./gradlew testDebugUnitTest assembleDebug 2>&1 | tail -12`
Expected: BUILD SUCCESSFUL; all unit tests pass.

- [ ] **Step 3: Commit any incidental fixes, then stop for device smoke test**

The on-device smoke test (stream to local `ffmpeg`/MediaMTX via `adb reverse tcp:1935 tcp:1935`, toggle airplane mode, watch `RECONNECTING` → `LIVE`) is a user-run verification — do NOT attempt to automate it. Report it as the manual acceptance step.

---

## Out of scope (later M2 sub-projects)
A/V sync, local recording, real HEVC-over-the-wire negotiation proof. The optional carry-forward cleanups (`FakeStreamEngine` → `src/test`, `StreamViewModel` cast) are NOT included here to keep this sub-project focused; pick them up only if a task naturally touches those files.
