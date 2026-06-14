#pragma once
#include <atomic>
#include <functional>
#include <memory>
#include <thread>
#include "media_queue.h"
#include "rtmp_client.h"
#include "transport.h"
namespace ps {

// Int values cross JNI to Kotlin (nativeState): 0=Idle,1=Connecting,2=Live,3=Dropped,4=Rejected.
// Dropped = transient transport failure (Kotlin reconnects). Rejected = server refused
// (auth/bad-key/already-publishing) → terminal.
enum class SessionState { Idle = 0, Connecting = 1, Live = 2, Dropped = 3, Rejected = 4 };

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
    uint64_t bytesSent() const { return bytesSent_.load(); }
    int queueDepth() const { return queueDepth_.load(); }

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
    std::atomic<uint64_t> bytesSent_{0};
    std::atomic<int> queueDepth_{0};
    std::atomic<bool> running_{false};
};
}
