#pragma once

namespace ps {

// A snapshot of SRT link health (subset of srt_bstats). Host-testable: NO libsrt.
struct AbrStats {
    double rttMs;       // smoothed RTT
    int sndBufPkts;     // packets buffered in the SRT send buffer (backlog)
    int inflight;       // packets in flight (sent, unacknowledged)
    double lossPct;     // send loss rate (%) over the interval
};

struct AbrConfig {
    int minBps;
    int targetBps;
    int maxBps;
};

// Pure BELABOX-style control law. Returns the next target encoder bitrate (bps).
// Decrease fast on send-buffer buildup / loss / high RTT; increase slowly when clear.
// Always clamped to [minBps, maxBps] and rate-limited per call.
int AbrNextBitrate(const AbrStats& s, const AbrConfig& cfg, int currentBps);

}  // namespace ps
