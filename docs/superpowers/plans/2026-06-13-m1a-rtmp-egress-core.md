# M1-A: RTMP Egress Core (C++) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a platform-agnostic C++ library that takes encoded H.264/AAC access units and publishes them to an RTMP ingest (Twitch/YouTube), proven against a real ingest by a host-side harness — with zero Android dependencies.

**Architecture:** A `core/` static library (no Android headers) containing AMF0 codec, FLV tag packaging, RTMP chunking/handshake, and an `RtmpClient` that drives connect→publish over a `Transport` interface (`TcpTransport` real, `StubTransport` for tests). Both the Android NDK `.so` build and a host GoogleTest build link the same `core/` lib, so all logic is TDD'd on the dev host (macOS) with no emulator. This is M1 stage 1–2 from the design spec; M1-B (Android capture/encode/JNI) consumes this library.

**Tech Stack:** C++17, CMake, GoogleTest (via FetchContent), BSD sockets (portable across macOS host and Android).

**Reference:** Moblin's vendored HaishinKit stack, cloned at `/tmp/moblin/Moblin/Media/HaishinKit/` (Swift). Byte layouts and test vectors below were extracted from it (`Rtmp/`, `Flv/`, `MoblinTests/RtmpSuite.swift`, `MoblinTests/AmfSuite.swift`). **Key Android delta vs. Moblin:** Apple's `CMSampleBuffer` provides NAL units already in AVCC (length-prefixed) form plus a pre-built `avcC` box; Android `MediaCodec` emits **Annex-B** (`00 00 00 01` start codes) and gives SPS/PPS in the codec-config buffer — so this library **converts Annex-B→AVCC and builds `avcC` itself** (Tasks 7, 9).

---

## File Structure

```
app/src/main/cpp/
  CMakeLists.txt              # MODIFIED: add_subdirectory(core); link core into the .so
  native-lib.cpp             # unchanged (JNI bridge is M1-B)
  core/
    CMakeLists.txt           # NEW: defines plohoystream_core STATIC lib (shared by both builds)
    byte_writer.h            # NEW: big-endian append helpers (header-only)
    amf0.h / amf0.cpp        # NEW: AMF0 encode + minimal decode
    transport.h              # NEW: Transport interface
    stub_transport.h         # NEW: in-memory test double (header-only)
    tcp_transport.h/.cpp     # NEW: BSD-socket Transport
    rtmp_handshake.h/.cpp    # NEW: C0C1/C2 build, S0S1/S2 parse
    rtmp_chunk.h/.cpp        # NEW: chunk header encode + fragmentation
    flv.h/.cpp               # NEW: avcC, AudioSpecificConfig, Annex-B->AVCC, video/audio tags, onMetaData
    rtmp_client.h/.cpp       # NEW: connect->publish state machine + media send
  test/
    CMakeLists.txt           # NEW: host-only; FetchContent GoogleTest; builds core_tests
    test_byte_writer.cpp
    test_amf0.cpp
    test_transport.cpp
    test_rtmp_handshake.cpp
    test_rtmp_chunk.cpp
    test_flv.cpp
    test_rtmp_client.cpp
  harness/
    CMakeLists.txt           # NEW: host-only; builds rtmp_harness exe (links core)
    main.cpp                 # NEW: push a canned H.264+AAC sample to a real ingest
```

**Build commands (host, run from `app/src/main/cpp/`):**
```bash
cmake -S test -B build-test && cmake --build build-test            # unit tests
ctest --test-dir build-test --output-on-failure                    # run tests
cmake -S harness -B build-harness && cmake --build build-harness   # manual harness
```

---

## Conventions used by every task

- All multi-byte RTMP/FLV fields are **big-endian** EXCEPT the chunk message-header stream id (4-byte **little-endian**).
- Bytes are `std::vector<uint8_t>`. A "buffer" parameter is appended to, never reallocated wholesale.
- Tests assert exact byte vectors. Helper `Hex(...)` in tests pretty-prints mismatches (provided in Task 0).

---

### Task 0: Host test scaffold + shared core lib

**Files:**
- Create: `app/src/main/cpp/core/CMakeLists.txt`
- Create: `app/src/main/cpp/core/version.h`
- Create: `app/src/main/cpp/test/CMakeLists.txt`
- Create: `app/src/main/cpp/test/test_smoke.cpp`
- Modify: `app/src/main/cpp/CMakeLists.txt`

- [ ] **Step 1: Create the core lib CMake (initially one trivial source)**

`app/src/main/cpp/core/version.h`:
```cpp
#pragma once
namespace ps { inline const char* CoreVersion() { return "m1a"; } }
```

`app/src/main/cpp/core/CMakeLists.txt`:
```cmake
add_library(plohoystream_core STATIC
        version.cpp)
target_include_directories(plohoystream_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(plohoystream_core PUBLIC cxx_std_17)
```

`app/src/main/cpp/core/version.cpp`:
```cpp
#include "version.h"
namespace ps { /* translation unit anchor */ }
```

- [ ] **Step 2: Create the host test CMake**

`app/src/main/cpp/test/CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.22)
project(plohoystream_core_tests CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(googletest
        GIT_REPOSITORY https://github.com/google/googletest.git
        GIT_TAG v1.15.2)
FetchContent_MakeAvailable(googletest)

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../core core_build)

enable_testing()
add_executable(core_tests
        test_smoke.cpp)
target_link_libraries(core_tests PRIVATE plohoystream_core GTest::gtest_main)
include(GoogleTest)
gtest_discover_tests(core_tests)
```

`app/src/main/cpp/test/test_smoke.cpp`:
```cpp
#include <gtest/gtest.h>
#include "version.h"
TEST(Smoke, CoreLinks) { EXPECT_STREQ(ps::CoreVersion(), "m1a"); }
```

- [ ] **Step 3: Build & run — verify the loop works**

Run (from `app/src/main/cpp/`):
```bash
cmake -S test -B build-test && cmake --build build-test && ctest --test-dir build-test --output-on-failure
```
Expected: `1 test from Smoke` PASSED.

- [ ] **Step 4: Wire core into the Android build (compile parity)**

Modify `app/src/main/cpp/CMakeLists.txt` — after the `project("plohoystream")` line add `add_subdirectory(core)`, and change the `target_link_libraries` block to include the core lib:
```cmake
add_subdirectory(core)

add_library(${CMAKE_PROJECT_NAME} SHARED
        native-lib.cpp)

target_link_libraries(${CMAKE_PROJECT_NAME}
        plohoystream_core
        android
        log)
```

- [ ] **Step 5: Add a shared hex helper for tests**

`app/src/main/cpp/test/test_helpers.h`:
```cpp
#pragma once
#include <cstdint>
#include <string>
#include <vector>
#include <gtest/gtest.h>
inline std::string Hex(const std::vector<uint8_t>& b) {
    static const char* d = "0123456789abcdef";
    std::string s;
    for (uint8_t c : b) { s += d[c >> 4]; s += d[c & 0xF]; s += ' '; }
    return s;
}
#define EXPECT_BYTES(actual, ...) \
    do { std::vector<uint8_t> _e = __VA_ARGS__; \
         EXPECT_EQ(actual, _e) << "\n  got: " << Hex(actual) << "\n  exp: " << Hex(_e); } while (0)
```

- [ ] **Step 6: Commit**
```bash
git add app/src/main/cpp/core app/src/main/cpp/test app/src/main/cpp/CMakeLists.txt
git commit -m "build(m1a): host gtest scaffold + shared core static lib"
```

---

### Task 1: ByteWriter big-endian helpers

**Files:**
- Create: `app/src/main/cpp/core/byte_writer.h`
- Test: `app/src/main/cpp/test/test_byte_writer.cpp`

- [ ] **Step 1: Write the failing test**

`app/src/main/cpp/test/test_byte_writer.cpp`:
```cpp
#include "byte_writer.h"
#include "test_helpers.h"
using namespace ps;
TEST(ByteWriter, BigEndianWidths) {
    std::vector<uint8_t> b;
    PutU8(b, 0x03);
    PutU16BE(b, 0x0102);
    PutU24BE(b, 0x010203);
    PutU32BE(b, 0x01020304);
    EXPECT_BYTES(b, {0x03, 0x01,0x02, 0x01,0x02,0x03, 0x01,0x02,0x03,0x04});
}
TEST(ByteWriter, StreamIdIsLittleEndian) {
    std::vector<uint8_t> b; PutU32LE(b, 0x01020304);
    EXPECT_BYTES(b, {0x04,0x03,0x02,0x01});
}
TEST(ByteWriter, DoubleIsBigEndianIEEE754) {
    std::vector<uint8_t> b; PutDoubleBE(b, 1.0);
    EXPECT_BYTES(b, {0x3F,0xF0,0x00,0x00,0x00,0x00,0x00,0x00});
}
```

- [ ] **Step 2: Run — verify it fails**

Run: `cmake --build build-test 2>&1 | tail -5`
Expected: compile error, `byte_writer.h: No such file`.

- [ ] **Step 3: Implement**

`app/src/main/cpp/core/byte_writer.h`:
```cpp
#pragma once
#include <cstdint>
#include <cstring>
#include <vector>
namespace ps {
using Bytes = std::vector<uint8_t>;
inline void PutU8(Bytes& b, uint8_t v) { b.push_back(v); }
inline void PutU16BE(Bytes& b, uint16_t v) { b.push_back(v >> 8); b.push_back(v & 0xFF); }
inline void PutU24BE(Bytes& b, uint32_t v) { b.push_back((v >> 16) & 0xFF); b.push_back((v >> 8) & 0xFF); b.push_back(v & 0xFF); }
inline void PutU32BE(Bytes& b, uint32_t v) { b.push_back((v >> 24) & 0xFF); b.push_back((v >> 16) & 0xFF); b.push_back((v >> 8) & 0xFF); b.push_back(v & 0xFF); }
inline void PutU32LE(Bytes& b, uint32_t v) { b.push_back(v & 0xFF); b.push_back((v >> 8) & 0xFF); b.push_back((v >> 16) & 0xFF); b.push_back((v >> 24) & 0xFF); }
inline void PutDoubleBE(Bytes& b, double v) {
    uint64_t bits; std::memcpy(&bits, &v, 8);
    for (int i = 7; i >= 0; --i) b.push_back((bits >> (i * 8)) & 0xFF);
}
inline void PutBytes(Bytes& b, const uint8_t* p, size_t n) { b.insert(b.end(), p, p + n); }
}
```

Add `test_byte_writer.cpp` to `add_executable(core_tests ...)` in `test/CMakeLists.txt`.

- [ ] **Step 4: Run — verify pass**

Run: `cmake --build build-test && ctest --test-dir build-test -R ByteWriter --output-on-failure`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/byte_writer.h app/src/main/cpp/test
git commit -m "feat(m1a): byte_writer big-endian/LE/double helpers (TDD)"
```

---

### Task 2: AMF0 encoder

Vectors from `MoblinTests/AmfSuite.swift`. AMF0 markers: number `0x00`, bool `0x01`, string `0x02`, object `0x03`, null `0x05`, ecma-array `0x08`, object-end `0x09`.

**Files:**
- Create: `app/src/main/cpp/core/amf0.h`, `app/src/main/cpp/core/amf0.cpp`
- Test: `app/src/main/cpp/test/test_amf0.cpp`

- [ ] **Step 1: Write the failing test (exact Moblin vectors)**

`app/src/main/cpp/test/test_amf0.cpp`:
```cpp
#include "amf0.h"
#include "test_helpers.h"
using namespace ps;
TEST(Amf0, Number)  { Bytes b; Amf0::Number(b, 1.0);  EXPECT_BYTES(b, {0x00,0x3F,0xF0,0,0,0,0,0,0}); }
TEST(Amf0, BoolT)   { Bytes b; Amf0::Boolean(b, true);  EXPECT_BYTES(b, {0x01,0x01}); }
TEST(Amf0, BoolF)   { Bytes b; Amf0::Boolean(b, false); EXPECT_BYTES(b, {0x01,0x00}); }
TEST(Amf0, String)  { Bytes b; Amf0::String(b, "1234"); EXPECT_BYTES(b, {0x02,0x00,0x04,'1','2','3','4'}); }
TEST(Amf0, Null)    { Bytes b; Amf0::Null(b);           EXPECT_BYTES(b, {0x05}); }
TEST(Amf0, Object)  {
    Bytes b; Amf0::ObjectBegin(b);
    Amf0::Key(b, "1"); Amf0::String(b, "2");
    Amf0::ObjectEnd(b);
    EXPECT_BYTES(b, {0x03, 0x00,0x01,'1', 0x02,0x00,0x01,'2', 0x00,0x00,0x09});
}
TEST(Amf0, EcmaArray) {
    Bytes b; Amf0::EcmaArrayBegin(b, 2);
    Amf0::Key(b, "foo"); Amf0::Boolean(b, true);
    Amf0::Key(b, "bar"); Amf0::String(b, "fie");
    Amf0::ObjectEnd(b);
    EXPECT_BYTES(b, {0x08, 0,0,0,2,
        0x00,0x03,'f','o','o', 0x01,0x01,
        0x00,0x03,'b','a','r', 0x02,0x00,0x03,'f','i','e',
        0x00,0x00,0x09});
}
```

- [ ] **Step 2: Run — verify it fails** (`amf0.h` missing).
Run: `cmake --build build-test 2>&1 | tail -3` → compile error.

- [ ] **Step 3: Implement**

`app/src/main/cpp/core/amf0.h`:
```cpp
#pragma once
#include <string>
#include "byte_writer.h"
namespace ps {
struct Amf0 {
    static void Number(Bytes& b, double v);
    static void Boolean(Bytes& b, bool v);
    static void String(Bytes& b, const std::string& s);     // with 0x02 marker
    static void Key(Bytes& b, const std::string& s);        // bare u16-len string, NO marker
    static void Null(Bytes& b);
    static void ObjectBegin(Bytes& b);                      // 0x03
    static void EcmaArrayBegin(Bytes& b, uint32_t count);   // 0x08 + count
    static void ObjectEnd(Bytes& b);                        // 00 00 09
};
}
```

`app/src/main/cpp/core/amf0.cpp`:
```cpp
#include "amf0.h"
namespace ps {
void Amf0::Key(Bytes& b, const std::string& s) {
    PutU16BE(b, (uint16_t)s.size());
    PutBytes(b, (const uint8_t*)s.data(), s.size());
}
void Amf0::Number(Bytes& b, double v) { PutU8(b, 0x00); PutDoubleBE(b, v); }
void Amf0::Boolean(Bytes& b, bool v) { PutU8(b, 0x01); PutU8(b, v ? 1 : 0); }
void Amf0::String(Bytes& b, const std::string& s) { PutU8(b, 0x02); Key(b, s); }
void Amf0::Null(Bytes& b) { PutU8(b, 0x05); }
void Amf0::ObjectBegin(Bytes& b) { PutU8(b, 0x03); }
void Amf0::EcmaArrayBegin(Bytes& b, uint32_t count) { PutU8(b, 0x08); PutU32BE(b, count); }
void Amf0::ObjectEnd(Bytes& b) { PutU8(b, 0x00); PutU8(b, 0x00); PutU8(b, 0x09); }
}
```

Add `amf0.cpp` to `core/CMakeLists.txt` sources; add `test_amf0.cpp` to test exe.

- [ ] **Step 4: Run — verify pass**
Run: `cmake --build build-test && ctest --test-dir build-test -R Amf0 --output-on-failure`
Expected: 7 PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/amf0.* app/src/main/cpp/core/CMakeLists.txt app/src/main/cpp/test
git commit -m "feat(m1a): AMF0 encoder matching Moblin AmfSuite vectors (TDD)"
```

---

### Task 3: AMF0 minimal decoder (read server replies)

Only what the client needs: read a top-level command name (string), and find a string value for a given object key (to detect `code == "NetConnection.Connect.Success"` / `"NetStream.Publish.Start"`), and read a number (createStream's stream id). Validated against the real `_result` vector in `AmfSuite.swift:160+`.

**Files:**
- Modify: `app/src/main/cpp/core/amf0.h`, `app/src/main/cpp/core/amf0.cpp`
- Test: `app/src/main/cpp/test/test_amf0.cpp`

- [ ] **Step 1: Write the failing test**

Append to `test_amf0.cpp`:
```cpp
TEST(Amf0Decode, ReadCommandName) {
    // "_result", number 1, then object... (truncated real reply head)
    Bytes msg = {0x02,0x00,0x07,'_','r','e','s','u','l','t', 0x00,0x3F,0xF0,0,0,0,0,0,0, 0x05};
    Amf0Reader r(msg.data(), msg.size());
    EXPECT_EQ(r.ReadString(), "_result");
    EXPECT_EQ(r.ReadNumber(), 1.0);
}
TEST(Amf0Decode, FindStatusCode) {
    // onStatus info object {level:"status", code:"NetStream.Publish.Start"}
    Bytes obj = {0x03,
        0x00,0x05,'l','e','v','e','l', 0x02,0x00,0x06,'s','t','a','t','u','s',
        0x00,0x04,'c','o','d','e', 0x02,0x00,0x17,
        'N','e','t','S','t','r','e','a','m','.','P','u','b','l','i','s','h','.','S','t','a','r','t',
        0x00,0x00,0x09};
    EXPECT_EQ(Amf0::FindStringValue(obj, "code"), "NetStream.Publish.Start");
}
```

- [ ] **Step 2: Run — verify it fails** (`Amf0Reader` undefined).

- [ ] **Step 3: Implement** — append to `amf0.h`:
```cpp
class Amf0Reader {
public:
    Amf0Reader(const uint8_t* p, size_t n) : p_(p), n_(n), i_(0) {}
    std::string ReadString();   // expects 0x02 marker
    double ReadNumber();        // expects 0x00 marker
    bool eof() const { return i_ >= n_; }
private:
    const uint8_t* p_; size_t n_; size_t i_;
    uint16_t u16() { uint16_t v = (p_[i_] << 8) | p_[i_+1]; i_ += 2; return v; }
};
```
Add to `struct Amf0`: `static std::string FindStringValue(const Bytes& obj, const std::string& key);`

Append to `amf0.cpp`:
```cpp
#include <cstring>
std::string Amf0Reader::ReadString() {
    if (i_ >= n_ || p_[i_] != 0x02) return {};
    ++i_; uint16_t len = u16();
    std::string s((const char*)p_ + i_, len); i_ += len; return s;
}
double Amf0Reader::ReadNumber() {
    if (i_ >= n_ || p_[i_] != 0x00) return 0; ++i_;
    uint64_t bits = 0; for (int k = 0; k < 8; ++k) bits = (bits << 8) | p_[i_++];
    double d; std::memcpy(&d, &bits, 8); return d;
}
// Scan an AMF0 object body for `key` whose value is a string. Minimal: handles
// string/number/bool/null values so it can skip past them to find the wanted key.
std::string Amf0::FindStringValue(const Bytes& obj, const std::string& key) {
    size_t i = 0, n = obj.size();
    if (i < n && obj[i] == 0x03) ++i;            // skip object marker if present
    while (i + 2 <= n) {
        uint16_t klen = (obj[i] << 8) | obj[i+1]; i += 2;
        if (klen == 0) break;                     // object end
        std::string k((const char*)&obj[i], klen); i += klen;
        if (i >= n) break;
        uint8_t marker = obj[i++];
        if (marker == 0x02) {                     // string value
            uint16_t vlen = (obj[i] << 8) | obj[i+1]; i += 2;
            std::string v((const char*)&obj[i], vlen); i += vlen;
            if (k == key) return v;
        } else if (marker == 0x00) { i += 8; }    // number
        else if (marker == 0x01) { i += 1; }      // bool
        else if (marker == 0x05) { /* null */ }
        else break;                                // unknown -> stop
    }
    return {};
}
```

- [ ] **Step 4: Run — verify pass**
Run: `cmake --build build-test && ctest --test-dir build-test -R Amf0Decode --output-on-failure` → 2 PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/amf0.* app/src/main/cpp/test/test_amf0.cpp
git commit -m "feat(m1a): minimal AMF0 decoder (command name, number, status code) (TDD)"
```

---

### Task 4: Transport interface + StubTransport

**Files:**
- Create: `app/src/main/cpp/core/transport.h`, `app/src/main/cpp/core/stub_transport.h`
- Test: `app/src/main/cpp/test/test_transport.cpp`

- [ ] **Step 1: Write the failing test**

`app/src/main/cpp/test/test_transport.cpp`:
```cpp
#include "stub_transport.h"
#include "test_helpers.h"
using namespace ps;
TEST(StubTransport, CapturesWrites) {
    StubTransport t;
    EXPECT_TRUE(t.Connect("ignored", 1935));
    t.Write({0xDE,0xAD}); t.Write({0xBE,0xEF});
    EXPECT_BYTES(t.written(), {0xDE,0xAD,0xBE,0xEF});
    EXPECT_TRUE(t.connected());
    t.Close();
    EXPECT_FALSE(t.connected());
}
TEST(StubTransport, FeedsReads) {
    StubTransport t; t.Connect("x", 1);
    t.FeedIncoming({0x01,0x02,0x03});
    uint8_t buf[8]; int n = t.Read(buf, sizeof(buf));
    EXPECT_EQ(n, 3); EXPECT_EQ(buf[0], 0x01); EXPECT_EQ(buf[2], 0x03);
}
```

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement**

`app/src/main/cpp/core/transport.h`:
```cpp
#pragma once
#include <cstdint>
#include <vector>
namespace ps {
// Byte pipe to a server. Implementations: TcpTransport (real), StubTransport (tests).
class Transport {
public:
    virtual ~Transport() = default;
    virtual bool Connect(const std::string& host, uint16_t port) = 0;
    virtual bool Write(const std::vector<uint8_t>& data) = 0;
    virtual int  Read(uint8_t* buf, int maxLen) = 0;   // blocking; >0 bytes, 0 closed, <0 error
    virtual void Close() = 0;
    virtual bool connected() const = 0;
};
}
```
(Add `#include <string>` at top.)

`app/src/main/cpp/core/stub_transport.h`:
```cpp
#pragma once
#include <algorithm>
#include "transport.h"
namespace ps {
class StubTransport : public Transport {
public:
    bool Connect(const std::string&, uint16_t) override { connected_ = true; return true; }
    bool Write(const std::vector<uint8_t>& d) override { written_.insert(written_.end(), d.begin(), d.end()); return true; }
    int Read(uint8_t* buf, int maxLen) override {
        int n = std::min((int)(incoming_.size() - rpos_), maxLen);
        for (int i = 0; i < n; ++i) buf[i] = incoming_[rpos_++];
        return n;
    }
    void Close() override { connected_ = false; }
    bool connected() const override { return connected_; }
    // test helpers
    const std::vector<uint8_t>& written() const { return written_; }
    void clear() { written_.clear(); }
    void FeedIncoming(const std::vector<uint8_t>& d) { incoming_.insert(incoming_.end(), d.begin(), d.end()); }
private:
    bool connected_ = false;
    std::vector<uint8_t> written_, incoming_;
    size_t rpos_ = 0;
};
}
```
Add `test_transport.cpp` to test exe.

- [ ] **Step 4: Run — verify pass** (`ctest -R StubTransport`) → 2 PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/transport.h app/src/main/cpp/core/stub_transport.h app/src/main/cpp/test
git commit -m "feat(m1a): Transport interface + StubTransport double (TDD)"
```

---

### Task 5: RTMP handshake

Simple (unencrypted) handshake. C0=`0x03`; C1 = 1536 bytes = 4-byte time(0) + 4 zero + 1528 payload. C0C1 total 1537. C2 = 1536 = S1[0:4] time + 4 echo-time(0) + S1[8:1536] (1528 bytes). Ref: `Rtmp/RtmpHandshake.swift`.

**Files:**
- Create: `app/src/main/cpp/core/rtmp_handshake.h`, `.cpp`
- Test: `app/src/main/cpp/test/test_rtmp_handshake.cpp`

- [ ] **Step 1: Write the failing test**

`test/test_rtmp_handshake.cpp`:
```cpp
#include "rtmp_handshake.h"
#include "test_helpers.h"
using namespace ps;
TEST(Handshake, C0C1ShapeAndVersion) {
    RtmpHandshake h;
    Bytes c0c1 = h.BuildC0C1();
    ASSERT_EQ(c0c1.size(), 1537u);
    EXPECT_EQ(c0c1[0], 0x03);                 // C0 version
    EXPECT_EQ(c0c1[1], 0); EXPECT_EQ(c0c1[2], 0);
    EXPECT_EQ(c0c1[3], 0); EXPECT_EQ(c0c1[4], 0);   // time = 0
    EXPECT_EQ(c0c1[5], 0); EXPECT_EQ(c0c1[8], 0);   // 4 zero bytes
}
TEST(Handshake, C2EchoesS1) {
    RtmpHandshake h;
    Bytes s0s1(1537, 0);
    s0s1[1]=0x11; s0s1[2]=0x22; s0s1[3]=0x33; s0s1[4]=0x44;  // S1 time
    for (size_t i = 9; i < 1537; ++i) s0s1[i] = (uint8_t)(i & 0xFF); // S1 random
    Bytes c2 = h.BuildC2(s0s1);
    ASSERT_EQ(c2.size(), 1536u);
    EXPECT_EQ(c2[0],0x11); EXPECT_EQ(c2[1],0x22); EXPECT_EQ(c2[2],0x33); EXPECT_EQ(c2[3],0x44);
    EXPECT_EQ(c2[4],0); EXPECT_EQ(c2[7],0);                   // echo time = 0
    EXPECT_EQ(c2[8], (uint8_t)(9 & 0xFF));                    // first random byte echoed
    EXPECT_EQ(c2[1535], (uint8_t)(1536 & 0xFF));
}
```

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement**

`core/rtmp_handshake.h`:
```cpp
#pragma once
#include "byte_writer.h"
namespace ps {
class RtmpHandshake {
public:
    static constexpr int kSig = 1536;
    Bytes BuildC0C1();             // 1537 bytes
    Bytes BuildC2(const Bytes& s0s1);  // 1536 bytes
};
}
```
`core/rtmp_handshake.cpp`:
```cpp
#include "rtmp_handshake.h"
namespace ps {
Bytes RtmpHandshake::BuildC0C1() {
    Bytes b; b.reserve(1 + kSig);
    PutU8(b, 0x03);
    PutU32BE(b, 0);            // time
    PutU32BE(b, 0);            // zero
    for (int i = 0; i < kSig - 8; ++i) PutU8(b, (uint8_t)(i & 0xFF)); // deterministic "random"
    return b;
}
Bytes RtmpHandshake::BuildC2(const Bytes& s0s1) {
    Bytes b; b.reserve(kSig);
    PutBytes(b, s0s1.data() + 1, 4);         // S1 time (bytes 1..4)
    PutU32BE(b, 0);                          // echo time
    PutBytes(b, s0s1.data() + 9, kSig - 8);  // S1 random (bytes 9..1536)
    return b;
}
}
```
Add sources/tests to CMake.

- [ ] **Step 4: Run — verify pass** (`ctest -R Handshake`) → 2 PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/rtmp_handshake.* app/src/main/cpp/core/CMakeLists.txt app/src/main/cpp/test
git commit -m "feat(m1a): RTMP simple handshake C0C1/C2 (TDD)"
```

---

### Task 6: RTMP chunk encoder (header + fragmentation)

Encode one message into chunks. Basic header for csid 2..63 = `(fmt<<6)|csid` (1 byte). Type-0 message header: timestamp(3 BE) + length(3 BE) + type(1) + streamId(4 **LE**). Extended timestamp: if ts ≥ 0xFFFFFF, write `0xFFFFFF` then 4-byte BE ts. Payloads larger than the out chunk size split into continuation chunks with a type-3 basic header (`0xC0|csid`). Default out chunk size 128; we raise to 4096 after connect. Ref: `Rtmp/RtmpChunk.swift`.

**Files:**
- Create: `app/src/main/cpp/core/rtmp_chunk.h`, `.cpp`
- Test: `app/src/main/cpp/test/test_rtmp_chunk.cpp`

- [ ] **Step 1: Write the failing test**

`test/test_rtmp_chunk.cpp`:
```cpp
#include "rtmp_chunk.h"
#include "test_helpers.h"
using namespace ps;
TEST(Chunk, Type0SmallMessage) {
    // csid=3, type=0x14, msgStreamId=0, ts=0, payload {0xAA,0xBB,0xCC}
    Bytes out = ChunkEncode(/*csid*/3, /*type*/0x14, /*msgStreamId*/0, /*ts*/0,
                            {0xAA,0xBB,0xCC}, /*chunkSize*/128);
    EXPECT_BYTES(out, {0x03,            // fmt0 | csid3
                       0x00,0x00,0x00,  // ts
                       0x00,0x00,0x03,  // length 3
                       0x14,            // type
                       0x00,0x00,0x00,0x00, // streamId LE
                       0xAA,0xBB,0xCC});
}
TEST(Chunk, FragmentsAtChunkSize) {
    Bytes payload(200, 0x55);
    Bytes out = ChunkEncode(5, 0x09, 1, 0, payload, /*chunkSize*/128);
    // header(12) + 128 + type3 basic header(1) + 72 = 213
    EXPECT_EQ(out.size(), 12u + 128u + 1u + 72u);
    EXPECT_EQ(out[0], 0x05);             // fmt0|csid5
    EXPECT_EQ(out[12 + 128], 0xC5);      // fmt3|csid5 continuation
}
TEST(Chunk, ExtendedTimestamp) {
    Bytes out = ChunkEncode(4, 0x08, 1, 0x1000000, {0x01}, 128);
    EXPECT_EQ(out[1], 0xFF); EXPECT_EQ(out[2], 0xFF); EXPECT_EQ(out[3], 0xFF); // marker
    // ext ts (4 BE) sits after the 1-byte basic header + 11-byte type0 header = index 12
    EXPECT_EQ(out[12], 0x01); EXPECT_EQ(out[13], 0x00);
    EXPECT_EQ(out[14], 0x00); EXPECT_EQ(out[15], 0x00);
}
```

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement**

`core/rtmp_chunk.h`:
```cpp
#pragma once
#include "byte_writer.h"
namespace ps {
// Encode a full RTMP message (type-0 first chunk + type-3 continuations) into bytes.
Bytes ChunkEncode(uint8_t csid, uint8_t msgType, uint32_t msgStreamId,
                  uint32_t timestamp, const Bytes& payload, uint32_t chunkSize);
}
```
`core/rtmp_chunk.cpp`:
```cpp
#include "rtmp_chunk.h"
namespace ps {
Bytes ChunkEncode(uint8_t csid, uint8_t msgType, uint32_t msgStreamId,
                  uint32_t timestamp, const Bytes& payload, uint32_t chunkSize) {
    Bytes b;
    bool ext = timestamp >= 0xFFFFFF;
    PutU8(b, (0 << 6) | (csid & 0x3F));            // fmt0 basic header
    PutU24BE(b, ext ? 0xFFFFFF : timestamp);       // timestamp / marker
    PutU24BE(b, (uint32_t)payload.size());         // message length
    PutU8(b, msgType);                             // message type id
    PutU32LE(b, msgStreamId);                      // stream id (little-endian!)
    if (ext) PutU32BE(b, timestamp);               // extended timestamp
    // payload, split across chunks
    size_t off = 0, n = payload.size();
    size_t first = std::min((size_t)chunkSize, n);
    PutBytes(b, payload.data(), first); off = first;
    while (off < n) {
        PutU8(b, (3 << 6) | (csid & 0x3F));        // fmt3 continuation
        size_t take = std::min((size_t)chunkSize, n - off);
        PutBytes(b, payload.data() + off, take); off += take;
    }
    return b;
}
}
```
(Add `#include <algorithm>`.) Add sources/tests to CMake.

- [ ] **Step 4: Run — verify pass** (`ctest -R Chunk`) → 3 PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/rtmp_chunk.* app/src/main/cpp/core/CMakeLists.txt app/src/main/cpp/test
git commit -m "feat(m1a): RTMP chunk encoder with fragmentation + ext timestamp (TDD)"
```

---

### Task 7: FLV — build avcC from SPS/PPS

Android-specific: build `AVCDecoderConfigurationRecord` ourselves (Apple hands this pre-built; Android does not). Layout (ref `Mpeg/Avc/MpegTsVideoConfigAvc.swift`): `01, SPS[1], SPS[2], SPS[3], 0xFF (lengthSizeMinusOne=3), 0xE1 (numSPS=1), SPSlen(2 BE), SPS, 01 (numPPS), PPSlen(2 BE), PPS`. SPS/PPS passed in **without** Annex-B start codes.

**Files:**
- Create: `app/src/main/cpp/core/flv.h`, `.cpp`
- Test: `app/src/main/cpp/test/test_flv.cpp`

- [ ] **Step 1: Write the failing test**
```cpp
#include "flv.h"
#include "test_helpers.h"
using namespace ps;
TEST(Flv, AvcCFromSpsPps) {
    Bytes sps = {0x67, 0x42, 0xC0, 0x1F, 0xAA};  // [0]=nal hdr, [1..3]=profile/compat/level
    Bytes pps = {0x68, 0xCE, 0x3C, 0x80};
    Bytes avcc = BuildAvcC(sps, pps);
    EXPECT_BYTES(avcc, {0x01, 0x42, 0xC0, 0x1F, 0xFF, 0xE1,
                        0x00,0x05, 0x67,0x42,0xC0,0x1F,0xAA,
                        0x01, 0x00,0x04, 0x68,0xCE,0x3C,0x80});
}
```

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement**

`core/flv.h`:
```cpp
#pragma once
#include "byte_writer.h"
namespace ps {
Bytes BuildAvcC(const Bytes& sps, const Bytes& pps);
}
```
`core/flv.cpp`:
```cpp
#include "flv.h"
namespace ps {
Bytes BuildAvcC(const Bytes& sps, const Bytes& pps) {
    Bytes b;
    PutU8(b, 0x01);            // configurationVersion
    PutU8(b, sps[1]);          // AVCProfileIndication
    PutU8(b, sps[2]);          // profile_compatibility
    PutU8(b, sps[3]);          // AVCLevelIndication
    PutU8(b, 0xFF);            // 6 bits reserved + lengthSizeMinusOne=3
    PutU8(b, 0xE1);            // 3 bits reserved + numSPS=1
    PutU16BE(b, (uint16_t)sps.size()); PutBytes(b, sps.data(), sps.size());
    PutU8(b, 0x01);            // numPPS=1
    PutU16BE(b, (uint16_t)pps.size()); PutBytes(b, pps.data(), pps.size());
    return b;
}
}
```
Add sources/tests to CMake.

- [ ] **Step 4: Run — verify pass** (`ctest -R "Flv.AvcC"`) → PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/flv.* app/src/main/cpp/core/CMakeLists.txt app/src/main/cpp/test/test_flv.cpp
git commit -m "feat(m1a): build avcC config record from SPS/PPS (TDD)"
```

---

### Task 8: FLV — AudioSpecificConfig (AAC-LC)

ASC is 2 bytes: `byte0 = (objectType<<3) | (freqIndex>>1)`, `byte1 = ((freqIndex&1)<<7) | (channels<<3)`. AAC-LC objectType=2. Sample-rate index table: 48000→3, 44100→4, 32000→5, 24000→6, 22050→7, 16000→8. Worked example **44100 stereo → `0x12 0x10`** (NOT 0x1290 — that's a 48 kHz artifact). Ref: `Mpeg/MpegTsAudioConfig.swift`.

**Files:** Modify `flv.h`/`flv.cpp`; Test `test_flv.cpp`.

- [ ] **Step 1: Write the failing test**
```cpp
TEST(Flv, Asc44kStereo) { EXPECT_BYTES(BuildAsc(44100, 2), {0x12, 0x10}); }
TEST(Flv, Asc48kStereo) { EXPECT_BYTES(BuildAsc(48000, 2), {0x11, 0x90}); }
TEST(Flv, Asc44kMono)   { EXPECT_BYTES(BuildAsc(44100, 1), {0x12, 0x08}); }
```

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement** — append to `flv.h`: `Bytes BuildAsc(int sampleRate, int channels);` and to `flv.cpp`:
```cpp
static int FreqIndex(int sr) {
    switch (sr) { case 96000: return 0; case 88200: return 1; case 64000: return 2;
        case 48000: return 3; case 44100: return 4; case 32000: return 5;
        case 24000: return 6; case 22050: return 7; case 16000: return 8;
        case 12000: return 9; case 11025: return 10; case 8000: return 11; default: return 4; }
}
Bytes BuildAsc(int sampleRate, int channels) {
    int objectType = 2;                         // AAC-LC
    int f = FreqIndex(sampleRate);
    Bytes b;
    PutU8(b, (uint8_t)((objectType << 3) | (f >> 1)));
    PutU8(b, (uint8_t)(((f & 1) << 7) | (channels << 3)));
    return b;
}
```

- [ ] **Step 4: Run — verify pass** (`ctest -R "Flv.Asc"`) → 3 PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/flv.* app/src/main/cpp/test/test_flv.cpp
git commit -m "feat(m1a): build AAC AudioSpecificConfig (TDD)"
```

---

### Task 9: FLV — Annex-B → AVCC conversion

Android MediaCodec emits NAL units separated by `00 00 00 01` (or `00 00 01`) start codes. Convert to AVCC: each NAL prefixed with a 4-byte big-endian length. Also need a helper to extract SPS & PPS from a codec-config Annex-B buffer (NAL type = `byte & 0x1F`; SPS=7, PPS=8).

**Files:** Modify `flv.h`/`flv.cpp`; Test `test_flv.cpp`.

- [ ] **Step 1: Write the failing test**
```cpp
TEST(Flv, AnnexBToAvcc) {
    Bytes in = {0,0,0,1, 0x65,0xAA,0xBB, 0,0,1, 0x41,0xCC};
    EXPECT_BYTES(AnnexBToAvcc(in), {0,0,0,3, 0x65,0xAA,0xBB, 0,0,0,2, 0x41,0xCC});
}
TEST(Flv, SplitSpsPps) {
    Bytes cfg = {0,0,0,1, 0x67,0x42,0xC0,0x1F,0xAA, 0,0,0,1, 0x68,0xCE,0x3C,0x80};
    Bytes sps, pps; SplitSpsPps(cfg, sps, pps);
    EXPECT_BYTES(sps, {0x67,0x42,0xC0,0x1F,0xAA});
    EXPECT_BYTES(pps, {0x68,0xCE,0x3C,0x80});
}
```

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement** — append declarations to `flv.h`:
```cpp
Bytes AnnexBToAvcc(const Bytes& annexb);
void  SplitSpsPps(const Bytes& cfg, Bytes& sps, Bytes& pps);
```
and to `flv.cpp`:
```cpp
#include <vector>
// Find start-code positions (3- or 4-byte). Returns list of NAL byte ranges [start,end).
static std::vector<std::pair<size_t,size_t>> FindNals(const Bytes& d) {
    std::vector<size_t> starts; size_t i = 0, n = d.size();
    while (i + 3 <= n) {
        bool sc4 = (i + 4 <= n && d[i]==0 && d[i+1]==0 && d[i+2]==0 && d[i+3]==1);
        bool sc3 = (d[i]==0 && d[i+1]==0 && d[i+2]==1);
        if (sc4) { starts.push_back(i + 4); i += 4; }
        else if (sc3) { starts.push_back(i + 3); i += 3; }
        else ++i;
    }
    std::vector<std::pair<size_t,size_t>> nals;
    for (size_t k = 0; k < starts.size(); ++k) {
        size_t s = starts[k];
        size_t e = (k + 1 < starts.size()) ? starts[k+1] : n;
        // trim the trailing start code of the next NAL (back up over 0,0,0,1 / 0,0,1)
        if (k + 1 < starts.size()) { e -= (d[e-1]==1 && d[e-2]==0 && d[e-3]==0 && (e>=4 && d[e-4]==0)) ? 4 : 3; }
        nals.push_back({s, e});
    }
    return nals;
}
Bytes AnnexBToAvcc(const Bytes& annexb) {
    Bytes out;
    for (auto [s, e] : FindNals(annexb)) {
        PutU32BE(out, (uint32_t)(e - s));
        PutBytes(out, annexb.data() + s, e - s);
    }
    return out;
}
void SplitSpsPps(const Bytes& cfg, Bytes& sps, Bytes& pps) {
    for (auto [s, e] : FindNals(cfg)) {
        uint8_t type = cfg[s] & 0x1F;
        Bytes nal(cfg.begin() + s, cfg.begin() + e);
        if (type == 7) sps = nal; else if (type == 8) pps = nal;
    }
}
```

- [ ] **Step 4: Run — verify pass** (`ctest -R "Flv.AnnexB|Flv.SplitSpsPps"`) → 2 PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/flv.* app/src/main/cpp/test/test_flv.cpp
git commit -m "feat(m1a): Annex-B to AVCC + SPS/PPS split (TDD)"
```

---

### Task 10: FLV video tag bodies

Video tag body: `byte0 = (frameType<<4) | 7` (key=1→0x17, inter=2→0x27); `byte1` = AVC packet type (0 seq header, 1 NALU); `bytes2..4` = composition time (3 BE, =(PTS−DTS)/1000 ms units; 0 with no B-frames); then payload (avcC for seq header; AVCC NALs for frame). Ref `Rtmp/RtmpStream.swift:408-465`.

**Files:** Modify `flv.h`/`flv.cpp`; Test `test_flv.cpp`.

- [ ] **Step 1: Write the failing test**
```cpp
TEST(Flv, VideoSeqHeader) {
    Bytes avcc = {0x01,0x42,0xC0,0x1F};
    EXPECT_BYTES(FlvVideoSeqHeader(avcc),
        {0x17, 0x00, 0x00,0x00,0x00, 0x01,0x42,0xC0,0x1F});
}
TEST(Flv, VideoKeyFrame) {
    Bytes avccNals = {0,0,0,2, 0x65,0xAA};
    EXPECT_BYTES(FlvVideoFrame(avccNals, /*key*/true, /*cts*/0),
        {0x17, 0x01, 0x00,0x00,0x00, 0,0,0,2, 0x65,0xAA});
}
TEST(Flv, VideoInterFrameCts) {
    Bytes avccNals = {0,0,0,1, 0x41};
    EXPECT_BYTES(FlvVideoFrame(avccNals, false, /*cts*/40),
        {0x27, 0x01, 0x00,0x00,0x28, 0,0,0,1, 0x41});
}
```

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement** — append to `flv.h`:
```cpp
Bytes FlvVideoSeqHeader(const Bytes& avcc);
Bytes FlvVideoFrame(const Bytes& avccNals, bool keyframe, uint32_t compositionTimeMs);
```
to `flv.cpp`:
```cpp
Bytes FlvVideoSeqHeader(const Bytes& avcc) {
    Bytes b; PutU8(b, 0x17); PutU8(b, 0x00); PutU24BE(b, 0);
    PutBytes(b, avcc.data(), avcc.size()); return b;
}
Bytes FlvVideoFrame(const Bytes& avccNals, bool keyframe, uint32_t cts) {
    Bytes b; PutU8(b, keyframe ? 0x17 : 0x27); PutU8(b, 0x01); PutU24BE(b, cts);
    PutBytes(b, avccNals.data(), avccNals.size()); return b;
}
```

- [ ] **Step 4: Run — verify pass** (`ctest -R "Flv.Video"`) → 3 PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/flv.* app/src/main/cpp/test/test_flv.cpp
git commit -m "feat(m1a): FLV video tag bodies (seq header + frames) (TDD)"
```

---

### Task 11: FLV audio tag bodies (AAC)

Audio tag body: `byte0 = 0xAF` (AAC=10<<4 | rate=3<<2 | 16-bit=1<<1 | stereo=1; constant for AAC); `byte1` = AAC packet type (0 seq header/ASC, 1 raw); then payload. Ref `Rtmp/RtmpStream.swift:351-406`.

**Files:** Modify `flv.h`/`flv.cpp`; Test `test_flv.cpp`.

- [ ] **Step 1: Write the failing test**
```cpp
TEST(Flv, AudioSeqHeader) {
    Bytes asc = {0x12,0x10};
    EXPECT_BYTES(FlvAudioSeqHeader(asc), {0xAF, 0x00, 0x12, 0x10});
}
TEST(Flv, AudioRaw) {
    Bytes aac = {0xDE,0xAD,0xBE,0xEF};
    EXPECT_BYTES(FlvAudioFrame(aac), {0xAF, 0x01, 0xDE,0xAD,0xBE,0xEF});
}
```

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement** — append to `flv.h`:
```cpp
Bytes FlvAudioSeqHeader(const Bytes& asc);
Bytes FlvAudioFrame(const Bytes& aacRaw);
```
to `flv.cpp`:
```cpp
Bytes FlvAudioSeqHeader(const Bytes& asc) {
    Bytes b; PutU8(b, 0xAF); PutU8(b, 0x00); PutBytes(b, asc.data(), asc.size()); return b;
}
Bytes FlvAudioFrame(const Bytes& aacRaw) {
    Bytes b; PutU8(b, 0xAF); PutU8(b, 0x01); PutBytes(b, aacRaw.data(), aacRaw.size()); return b;
}
```

- [ ] **Step 4: Run — verify pass** (`ctest -R "Flv.Audio"`) → 2 PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/flv.* app/src/main/cpp/test/test_flv.cpp
git commit -m "feat(m1a): FLV audio tag bodies (AAC seq header + raw) (TDD)"
```

---

### Task 12: onMetaData (@setDataFrame) builder

AMF0 data payload: string `"@setDataFrame"`, string `"onMetaData"`, ecma-array `{ width, height, framerate, videocodecid:7, audiocodecid:10, audiosamplerate }`. Ref `Rtmp/RtmpStream.swift:221-267`.

**Files:** Modify `flv.h`/`flv.cpp`; Test `test_flv.cpp`.

- [ ] **Step 1: Write the failing test**
```cpp
TEST(Flv, OnMetaDataShape) {
    Bytes m = BuildOnMetaData(1280, 720, 30.0, 44100);
    // starts with AMF0 string "@setDataFrame"
    EXPECT_EQ(m[0], 0x02); EXPECT_EQ(m[1], 0x00); EXPECT_EQ(m[2], 13);
    EXPECT_EQ(std::string((char*)&m[3], 13), "@setDataFrame");
    // contains "onMetaData" and ends with object-end 00 00 09
    std::string s((char*)m.data(), m.size());
    EXPECT_NE(s.find("onMetaData"), std::string::npos);
    EXPECT_NE(s.find("videocodecid"), std::string::npos);
    ASSERT_GE(m.size(), 3u);
    EXPECT_EQ(m[m.size()-1], 0x09);
    EXPECT_EQ(m[m.size()-2], 0x00);
    EXPECT_EQ(m[m.size()-3], 0x00);
}
```

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement** — append to `flv.h`: `Bytes BuildOnMetaData(int w, int h, double fps, int sampleRate);` and to `flv.cpp` (add `#include "amf0.h"`):
```cpp
Bytes BuildOnMetaData(int w, int h, double fps, int sampleRate) {
    Bytes b;
    Amf0::String(b, "@setDataFrame");
    Amf0::String(b, "onMetaData");
    Amf0::EcmaArrayBegin(b, 6);
    Amf0::Key(b, "width");          Amf0::Number(b, w);
    Amf0::Key(b, "height");         Amf0::Number(b, h);
    Amf0::Key(b, "framerate");      Amf0::Number(b, fps);
    Amf0::Key(b, "videocodecid");   Amf0::Number(b, 7);   // AVC
    Amf0::Key(b, "audiocodecid");   Amf0::Number(b, 10);  // AAC
    Amf0::Key(b, "audiosamplerate"); Amf0::Number(b, sampleRate);
    Amf0::ObjectEnd(b);
    return b;
}
```

- [ ] **Step 4: Run — verify pass** (`ctest -R "Flv.OnMetaData"`) → PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/flv.* app/src/main/cpp/test/test_flv.cpp
git commit -m "feat(m1a): onMetaData/@setDataFrame builder (TDD)"
```

---

### Task 13: RtmpClient — connect command bytes

`RtmpClient` owns a `Transport&` and a `StreamParams{host, port, app, streamKey, tcUrl, width, height, fps, sampleRate}`. `BeginConnect()` writes C0C1, then (driven separately after S0S1) writes C2 and the AMF0 `connect` command. This task tests the **connect command bytes** in isolation via a public helper, against the Moblin `connect` object (`RtmpStreamSuite.swift:407-438`).

**Files:**
- Create: `app/src/main/cpp/core/rtmp_client.h`, `.cpp`
- Test: `app/src/main/cpp/test/test_rtmp_client.cpp`

- [ ] **Step 1: Write the failing test**
```cpp
#include "rtmp_client.h"
#include "stub_transport.h"
#include "test_helpers.h"
using namespace ps;
TEST(RtmpClient, ConnectCommandObject) {
    StreamParams p; p.app = "live"; p.tcUrl = "rtmp://h/live"; p.streamKey = "key";
    Bytes payload = BuildConnectCommand(p, /*txn*/1);
    // command name "connect"
    EXPECT_EQ(payload[0], 0x02); EXPECT_EQ(payload[2], 7);
    EXPECT_EQ(std::string((char*)&payload[3], 7), "connect");
    // transaction id number 1.0 follows
    size_t i = 3 + 7;
    EXPECT_EQ(payload[i], 0x00);                 // number marker
    EXPECT_EQ(payload[i+1], 0x3F); EXPECT_EQ(payload[i+2], 0xF0);
    // object marker then "app"
    EXPECT_EQ(payload[i+9], 0x03);
    std::string s((char*)payload.data(), payload.size());
    EXPECT_NE(s.find("app"), std::string::npos);
    EXPECT_NE(s.find("live"), std::string::npos);
    EXPECT_NE(s.find("tcUrl"), std::string::npos);
    EXPECT_NE(s.find("flashVer"), std::string::npos);
}
```

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement**

`core/rtmp_client.h`:
```cpp
#pragma once
#include <string>
#include "byte_writer.h"
#include "transport.h"
namespace ps {
struct StreamParams {
    std::string host, app, streamKey, tcUrl;
    uint16_t port = 1935;
    int width = 1280, height = 720, sampleRate = 44100;
    double fps = 30.0;
};
// AMF0 body of the `connect` command (without chunk framing). Public for testing.
Bytes BuildConnectCommand(const StreamParams& p, int txn);
}
```
`core/rtmp_client.cpp`:
```cpp
#include "rtmp_client.h"
#include "amf0.h"
namespace ps {
Bytes BuildConnectCommand(const StreamParams& p, int txn) {
    Bytes b;
    Amf0::String(b, "connect");
    Amf0::Number(b, txn);
    Amf0::ObjectBegin(b);
    Amf0::Key(b, "app");           Amf0::String(b, p.app);
    Amf0::Key(b, "flashVer");      Amf0::String(b, "FMLE/3.0 (compatible; FMSc/1.0)");
    Amf0::Key(b, "swfUrl");        Amf0::Null(b);
    Amf0::Key(b, "tcUrl");         Amf0::String(b, p.tcUrl);
    Amf0::Key(b, "fpad");          Amf0::Boolean(b, false);
    Amf0::Key(b, "capabilities");  Amf0::Number(b, 239);
    Amf0::Key(b, "audioCodecs");   Amf0::Number(b, 1024); // AAC
    Amf0::Key(b, "videoCodecs");   Amf0::Number(b, 128);  // H.264
    Amf0::Key(b, "videoFunction"); Amf0::Number(b, 1);
    Amf0::Key(b, "pageUrl");       Amf0::Null(b);
    Amf0::Key(b, "objectEncoding"); Amf0::Number(b, 0);
    Amf0::ObjectEnd(b);
    return b;
}
}
```
Add sources/tests to CMake.

- [ ] **Step 4: Run — verify pass** (`ctest -R "RtmpClient.ConnectCommand"`) → PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/rtmp_client.* app/src/main/cpp/core/CMakeLists.txt app/src/main/cpp/test/test_rtmp_client.cpp
git commit -m "feat(m1a): RTMP connect command builder (TDD)"
```

---

### Task 14: RtmpClient — publish handshake state machine

Drives the full sequence over a `Transport`, reacting to fed server bytes:
handshake → `connect` → (on `_result` Success) SetChunkSize + `releaseStream` + `FCPublish` + `createStream` → (on createStream `_result` with stream id) `publish` → (on `onStatus` Publish.Start) → `Publishing`. Server control/command messages in M1 are small single-chunk messages; an `RtmpReader` de-frames one message at a time. Ref `RtmpConnection.swift`, `RtmpStream.swift`.

**Files:** Modify `rtmp_client.h`/`.cpp`; Test `test_rtmp_client.cpp`.

- [ ] **Step 1: Write the failing test**
```cpp
TEST(RtmpClient, ReachesPublishing) {
    StubTransport t;
    StreamParams p; p.app="live"; p.tcUrl="rtmp://h/live"; p.streamKey="5"; p.host="h";
    RtmpClient c(t, p);
    c.Begin();                                  // writes C0C1
    EXPECT_EQ(c.state(), RtmpState::HandshakeSent);
    ASSERT_GE(t.written().size(), 1537u);
    EXPECT_EQ(t.written()[0], 0x03);

    t.clear();
    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;
    c.OnBytes(s0s1s2);                          // feed S0S1+S2 -> sends C2 + connect
    EXPECT_EQ(c.state(), RtmpState::ConnectSent);

    // feed a _result NetConnection.Connect.Success (single chunk, csid 3, type 0x14)
    c.OnBytes(MakeResultSuccess());             // helper builds the chunk
    EXPECT_EQ(c.state(), RtmpState::CreateStreamSent);

    // feed createStream _result with stream id 1
    c.OnBytes(MakeCreateStreamResult(/*streamId*/1));
    EXPECT_EQ(c.state(), RtmpState::PublishSent);

    // feed onStatus NetStream.Publish.Start
    c.OnBytes(MakePublishStart());
    EXPECT_EQ(c.state(), RtmpState::Publishing);
}
```
(Provide the three `Make*` helpers at the top of the test file — each wraps a hand-built AMF0 body via `ChunkEncode(3, 0x14, 0, 0, body, 128)`. `MakeResultSuccess` body: `String("_result"), Number(1), Null, Object{level:"status", code:"NetConnection.Connect.Success"}`. `MakeCreateStreamResult`: `String("_result"), Number(4), Null, Number(streamId)`. `MakePublishStart`: `String("onStatus"), Number(0), Null, Object{code:"NetStream.Publish.Start"}`.)

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement** — append to `rtmp_client.h`:
```cpp
enum class RtmpState { Idle, HandshakeSent, ConnectSent, CreateStreamSent, PublishSent, Publishing, Error };
class RtmpReader {                              // de-frames single-chunk messages
public:
    void Feed(const Bytes& d) { buf_.insert(buf_.end(), d.begin(), d.end()); }
    // Pops one complete message: returns true and fills (msgType, payload). Skips set-chunk-size etc.
    bool Next(uint8_t& msgType, Bytes& payload);
private:
    Bytes buf_; size_t pos_ = 0;
};
class RtmpClient {
public:
    RtmpClient(Transport& t, StreamParams p) : t_(t), p_(std::move(p)) {}
    void Begin();                  // sends C0C1
    void OnBytes(const Bytes& d);  // advances state machine
    RtmpState state() const { return state_; }
    int streamId() const { return streamId_; }
private:
    void sendCommand(const Bytes& body, int msgStreamId);
    void afterHandshake();
    Transport& t_; StreamParams p_;
    RtmpState state_ = RtmpState::Idle;
    RtmpReader reader_;
    bool handshakeDone_ = false; size_t handshakeNeed_ = 1537 + 1536;
    Bytes hsBuf_;
    int txn_ = 1, streamId_ = 0;
    uint32_t outChunkSize_ = 128;
};
```
Append to `rtmp_client.cpp` (add `#include "rtmp_handshake.h"`, `"rtmp_chunk.h"`, `"flv.h"`):
```cpp
void RtmpClient::Begin() {
    RtmpHandshake h; t_.Write(h.BuildC0C1());
    state_ = RtmpState::HandshakeSent;
}
void RtmpClient::sendCommand(const Bytes& body, int msid) {
    t_.Write(ChunkEncode(3, 0x14, msid, 0, body, outChunkSize_));
}
void RtmpClient::afterHandshake() {
    sendCommand(BuildConnectCommand(p_, ++txn_ == 2 ? 1 : 1), 0); // connect txn=1
    state_ = RtmpState::ConnectSent;
}
void RtmpClient::OnBytes(const Bytes& d) {
    if (!handshakeDone_) {
        hsBuf_.insert(hsBuf_.end(), d.begin(), d.end());
        if (hsBuf_.size() < handshakeNeed_) return;
        // got S0S1 + S2: send C2 (echo S1 = hsBuf_[1..1537])
        RtmpHandshake h;
        Bytes s0s1(hsBuf_.begin(), hsBuf_.begin() + 1537);
        t_.Write(h.BuildC2(s0s1));
        handshakeDone_ = true;
        // feed any leftover bytes to the reader
        if (hsBuf_.size() > handshakeNeed_)
            reader_.Feed(Bytes(hsBuf_.begin() + handshakeNeed_, hsBuf_.end()));
        afterHandshake();
        return;
    }
    reader_.Feed(d);
    uint8_t type; Bytes payload;
    while (reader_.Next(type, payload)) {
        if (type != 0x14) continue;                 // only AMF0 commands drive the FSM
        Amf0Reader r(payload.data(), payload.size());
        std::string name = r.ReadString();
        double rtxn = r.ReadNumber();
        if (name == "_result" && state_ == RtmpState::ConnectSent) {
            // raise chunk size, then releaseStream/FCPublish/createStream
            outChunkSize_ = 4096;
            t_.Write(ChunkEncode(2, 0x01, 0, 0, [] { Bytes b; PutU32BE(b, 4096); return b; }(), 128));
            { Bytes b; Amf0::String(b,"releaseStream"); Amf0::Number(b,++txn_); Amf0::Null(b); Amf0::String(b,p_.streamKey); sendCommand(b,0); }
            { Bytes b; Amf0::String(b,"FCPublish");     Amf0::Number(b,++txn_); Amf0::Null(b); Amf0::String(b,p_.streamKey); sendCommand(b,0); }
            { Bytes b; Amf0::String(b,"createStream");  Amf0::Number(b,++txn_); Amf0::Null(b); sendCommand(b,0); }
            state_ = RtmpState::CreateStreamSent;
        } else if (name == "_result" && state_ == RtmpState::CreateStreamSent) {
            r.ReadString();                 // null command object slot (best-effort skip)
            streamId_ = (int)r.ReadNumber();
            { Bytes b; Amf0::String(b,"publish"); Amf0::Number(b,++txn_); Amf0::Null(b);
              Amf0::String(b,p_.streamKey); Amf0::String(b,"live"); sendCommand(b, streamId_); }
            state_ = RtmpState::PublishSent;
        } else if (name == "onStatus" && state_ == RtmpState::PublishSent) {
            // payload object after name+txn+null contains code
            if (Amf0::FindStringValue(payload, "code") == "NetStream.Publish.Start") {
                t_.Write(ChunkEncode(8, 0x12, streamId_, 0,
                         BuildOnMetaData(p_.width, p_.height, p_.fps, p_.sampleRate), outChunkSize_));
                state_ = RtmpState::Publishing;
            }
        }
        (void)rtxn;
    }
}
// Minimal single-chunk de-framer: handles fmt0 (12-byte header for csid 2..63) messages.
bool RtmpReader::Next(uint8_t& msgType, Bytes& payload) {
    if (pos_ + 12 > buf_.size()) return false;
    uint8_t b0 = buf_[pos_];
    uint8_t fmt = b0 >> 6, csid = b0 & 0x3F;
    if (fmt != 0) { pos_ += 1; return Next(msgType, payload); } // skip non-type0 control noise
    uint32_t len = (buf_[pos_+4] << 16) | (buf_[pos_+5] << 8) | buf_[pos_+6];
    msgType = buf_[pos_+7];
    if (pos_ + 12 + len > buf_.size()) return false;
    payload.assign(buf_.begin() + pos_ + 12, buf_.begin() + pos_ + 12 + len);
    pos_ += 12 + len;
    (void)csid; return true;
}
```
> NOTE for executor: the `FindStringValue(payload, ...)` call scans the whole command payload for the `code` key, which is robust to the leading name/txn/null. Keep `RtmpReader` minimal — real ingests send small single-chunk control/command messages during setup, which this handles. If a server fragments a command (rare), extend the reader; do not over-build now.

- [ ] **Step 4: Run — verify pass** (`ctest -R "RtmpClient.ReachesPublishing"`) → PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/rtmp_client.* app/src/main/cpp/test/test_rtmp_client.cpp
git commit -m "feat(m1a): RTMP connect->publish state machine over Transport (TDD)"
```

---

### Task 15: RtmpClient — media send API

Public API the Android layer (M1-B) calls: `SendVideoConfig(sps,pps)`, `SendVideo(annexb, key, ptsMs, dtsMs)`, `SendAudioConfig(sampleRate, channels)`, `SendAudio(aacRaw, ptsMs)`. These wrap FLV bodies in video(0x09)/audio(0x08) RTMP messages on csid 5/4 at the message timestamp.

**Files:** Modify `rtmp_client.h`/`.cpp`; Test `test_rtmp_client.cpp`.

- [ ] **Step 1: Write the failing test**
```cpp
TEST(RtmpClient, SendVideoEmitsTaggedChunk) {
    StubTransport t; StreamParams p; p.streamKey="5";
    RtmpClient c(t, p); ForcePublishing(c);   // test helper sets state+streamId via Begin/OnBytes flow
    t.clear();
    c.SendVideoConfig({0x67,0x42,0xC0,0x1F,0xAA}, {0x68,0xCE,0x3C,0x80});
    const Bytes& w = t.written();
    EXPECT_EQ(w[0] & 0x3F, 5);            // csid 5 (video)
    EXPECT_EQ(w[7], 0x09);               // message type video
    EXPECT_EQ(w[12], 0x17);              // FLV: keyframe+AVC
    EXPECT_EQ(w[13], 0x00);              // AVC seq header
}
TEST(RtmpClient, SendAudioRawEmitsTaggedChunk) {
    StubTransport t; StreamParams p; RtmpClient c(t, p); ForcePublishing(c);
    t.clear();
    c.SendAudio({0xDE,0xAD}, /*ptsMs*/40);
    const Bytes& w = t.written();
    EXPECT_EQ(w[0] & 0x3F, 4);            // csid 4 (audio)
    EXPECT_EQ(w[7], 0x08);               // message type audio
    EXPECT_EQ(w[12], 0xAF); EXPECT_EQ(w[13], 0x01); // AAC raw
}
```
(`ForcePublishing` helper drives `Begin()`+the four `OnBytes` feeds from Task 14, leaving the client in `Publishing` with streamId set. Reuse the `Make*` helpers.)

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement** — append to `rtmp_client.h` (public methods):
```cpp
    void SendVideoConfig(const Bytes& sps, const Bytes& pps);
    void SendVideo(const Bytes& annexb, bool keyframe, uint32_t ptsMs, uint32_t dtsMs);
    void SendAudioConfig(int sampleRate, int channels);
    void SendAudio(const Bytes& aacRaw, uint32_t ptsMs);
```
to `rtmp_client.cpp`:
```cpp
void RtmpClient::SendVideoConfig(const Bytes& sps, const Bytes& pps) {
    Bytes body = FlvVideoSeqHeader(BuildAvcC(sps, pps));
    t_.Write(ChunkEncode(5, 0x09, streamId_, 0, body, outChunkSize_));
}
void RtmpClient::SendVideo(const Bytes& annexb, bool key, uint32_t pts, uint32_t dts) {
    uint32_t cts = pts >= dts ? pts - dts : 0;
    Bytes body = FlvVideoFrame(AnnexBToAvcc(annexb), key, cts);
    t_.Write(ChunkEncode(5, 0x09, streamId_, dts, body, outChunkSize_));
}
void RtmpClient::SendAudioConfig(int sampleRate, int channels) {
    Bytes body = FlvAudioSeqHeader(BuildAsc(sampleRate, channels));
    t_.Write(ChunkEncode(4, 0x08, streamId_, 0, body, outChunkSize_));
}
void RtmpClient::SendAudio(const Bytes& aacRaw, uint32_t pts) {
    Bytes body = FlvAudioFrame(aacRaw);
    t_.Write(ChunkEncode(4, 0x08, streamId_, pts, body, outChunkSize_));
}
```

- [ ] **Step 4: Run — verify pass** (`ctest -R "RtmpClient.Send"`) → 2 PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/rtmp_client.* app/src/main/cpp/test/test_rtmp_client.cpp
git commit -m "feat(m1a): RtmpClient media send API (video/audio config + frames) (TDD)"
```

---

### Task 16: TcpTransport (real BSD sockets)

Portable across macOS host and Android NDK. Blocking connect/read/write.

**Files:**
- Create: `app/src/main/cpp/core/tcp_transport.h`, `.cpp`
- Test: `app/src/main/cpp/test/test_transport.cpp` (add a localhost loopback test)

- [ ] **Step 1: Write the failing test (loopback)**

Append to `test/test_transport.cpp`:
```cpp
#include "tcp_transport.h"
#include <thread>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
TEST(TcpTransport, LoopbackWriteRead) {
    int srv = socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in addr{}; addr.sin_family = AF_INET; addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK); addr.sin_port = 0;
    ASSERT_EQ(bind(srv, (sockaddr*)&addr, sizeof(addr)), 0);
    socklen_t al = sizeof(addr); getsockname(srv, (sockaddr*)&addr, &al);
    ASSERT_EQ(listen(srv, 1), 0);
    uint16_t port = ntohs(addr.sin_port);
    std::thread server([&]{
        int c = accept(srv, nullptr, nullptr);
        uint8_t buf[4]; int n = (int)recv(c, buf, 4, 0);
        send(c, buf, n, 0);   // echo
        close(c);
    });
    ps::TcpTransport t;
    ASSERT_TRUE(t.Connect("127.0.0.1", port));
    ASSERT_TRUE(t.Write({1,2,3,4}));
    uint8_t in[4]; int n = t.Read(in, 4);
    EXPECT_EQ(n, 4); EXPECT_EQ(in[0], 1); EXPECT_EQ(in[3], 4);
    t.Close(); server.join(); close(srv);
}
```

- [ ] **Step 2: Run — verify it fails.**

- [ ] **Step 3: Implement**

`core/tcp_transport.h`:
```cpp
#pragma once
#include "transport.h"
namespace ps {
class TcpTransport : public Transport {
public:
    ~TcpTransport() override { Close(); }
    bool Connect(const std::string& host, uint16_t port) override;
    bool Write(const std::vector<uint8_t>& data) override;
    int  Read(uint8_t* buf, int maxLen) override;
    void Close() override;
    bool connected() const override { return fd_ >= 0; }
private:
    int fd_ = -1;
};
}
```
`core/tcp_transport.cpp`:
```cpp
#include "tcp_transport.h"
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>
#include <cstring>
namespace ps {
bool TcpTransport::Connect(const std::string& host, uint16_t port) {
    addrinfo hints{}; hints.ai_family = AF_INET; hints.ai_socktype = SOCK_STREAM;
    addrinfo* res = nullptr;
    if (getaddrinfo(host.c_str(), std::to_string(port).c_str(), &hints, &res) != 0) return false;
    for (addrinfo* a = res; a; a = a->ai_next) {
        fd_ = socket(a->ai_family, a->ai_socktype, a->ai_protocol);
        if (fd_ < 0) continue;
        int one = 1; setsockopt(fd_, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
        if (connect(fd_, a->ai_addr, a->ai_addrlen) == 0) { freeaddrinfo(res); return true; }
        ::close(fd_); fd_ = -1;
    }
    freeaddrinfo(res); return false;
}
bool TcpTransport::Write(const std::vector<uint8_t>& d) {
    size_t off = 0;
    while (off < d.size()) {
        ssize_t n = ::send(fd_, d.data() + off, d.size() - off, 0);
        if (n <= 0) return false;
        off += n;
    }
    return true;
}
int TcpTransport::Read(uint8_t* buf, int maxLen) { return (int)::recv(fd_, buf, maxLen, 0); }
void TcpTransport::Close() { if (fd_ >= 0) { ::close(fd_); fd_ = -1; } }
}
```
Add sources/tests to CMake.

- [ ] **Step 4: Run — verify pass** (`ctest -R "TcpTransport"`) → PASS.

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/core/tcp_transport.* app/src/main/cpp/core/CMakeLists.txt app/src/main/cpp/test/test_transport.cpp
git commit -m "feat(m1a): TcpTransport BSD-socket implementation (TDD loopback)"
```

---

### Task 17: Host harness — push a canned sample to a real ingest (acceptance gate)

Proves the entire core against a real Twitch/YouTube ingest **before any Android code exists**. Reads a raw H.264 Annex-B elementary stream and a raw AAC stream from disk (paths + RTMP URL/key as argv) and streams them in a paced loop. This is the M1-A "done" gate — not a unit test.

**Files:**
- Create: `app/src/main/cpp/harness/CMakeLists.txt`, `app/src/main/cpp/harness/main.cpp`

- [ ] **Step 1: Create the harness CMake**

`app/src/main/cpp/harness/CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.22)
project(rtmp_harness CXX)
set(CMAKE_CXX_STANDARD 17)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../core core_build)
add_executable(rtmp_harness main.cpp)
target_link_libraries(rtmp_harness PRIVATE plohoystream_core)
```

- [ ] **Step 2: Write the harness**

`app/src/main/cpp/harness/main.cpp`:
```cpp
#include <cstdio>
#include <fstream>
#include <thread>
#include <chrono>
#include <vector>
#include "rtmp_client.h"
#include "tcp_transport.h"
#include "flv.h"
using namespace ps;
static Bytes ReadFile(const char* path) {
    std::ifstream f(path, std::ios::binary);
    return Bytes((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}
// Split an Annex-B elementary stream into access units (each starts at an AUD/SPS/IDR/non-IDR boundary).
// For the harness we approximate: split on start codes, group [SPS,PPS,IDR] or single slices.
int main(int argc, char** argv) {
    if (argc < 5) { fprintf(stderr, "usage: rtmp_harness <host> <app> <streamKey> <h264file> [aacfile]\n"); return 2; }
    StreamParams p; p.host = argv[1]; p.app = argv[2]; p.streamKey = argv[3];
    p.tcUrl = "rtmp://" + p.host + "/" + p.app; p.port = 1935;
    TcpTransport t;
    if (!t.Connect(p.host, p.port)) { fprintf(stderr, "connect failed\n"); return 1; }
    RtmpClient c(t, p);
    c.Begin();
    // pump handshake + setup
    uint8_t buf[8192];
    while (c.state() != RtmpState::Publishing) {
        int n = t.Read(buf, sizeof(buf));
        if (n <= 0) { fprintf(stderr, "setup read failed, state=%d\n", (int)c.state()); return 1; }
        c.OnBytes(Bytes(buf, buf + n));
    }
    fprintf(stderr, "PUBLISHING streamId=%d\n", c.streamId());
    // ... feed SPS/PPS via SendVideoConfig, then SendVideo per AU at ~33ms cadence,
    //     and SendAudio per AAC frame. (Frame slicing left to the executor; see NOTE.)
    Bytes h264 = ReadFile(argv[4]);
    // Minimal proof-of-life: send config + one IDR so the dashboard shows "receiving".
    Bytes sps, pps; SplitSpsPps(h264, sps, pps);
    if (!sps.empty() && !pps.empty()) c.SendVideoConfig(sps, pps);
    fprintf(stderr, "sent video config (sps=%zu pps=%zu)\n", sps.size(), pps.size());
    return 0;
}
```
> NOTE for executor: full AU slicing + real-time pacing is the harness's job, but the **acceptance gate** is: Twitch/YouTube dashboard transitions to "receiving data"/"stream healthy" after `SendVideoConfig` + a real IDR loop. Generate a test clip with: `ffmpeg -f lavfi -i testsrc=size=1280x720:rate=30 -t 10 -c:v libx264 -profile:v baseline -bsf:v h264_mp4toannexb -f h264 /tmp/test.h264`. Expand `main.cpp` to loop real ADTS/AAC + paced video AUs until the dashboard is green.

- [ ] **Step 3: Build the harness**

Run: `cmake -S harness -B build-harness && cmake --build build-harness`
Expected: builds `build-harness/rtmp_harness`.

- [ ] **Step 4: Manual acceptance run**

```bash
ffmpeg -f lavfi -i testsrc=size=1280x720:rate=30 -t 10 -c:v libx264 -profile:v baseline -bsf:v h264_mp4toannexb -f h264 /tmp/test.h264
./build-harness/rtmp_harness live.twitch.tv app <YOUR_STREAM_KEY> /tmp/test.h264
```
Expected: stderr prints `PUBLISHING streamId=...`; the Twitch/YouTube Live dashboard shows the stream connected/receiving. **This is the M1-A acceptance gate.**

- [ ] **Step 5: Commit**
```bash
git add app/src/main/cpp/harness
git commit -m "feat(m1a): host harness pushing canned H.264 to a real RTMP ingest"
```

---

### Task 18: Verify Android NDK build links the core

Make sure the same `core/` compiles under the NDK toolchain (no host-only assumptions), even though JNI wiring is M1-B.

**Files:** (no new files) — uses the existing Gradle/NDK build.

- [ ] **Step 1: Build the debug APK's native libs**

Run: `./gradlew :app:externalNativeBuildDebug`
Expected: BUILD SUCCESSFUL; `plohoystream` `.so` produced for each ABI, linking `plohoystream_core`.

- [ ] **Step 2: If a host-only header leaked, fix includes**

If the NDK build errors on a socket/header difference, guard or adjust includes in `tcp_transport.cpp` (Android provides the same BSD headers, so this should compile as-is). Do not add Android-specific code to `core/`.

- [ ] **Step 3: Commit (only if any fix was needed)**
```bash
git add app/src/main/cpp
git commit -m "build(m1a): verify core compiles under Android NDK toolchain"
```

---

## Self-Review

**Spec coverage (M1-A = design spec stages 1–2):**
- C++ egress core `FlvMuxer` → ✅ Tasks 7–12. `RtmpClient` (handshake + AMF0 connect/publish) → ✅ Tasks 5,13,14. `Transport`/`TcpTransport`/`StubTransport` seam → ✅ Tasks 4,16. Build offline-first, prove against real ingest → ✅ Tasks 0,17. Android-NDK parity → ✅ Task 18.
- Deliberately deferred to M1-B (not in this plan): Camera2/MediaCodec, JNI bridge, Compose UI, foreground service, `StreamEngine`. Stated in the design spec.

**Placeholder scan:** No "TBD"/"add error handling"-style gaps. Two explicit NOTE-to-executor callouts (Task 14 reader scope, Task 17 frame slicing) describe bounded, intentional scope edges with the exact commands/data needed — not vague placeholders.

**Type consistency:** `Bytes` alias used throughout; `Amf0::Key` (bare) vs `Amf0::String` (markered) distinction consistent across Tasks 2,12,13,14; `ChunkEncode` signature stable from Task 6 through 15; `RtmpState`/`RtmpClient` members consistent across Tasks 13–15.

**Known correctness watch-items for execution (verify by the Task 17 gate):**
- AAC ASC value 44.1k stereo = `0x12 0x10` (Task 8) — corrected from a faulty reference figure; the unit test pins it.
- `RtmpReader` handles single-chunk fmt-0 server messages only (Task 14) — sufficient for setup; the real ingest in Task 17 confirms.
- `createStream` `_result` stream-id parse (Task 14) does a best-effort skip of the null command object; if a server sends a non-null object there, adjust the skip — the harness will surface it.

---

## Execution Handoff

This plan (M1-A) builds the RTMP egress core, proven against a real ingest. **M1-B** (Android Camera2/MediaCodec capture+encode, JNI bridge to this core, Compose UI, foreground service, `StreamEngine`) is the follow-up plan, written after M1-A is green.
