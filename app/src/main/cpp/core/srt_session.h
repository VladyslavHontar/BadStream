#pragma once
#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <thread>

#include "abr.h"
#include "media_queue.h"
#include "stream_session.h"   // SessionState
#include "ts_muxer.h"

namespace ps {

// SRT egress session, mirroring StreamSession's shape (dedicated egress thread draining the
// shared MediaQueue; same Send* API + bytesSent/queueDepth/state with Dropped/Rejected so the
// existing Kotlin reconnect loop applies). The body differs: samples are fed to TsMuxer and the
// produced MPEG-TS bytes are sent over a single libsrt LIVE caller (SrtLink), sliced to 1316.
//
// NDK-ONLY: pulls in srt_link.h (-> <srt/srt.h>) via the .cpp, so this lives in the JNI lib
// (plohoystream), NOT in plohoystream_core.
//
// SessionState ints cross JNI identically to StreamSession (0=Idle..4=Rejected).
class SrtSession {
public:
    struct Params {
        std::string host;
        int port = 0;
        std::string streamid;
        int latencyMs = 2000;
        VideoCodecKind video = VideoCodecKind::Avc;
        int sampleRate = 44100;
        int channels = 2;
        AbrConfig abr{};   // min/target/max bps for the ABR controller
    };

    explicit SrtSession(Params params) : params_(std::move(params)), queue_(256) {
        targetBitrate_.store(params_.abr.targetBps);
    }
    ~SrtSession() { Stop(); }

    void Start();   // spawns the egress thread; returns immediately
    void Stop();    // closes queue, joins thread, closes the link

    SessionState state() const { return state_.load(); }
    uint64_t bytesSent() const { return bytesSent_.load(); }
    int queueDepth() const { return queueDepth_.load(); }
    // Latest ABR target bitrate (bps); polled by the engine to drive the encoder.
    int targetBitrate() const { return targetBitrate_.load(); }

    void SendVideoConfig(const Bytes& csd);
    void SendVideo(const Bytes& annexb, bool key, uint32_t ptsMs, uint32_t dtsMs);
    void SendAudioConfig(int sampleRate, int channels);
    void SendAudio(const Bytes& aacRaw, uint32_t ptsMs);

private:
    void run();   // egress thread body

    Params params_;
    MediaQueue queue_;
    std::thread thread_;
    std::atomic<SessionState> state_{SessionState::Idle};
    std::atomic<uint64_t> bytesSent_{0};
    std::atomic<int> queueDepth_{0};
    std::atomic<int> targetBitrate_{0};
    std::atomic<bool> running_{false};
};

}  // namespace ps
