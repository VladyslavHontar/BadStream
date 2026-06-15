#include "abr.h"
#include <algorithm>

namespace ps {

// BELABOX-style adaptive bitrate: react quickly to congestion (send-buffer buildup,
// packet loss, RTT inflation), recover slowly when the link is clear. Pure function:
// one decision per call given the latest stats and the current encoder bitrate.
//
// Tuning constants (per-tick fractions of current bitrate):
//   - climb:  +12.5% when fully healthy (slow recovery, rate-limited)
//   - drop:   -40%   when congested (fast back-off)
// Congestion signals: send buffer above a threshold, any loss, or RTT well above a floor.
int AbrNextBitrate(const AbrStats& s, const AbrConfig& cfg, int currentBps) {
    int minBps = cfg.minBps;
    int maxBps = std::max(cfg.minBps, cfg.maxBps);

    // Congestion detection.
    const int kSndBufThreshold = 1000;     // packets queued in the SRT send buffer
    const double kRttFloorMs = 200.0;      // RTT above this (with backlog) signals congestion
    bool buffering = s.sndBufPkts > kSndBufThreshold;
    bool lossy = s.lossPct > 0.5;
    bool rttHigh = s.rttMs > kRttFloorMs && s.sndBufPkts > kSndBufThreshold / 2;

    int next;
    if (buffering || lossy || rttHigh) {
        // Fast multiplicative decrease.
        next = (int)((double)currentBps * 0.60);   // -40%
    } else {
        // Slow additive/multiplicative increase toward max.
        int step = currentBps / 8;                 // +12.5%
        if (step < 50'000) step = 50'000;          // ensure forward progress at low rates
        next = currentBps + step;
    }

    // Clamp to configured bounds.
    if (next < minBps) next = minBps;
    if (next > maxBps) next = maxBps;
    return next;
}

}  // namespace ps
