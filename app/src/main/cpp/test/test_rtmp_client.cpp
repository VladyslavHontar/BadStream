#include "rtmp_client.h"
#include "stub_transport.h"
#include "test_helpers.h"
#include "rtmp_chunk.h"
#include "amf0.h"
using namespace ps;
TEST(RtmpClient, ConnectCommandObject) {
    StreamParams p; p.app = "live"; p.tcUrl = "rtmp://h/live"; p.streamKey = "key";
    Bytes payload = BuildConnectCommand(p, /*txn*/1);
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
