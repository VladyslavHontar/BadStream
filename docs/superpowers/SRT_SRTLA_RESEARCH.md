# SRT + SRTLA Bonding Research — PlohoyStream

*Research date: 2026-06-15. Sources: Moblin source at `/tmp/moblin` (commit depth-1 clone of
`https://github.com/eerimoq/moblin`), BELABOX srtla GitHub, Haivision/srt docs, Android NDK docs.*

---

## 1. Moblin's SRT/SRTLA Implementation

### 1.1 libsrt integration

Moblin uses **two parallel SRT paths** — a "Moblin" (custom Swift SRT stack) and an "Official"
(libsrt C library) path — selected via `SettingsStreamSrtImplementation`.

**Official path (`SrtStreamOfficial.swift`):**
- Imports `import libsrt` — libsrt is pulled as a Swift Package from
  `https://github.com/eerimoq/SrtSwift` (seen in `Moblin.xcodeproj/project.pbxproj`).
  This is a thin XCFramework wrapper around the haivision/srt C library built for iOS/macOS.
  It is **not vendored as source** in the Moblin repo; it is a prebuilt binary package.
- Key API call sequence:
  ```swift
  srt_startup()                     // once at init
  socket = srt_create_socket()
  srt_setsockopt(socket, 0, SRTO_TRANSTYPE, &SRTT_LIVE, …)  // via SrtSocketOption
  srt_setsockopt(socket, 0, SRTO_LATENCY, …)
  srt_setsockopt(socket, 0, SRTO_STREAMID, …)
  srt_setsockopt(socket, 0, SRTO_PAYLOADSIZE, …)  // 1316 (7 × 188)
  srt_connect(socket, &sockaddr, sizeof)
  // send loop:
  srt_sendmsg2(socket, buf, len, nil)
  // stats:
  srt_bstats(socket, &perf, 1)      // fills CBytePerfMon
  srt_close(socket)
  srt_cleanup()
  ```
- A custom `srt_send_callback` hook is registered so the "Official" path can **intercept raw
  SRT wire packets** and forward them to SRTLA's `RemoteConnection` dispatch layer instead of
  sending on a real socket. This is the bridge between libsrt's internal UDP send and SRTLA.
- `SrtSocketOption.swift` maps URL query parameters (e.g. `?latency=2000&streamid=…`) to
  `SRT_SOCKOPT` enum values. Supported options include `SRTO_LATENCY`, `SRTO_STREAMID`,
  `SRTO_PASSPHRASE`, `SRTO_PBKEYLEN`, `SRTO_PAYLOADSIZE`, `SRTO_TRANSTYPE`, `SRTO_MAXBW`,
  `SRTO_INPUTBW`, `SRTO_OHEADBW`, and many more.
- A custom option `srtlaPatches` maps to `SRTO_SRTLAPATCHES` — a Moblin-specific libsrt
  extension that disables SRT's internal rate-limiting (letting SRTLA manage pacing instead).

**Moblin (custom) path (`SrtStreamMoblin.swift` + `SrtSender.swift`):**
- Implements the full SRT sender protocol **from scratch in Swift**, without libsrt.
- Performs SRT induction + conclusion handshake (version 4 → 5 with HSv5 extension blocks for
  latency and stream ID negotiation).
- Manages SRT data packet headers (sequence numbers, timestamps, retransmission bit).
- Handles ACK/NAK/ACKACK/KEEPALIVE control packets.
- Retransmit queues are split into `audioSequenceNumbersToRetransmit` and
  `videoSequenceNumbersToRetransmit`; audio gets priority in the retransmit scheduler.
- Output packets go directly to `SrtlaClient.handleLocalPacket()` without any local UDP socket.

### 1.2 MPEG-TS muxer

Moblin uses its own **Swift MPEG-TS muxer** (`MpegTsWriter.swift`), ported from the HaishinKit
open-source project. It is **not vendored as source** in Moblin directly; the Moblin repo
contains a forked copy under `Moblin/Media/HaishinKit/Mpeg/`.

Key characteristics:
- Fixed `payloadSize = 1316` bytes (global var in `MpegTsWriter.swift`), i.e. exactly 7 × 188.
  This fills one SRT LIVE data packet.
- PIDs: video = 256 (0x100), audio = 257 (0x101), PAT = 0x0000, PMT = 0x0FFF.
- Stream type: H.264 = 0x1B, HEVC = 0x24, AAC ADTS = 0x0F.
- PAT/PMT are re-emitted periodically (every 2 s segment boundary) or whenever codec changes.
- Continuity counters are per-PID, wrapping at 0xF.
- Audio and video TS packets are interleaved: audio slots fill partial audio chunks with
  leftover video bytes (`writeAudioNew` / `writeVideoNew` logic) to keep A/V in same SRT packet.
- PCR (Program Clock Reference) is carried in the video adaptation field.

### 1.3 SRTLA client

**Architecture:**
- `SrtlaClient` manages N `RemoteConnection` objects, one per network interface.
- For SRTLA mode: interfaces discovered via `NWPathMonitor`; for each interface type
  (`.cellular`, `.wifi`, `.wiredEthernet`) a separate `RemoteConnection` is created.
- `NWConnection(…, using: NWParameters(dtls: .none))` with `params.requiredInterface = interface`
  — this is iOS's mechanism for binding a UDP socket to a specific NWInterface.
- For passThrough (plain SRT without bonding): a single RemoteConnection with `type: nil`.

**Registration handshake (implemented in `RemoteConnection.swift` + `Srtla.swift`):**

Packet types (`Srtla.swift`):
```
keepalive = 0x1000
ack       = 0x1100
reg1      = 0x1200  // "create group" – 2-byte type + 256-byte groupId
reg2      = 0x1201  // "register connection" – 2-byte type + 256-byte groupId
reg3      = 0x1202  // "connection registered" – 2-byte type only
regErr    = 0x1210
regNgp    = 0x1211  // "no group" – server doesn't have this groupId yet
regNak    = 0x1212
```

Note: Moblin uses type values with `0x8000` OR'd in (the SRT control packet bit), so wire values
are 0x9000, 0x9100, 0x9200 etc. — matching the BELABOX `common.h` definitions exactly.

Handshake flow (via `SrtlaClient` + `RemoteConnection`):
1. Connection 0 (first socket): UDP connects → `.ready` → `probe()` → sends **REG1** with a
   random 256-byte groupId.
2. Server responds with **REG2** (258 bytes): first 128 bytes echoed from REG1, last 128 bytes
   server-generated. This is the final 256-byte `groupId`.
3. Client broadcasts **REG2** to all other connections using the full group ID → each responds
   with **REG3** confirming registration.
4. After all connections register: `startListener()` → SRTLA is running.
5. New connections (e.g. after reconnect) send REG2 directly if `hasFullGroupId`.

**Packet striping (scored, not round-robin):**
- `selectRemoteConnection()` picks the connection with highest `score()`.
- `score() = windowSize / (packetsInFlight.count + 1) × priority`
- `windowSize` starts at `windowDefault × windowMultiply` = 20,000.
- On SRTLA ACK: `windowSize += 1` (capped at 60,000).
- On SRT NAK: `windowSize -= 100` (floor 1,000).
- Priority is user-configurable per-interface (float 0.0–n.0; 0 = disabled).

**Per-link keepalive:**
- Periodic timer (1 s) sends `SRTLA_KEEPALIVE` packet containing a timestamp.
- Server echoes keepalive; RTT is measured from round-trip time (`rtt = getKeepAliveTime() - sendTime`).
- If no packet received in 5 s: `reconnect()` → stops + restarts connection.

**Data packet padding:**
- Each SRT data packet is padded with null TS packets (sync byte 0x47, PID 0x1FFF) to fill
  the full `mpegtsPacketsPerPacket × 188` size. This keeps all SRTLA packets the same size,
  which is required by some SRTLA server implementations.
- Data packets are batched (up to 15) and sent as a `NWConnection.batch{}` block for efficiency.
- Every 15 ms, `flushDataPackets()` is called on all connections to force-send partial batches.

**BELABOX origin:** Moblin's SRTLA client is based directly on the
[BELABOX/srtla](https://github.com/BELABOX/srtla) C reference implementation
(`srtla_send.c`), translated to Swift. The adaptive bitrate algorithm
(`AdaptiveBitrateSrtBelabox.swift`) is ported from
[BELABOX/belacoder](https://github.com/BELABOX/belacoder).

### 1.4 Adaptive bitrate

**Algorithm (Belabox variant, `AdaptiveBitrateSrtBelabox.swift`):**

Inputs from `srt_bstats` / custom stats (struct `StreamStats`):
- `rttMs` — smoothed RTT in ms
- `packetsInFlight` — `pktFlightSize` from `CBytePerfMon` (= `pktSndBuf` in Official path)
- `mbpsSendRate` — observed send throughput
- `latency` — configured SRT latency (SRTO_LATENCY)

Key thresholds (Belabox defaults):
- `packetsInFlight: 200`, `rttDiffHighFactor: 0.9`, `minimumBitrate: 250_000`
- Bitrate increased by `100k + bitrate/30` every 400 ms when RTT is low and stable.
- Bitrate decreased by `100k + bitrate/10` every 200 ms when RTT or buffer is elevated.
- **Set to minimum immediately** if `rtt >= latency/3` or `packetsInFlight > 4× average`.

**Also available:** `AdaptiveBitrateSrtFight.swift` — an alternative algorithm using
the same inputs but different parameters.

### 1.5 Interface binding (iOS)

Moblin uses `NWParameters(dtls: .none)` with `params.requiredInterface = nwInterface`
(`NWInterface` from `NWPathMonitor`). This is the **iOS Network.framework** mechanism;
there is no direct equivalent on Android (see Section 3).

### 1.6 SRT/SRTLA settings and URL scheme

The URL scheme is `srtla://host:port?streamid=…&latency=2000&…` for SRTLA mode and
`srt://host:port?streamid=…` for plain SRT. All `SrtSocketOption` enum cases map to
URL query parameters. Key exposed settings:
- `streamid` — stream key / target ID for BELABOX relay or Twitch
- `latency` — SRTO_LATENCY in ms (default typically 2000)
- `passphrase` / `pbkeylen` — encryption
- `payloadsize` — always 1316 in practice
- `mpegtsPacketsPerPacket` — 7 (= 1316/188) hardcoded effectively
- `connectionPriorities` — per-interface weight (cellular/wifi)

---

## 2. Protocol & Library Facts

### 2.1 libsrt LIVE caller C API

Minimal call sequence for a live CALLER connection:

```c
srt_startup();

SRTSOCKET sock = srt_create_socket();

// Pre-connect options (binding = PRE):
int transtype = SRTT_LIVE;
srt_setsockflag(sock, SRTO_TRANSTYPE,   &transtype,  sizeof(transtype));

int latency_ms = 2000;
srt_setsockflag(sock, SRTO_LATENCY,     &latency_ms, sizeof(latency_ms));

char streamid[] = "#!::r=live/stream,m=publish";  // BELABOX or Twitch format
srt_setsockflag(sock, SRTO_STREAMID,    streamid,    strlen(streamid));

int payloadsize = 1316;
srt_setsockflag(sock, SRTO_PAYLOADSIZE, &payloadsize,sizeof(payloadsize));

// Connect (blocking by default):
struct sockaddr_in addr = …;
srt_connect(sock, (struct sockaddr*)&addr, sizeof(addr));

// Send loop:
// buf must be exactly payloadsize bytes or ≤ payloadsize in LIVE mode
srt_sendmsg2(sock, buf, payloadsize, NULL);

// Stats (call periodically, e.g. every 500 ms):
CBytePerfMon perf;
srt_bstats(sock, &perf, 1 /*clear*/);
// Use: perf.msRTT, perf.pktFlightSize, perf.mbpsSendRate, perf.pktSndBuf

srt_close(sock);
srt_cleanup();
```

**Key `CBytePerfMon` fields for ABR:**
| Field | Description |
|---|---|
| `msRTT` | Smoothed RTT in ms |
| `pktFlightSize` | Packets in-flight (sent, not yet ACK'd) |
| `mbpsSendRate` | Observed send rate in Mbit/s |
| `pktSndBuf` | Packets queued in send buffer |
| `pktRetransTotal` | Cumulative retransmits |
| `pktSndDropTotal` | Packets dropped due to latency limit |

**`streamid` formats:**
- BELABOX/srtla_rec: usually a raw stream key string configured in the relay.
- Twitch: `#!::r=live/<channel>,m=publish` (HSv5 Access Control extension).
- Any opaque string up to 512 bytes; the relay/server interprets it.

### 2.2 Building libsrt for Android NDK

**Official support:** Haivision maintains an Android build script at
`srt/scripts/build-android/build-android`. It is a first-class supported target.

**Build command:**
```bash
./scripts/build-android/build-android -n /path/to/ndk [-e openssl|mbedtls|botan]
```

**Default ABIs built:** `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.

**Output:** Shared libraries (`.so`) in a `prebuilt/` folder:
- `libsrt.so` (depends on crypto .so)
- `libmbedcrypto.so`, `libmbedtls.so`, `libmbedx509.so` (if mbedTLS chosen)
- `libssl.so`, `libcrypto.so` (if OpenSSL chosen)

**Crypto decision for BELABOX use:**

| Option | Pros | Cons |
|---|---|---|
| `ENABLE_ENCRYPTION=OFF` | No crypto dependency; smallest binary; simplest build | No AES encryption; BELABOX relay can work without it (unencrypted stream) |
| `mbedtls` | Smaller than OpenSSL; permissive license (Apache 2); easier to statically link | Less battle-tested for SRT than OpenSSL |
| `openssl` | Default and most tested | Larger; GPL/OpenSSL licence complications; building for Android adds ~4 MB per ABI |

**Recommendation for Phase A:** Start with `ENABLE_ENCRYPTION=OFF` — BELABOX relay does not
require encryption and this eliminates the crypto build dependency entirely. Add mbedTLS in a
later phase if stream encryption is required.

**Integration options:**

1. **Prebuilt `.so` per ABI (recommended for Phase A):**
   - Run the build script once on a build machine, commit the `.so` files to `app/libs/`.
   - In CMakeLists.txt:
     ```cmake
     add_library(srt SHARED IMPORTED)
     set_target_properties(srt PROPERTIES
         IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../libs/${ANDROID_ABI}/libsrt.so)
     target_link_libraries(my_native_lib srt)
     ```
   - Simplest; no per-developer toolchain setup; reproducible.

2. **CMake `add_subdirectory` (subproject):**
   - Clone srt as a git submodule; add `add_subdirectory(third_party/srt)`.
   - Needs `-DENABLE_SHARED=OFF -DENABLE_APPS=OFF -DENABLE_TESTING=OFF` etc.
   - Slower build; tighter control; good for CI.

3. **`FetchContent`:**
   - Downloads and builds srt at CMake configure time.
   - Convenient for single-dev; poor for reproducibility.

**Key NDK version concern:** libsrt requires NDK r21+ and API 21+ (Android 5.0). Both are well
within PlohoyStream's likely minimum target. CMake ≥ 3.18 is recommended.

**Effort estimate:** ~1–2 days to get a working `ENABLE_ENCRYPTION=OFF` prebuilt for arm64-v8a
with a smoke-test send call. The Haivision build script handles all the heavy lifting.

### 2.3 MPEG-TS muxing for SRT-LIVE

**Minimum a TS muxer must produce:**
- 188-byte packets with sync byte `0x47`.
- **PAT** on PID 0x0000: program 1 → PMT PID 0x0FFF.
- **PMT** on PID 0x0FFF: video elementary stream (PID 0x100, type 0x1B H.264 or 0x24 HEVC),
  audio elementary stream (PID 0x101, type 0x0F AAC-ADTS).
- **PES** headers per frame for video and audio:
  - Start code `00 00 01`, stream_id (0xE0 video, 0xC0 audio), PES_packet_length.
  - PTS (and DTS for video) in 33-bit 90 kHz clock format.
- **PCR** (Program Clock Reference) in the video TS packet adaptation field.
  PCR must track the video DTS (typically DTS × 90).
- **Continuity counters**: 4-bit per PID, incrementing modulo 16.
- **Payload packing**: 7 TS packets × 188 bytes = 1316 bytes per SRT send call.
  First TS packet for a PES starts with `payload_unit_start_indicator=1`.

**Embeddable muxer options:**

| Library | Size | Licence | Notes |
|---|---|---|---|
| Roll our own | ~400–600 LOC | Own | Straightforward; only PAT/PMT/PES/PCR needed; testable as pure bytes (no network) |
| `OwnZones/mpegts` (C++) | ~800 LOC | MIT | H.264 + AAC, configurable PIDs; no HEVC |
| `libavformat` (ffmpeg) | Huge | LGPL | Overkill; binary size penalty |
| Porting Moblin's `MpegTsWriter` | Medium | MIT (HaishinKit origin) | Good reference but requires Swift→C++ translation |

**Recommendation:** Write our own minimal C++ TS muxer. PlohoyStream already has a
`ByteWriter`-style pattern and all the needed codec knowledge (from `hevc.cpp`, `flv.cpp`).
The muxer is purely a byte-transformation layer — no network, no threading — so it is fully
unit-testable on the host. Estimate: **1–2 days** for H.264 + AAC, plus 1 day for HEVC.

**Payload construction rule:**
- Video: each NALU in Annex B format, split across TS packets.
- Audio: raw AAC frames wrapped in ADTS headers (7 bytes each), then PES.
- PCR: emit in the first TS packet of each video PES, set to DTS × 90 (since DTS is in ms,
  multiply by 90 to get 90 kHz ticks; PCR in 27 MHz base = DTS × 90,000 and ext = 0).

### 2.4 SRTLA Protocol (BELABOX reference)

Source: [github.com/BELABOX/srtla](https://github.com/BELABOX/srtla) (`common.h`, `srtla_send.c`,
`srtla_rec.c`).

**Packet encoding:** SRTLA control packets look like SRT control packets (MSB of first byte =
1). Type field occupies the low 15 bits of the first 2 bytes. Wire type values (matching Moblin
exactly):

| Message | Wire type | Size | Direction |
|---|---|---|---|
| KEEPALIVE | 0x9000 | 10 bytes (2 type + 8 timestamp) | bidirectional |
| ACK | 0x9100 | variable (2 + 4×N seq nums) | server→sender |
| REG1 | 0x9200 | 258 bytes (2 + 256 groupId) | sender→server |
| REG2 | 0x9201 | 258 bytes (2 + 256 groupId) | bidirectional |
| REG3 | 0x9202 | 2 bytes | server→sender |
| REG_ERR | 0x9210 | 2 bytes | server→sender |
| REG_NGP | 0x9211 | 2 bytes | server: no such group |
| REG_NAK | 0x9212 | 2 bytes | server: rejected |

**REG1/REG2/REG3 handshake detail:**
1. **Sender→server (first link):** REG1 with 256 random bytes (initial group ID candidate).
2. **Server→sender:** REG2 with first 128 bytes from REG1 + 128 server-generated bytes =
   final 256-byte `groupId`.
3. **Sender broadcasts REG2** (containing full `groupId`) on ALL other links.
4. **Server→each link:** REG3 confirming registration of that link into the group.
5. If server doesn't have the group yet (first link ever): server replies REG_NGP; sender
   then sends REG1 to create the group.

**Packet striping:**
- Sender selects the link with the highest score: `window / (in_flight + 1)`.
- `window` adapts between WINDOW_MIN=1 and WINDOW_MAX=60 (in units of 1000).
- ACK window increments: +30 on SRTLA_ACK; decrements: -100 on SRT NAK.
- Null TS padding fills each packet to the full `mpegtsPacketsPerPacket × 188` size.

**Keepalive / dead-link detection:**
- Keepalive sent every 1 s idle on each link (carries send timestamp for RTT measurement).
- If no packet received in 5 s: link is dead → reconnect (RE-REG2 with existing groupId).

**What srtla_rec does:**
- Listens on one UDP port; accepts any UDP source that presents a known groupId.
- Reassembles packets from multiple links by stripping SRTLA metadata; forwards raw SRT
  packets to a local SRT listener (srt-live-transmit or similar) on `127.0.0.1:SRT_PORT`.
- Handles SRT ACK/NAK forwarding back to the sender's correct link.
- Does NOT do any SRT-level processing — it just reorders and delivers UDP datagrams.

**User setup for BELABOX relay:**
- Self-hosted: run `srtla_rec` + `srt-live-transmit` (or oven_media_engine / SRS / OBS).
  URL from sender: `srtla://relay-host:srtla-port?streamid=…`
- BELABOX cloud: URL is provided by the cloud dashboard; same protocol.

---

## 3. Android Multi-Interface Bonding

### 3.1 Holding cellular + WiFi simultaneously

Android normally routes all traffic via the "best" network. To use both at once:

**Kotlin side:**
```kotlin
val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

val cellRequest = NetworkRequest.Builder()
    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    .build()

cm.requestNetwork(cellRequest, object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
        val handle = network.networkHandle   // Long; pass to JNI
        notifyNativeCellularAvailable(handle)
    }
    override fun onLost(network: Network) {
        notifyNativeCellularLost()
    }
})
```

- `ConnectivityManager.requestNetwork()` keeps the cellular radio active even when WiFi is
  the default route. This is the critical call — without it, Android may shut down the
  cellular data path when WiFi is present.
- Required manifest permission: `android.permission.CHANGE_NETWORK_STATE`.
- The system limits outstanding network requests to 100 per app UID.

**WiFi** is usually the default network and does not need explicit requesting. If you also need
to force WiFi while cellular is default, request WiFi the same way.

### 3.2 Binding a native socket to a specific network

**NDK API:** `<android/multinetwork.h>` (API level 23+, Android 6.0+).

```c
#include <android/multinetwork.h>

// net_handle is the uint64_t from network.getNetworkHandle() in Java,
// cast directly: (net_handle_t)jlong_handle
int android_setsocknetwork(net_handle_t network, int fd);
// Returns 0 on success, -1 on failure (check errno).

// Alternatively, bind the whole process (affects all future sockets):
int android_setprocnetwork(net_handle_t network);
```

**For SRTLA, use `android_setsocknetwork` per link:**
```c
// After creating a UDP socket for the SRTLA link:
int udp_fd = socket(AF_INET, SOCK_DGRAM, 0);
android_setsocknetwork((net_handle_t)java_net_handle, udp_fd);
// Then connect/sendto as normal.
```

`net_handle_t` is `uint64_t`. The Java `Network.getNetworkHandle()` returns a `long` which
is safe to cast directly to `net_handle_t`. The JNI bridge:
```kotlin
// Kotlin:
val handle: Long = network.networkHandle
nativeLinkBound(handle)
```
```c
// C++:
JNIEXPORT void JNICALL Java_…_nativeLinkBound(JNIEnv*, jobject, jlong handle) {
    net_handle_t net = static_cast<net_handle_t>(handle);
    android_setsocknetwork(net, udp_fd);
}
```

**If the network disconnects:** all sockets previously bound to it stop working. The
`NetworkCallback.onLost()` fires in Kotlin; the C++ SRTLA layer must be notified to stop
that link and reconnect once a new network becomes available.

### 3.3 Caveats

- **API level 23 minimum** (`android_setsocknetwork`). Android 6.0 = ~95%+ of active devices
  as of 2026, so this is not a meaningful restriction.
- **OEM behaviour:** Some MIUI/Samsung overlays aggressively kill "background" cellular when
  WiFi is present even with `requestNetwork`. Workaround: hold a `PARTIAL_WAKE_LOCK` during
  streaming and consider `foreground service` with ongoing notification (which PlohoyStream
  likely already uses).
- **Battery:** Holding cellular up while WiFi is active uses noticeably more power. Expected
  for a live-streaming app; acceptable in the use case.
- **Cellular data cost:** User must be informed; consider a UI warning.

---

## 4. Recommendation — Phased Plan

### 4.1 How SRT would slot into the existing egress seam

**Current seam** (`stream_session.h`, `transport.h`, `media_queue.h`):
- `StreamSession` owns a `Transport` (abstract interface) and a `MediaQueue`.
- The egress thread pops `MediaItem` (Video, Audio, VideoConfig, AudioConfig annex-B / raw
  AAC / SPS+PPS blobs) and calls `RtmpClient::Send*()`.
- `Transport` is a byte-pipe abstraction: `Connect()`, `Write(vector<uint8_t>)`, `Close()`.

**SRT insertion point — two options:**

**Option A: New `SrtSession` class parallel to `StreamSession`.**
```
MediaQueue → SrtSession::run() → TsMuxer → SrtLink (libsrt socket or custom stack)
                                          ↕
                                     (srt_bstats → ABR → encoder bitrate)
```
- `SrtSession` receives the same `MediaItem` types from `MediaQueue`.
- An internal `TsMuxer` (new C++ class) converts them to 188-byte TS packets and accumulates
  into 1316-byte payloads.
- An `SrtLink` wraps libsrt: `srt_create_socket → srt_connect → srt_sendmsg2`.
- ABR: periodic `srt_bstats` call feeds a C++ port of the Belabox algorithm; result is passed
  back via JNI callback to the Kotlin encoder bitrate setter.
- `SrtSession` implements the same public API as `StreamSession`
  (`Start/Stop/SendVideo/SendAudio/…`) so Kotlin can switch protocols without changing the
  camera/encoder pipeline.

**Option B: New `SrtTransport : Transport` implementing the Transport interface.**
- Requires `Write(vector<uint8_t>)` to send raw bytes — but for SRT you need to accumulate
  1316-byte chunks, not arbitrary byte streams.
- Worse fit: the `Transport` interface is a streaming byte-pipe; SRT-LIVE is message-oriented.
- **Not recommended.** Option A is cleaner.

**Kotlin-side change needed:** A factory that creates `SrtSession` instead of `StreamSession`
when the URL scheme is `srt://` or `srtla://`. The `MediaQueue`, `SendVideo`, `SendAudio` etc.
calls are identical.

### 4.2 Phase A — SRT single-link egress

**Scope:**
1. `TsMuxer` — C++ class: takes `MediaItem` events, emits 1316-byte TS payloads.
   - PAT/PMT/PES for H.264 + AAC.
   - PCR, continuity counters, ADTS wrapping.
   - Output: `std::vector<uint8_t>` of exactly 1316 bytes (padded with null TS if needed).
2. `SrtLink` — C++ class wrapping libsrt caller:
   - `Connect(host, port, streamid, latency)` → `srt_startup / srt_create_socket / setsockflag / srt_connect`.
   - `SendPayload(const uint8_t* buf, int len)` → `srt_sendmsg2`.
   - `GetStats()` → `srt_bstats` → returns simplified `SrtStats {rttMs, pktFlightSize, mbpsSendRate}`.
   - `Close()` → `srt_close / srt_cleanup`.
3. `SrtSession` — mirrors `StreamSession` lifecycle: own thread, pops from `MediaQueue`,
   feeds `TsMuxer`, calls `SrtLink::SendPayload`.
4. ABR stub — port of Belabox algorithm in C++; callback to Kotlin `onBitrateChange(bitrate)`.
5. libsrt prebuilt `.so` for arm64-v8a (device) and x86_64 (emulator), `ENABLE_ENCRYPTION=OFF`.

**Host-testable parts (no device/relay needed):**
- `TsMuxer` — pure byte transformation; unit test against known-good TS byte sequences.
- `SrtLink::Connect` + `srt_sendmsg2` against a local `srt-live-transmit` listener on macOS/Linux.

**Device/relay-needed:**
- End-to-end SRT stream to BELABOX relay or `srtla_rec` + OBS.
- ABR loop (requires real network conditions).

**Shippable independently:** Yes. A working SRT single-link stream from Android to BELABOX is
immediately useful; many users don't need bonding.

### 4.3 Phase B — SRTLA bonding (single-interface first)

**Scope:**
1. `SrtlaClient` C++ class:
   - Manages N `SrtlaLink` objects (each wraps a raw UDP socket).
   - REG1/REG2/REG3 handshake state machine per link.
   - Packet striping: scored selection (`window / (in_flight + 1)`).
   - Keepalive timer (1 s) per link.
   - Dead-link detection + reconnect (5 s timeout).
2. `SrtSender` — either:
   - Reuse libsrt with `srt_send_callback` hook (intercepts SRT wire packets → dispatches to
     `SrtlaClient`), matching Moblin's Official path. OR
   - Port Moblin's custom SRT stack to C++. Custom stack is simpler for SRTLA integration but
     requires implementing SRT handshake, ACK/NAK, retransmit queues from scratch.
   - **Recommended: libsrt + send_callback hook.** This reuses a tested SRT implementation.
3. Start with 1 link (effectively a pass-through SRTLA), then add multi-link.

**Shippable independently:** Yes. SRTLA with 1 link is identical to plain SRT from the user's
perspective but validates the handshake and relay connectivity.

### 4.4 Phase C — Android multi-interface binding + UI

**Scope:**
1. Kotlin: `ConnectivityManager.requestNetwork(TRANSPORT_CELLULAR, …)` callback that obtains
   `Network.getNetworkHandle()` and passes the `long` to JNI.
2. C++ `SrtlaClient`: for each `SrtlaLink`, call `android_setsocknetwork(net_handle, udp_fd)`.
3. `NWPathMonitor` equivalent on Android: `NetworkCallback.onAvailable/onLost` for adding/
   removing links dynamically.
4. UI: interface priority sliders (like Moblin's `StreamSrtConnectionPriority2View`), link
   status indicators (RTT, bytes/s per link).
5. Permissions: `CHANGE_NETWORK_STATE`, `ACCESS_NETWORK_STATE` in manifest.

**Caveats:**
- OEM battery/connectivity restrictions may break `requestNetwork` on MIUI/Samsung.
- Foreground service (streaming notification) should mitigate this.

**Shippable independently:** Yes, but depends on Phase B.

### 4.5 Biggest risks per phase

| Phase | Risk | Mitigation |
|---|---|---|
| A | **libsrt Android build.** Never built before for this project. OpenSSL/mbedTLS dependency hell. | Use `ENABLE_ENCRYPTION=OFF`; test with prebuilt `.so`. |
| A | **PCR/PTS/DTS synchronisation.** Wrong timestamps cause relay rejection or A/V desync. | Unit-test TS muxer against reference bitstreams; verify with `ffprobe`. |
| A | **`srt_sendmsg2` must be called on a non-UI thread.** | Already handled by `SrtSession`'s egress thread model. |
| B | **`srt_send_callback` hook is underdocumented.** | Reference: `SrtStreamOfficial.swift` in Moblin shows exact usage. |
| B | **SRTLA null-padding requirement.** Some `srtla_rec` versions require fixed-size packets. | Pad to `7 × 188 = 1316` like Moblin does. |
| C | **OEM cellular kill.** `requestNetwork` with `TRANSPORT_CELLULAR` is not guaranteed to keep cellular alive on all devices. | Test on MIUI and Samsung early; document known-broken devices. |
| C | **`android_setsocknetwork` requires API 23.** | Set `minSdkVersion 23` (already very likely for this app). |

---

*End of research document.*
