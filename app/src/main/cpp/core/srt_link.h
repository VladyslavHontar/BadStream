#pragma once
#include <cstdint>
#include <string>
#include "byte_writer.h"   // Bytes
#include "abr.h"           // AbrStats

namespace ps {

// libsrt LIVE caller. NDK-ONLY: includes <srt/srt.h>, so it is built into the JNI lib
// (plohoystream), NOT into plohoystream_core (which the host GoogleTest build links).
//
// One SrtLink == one SRT caller socket. srt_startup/srt_cleanup are reference-counted so
// multiple links over a process lifetime are safe.
class SrtLink {
public:
    SrtLink();
    ~SrtLink();

    SrtLink(const SrtLink&) = delete;
    SrtLink& operator=(const SrtLink&) = delete;

    // Connect a LIVE caller to host:port. Sets SRTO_TRANSTYPE=SRTT_LIVE,
    // SRTO_LATENCY=latencyMs, SRTO_PAYLOADSIZE=1316, SRTO_STREAMID=streamid (if non-empty).
    // Returns true once srt_connect succeeds. On failure sets rejected_ when the peer refused
    // (so the caller can map it to a terminal state) vs a transient connect error.
    bool Connect(const std::string& host, int port, const std::string& streamid, int latencyMs);

    // Send the whole buffer, sliced into <=1316-byte SRT messages. Returns true if every chunk
    // was accepted; false on any send error (link broken / closed).
    bool Send(const uint8_t* data, size_t size);
    bool Send(const Bytes& b) { return Send(b.data(), b.size()); }

    // Snapshot link health from srt_bstats into AbrStats. Returns false if stats are unavailable.
    bool Stats(AbrStats& out);

    void Close();
    bool connected() const { return sock_ != -1 && connected_; }

    // True when the last connect failure was a peer rejection (terminal) rather than a
    // transient connect/network error (the caller should reconnect on the latter).
    bool rejected() const { return rejected_; }

    // Close the socket from ANOTHER thread to unblock a blocking srt_connect() (so a user
    // Stop() during connect returns promptly instead of waiting out SRTO_CONNTIMEO). Safe to
    // call concurrently with Connect(); the connect then fails and run() exits. Does not reset
    // sock_ (Connect()'s own failure path / Close() handle that — a double srt_close is benign).
    void Interrupt();

private:
    static constexpr int kPayloadSize = 1316;   // 7 x 188-byte TS packets

    int sock_ = -1;        // SRTSOCKET (int); -1 == SRT_INVALID_SOCK
    bool connected_ = false;
    bool rejected_ = false;
    bool startedUp_ = false;
};

}  // namespace ps
