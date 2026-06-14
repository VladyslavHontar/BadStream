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
}
