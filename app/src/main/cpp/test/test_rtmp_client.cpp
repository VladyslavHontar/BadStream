#include "rtmp_client.h"
#include "stub_transport.h"
#include "test_helpers.h"
#include "rtmp_chunk.h"
#include "amf0.h"
using namespace ps;
TEST(RtmpClient, ConnectCommandObject) {
    StreamParams p; p.app = "live"; p.tcUrl = "rtmp://h/live"; p.streamKey = "key";
    Bytes payload = BuildConnectCommand(p, /*txn*/1, Codec::Avc);
    // command name "connect"
    EXPECT_EQ(payload[0], 0x02); EXPECT_EQ(payload[2], 7);
    EXPECT_EQ(std::string((char*)&payload[3], 7), "connect");
    // transaction id number 1.0 follows
    size_t i = 3 + 7;
    EXPECT_EQ(payload[i], 0x00);                 // number marker
    EXPECT_EQ(payload[i+1], 0x3F); EXPECT_EQ(payload[i+2], 0xF0);
    // object marker then content
    EXPECT_EQ(payload[i+9], 0x03);
    std::string s((char*)payload.data(), payload.size());
    EXPECT_NE(s.find("app"), std::string::npos);
    EXPECT_NE(s.find("live"), std::string::npos);
    EXPECT_NE(s.find("tcUrl"), std::string::npos);
    EXPECT_NE(s.find("flashVer"), std::string::npos);
}

static Bytes MakeCommand(const Bytes& body) { return ChunkEncode(3, 0x14, 0, 0, body, 128); }
static Bytes MakeResultSuccess() {
    Bytes b; Amf0::String(b,"_result"); Amf0::Number(b,1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b,"level"); Amf0::String(b,"status");
    Amf0::Key(b,"code");  Amf0::String(b,"NetConnection.Connect.Success");
    // Advertise both codecs so a HEVC request negotiates through to HEVC; an AVC request
    // still negotiates to AVC. Mirrors an enhanced-RTMP server that supports HEVC.
    Amf0::Key(b,"fourCcList"); Amf0::StrictArrayBegin(b,2);
    Amf0::String(b,"hvc1"); Amf0::String(b,"avc1");
    Amf0::ObjectEnd(b);
    return MakeCommand(b);
}
static Bytes MakeCreateStreamResult(int streamId) {
    Bytes b; Amf0::String(b,"_result"); Amf0::Number(b,4); Amf0::Null(b); Amf0::Number(b, streamId);
    return MakeCommand(b);
}
static Bytes MakePublishStart() {
    Bytes b; Amf0::String(b,"onStatus"); Amf0::Number(b,0); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b,"level"); Amf0::String(b,"status");
    Amf0::Key(b,"code");  Amf0::String(b,"NetStream.Publish.Start");
    Amf0::ObjectEnd(b);
    return MakeCommand(b);
}
TEST(RtmpClient, ReachesPublishing) {
    StubTransport t;
    StreamParams p; p.app="live"; p.tcUrl="rtmp://h/live"; p.streamKey="5"; p.host="h";
    RtmpClient c(t, p);
    c.Begin();
    EXPECT_EQ(c.state(), RtmpState::HandshakeSent);
    ASSERT_GE(t.written().size(), 1537u);
    EXPECT_EQ(t.written()[0], 0x03);

    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;
    c.OnBytes(s0s1s2);                          // feed S0S1+S2 -> sends C2 + connect
    EXPECT_EQ(c.state(), RtmpState::ConnectSent);

    c.OnBytes(MakeResultSuccess());             // connect success -> createStream phase
    EXPECT_EQ(c.state(), RtmpState::CreateStreamSent);

    c.OnBytes(MakeCreateStreamResult(1));       // stream id 1 -> publish
    EXPECT_EQ(c.state(), RtmpState::PublishSent);
    EXPECT_EQ(c.streamId(), 1);

    c.OnBytes(MakePublishStart());              // publish start -> Publishing
    EXPECT_EQ(c.state(), RtmpState::Publishing);
}
static Bytes MakeError() {
    Bytes b; Amf0::String(b,"_error"); Amf0::Number(b,1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b,"level"); Amf0::String(b,"error");
    Amf0::Key(b,"code");  Amf0::String(b,"NetConnection.Connect.Rejected");
    Amf0::ObjectEnd(b);
    return MakeCommand(b);
}
TEST(RtmpClient, ConnectRejectGoesToError) {
    StubTransport t;
    StreamParams p; p.app="live"; p.tcUrl="rtmp://h/live"; p.streamKey="5";
    RtmpClient c(t, p);
    c.Begin();
    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;
    c.OnBytes(s0s1s2);                       // -> ConnectSent
    c.OnBytes(MakeError());                   // server rejects connect
    EXPECT_EQ(c.state(), RtmpState::Error);
}
TEST(RtmpClient, StrayResultBeforeCreateStreamIgnored) {
    StubTransport t;
    StreamParams p; p.app="live"; p.tcUrl="rtmp://h/live"; p.streamKey="5";
    RtmpClient c(t, p);
    c.Begin();
    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;
    c.OnBytes(s0s1s2);
    c.OnBytes(MakeResultSuccess());           // -> CreateStreamSent
    // a stray _result with txn=2 (releaseStream ack) must NOT trigger publish
    { Bytes b; Amf0::String(b,"_result"); Amf0::Number(b,2); Amf0::Null(b); Amf0::Number(b,0);
      c.OnBytes(MakeCommand(b)); }
    EXPECT_EQ(c.state(), RtmpState::CreateStreamSent);   // still waiting for txn=4
    c.OnBytes(MakeCreateStreamResult(1));     // the real createStream result
    EXPECT_EQ(c.state(), RtmpState::PublishSent);
    EXPECT_EQ(c.streamId(), 1);
}

// Drives the client through handshake+connect+createStream+publish to Publishing (streamId=1).
static void ForcePublishing(RtmpClient& c) {
    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;
    c.Begin();
    c.OnBytes(s0s1s2);
    c.OnBytes(MakeResultSuccess());
    c.OnBytes(MakeCreateStreamResult(1));
    c.OnBytes(MakePublishStart());
}
TEST(RtmpClient, SendVideoConfigEmitsTaggedChunk) {
    StubTransport t; StreamParams p; p.streamKey="5";
    RtmpClient c(t, p); ForcePublishing(c);
    ASSERT_EQ(c.state(), RtmpState::Publishing);
    t.clear();
    c.SendVideoConfig({0x67,0x42,0xC0,0x1F,0xAA}, {0x68,0xCE,0x3C,0x80});
    const Bytes& w = t.written();
    ASSERT_GE(w.size(), 14u);
    EXPECT_EQ(w[0] & 0x3F, 5);            // csid 5 (video)
    EXPECT_EQ(w[7], 0x09);               // message type video
    EXPECT_EQ(w[12], 0x17);              // FLV: keyframe+AVC
    EXPECT_EQ(w[13], 0x00);              // AVC seq header
}
TEST(RtmpClient, AvcSendUnchangedAfterCodecSeam) {
    StubTransport t; StreamParams p; p.streamKey="5";
    RtmpClient c(t, p); ForcePublishing(c);   // AVC is the default codec
    t.clear();
    Bytes sps{0x67,0x42,0x00,0x1e,0x11}, pps{0x68,0xce,0x3c,0x80};
    c.SendVideoConfig(sps, pps);
    const Bytes& w = t.written();
    ASSERT_GT(w.size(), 12u);
    EXPECT_EQ(w[12], 0x17); EXPECT_EQ(w[13], 0x00);   // legacy AVC seq header
}
TEST(RtmpClient, HevcSendEmitsExVideoTag) {
    StubTransport t; StreamParams p; p.streamKey="5";
    RtmpClient c(t, p); c.RequestCodec(Codec::Hevc); ForcePublishing(c);
    t.clear();
    Bytes hevcCsd{0,0,0,1, 0x40,0x01,0x0c,0x01, 0,0,0,1, 0x42,0x01,0x01, 0,0,0,1, 0x44,0x01,0xc0};
    c.SendVideoConfig(hevcCsd);
    const Bytes& w = t.written();
    ASSERT_GT(w.size(), 17u);
    EXPECT_EQ(w[12], 0x90);            // IsExHeader|key|SequenceStart
    EXPECT_EQ(w[13], 'h'); EXPECT_EQ(w[16], '1');
}
TEST(RtmpClient, SendAudioRawEmitsTaggedChunk) {
    StubTransport t; StreamParams p; p.streamKey="5";
    RtmpClient c(t, p); ForcePublishing(c);
    t.clear();
    c.SendAudio({0xDE,0xAD}, /*ptsMs*/40);
    const Bytes& w = t.written();
    ASSERT_GE(w.size(), 14u);
    EXPECT_EQ(w[0] & 0x3F, 4);            // csid 4 (audio)
    EXPECT_EQ(w[7], 0x08);               // message type audio
    EXPECT_EQ(w[12], 0xAF); EXPECT_EQ(w[13], 0x01); // AAC raw
}
// Real servers (ffmpeg/nginx/Twitch) send a second control message on the same csid as a
// fmt1 chunk. The reader must not desync on it and must still surface the later _result.
TEST(RtmpReader, HandlesFmt1OnSameCsid) {
    RtmpReader r;
    // fmt0 Window Ack Size (csid 2, type 5, len 4)
    r.Feed(ChunkEncode(2, 0x05, 0, 0, {0x00,0x26,0x25,0xa0}, 128));
    // fmt1 Set Peer Bandwidth (csid 2, type 6, len 5) -- hand-built fmt1 chunk
    r.Feed(Bytes{0x42, 0x00,0x00,0x00, 0x00,0x00,0x05, 0x06, 0x00,0x26,0x25,0xa0,0x02});
    // fmt0 _result command (csid 3, type 0x14)
    { Bytes body; Amf0::String(body,"_result"); Amf0::Number(body,1); Amf0::Null(body);
      r.Feed(ChunkEncode(3, 0x14, 0, 0, body, 128)); }
    uint8_t type; Bytes payload;
    ASSERT_TRUE(r.Next(type, payload)); EXPECT_EQ(type, 0x05);
    ASSERT_TRUE(r.Next(type, payload)); EXPECT_EQ(type, 0x06);
    ASSERT_TRUE(r.Next(type, payload)); EXPECT_EQ(type, 0x14);   // <- desync would lose this
    EXPECT_FALSE(r.Next(type, payload));
}
// A message larger than the inbound chunk size arrives split across chunks (fmt0 + fmt3);
// the reader must reassemble the full payload.
TEST(RtmpReader, ReassemblesMultiChunkMessage) {
    RtmpReader r;
    Bytes big(200); for (size_t i = 0; i < big.size(); ++i) big[i] = (uint8_t)(i & 0xFF); // ramp
    r.Feed(ChunkEncode(4, 0x14, 0, 0, big, 128));   // 128 + 72 split into fmt0 + fmt3
    uint8_t type; Bytes payload;
    ASSERT_TRUE(r.Next(type, payload));
    EXPECT_EQ(type, 0x14);
    ASSERT_EQ(payload.size(), 200u);
    EXPECT_EQ(payload, big);   // exact: a leaked continuation-header byte would corrupt the middle
}
// Server raises its outbound chunk size via Set Chunk Size; the reader must apply it to
// subsequent inbound messages.
TEST(RtmpReader, AppliesInboundSetChunkSize) {
    RtmpReader r;
    // Set Chunk Size = 256 (csid 2, type 1)
    r.Feed(ChunkEncode(2, 0x01, 0, 0, {0x00,0x00,0x01,0x00}, 128));
    // a 200-byte message now fits in ONE chunk (<=256), so it must parse as a single chunk
    Bytes msg(200, 0xCD);
    r.Feed(ChunkEncode(5, 0x14, 0, 0, msg, 256));
    uint8_t type; Bytes payload;
    ASSERT_TRUE(r.Next(type, payload)); EXPECT_EQ(type, 0x01);   // set chunk size
    ASSERT_TRUE(r.Next(type, payload)); EXPECT_EQ(type, 0x14);
    EXPECT_EQ(payload.size(), 200u);
}

TEST(RtmpClient, BytesSentIncreasesOnPublish) {
    StubTransport t;
    StreamParams p; p.app="live"; p.tcUrl="rtmp://h/live"; p.streamKey="5"; p.host="h";
    RtmpClient c(t, p);
    c.Begin();
    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;
    c.OnBytes(s0s1s2);
    c.OnBytes(MakeResultSuccess());
    c.OnBytes(MakeCreateStreamResult(1));
    c.OnBytes(MakePublishStart());
    ASSERT_EQ(c.state(), RtmpState::Publishing);
    uint64_t before = c.bytesSent();
    EXPECT_GT(before, 0u);                      // handshake + commands already counted
    c.SendVideoConfig({0,0,0,1, 0x67,0x42,0x00,0x1e, 0,0,0,1, 0x68,0xce,0x3c,0x80});
    c.SendVideo({0,0,0,1, 0x65, 0x88}, true, 0, 0);
    EXPECT_GT(c.bytesSent(), before);
}
