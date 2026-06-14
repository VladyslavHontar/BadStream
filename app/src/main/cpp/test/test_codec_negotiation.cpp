#include <gtest/gtest.h>
#include <string>
#include "rtmp_client.h"
#include "stub_transport.h"
#include "rtmp_chunk.h"
#include "amf0.h"
using namespace ps;

TEST(Connect, AdvertisesHevcFourCc) {
    StreamParams p; p.app = "app"; p.tcUrl = "rtmp://h/app";
    Bytes cmd = BuildConnectCommand(p, 1, Codec::Hevc);
    std::string s(cmd.begin(), cmd.end());
    EXPECT_NE(s.find("hvc1"), std::string::npos);
    EXPECT_NE(s.find("fourCcList"), std::string::npos);
}

TEST(Connect, AvcRequestOmitsFourCc) {
    StreamParams p; p.app = "app"; p.tcUrl = "rtmp://h/app";
    Bytes cmd = BuildConnectCommand(p, 1, Codec::Avc);
    std::string s(cmd.begin(), cmd.end());
    EXPECT_EQ(s.find("hvc1"), std::string::npos);
}

static Bytes MakeCommand(const Bytes& body) { return ChunkEncode(3, 0x14, 0, 0, body, 128); }

// connect _result information object, optionally advertising a HEVC fourCcList.
static Bytes MakeConnectResult(bool serverHevc) {
    Bytes b; Amf0::String(b,"_result"); Amf0::Number(b,1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b,"level"); Amf0::String(b,"status");
    Amf0::Key(b,"code");  Amf0::String(b,"NetConnection.Connect.Success");
    if (serverHevc) {
        Amf0::Key(b,"fourCcList");
        Amf0::StrictArrayBegin(b, 2);
        Amf0::String(b,"hvc1");
        Amf0::String(b,"avc1");
    }
    Amf0::ObjectEnd(b);
    return MakeCommand(b);
}

// Drives the client through the handshake to ConnectSent, then feeds the connect _result.
static void DriveToConnectResult(RtmpClient& c, bool serverHevc) {
    c.Begin();
    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;
    c.OnBytes(s0s1s2);                       // -> ConnectSent (sends C2 + connect)
    c.OnBytes(MakeConnectResult(serverHevc));
}

TEST(Connect, NegotiatesHevcWhenServerAdvertises) {
    StubTransport t;
    StreamParams p; p.app="app"; p.tcUrl="rtmp://h/app"; p.streamKey="5";
    RtmpClient c(t, p);
    c.RequestCodec(Codec::Hevc);             // before Begin()
    DriveToConnectResult(c, /*serverHevc*/true);
    EXPECT_EQ(c.negotiatedCodec(), Codec::Hevc);
}

TEST(Connect, FallsBackToAvcWhenServerLacksHevc) {
    StubTransport t;
    StreamParams p; p.app="app"; p.tcUrl="rtmp://h/app"; p.streamKey="5";
    RtmpClient c(t, p);
    c.RequestCodec(Codec::Hevc);             // before Begin()
    DriveToConnectResult(c, /*serverHevc*/false);
    EXPECT_EQ(c.negotiatedCodec(), Codec::Avc);
}
