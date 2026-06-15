#include "srt_session.h"

#include <chrono>

#include "srt_link.h"

namespace ps {

void SrtSession::Start() {
    if (running_.exchange(true)) return;
    state_ = SessionState::Connecting;
    thread_ = std::thread([this] { run(); });
}

void SrtSession::Stop() {
    if (!running_.exchange(false)) {
        if (thread_.joinable()) thread_.join();
        return;
    }
    queue_.Close();   // wake the drain loop so the thread exits promptly
    // If a connect is in flight, interrupt it so the join doesn't wait out SRTO_CONNTIMEO.
    { std::lock_guard<std::mutex> lk(linkMutex_); if (activeLink_) activeLink_->Interrupt(); }
    if (thread_.joinable()) thread_.join();
    state_ = SessionState::Idle;
}

void SrtSession::SendVideoConfig(const Bytes& csd) {
    queue_.Push(MediaItem{MediaItem::VideoConfig, csd, false, 0, 0});
}
void SrtSession::SendVideo(const Bytes& annexb, bool key, uint32_t pts, uint32_t dts) {
    queue_.Push(MediaItem{MediaItem::Video, annexb, key, pts, dts});
}
void SrtSession::SendAudioConfig(int sampleRate, int channels) {
    MediaItem it{MediaItem::AudioConfig, {}, false, 0, 0};
    it.sampleRate = sampleRate;
    it.channels = channels;
    queue_.Push(std::move(it));
}
void SrtSession::SendAudio(const Bytes& aac, uint32_t pts) {
    queue_.Push(MediaItem{MediaItem::Audio, aac, false, pts, 0});
}

void SrtSession::run() {
    SrtLink link;
    // Expose the link to Stop() ONLY for the duration of the blocking Connect(), so a user
    // Stop() during connect can Interrupt() it; clear it the moment Connect() returns.
    { std::lock_guard<std::mutex> lk(linkMutex_); activeLink_ = &link; }
    bool ok = link.Connect(params_.host, params_.port, params_.streamid, params_.latencyMs);
    { std::lock_guard<std::mutex> lk(linkMutex_); activeLink_ = nullptr; }
    if (!ok) {
        // Peer rejection (bad streamid/handshake) -> terminal; transient connect error ->
        // Dropped so the Kotlin reconnect loop retries. Skip the flash if a user Stop() already
        // cleared running_ (Stop() sets Idle).
        if (running_.exchange(false))
            state_ = link.rejected() ? SessionState::Rejected : SessionState::Dropped;
        return;
    }
    if (!running_.load()) return;   // user Stop() during connect

    state_ = SessionState::Live;

    TsMuxer muxer;
    muxer.SetVideo(params_.video);
    muxer.SetAudio(params_.sampleRate, params_.channels);

    // Codec config (SPS/PPS / VPS+SPS+PPS) is carried in-band in the TS stream prepended to the
    // first keyframe (TsMuxer expects Annex-B params on the first keyframe). We stash the csd and
    // prepend it to the next keyframe access unit.
    Bytes pendingCsd;
    bool csdPrepended = false;

    MediaItem item;
    bool haveBase = false;
    uint32_t baseMs = 0;
    auto rebase = [&](uint32_t ts) -> uint32_t { return ts >= baseMs ? ts - baseMs : 0; };

    SessionState endState = SessionState::Idle;   // Idle => user-stop (Stop sets Idle)

    auto lastStats = std::chrono::steady_clock::now();
    const auto kStatsInterval = std::chrono::seconds(1);

    while (running_.load()) {
        if (queue_.PopTimeout(item, 50)) {
            Bytes ts;
            switch (item.kind) {
                case MediaItem::VideoConfig:
                    pendingCsd = item.data;   // hold for the next keyframe
                    break;
                case MediaItem::Video: {
                    if (!haveBase) { baseMs = item.dtsMs; haveBase = true; }
                    if (!csdPrepended && item.keyframe && !pendingCsd.empty()) {
                        Bytes au = pendingCsd;
                        au.insert(au.end(), item.data.begin(), item.data.end());
                        ts = muxer.WriteVideo(au, true, rebase(item.ptsMs), rebase(item.dtsMs));
                        csdPrepended = true;
                    } else {
                        ts = muxer.WriteVideo(item.data, item.keyframe,
                                              rebase(item.ptsMs), rebase(item.dtsMs));
                    }
                    break;
                }
                case MediaItem::AudioConfig:
                    muxer.SetAudio(item.sampleRate, item.channels);
                    break;
                case MediaItem::Audio:
                    if (!haveBase) { baseMs = item.ptsMs; haveBase = true; }
                    ts = muxer.WriteAudio(item.data, rebase(item.ptsMs));
                    break;
            }

            if (!ts.empty()) {
                if (!link.Send(ts)) { endState = SessionState::Dropped; break; }
                bytesSent_.fetch_add(ts.size());
                queueDepth_.store(static_cast<int>(queue_.size()));
            }
        }

        // Periodic ABR: poll srt_bstats, run the control law, publish the new target bitrate.
        auto now = std::chrono::steady_clock::now();
        if (now - lastStats >= kStatsInterval) {
            lastStats = now;
            AbrStats s{};
            if (link.Stats(s)) {
                int next = AbrNextBitrate(s, params_.abr, targetBitrate_.load());
                targetBitrate_.store(next);
            }
        }

        if (!link.connected()) { endState = SessionState::Dropped; break; }
    }

    link.Close();
    if (endState == SessionState::Dropped || endState == SessionState::Rejected)
        state_ = endState;   // else user-stop: leave state for Stop() to set Idle
}

}  // namespace ps
