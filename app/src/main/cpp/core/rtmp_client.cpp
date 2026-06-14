#include "rtmp_client.h"
#include "amf0.h"
#include "rtmp_handshake.h"
#include "rtmp_chunk.h"
#include "flv.h"
namespace ps {

Bytes BuildConnectCommand(const StreamParams& p, int txn, Codec requested) {
    Bytes b;
    Amf0::String(b, "connect");
    Amf0::Number(b, txn);
    Amf0::ObjectBegin(b);
    Amf0::Key(b, "app");            Amf0::String(b, p.app);
    Amf0::Key(b, "flashVer");       Amf0::String(b, "FMLE/3.0 (compatible; FMSc/1.0)");
    Amf0::Key(b, "swfUrl");         Amf0::Null(b);
    Amf0::Key(b, "tcUrl");          Amf0::String(b, p.tcUrl);
    Amf0::Key(b, "fpad");           Amf0::Boolean(b, false);
    Amf0::Key(b, "capabilities");   Amf0::Number(b, 239);
    Amf0::Key(b, "audioCodecs");    Amf0::Number(b, 1024); // AAC
    Amf0::Key(b, "videoCodecs");    Amf0::Number(b, 128);  // H.264
    Amf0::Key(b, "videoFunction");  Amf0::Number(b, 1);
    Amf0::Key(b, "pageUrl");        Amf0::Null(b);
    Amf0::Key(b, "objectEncoding"); Amf0::Number(b, 0);
    if (requested == Codec::Hevc) {
        Amf0::Key(b, "fourCcList");
        Amf0::StrictArrayBegin(b, 2);
        Amf0::String(b, "hvc1");
        Amf0::String(b, "avc1");
    }
    Amf0::ObjectEnd(b);
    return b;
}

void RtmpClient::Begin() {
    codec_ = (requestedCodec_ == Codec::Hevc)
        ? std::unique_ptr<VideoCodec>(new HevcCodec())
        : std::unique_ptr<VideoCodec>(new AvcCodec());
    negotiatedCodec_ = requestedCodec_;
    RtmpHandshake h; Send(h.BuildC0C1());
    state_ = RtmpState::HandshakeSent;
}
void RtmpClient::sendCommand(const Bytes& body, int msid) {
    Send(ChunkEncode(3, 0x14, msid, 0, body, outChunkSize_));
}
void RtmpClient::afterHandshake() {
    sendCommand(BuildConnectCommand(p_, 1, requestedCodec_), 0);   // connect, txn=1
    state_ = RtmpState::ConnectSent;
}
void RtmpClient::OnBytes(const Bytes& d) {
    receivedBytes_ += d.size();
    if (!handshakeDone_) {
        hsBuf_.insert(hsBuf_.end(), d.begin(), d.end());
        if (hsBuf_.size() < handshakeNeed_) return;
        RtmpHandshake h;
        Bytes s0s1(hsBuf_.begin(), hsBuf_.begin() + 1537);
        Send(h.BuildC2(s0s1));
        handshakeDone_ = true;
        if (hsBuf_.size() > handshakeNeed_)
            reader_.Feed(Bytes(hsBuf_.begin() + handshakeNeed_, hsBuf_.end()));
        afterHandshake();
        return;
    }
    reader_.Feed(d);
    uint8_t type; Bytes payload;
    while (reader_.Next(type, payload)) {
        if (type == 0x05) {                                  // Window Acknowledgement Size
            if (payload.size() >= 4) {
                serverWindow_ = ((uint32_t)payload[0]<<24)|((uint32_t)payload[1]<<16)|((uint32_t)payload[2]<<8)|payload[3];
                Bytes w; PutU32BE(w, serverWindow_);
                Send(ChunkEncode(2, 0x05, 0, 0, w, 128));    // echo our own window
            }
            continue;
        }
        if (type == 0x06) {                                  // Set Peer Bandwidth
            if (payload.size() >= 4) {
                serverWindow_ = ((uint32_t)payload[0]<<24)|((uint32_t)payload[1]<<16)|((uint32_t)payload[2]<<8)|payload[3];
                Bytes w; PutU32BE(w, serverWindow_);
                Send(ChunkEncode(2, 0x05, 0, 0, w, 128));    // reply with Window Ack Size
            }
            continue;
        }
        if (type == 0x04) {                                  // User Control
            if (payload.size() >= 6 && payload[0] == 0x00 && payload[1] == 0x06) {  // PingRequest
                Bytes pong = {0x00, 0x07,                    // PingResponse event
                              payload[2], payload[3], payload[4], payload[5]};       // echo ts
                Send(ChunkEncode(2, 0x04, 0, 0, pong, 128));
            }
            continue;
        }
        if (type == 0x03) continue;                          // Acknowledgement from server (info)
        if (type != 0x14) continue;                          // only AMF0 commands drive the FSM
        Amf0Reader r(payload.data(), payload.size());
        std::string name = r.ReadString();
        int txn = (int)r.ReadNumber();
        if (name == "_error") {
            state_ = RtmpState::Error;
        } else if (name == "_result" && state_ == RtmpState::ConnectSent) {
            // Some servers reject the connection with a _result carrying level=="error" (e.g.
            // NetConnection.Connect.Rejected) instead of an _error command. Treat it as terminal.
            if (Amf0::FindStringValue(payload, "level") == "error") { state_ = RtmpState::Error; continue; }
            bool serverHevc = VideoCodecAdvertised(payload, "hvc1");
            negotiatedCodec_ = (requestedCodec_ == Codec::Hevc && serverHevc) ? Codec::Hevc : Codec::Avc;
            codec_ = (negotiatedCodec_ == Codec::Hevc)
                ? std::unique_ptr<VideoCodec>(new HevcCodec())
                : std::unique_ptr<VideoCodec>(new AvcCodec());
            outChunkSize_ = 4096;
            { Bytes cs; PutU32BE(cs, 4096); Send(ChunkEncode(2, 0x01, 0, 0, cs, 128)); }
            { Bytes b; Amf0::String(b,"releaseStream"); Amf0::Number(b,++txn_); Amf0::Null(b); Amf0::String(b,p_.streamKey); sendCommand(b,0); }
            { Bytes b; Amf0::String(b,"FCPublish");     Amf0::Number(b,++txn_); Amf0::Null(b); Amf0::String(b,p_.streamKey); sendCommand(b,0); }
            createStreamTxn_ = ++txn_;
            { Bytes b; Amf0::String(b,"createStream");  Amf0::Number(b,createStreamTxn_); Amf0::Null(b); sendCommand(b,0); }
            state_ = RtmpState::CreateStreamSent;
        } else if (name == "_result" && state_ == RtmpState::CreateStreamSent && txn == createStreamTxn_) {
            r.SkipValue();
            streamId_ = (int)r.ReadNumber();
            { Bytes b; Amf0::String(b,"publish"); Amf0::Number(b,++txn_); Amf0::Null(b);
              Amf0::String(b,p_.streamKey); Amf0::String(b,"live"); sendCommand(b, streamId_); }
            state_ = RtmpState::PublishSent;
        } else if (name == "onStatus" && state_ == RtmpState::PublishSent) {
            if (Amf0::FindStringValue(payload, "code") == "NetStream.Publish.Start") {
                Send(ChunkEncode(8, 0x12, streamId_, 0,
                         BuildOnMetaData(p_.width, p_.height, p_.fps, p_.sampleRate), outChunkSize_));
                state_ = RtmpState::Publishing;
            } else if (Amf0::FindStringValue(payload, "level") == "error") {
                state_ = RtmpState::Error;
            }
        }
    }
    // Send our own Acknowledgement once we've consumed a full window of inbound bytes.
    if (serverWindow_ > 0 && receivedBytes_ - lastAckBytes_ >= serverWindow_) {
        Bytes a; PutU32BE(a, (uint32_t)receivedBytes_);
        Send(ChunkEncode(2, 0x03, 0, 0, a, 128));
        lastAckBytes_ = receivedBytes_;
    }
}
void RtmpClient::SendVideoConfig(const Bytes& csd) {
    Bytes body = codec_->SequenceHeader(csd);
    Send(ChunkEncode(5, 0x09, streamId_, 0, body, outChunkSize_));
}
void RtmpClient::SendVideoConfig(const Bytes& sps, const Bytes& pps) {
    Bytes csd; csd.insert(csd.end(), {0,0,0,1}); csd.insert(csd.end(), sps.begin(), sps.end());
    csd.insert(csd.end(), {0,0,0,1}); csd.insert(csd.end(), pps.begin(), pps.end());
    SendVideoConfig(csd);
}
void RtmpClient::SendVideo(const Bytes& annexb, bool keyframe, uint32_t ptsMs, uint32_t dtsMs) {
    uint32_t cts = ptsMs >= dtsMs ? ptsMs - dtsMs : 0;
    Bytes body = codec_->Frame(annexb, keyframe, cts);
    Send(ChunkEncode(5, 0x09, streamId_, dtsMs, body, outChunkSize_));
}
void RtmpClient::SendAudioConfig(int sampleRate, int channels) {
    Bytes body = FlvAudioSeqHeader(BuildAsc(sampleRate, channels));
    Send(ChunkEncode(4, 0x08, streamId_, 0, body, outChunkSize_));
}
void RtmpClient::SendAudio(const Bytes& aacRaw, uint32_t ptsMs) {
    Bytes body = FlvAudioFrame(aacRaw);
    Send(ChunkEncode(4, 0x08, streamId_, ptsMs, body, outChunkSize_));
}
void RtmpClient::SendUnpublish() {
    { Bytes b; Amf0::String(b,"FCUnpublish");  Amf0::Number(b,++txn_); Amf0::Null(b); Amf0::String(b,p_.streamKey); sendCommand(b,0); }
    { Bytes b; Amf0::String(b,"deleteStream"); Amf0::Number(b,++txn_); Amf0::Null(b); Amf0::Number(b, streamId_); sendCommand(b,0); }
    { Bytes b; Amf0::String(b,"closeStream");  Amf0::Number(b,++txn_); Amf0::Null(b); sendCommand(b, streamId_); }
}
// Full RTMP chunk de-assembler. Parses one chunk per iteration: basic header (fmt + csid),
// then a 0/4/8/11-byte message header per fmt with per-csid inheritance, optional extended
// timestamp, and up to inChunkSize_ payload bytes, reassembling messages split across chunks.
// Returns one complete message per call; partial chunks leave buf_/pos_ untouched until more
// bytes arrive. All buffer reads are bounds-guarded (network input).
bool RtmpReader::Next(uint8_t& msgType, Bytes& payload) {
    while (true) {
        size_t p = pos_, n = buf_.size();
        if (p >= n) return false;
        // --- basic header ---
        uint8_t b0 = buf_[p];
        uint8_t fmt = b0 >> 6;
        uint32_t csid = b0 & 0x3F;
        size_t hp = p + 1;
        if (csid == 0) { if (hp + 1 > n) return false; csid = 64 + buf_[hp]; hp += 1; }
        else if (csid == 1) { if (hp + 2 > n) return false; csid = 64u + (buf_[hp] << 8) + buf_[hp+1]; hp += 2; }
        Chunk& ch = cs_[csid];
        // --- message header (by fmt) ---
        uint32_t tsField = ch.ts;
        if (fmt <= 2) { if (hp + 3 > n) return false; tsField = (buf_[hp]<<16)|(buf_[hp+1]<<8)|buf_[hp+2]; hp += 3; }
        if (fmt <= 1) { if (hp + 4 > n) return false;
            ch.len = (buf_[hp]<<16)|(buf_[hp+1]<<8)|buf_[hp+2]; ch.type = buf_[hp+3]; hp += 4; }
        if (fmt == 0) { if (hp + 4 > n) return false;
            ch.streamId = buf_[hp] | (buf_[hp+1]<<8) | (buf_[hp+2]<<16) | ((uint32_t)buf_[hp+3]<<24); hp += 4; }
        // Extended timestamp: present on fmt0/1/2 when the 3-byte field is 0xFFFFFF, and ALSO
        // on every fmt3 continuation chunk of such a message (per RTMP spec, matching what
        // ffmpeg/FMS emit and expect). Without re-reading it on fmt3, 4 payload bytes would be
        // mis-consumed as a phantom timestamp and the chunk stream would desync.
        if (fmt <= 2) ch.extTs = (tsField == 0xFFFFFF);
        if ((fmt <= 2 && tsField == 0xFFFFFF) || (fmt == 3 && ch.extTs)) {
            if (hp + 4 > n) return false;                                        // extended timestamp
            tsField = (buf_[hp]<<24)|(buf_[hp+1]<<16)|(buf_[hp+2]<<8)|buf_[hp+3]; hp += 4; }
        ch.ts = tsField;
        // --- payload for this chunk ---
        bool startNew = (fmt != 3) || (ch.partial.size() >= ch.len);  // fmt0/1/2 begin a new msg
        if (startNew) ch.partial.clear();
        uint32_t remaining = ch.len - (uint32_t)ch.partial.size();
        uint32_t take = remaining < inChunkSize_ ? remaining : inChunkSize_;
        if (hp + take > n) return false;                              // wait for the full chunk
        ch.partial.insert(ch.partial.end(), buf_.begin() + hp, buf_.begin() + hp + take);
        pos_ = hp + take;                                             // commit consumption
        if (ch.partial.size() >= ch.len) {                            // message complete
            msgType = ch.type;
            payload = ch.partial;
            ch.partial.clear();
            if (msgType == 0x01 && payload.size() >= 4)               // Set Chunk Size (inbound)
                inChunkSize_ = (payload[0]<<24)|(payload[1]<<16)|(payload[2]<<8)|payload[3];
            if (pos_ > (1u << 16)) { buf_.erase(buf_.begin(), buf_.begin() + pos_); pos_ = 0; }
            return true;
        }
        // message still incomplete: loop to parse the next (continuation) chunk
    }
}
}
