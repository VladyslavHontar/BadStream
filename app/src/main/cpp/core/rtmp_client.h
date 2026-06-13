#pragma once
#include <string>
#include <utility>
#include "byte_writer.h"
#include "transport.h"
namespace ps {
struct StreamParams {
    std::string host, app, streamKey, tcUrl;
    uint16_t port = 1935;
    int width = 1280, height = 720, sampleRate = 44100;
    double fps = 30.0;
};
// AMF0 body of the `connect` command (without chunk framing). Public for testing.
Bytes BuildConnectCommand(const StreamParams& p, int txn);

enum class RtmpState { Idle, HandshakeSent, ConnectSent, CreateStreamSent, PublishSent, Publishing, Error };

// De-frames single-chunk fmt0 RTMP messages (sufficient for setup-phase control/command msgs).
class RtmpReader {
public:
    void Feed(const Bytes& d) { buf_.insert(buf_.end(), d.begin(), d.end()); }
    bool Next(uint8_t& msgType, Bytes& payload);   // pops one complete message
private:
    Bytes buf_; size_t pos_ = 0;
};

class RtmpClient {
public:
    RtmpClient(Transport& t, StreamParams p) : t_(t), p_(std::move(p)) {}
    void Begin();                  // sends C0C1
    void OnBytes(const Bytes& d);  // advances the state machine
    RtmpState state() const { return state_; }
    int streamId() const { return streamId_; }
    void SendVideoConfig(const Bytes& sps, const Bytes& pps);
    void SendVideo(const Bytes& annexb, bool keyframe, uint32_t ptsMs, uint32_t dtsMs);
    void SendAudioConfig(int sampleRate, int channels);
    void SendAudio(const Bytes& aacRaw, uint32_t ptsMs);
private:
    void sendCommand(const Bytes& body, int msgStreamId); // csid 3, type 0x14
    void afterHandshake();
    Transport& t_;
    StreamParams p_;
    RtmpState state_ = RtmpState::Idle;
    RtmpReader reader_;
    bool handshakeDone_ = false;
    size_t handshakeNeed_ = 1537 + 1536;
    Bytes hsBuf_;
    int txn_ = 1, streamId_ = 0, createStreamTxn_ = 0;
    uint32_t outChunkSize_ = 128;
};
}
