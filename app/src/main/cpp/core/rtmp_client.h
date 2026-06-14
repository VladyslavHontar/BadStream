#pragma once
#include <map>
#include <memory>
#include <string>
#include <utility>
#include "byte_writer.h"
#include "transport.h"
#include "video_codec.h"
namespace ps {
enum class Codec { Avc, Hevc };
struct StreamParams {
    std::string host, app, streamKey, tcUrl;
    uint16_t port = 1935;
    int width = 1280, height = 720, sampleRate = 44100;
    double fps = 30.0;
};
// AMF0 body of the `connect` command (without chunk framing). Public for testing.
Bytes BuildConnectCommand(const StreamParams& p, int txn, Codec requested);

enum class RtmpState { Idle, HandshakeSent, ConnectSent, CreateStreamSent, PublishSent, Publishing, Error };

// Full RTMP chunk de-assembler: handles chunk types fmt 0/1/2/3 with per-csid header
// inheritance, the inbound chunk size (default 128, updated on Set Chunk Size), extended
// timestamps, and reassembly of messages split across multiple chunks. Real servers
// (ffmpeg, nginx-rtmp, Twitch) interleave control messages using fmt1/fmt3, so a fmt0-only
// reader desyncs against them.
class RtmpReader {
public:
    void Feed(const Bytes& d) { buf_.insert(buf_.end(), d.begin(), d.end()); }
    bool Next(uint8_t& msgType, Bytes& payload);   // pops one complete message
private:
    struct Chunk { uint32_t len = 0, streamId = 0, ts = 0; uint8_t type = 0; bool extTs = false; Bytes partial; };
    Bytes buf_; size_t pos_ = 0;
    uint32_t inChunkSize_ = 128;
    std::map<uint32_t, Chunk> cs_;   // per-chunk-stream-id state
};

class RtmpClient {
public:
    RtmpClient(Transport& t, StreamParams p) : t_(t), p_(std::move(p)) {}
    void Begin();                  // sends C0C1
    void OnBytes(const Bytes& d);  // advances the state machine
    RtmpState state() const { return state_; }
    int streamId() const { return streamId_; }
    void RequestCodec(Codec c) { requestedCodec_ = c; } // call before Begin()
    Codec negotiatedCodec() const { return negotiatedCodec_; }
    void SendVideoConfig(const Bytes& csd);              // generic: dispatches through codec_
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
    std::unique_ptr<VideoCodec> codec_ = std::make_unique<AvcCodec>();
    Codec requestedCodec_ = Codec::Avc;
    Codec negotiatedCodec_ = Codec::Avc;
};
}
