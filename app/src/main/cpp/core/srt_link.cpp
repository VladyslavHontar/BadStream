#include "srt_link.h"

#include <srt/srt.h>

#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/socket.h>

#include <atomic>
#include <cstring>

namespace ps {

namespace {
// Reference-counted srt_startup/srt_cleanup across all SrtLink instances in the process.
std::atomic<int> g_startupRefs{0};

void StartupRef() {
    if (g_startupRefs.fetch_add(1) == 0) {
        srt_startup();
    }
}
void StartupUnref() {
    if (g_startupRefs.fetch_sub(1) == 1) {
        srt_cleanup();
    }
}

// Resolve host (numeric or DNS) into a sockaddr_in. Returns false on failure.
bool ResolveV4(const std::string& host, int port, sockaddr_in& addr) {
    std::memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<uint16_t>(port));
    if (inet_pton(AF_INET, host.c_str(), &addr.sin_addr) == 1) return true;

    // DNS fallback (BELABOX relays are commonly addressed by hostname).
    addrinfo hints{};
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;
    addrinfo* res = nullptr;
    if (getaddrinfo(host.c_str(), nullptr, &hints, &res) != 0 || !res) return false;
    bool ok = false;
    for (addrinfo* p = res; p; p = p->ai_next) {
        if (p->ai_family == AF_INET && p->ai_addr) {
            auto* in = reinterpret_cast<sockaddr_in*>(p->ai_addr);
            addr.sin_addr = in->sin_addr;
            ok = true;
            break;
        }
    }
    freeaddrinfo(res);
    return ok;
}
}  // namespace

SrtLink::SrtLink() {
    StartupRef();
    startedUp_ = true;
}

SrtLink::~SrtLink() {
    Close();
    if (startedUp_) {
        StartupUnref();
        startedUp_ = false;
    }
}

// SRT reject reasons that are permanent — wrong passphrase, version/compat mismatch, malformed
// handshake. Anything else (timeout, listener resource/backlog exhaustion, system errors, a peer
// transiently refusing) is treated as a recoverable drop so the engine keeps reconnecting. The
// common IRL case: on a brief drop the OBS/relay listener still holds the previous peer for a few
// seconds, so the reconnect handshake returns SRT_REJ_TIMEOUT until it frees up — that MUST retry.
static bool IsPermanentRejectReason(int reason) {
    switch (reason) {
        case SRT_REJ_BADSECRET:   // wrong passphrase
        case SRT_REJ_UNSECURE:    // passphrase required but not supplied (or vice versa)
        case SRT_REJ_VERSION:     // incompatible SRT version
        case SRT_REJ_MESSAGEAPI:  // live/file/message API mismatch
        case SRT_REJ_ROGUE:       // malformed handshake
        case SRT_REJ_FILTER:      // packet filter mismatch
        case SRT_REJ_GROUP:       // group membership mismatch
            return true;
        default:                  // UNKNOWN/SYSTEM/PEER/RESOURCE/BACKLOG/CLOSE/CONGESTION/TIMEOUT/…
            return false;         // transient -> let the Kotlin reconnect loop retry
    }
}

bool SrtLink::Connect(const std::string& host, int port, const std::string& streamid, int latencyMs) {
    rejected_ = false;
    sockaddr_in addr{};
    if (!ResolveV4(host, port, addr)) return false;

    sock_ = srt_create_socket();
    if (sock_ == SRT_INVALID_SOCK) {
        sock_ = -1;
        return false;
    }

    int transtype = SRTT_LIVE;
    srt_setsockflag(sock_, SRTO_TRANSTYPE, &transtype, sizeof(transtype));

    if (latencyMs > 0)
        srt_setsockflag(sock_, SRTO_LATENCY, &latencyMs, sizeof(latencyMs));

    int payload = kPayloadSize;
    srt_setsockflag(sock_, SRTO_PAYLOADSIZE, &payload, sizeof(payload));

    if (!streamid.empty())
        srt_setsockflag(sock_, SRTO_STREAMID, streamid.c_str(),
                        static_cast<int>(streamid.size()));

    // Blocking connect (the SrtSession runs it on its own egress thread).
    if (srt_connect(sock_, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == SRT_ERROR) {
        // Only a permanent auth/version/compat refusal is terminal; transient handshake failures
        // (esp. SRT_REJ_TIMEOUT reconnecting to a listener still holding the previous peer) retry.
        int reason = srt_getrejectreason(sock_);
        rejected_ = IsPermanentRejectReason(reason);
        srt_close(sock_);
        sock_ = -1;
        return false;
    }

    connected_ = true;
    return true;
}

bool SrtLink::Send(const uint8_t* data, size_t size) {
    if (sock_ == -1 || !connected_) return false;
    const char* p = reinterpret_cast<const char*>(data);
    size_t remaining = size;
    while (remaining > 0) {
        int chunk = static_cast<int>(remaining < static_cast<size_t>(kPayloadSize)
                                         ? remaining
                                         : static_cast<size_t>(kPayloadSize));
        SRT_MSGCTRL ctrl;
        srt_msgctrl_init(&ctrl);
        int n = srt_sendmsg2(sock_, p, chunk, &ctrl);
        if (n == SRT_ERROR) {
            connected_ = false;
            return false;
        }
        p += chunk;
        remaining -= static_cast<size_t>(chunk);
    }
    return true;
}

bool SrtLink::Stats(AbrStats& out) {
    if (sock_ == -1) return false;
    SRT_TRACEBSTATS perf;
    // clear=1: reset interval counters so pktSndLoss/pktSent reflect THIS interval.
    if (srt_bstats(sock_, &perf, 1) == SRT_ERROR) return false;

    out.rttMs = perf.msRTT;
    out.sndBufPkts = perf.pktSndBuf;
    out.inflight = perf.pktFlightSize;

    // Interval send-loss percentage: lost / sent over the interval since the last clear.
    double sent = static_cast<double>(perf.pktSent);
    double lost = static_cast<double>(perf.pktSndLoss);
    out.lossPct = (sent > 0.0) ? (lost / sent) * 100.0 : 0.0;
    return true;
}

void SrtLink::Close() {
    if (sock_ != -1) {
        srt_close(sock_);
        sock_ = -1;
    }
    connected_ = false;
}

void SrtLink::Interrupt() {
    // Closing the socket from another thread unblocks a blocking srt_connect(); leave sock_ as
    // is so the owning thread's Close()/failure path tidies up (a second srt_close is benign).
    if (sock_ != -1) srt_close(sock_);
}

}  // namespace ps
