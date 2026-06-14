#include <gtest/gtest.h>
#include "rtmp_client.h"
#include "rtmp_chunk.h"
#include "stub_transport.h"
using namespace ps;

// Drive a fresh RtmpClient past the handshake so OnBytes will process RTMP chunks.
// Returns with handshakeDone_ true and the connect command already sent.
static void DoHandshake(RtmpClient& c, StubTransport& t) {
    c.Begin();                                  // sends C0C1
    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;
    c.OnBytes(s0s1s2);                          // completes handshake -> sends C2 + connect
    t.clear();                                  // forget handshake bytes; assert only what follows
}

// A protocol-control message (csid 2) the server would send us.
static Bytes Control(uint8_t type, const Bytes& payload) {
    return ChunkEncode(2, type, 0, 0, payload, 128);
}
// Search haystack for the contiguous subsequence needle.
static bool Contains(const Bytes& hay, const Bytes& needle) {
    if (needle.empty() || hay.size() < needle.size()) return false;
    for (size_t i = 0; i + needle.size() <= hay.size(); ++i)
        if (std::equal(needle.begin(), needle.end(), hay.begin() + i)) return true;
    return false;
}
static const Bytes& c_written(StubTransport& t) { return t.written(); }

TEST(RtmpControl, WriteFailureFlagsNotOk) {
    StubTransport t; RtmpClient c(t, StreamParams{});
    EXPECT_TRUE(c.writeOk());
    t.SetWriteFails(true);
    c.Begin();                  // a Send() now fails
    EXPECT_FALSE(c.writeOk());
}

TEST(RtmpControl, PingRequestGetsPingResponse) {
    StubTransport t; RtmpClient c(t, StreamParams{});
    DoHandshake(c, t);
    // User Control: event 6 (PingRequest) + 4-byte timestamp 0x01020304
    Bytes p = {0x00, 0x06, 0x01, 0x02, 0x03, 0x04};
    c.OnBytes(Control(0x04, p));
    // Expect a PingResponse: event 7 + echoed timestamp, somewhere in the written bytes.
    EXPECT_TRUE(Contains(c_written(t), Bytes{0x00, 0x07, 0x01, 0x02, 0x03, 0x04}));
}

TEST(RtmpControl, WindowAckSizeIsEchoed) {
    StubTransport t; RtmpClient c(t, StreamParams{});
    DoHandshake(c, t);
    Bytes win = {0x00, 0x10, 0x00, 0x00};           // window = 0x00100000
    c.OnBytes(Control(0x05, win));                   // Window Acknowledgement Size
    // We reply with our own Window Ack Size message (type 0x05) carrying the same window.
    EXPECT_TRUE(Contains(c_written(t), win));
}

TEST(RtmpControl, SetPeerBandwidthRepliesWindowAckSize) {
    StubTransport t; RtmpClient c(t, StreamParams{});
    DoHandshake(c, t);
    Bytes pbw = {0x00, 0x20, 0x00, 0x00, 0x02};      // window=0x00200000, limitType=dynamic
    c.OnBytes(Control(0x06, pbw));
    EXPECT_TRUE(Contains(c_written(t), Bytes{0x00, 0x20, 0x00, 0x00}));  // our Window Ack Size
}

TEST(RtmpControl, EmitsAcknowledgementAfterWindowOfBytes) {
    StubTransport t; RtmpClient c(t, StreamParams{});
    DoHandshake(c, t);
    // Tell the client our window is small so an Ack triggers quickly.
    c.OnBytes(Control(0x05, Bytes{0x00, 0x00, 0x00, 0x40}));  // window = 64 bytes
    t.clear();
    // Feed >64 bytes of (any) inbound; the client must emit an Acknowledgement (type 0x03).
    Bytes filler(200, 0x00);
    c.OnBytes(filler);
    // An Acknowledgement message has msg type 0x03 in its chunk header. ChunkEncode(2,0x03,...)
    // emits basic header 0x02 then an 11-byte msg header whose 7th byte (type) is 0x03.
    const Bytes& w = c_written(t);
    bool sawAck = false;
    for (size_t i = 0; i + 12 <= w.size(); ++i)
        if (w[i] == 0x02 && w[i + 7] == 0x03) { sawAck = true; break; }
    EXPECT_TRUE(sawAck);
}

// AMF0 string body marker for a command name: 0x02 + u16 length + bytes.
static Bytes Amf0Str(const std::string& s) {
    Bytes b = {0x02, (uint8_t)(s.size() >> 8), (uint8_t)(s.size() & 0xFF)};
    b.insert(b.end(), s.begin(), s.end()); return b;
}

TEST(RtmpControl, SendUnpublishWritesFcUnpublishDeleteAndClose) {
    StubTransport t; RtmpClient c(t, StreamParams{ "h","app","streamkey","rtmp://h/app" });
    DoHandshake(c, t);
    c.SendUnpublish();
    const Bytes& w = c_written(t);
    EXPECT_TRUE(Contains(w, Amf0Str("FCUnpublish")));
    EXPECT_TRUE(Contains(w, Amf0Str("deleteStream")));
    EXPECT_TRUE(Contains(w, Amf0Str("closeStream")));
}
