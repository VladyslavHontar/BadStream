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
