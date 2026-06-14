#include "stream_session.h"
#include "flv.h"
namespace ps {

void StreamSession::Start() {
    if (running_.exchange(true)) return;
    // Create the transport on the calling thread BEFORE spawning the egress thread so
    // Stop() can reach it and Close() it to interrupt a blocking Read(). The factory only
    // constructs the object (it does not connect), so this does not block.
    transport_ = factory_();
    state_ = SessionState::Connecting;
    thread_ = std::thread([this] { run(); });
}

void StreamSession::Stop() {
    if (!running_.exchange(false)) { if (thread_.joinable()) thread_.join(); return; }
    queue_.Close();
    // Close the socket to interrupt a blocking Read() on the egress thread (ANR fix).
    if (transport_) transport_->Close();
    if (thread_.joinable()) thread_.join();
    transport_.reset();
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
    if (!transport_ || !transport_->Connect(params_.host, params_.port)) {
        state_ = SessionState::Error; running_ = false; return;
    }
    RtmpClient client(*transport_, params_);
    client.RequestCodec(requestedCodec_);
    client.Begin();

    uint8_t buf[8192];
    while (running_.load() &&
           client.state() != RtmpState::Publishing &&
           client.state() != RtmpState::Error) {
        int n = transport_->Read(buf, sizeof(buf));
        if (n <= 0) { state_ = SessionState::Error; running_ = false; return; }
        client.OnBytes(Bytes(buf, buf + n));
    }
    if (client.state() != RtmpState::Publishing) {
        state_ = SessionState::Error; running_ = false; return;
    }
    state_ = SessionState::Live;
    negotiated_.store(client.negotiatedCodec());

    // Encoders timestamp samples with a boot-based monotonic clock (millions of ms), so the
    // stream would start at a huge timestamp and ride RTMP's extended-timestamp path from
    // frame 1. Subtract the first media sample's timestamp (shared across audio + video, so
    // their relative offset is preserved) to make the stream start near 0.
    MediaItem item;
    bool haveBase = false;
    uint32_t baseMs = 0;
    auto rebase = [&](uint32_t ts) -> uint32_t { return ts >= baseMs ? ts - baseMs : 0; };
    while (queue_.Pop(item)) {
        switch (item.kind) {
            case MediaItem::VideoConfig: {
                client.SendVideoConfig(item.data);
                break;
            }
            case MediaItem::Video: {
                if (!haveBase) { baseMs = item.dtsMs; haveBase = true; }
                client.SendVideo(item.data, item.keyframe, rebase(item.ptsMs), rebase(item.dtsMs));
                break;
            }
            case MediaItem::AudioConfig:
                client.SendAudioConfig(item.sampleRate, item.channels);
                break;
            case MediaItem::Audio: {
                if (!haveBase) { baseMs = item.ptsMs; haveBase = true; }
                client.SendAudio(item.data, rebase(item.ptsMs));
                break;
            }
        }
        bytesSent_.store(client.bytesSent());
        queueDepth_.store(static_cast<int>(queue_.size()));
        if (!transport_->connected()) { state_ = SessionState::Error; break; }
    }
    transport_->Close();
}
}
