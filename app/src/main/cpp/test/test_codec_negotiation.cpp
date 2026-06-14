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

// v1: fourCcList as strict array of strings, optionally including "hvc1".
static Bytes MakeConnectResultV1(bool hevc) {
    Bytes b;
    Amf0::String(b, "_result"); Amf0::Number(b, 1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b, "level"); Amf0::String(b, "status");
    Amf0::Key(b, "code");  Amf0::String(b, "NetConnection.Connect.Success");
    Amf0::Key(b, "fourCcList");
    if (hevc) {
        Amf0::StrictArrayBegin(b, 2);
        Amf0::String(b, "hvc1");
        Amf0::String(b, "avc1");
    } else {
        Amf0::StrictArrayBegin(b, 1);
        Amf0::String(b, "avc1");
    }
    Amf0::ObjectEnd(b);
    return MakeCommand(b);
}

// v2: videoFourCcInfoMap as ECMA-array keyed by FourCC (value = empty object).
static Bytes MakeConnectResultV2(bool hevc) {
    Bytes b;
    Amf0::String(b, "_result"); Amf0::Number(b, 1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b, "level"); Amf0::String(b, "status");
    Amf0::Key(b, "code");  Amf0::String(b, "NetConnection.Connect.Success");
    Amf0::Key(b, "videoFourCcInfoMap");
    uint32_t count = hevc ? 2 : 1;
    Amf0::EcmaArrayBegin(b, count);
    if (hevc) {
        Amf0::Key(b, "hvc1"); Amf0::ObjectBegin(b); Amf0::ObjectEnd(b);
    }
    Amf0::Key(b, "avc1"); Amf0::ObjectBegin(b); Amf0::ObjectEnd(b);
    Amf0::ObjectEnd(b);  // videoFourCcInfoMap end
    Amf0::ObjectEnd(b);  // outer object end
    return MakeCommand(b);
}

// Legacy: no enhanced codec advertisement field.
static Bytes MakeConnectResultLegacy() {
    Bytes b;
    Amf0::String(b, "_result"); Amf0::Number(b, 1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b, "level"); Amf0::String(b, "status");
    Amf0::Key(b, "code");  Amf0::String(b, "NetConnection.Connect.Success");
    Amf0::ObjectEnd(b);
    return MakeCommand(b);
}

// False-positive guard: "hvc1" appears as a substring in a string field (code or description)
// but NOT in fourCcList. The old substring scan would return true (bug); real AMF0 parse
// must return false.
static Bytes MakeConnectResultFalsePositive() {
    Bytes b;
    Amf0::String(b, "_result"); Amf0::Number(b, 1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b, "level"); Amf0::String(b, "status");
    // "hvc1" appears as a substring of the description string — NOT in the codec list.
    Amf0::Key(b, "description"); Amf0::String(b, "legacy-server-no-hvc1-support");
    // The fourCcList only contains "avc1".
    Amf0::Key(b, "fourCcList");
    Amf0::StrictArrayBegin(b, 1);
    Amf0::String(b, "avc1");
    Amf0::ObjectEnd(b);
    return MakeCommand(b);
}

// Drives the client through the handshake to ConnectSent, then feeds the connect _result.
static void DriveToConnectResult(RtmpClient& c, const Bytes& resultChunk) {
    c.Begin();
    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;
    c.OnBytes(s0s1s2);           // -> ConnectSent (sends C2 + connect)
    c.OnBytes(resultChunk);
}

// --- VideoCodecAdvertised unit tests (direct, without driving client) ---

// Build a bare AMF0 payload (no chunk framing) for VideoCodecAdvertised unit tests.
static Bytes MakeResultPayloadV1(bool hevc) {
    Bytes b;
    Amf0::String(b, "_result"); Amf0::Number(b, 1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b, "level"); Amf0::String(b, "status");
    Amf0::Key(b, "fourCcList");
    if (hevc) {
        Amf0::StrictArrayBegin(b, 2);
        Amf0::String(b, "hvc1");
        Amf0::String(b, "avc1");
    } else {
        Amf0::StrictArrayBegin(b, 1);
        Amf0::String(b, "avc1");
    }
    Amf0::ObjectEnd(b);
    return b;
}

static Bytes MakeResultPayloadV2(bool hevc) {
    Bytes b;
    Amf0::String(b, "_result"); Amf0::Number(b, 1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b, "level"); Amf0::String(b, "status");
    Amf0::Key(b, "videoFourCcInfoMap");
    uint32_t count = hevc ? 2 : 1;
    Amf0::EcmaArrayBegin(b, count);
    if (hevc) {
        Amf0::Key(b, "hvc1"); Amf0::ObjectBegin(b); Amf0::ObjectEnd(b);
    }
    Amf0::Key(b, "avc1"); Amf0::ObjectBegin(b); Amf0::ObjectEnd(b);
    Amf0::ObjectEnd(b);  // videoFourCcInfoMap end
    Amf0::ObjectEnd(b);
    return b;
}

static Bytes MakeResultPayloadLegacy() {
    Bytes b;
    Amf0::String(b, "_result"); Amf0::Number(b, 1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b, "level"); Amf0::String(b, "status");
    Amf0::Key(b, "code");  Amf0::String(b, "NetConnection.Connect.Success");
    Amf0::ObjectEnd(b);
    return b;
}

static Bytes MakeResultPayloadFalsePositive() {
    Bytes b;
    Amf0::String(b, "_result"); Amf0::Number(b, 1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b, "description"); Amf0::String(b, "legacy-server-no-hvc1-support");
    Amf0::Key(b, "fourCcList");
    Amf0::StrictArrayBegin(b, 1);
    Amf0::String(b, "avc1");
    Amf0::ObjectEnd(b);
    return b;
}

TEST(VideoCodecAdvertised, V1TrueForHvc1) {
    EXPECT_TRUE(VideoCodecAdvertised(MakeResultPayloadV1(true), "hvc1"));
    EXPECT_FALSE(VideoCodecAdvertised(MakeResultPayloadV1(false), "hvc1"));
}

TEST(VideoCodecAdvertised, V2TrueForHvc1) {
    EXPECT_TRUE(VideoCodecAdvertised(MakeResultPayloadV2(true), "hvc1"));
    EXPECT_FALSE(VideoCodecAdvertised(MakeResultPayloadV2(false), "hvc1"));
}

TEST(VideoCodecAdvertised, LegacyFalse) {
    EXPECT_FALSE(VideoCodecAdvertised(MakeResultPayloadLegacy(), "hvc1"));
}

// This test verifies the false-positive guard: "hvc1" substring in a string field must NOT
// cause VideoCodecAdvertised to return true. The old substring scan FAILED this test.
TEST(VideoCodecAdvertised, FalsePositiveGuard) {
    Bytes payload = MakeResultPayloadFalsePositive();
    // Verify "hvc1" IS present as a raw substring (confirming old code would have passed).
    std::string raw(payload.begin(), payload.end());
    EXPECT_NE(raw.find("hvc1"), std::string::npos) << "precondition: hvc1 must be a substring";
    // Real AMF0 parse must correctly return false.
    EXPECT_FALSE(VideoCodecAdvertised(payload, "hvc1"));
}

// --- Integration tests: drive RtmpClient ---

TEST(Connect, NegotiatesHevcWhenServerAdvertisesV1) {
    StubTransport t;
    StreamParams p; p.app="app"; p.tcUrl="rtmp://h/app"; p.streamKey="5";
    RtmpClient c(t, p);
    c.RequestCodec(Codec::Hevc);
    DriveToConnectResult(c, MakeConnectResultV1(true));
    EXPECT_EQ(c.negotiatedCodec(), Codec::Hevc);
}

TEST(Connect, NegotiatesHevcWhenServerAdvertisesV2) {
    StubTransport t;
    StreamParams p; p.app="app"; p.tcUrl="rtmp://h/app"; p.streamKey="5";
    RtmpClient c(t, p);
    c.RequestCodec(Codec::Hevc);
    DriveToConnectResult(c, MakeConnectResultV2(true));
    EXPECT_EQ(c.negotiatedCodec(), Codec::Hevc);
}

TEST(Connect, FallsBackToAvcLegacyServer) {
    StubTransport t;
    StreamParams p; p.app="app"; p.tcUrl="rtmp://h/app"; p.streamKey="5";
    RtmpClient c(t, p);
    c.RequestCodec(Codec::Hevc);
    DriveToConnectResult(c, MakeConnectResultLegacy());
    EXPECT_EQ(c.negotiatedCodec(), Codec::Avc);
}

TEST(Connect, FallsBackToAvcWhenServerLacksHevc) {
    StubTransport t;
    StreamParams p; p.app="app"; p.tcUrl="rtmp://h/app"; p.streamKey="5";
    RtmpClient c(t, p);
    c.RequestCodec(Codec::Hevc);
    DriveToConnectResult(c, MakeConnectResultV1(false));
    EXPECT_EQ(c.negotiatedCodec(), Codec::Avc);
}

// False-positive guard integration test: client must NOT negotiate HEVC when "hvc1" only
// appears in a description string field, not in fourCcList. This would have PASSED the old
// substring scan (wrong), and must FAIL against the old code and PASS against the new parser.
TEST(Connect, FalsePositiveGuard_SubstringInStringFieldNotCodecList) {
    StubTransport t;
    StreamParams p; p.app="app"; p.tcUrl="rtmp://h/app"; p.streamKey="5";
    RtmpClient c(t, p);
    c.RequestCodec(Codec::Hevc);
    DriveToConnectResult(c, MakeConnectResultFalsePositive());
    EXPECT_EQ(c.negotiatedCodec(), Codec::Avc);
}

// Keep backward-compat: original NegotiatesHevcWhenServerAdvertises test (v1 path)
TEST(Connect, NegotiatesHevcWhenServerAdvertises) {
    StubTransport t;
    StreamParams p; p.app="app"; p.tcUrl="rtmp://h/app"; p.streamKey="5";
    RtmpClient c(t, p);
    c.RequestCodec(Codec::Hevc);
    DriveToConnectResult(c, MakeConnectResultV1(true));
    EXPECT_EQ(c.negotiatedCodec(), Codec::Hevc);
}
