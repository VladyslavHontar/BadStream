#pragma once
#include <atomic>
#include <functional>
#include <memory>
#include <thread>
#include "media_queue.h"
#include "rtmp_client.h"
#include "transport.h"
namespace ps {

enum class SessionState { Idle, Connecting, Live, Error };

// Owns a transport + RtmpClient on a dedicated egress thread. Public methods are
// thread-safe (enqueue onto MediaQueue / atomics). The egress thread runs the proven
// harness loop: Begin -> read/OnBytes until Publishing -> drain queue -> Send*.
class StreamSession {
public:
    using TransportFactory = std::function<std::unique_ptr<Transport>()>;

    StreamSession(StreamParams params, TransportFactory factory, Codec requestedCodec = Codec::Avc)
        : params_(std::move(params)), factory_(std::move(factory)),
          requestedCodec_(requestedCodec), queue_(256) {}
    ~StreamSession() { Stop(); }

    void Start();   // spawns egress thread; returns immediately
    void Stop();    // closes queue, joins thread, closes transport

    SessionState state() const { return state_.load(); }
    Codec negotiatedCodec() const { return negotiated_.load(); }

    void SendVideoConfig(const Bytes& csd);  // raw SPS+PPS annexb blob; split natively
    void SendVideo(const Bytes& annexb, bool key, uint32_t ptsMs, uint32_t dtsMs);
    void SendAudioConfig(int sampleRate, int channels);
    void SendAudio(const Bytes& aacRaw, uint32_t ptsMs);

private:
    void run();     // egress thread body

    StreamParams params_;
    TransportFactory factory_;
    Codec requestedCodec_;
    std::unique_ptr<Transport> transport_;
    MediaQueue queue_;
    std::thread thread_;
    std::atomic<SessionState> state_{SessionState::Idle};
    std::atomic<Codec> negotiated_{Codec::Avc};
    std::atomic<bool> running_{false};
};
}
