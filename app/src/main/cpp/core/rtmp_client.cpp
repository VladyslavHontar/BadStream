#include "rtmp_client.h"
#include "amf0.h"
#include "rtmp_handshake.h"
#include "rtmp_chunk.h"
#include "flv.h"
namespace ps {
Bytes BuildConnectCommand(const StreamParams& p, int txn) {
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
    Amf0::ObjectEnd(b);
    return b;
}

void RtmpClient::Begin() {
    RtmpHandshake h; t_.Write(h.BuildC0C1());
    state_ = RtmpState::HandshakeSent;
}
void RtmpClient::sendCommand(const Bytes& body, int msid) {
    t_.Write(ChunkEncode(3, 0x14, msid, 0, body, outChunkSize_));
}
void RtmpClient::afterHandshake() {
    sendCommand(BuildConnectCommand(p_, 1), 0);   // connect, txn=1
    state_ = RtmpState::ConnectSent;
}
void RtmpClient::OnBytes(const Bytes& d) {
    if (!handshakeDone_) {
        hsBuf_.insert(hsBuf_.end(), d.begin(), d.end());
        if (hsBuf_.size() < handshakeNeed_) return;
        RtmpHandshake h;
        Bytes s0s1(hsBuf_.begin(), hsBuf_.begin() + 1537);
        t_.Write(h.BuildC2(s0s1));
        handshakeDone_ = true;
        if (hsBuf_.size() > handshakeNeed_)
            reader_.Feed(Bytes(hsBuf_.begin() + handshakeNeed_, hsBuf_.end()));
        afterHandshake();
        return;
    }
    reader_.Feed(d);
    uint8_t type; Bytes payload;
    while (reader_.Next(type, payload)) {
        if (type != 0x14) continue;               // only AMF0 commands drive the FSM
        Amf0Reader r(payload.data(), payload.size());
        std::string name = r.ReadString();
        int txn = (int)r.ReadNumber();
        if (name == "_error") {
            // Server rejected a command (e.g. NetConnection.Connect.Rejected). Surface it.
            state_ = RtmpState::Error;
        } else if (name == "_result" && state_ == RtmpState::ConnectSent) {
            outChunkSize_ = 4096;
            { Bytes cs; PutU32BE(cs, 4096); t_.Write(ChunkEncode(2, 0x01, 0, 0, cs, 128)); }
            { Bytes b; Amf0::String(b,"releaseStream"); Amf0::Number(b,++txn_); Amf0::Null(b); Amf0::String(b,p_.streamKey); sendCommand(b,0); }
            { Bytes b; Amf0::String(b,"FCPublish");     Amf0::Number(b,++txn_); Amf0::Null(b); Amf0::String(b,p_.streamKey); sendCommand(b,0); }
            createStreamTxn_ = ++txn_;
            { Bytes b; Amf0::String(b,"createStream");  Amf0::Number(b,createStreamTxn_); Amf0::Null(b); sendCommand(b,0); }
            state_ = RtmpState::CreateStreamSent;
        } else if (name == "_result" && state_ == RtmpState::CreateStreamSent && txn == createStreamTxn_) {
            // Only the createStream reply (matching txn) carries the stream id; ignore
            // stray _results that some servers send for releaseStream/FCPublish.
            r.SkipValue();                         // skip the null command object
            streamId_ = (int)r.ReadNumber();
            { Bytes b; Amf0::String(b,"publish"); Amf0::Number(b,++txn_); Amf0::Null(b);
              Amf0::String(b,p_.streamKey); Amf0::String(b,"live"); sendCommand(b, streamId_); }
            state_ = RtmpState::PublishSent;
        } else if (name == "onStatus" && state_ == RtmpState::PublishSent) {
            if (Amf0::FindStringValue(payload, "code") == "NetStream.Publish.Start") {
                t_.Write(ChunkEncode(8, 0x12, streamId_, 0,
                         BuildOnMetaData(p_.width, p_.height, p_.fps, p_.sampleRate), outChunkSize_));
                state_ = RtmpState::Publishing;
            } else if (Amf0::FindStringValue(payload, "level") == "error") {
                state_ = RtmpState::Error;         // e.g. NetStream.Publish.BadName
            }
        }
    }
}
// Minimal single-chunk fmt0 de-framer (csid 2..63). Skips non-fmt0 bytes (control noise).
bool RtmpReader::Next(uint8_t& msgType, Bytes& payload) {
    while (pos_ + 12 <= buf_.size()) {
        uint8_t fmt = buf_[pos_] >> 6;
        if (fmt != 0) { pos_ += 1; continue; }
        uint32_t len = (buf_[pos_+4] << 16) | (buf_[pos_+5] << 8) | buf_[pos_+6];
        if (pos_ + 12 + len > buf_.size()) return false;
        msgType = buf_[pos_+7];
        payload.assign(buf_.begin() + pos_ + 12, buf_.begin() + pos_ + 12 + len);
        pos_ += 12 + len;
        return true;
    }
    return false;
}
}
