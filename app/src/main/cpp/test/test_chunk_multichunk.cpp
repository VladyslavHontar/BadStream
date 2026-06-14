#include "rtmp_client.h"
#include "stub_transport.h"
#include "test_helpers.h"
#include "rtmp_chunk.h"
#include "flv.h"
#include "amf0.h"
using namespace ps;

// Walk RTMP chunk bytes for a SINGLE message that uses ONLY this csid (csid 5 video here).
// Rebuilds: the announced message length, msg type, timestamp, stream id, the number of
// chunks, and the reassembled payload (skipping the fmt0 12-byte header + each fmt3 1-byte
// continuation header). chunkSize is the outbound chunk size used by the encoder.
struct ParsedChunks {
    uint8_t  basic0 = 0;
    uint32_t timestamp = 0;
    uint32_t msgLen = 0;
    uint8_t  msgType = 0;
    uint32_t streamId = 0;
    int      numChunks = 0;
    std::vector<uint8_t> contBasics;  // each continuation chunk's basic header byte
    Bytes    payload;                 // reassembled payload
};

static ParsedChunks ParseSingleMessage(const Bytes& w, uint8_t csid, uint32_t chunkSize) {
    ParsedChunks r;
    size_t p = 0;
    // --- fmt0 header (12 bytes) ---
    r.basic0    = w[p];
    r.timestamp = (w[p+1] << 16) | (w[p+2] << 8) | w[p+3];
    r.msgLen    = (w[p+4] << 16) | (w[p+5] << 8) | w[p+6];
    r.msgType   = w[p+7];
    r.streamId  = w[p+8] | (w[p+9] << 8) | (w[p+10] << 16) | ((uint32_t)w[p+11] << 24);
    p += 12;
    r.numChunks = 1;
    // first chunk payload: up to chunkSize bytes
    uint32_t remaining = r.msgLen;
    uint32_t first = remaining < chunkSize ? remaining : chunkSize;
    r.payload.insert(r.payload.end(), w.begin() + p, w.begin() + p + first);
    p += first;
    remaining -= first;
    // --- continuation chunks (fmt3, 1-byte basic header each) ---
    while (remaining > 0) {
        r.contBasics.push_back(w[p]);   // expect 0xC0 | csid
        p += 1;
        uint32_t take = remaining < chunkSize ? remaining : chunkSize;
        r.payload.insert(r.payload.end(), w.begin() + p, w.begin() + p + take);
        p += take;
        remaining -= take;
        r.numChunks += 1;
    }
    (void)csid;
    return r;
}

// Phase 1a: ChunkEncode with a large (10000-byte) payload, chunkSize 4096.
TEST(ChunkMultiChunk, EncodeLargePayloadSplitsAndReassembles) {
    Bytes payload(10000);
    for (size_t i = 0; i < payload.size(); ++i) payload[i] = (uint8_t)((i * 31 + 7) & 0xFF);

    Bytes w = ChunkEncode(/*csid*/5, /*type*/0x09, /*msid*/1, /*ts*/1000, payload, /*chunkSize*/4096);

    ParsedChunks r = ParseSingleMessage(w, /*csid*/5, /*chunkSize*/4096);

    // fmt0 header fields
    EXPECT_EQ(r.basic0, 0x05) << "first basic header should be fmt0 (0b00) | csid 5";
    EXPECT_EQ(r.timestamp, 1000u);
    EXPECT_EQ(r.msgLen, 10000u);
    EXPECT_EQ(r.msgType, 0x09);
    EXPECT_EQ(r.streamId, 1u);

    // ceil(10000/4096) == 3 chunks
    EXPECT_EQ(r.numChunks, 3);

    // every continuation chunk must be fmt3 (0b11) | csid 5 == 0xC5
    for (size_t i = 0; i < r.contBasics.size(); ++i)
        EXPECT_EQ(r.contBasics[i], 0xC5) << "continuation chunk " << i << " basic header wrong";

    // exact reassembly
    ASSERT_EQ(r.payload.size(), payload.size());
    EXPECT_EQ(r.payload, payload) << "reassembled payload differs from input";

    // total encoded size sanity: 12 (fmt0) + 10000 (payload) + 2 (two fmt3 bytes)
    EXPECT_EQ(w.size(), 12u + 10000u + 2u);
}

// Drives the client through handshake+connect+createStream+publish to Publishing (streamId=1).
// Copied from test_rtmp_client.cpp and renamed to avoid ODR clashes.
static Bytes MC_MakeCommand(const Bytes& body) { return ChunkEncode(3, 0x14, 0, 0, body, 128); }
static Bytes MC_MakeResultSuccess() {
    Bytes b; Amf0::String(b,"_result"); Amf0::Number(b,1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b,"level"); Amf0::String(b,"status");
    Amf0::Key(b,"code");  Amf0::String(b,"NetConnection.Connect.Success");
    Amf0::ObjectEnd(b);
    return MC_MakeCommand(b);
}
static Bytes MC_MakeCreateStreamResult(int streamId) {
    Bytes b; Amf0::String(b,"_result"); Amf0::Number(b,4); Amf0::Null(b); Amf0::Number(b, streamId);
    return MC_MakeCommand(b);
}
static Bytes MC_MakePublishStart() {
    Bytes b; Amf0::String(b,"onStatus"); Amf0::Number(b,0); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b,"level"); Amf0::String(b,"status");
    Amf0::Key(b,"code");  Amf0::String(b,"NetStream.Publish.Start");
    Amf0::ObjectEnd(b);
    return MC_MakeCommand(b);
}
static void ForcePublishing2(RtmpClient& c) {
    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;
    c.Begin();
    c.OnBytes(s0s1s2);
    c.OnBytes(MC_MakeResultSuccess());
    c.OnBytes(MC_MakeCreateStreamResult(1));
    c.OnBytes(MC_MakePublishStart());
}

// Build a large Annex-B frame: one big NAL (so the FLV body spans 3+ chunks at 4096).
static Bytes MakeBigAnnexB(size_t nalDataLen) {
    Bytes a;
    a.push_back(0); a.push_back(0); a.push_back(0); a.push_back(1);  // 4-byte start code
    a.push_back(0x65);                                              // NAL header: IDR slice (type 5)
    for (size_t i = 0; i < nalDataLen; ++i) {
        uint8_t v = (uint8_t)((i * 17 + 3) & 0xFF);
        if (v == 0) v = 0x11;  // avoid accidental 00 00 01 start-code patterns in payload
        a.push_back(v);
    }
    return a;
}

// Phase 1b: full path -- SendVideo with a LARGE frame, parse on-the-wire bytes, confirm
// reassembly == FlvVideoFrame(AnnexBToAvcc(annexb), true, 0).
TEST(ChunkMultiChunk, SendVideoLargeFrameReassembles) {
    StubTransport t; StreamParams p; p.streamKey = "5";
    RtmpClient c(t, p); ForcePublishing2(c);
    ASSERT_EQ(c.state(), RtmpState::Publishing);
    t.clear();

    Bytes annexb = MakeBigAnnexB(9000);   // FLV body ~= 5 + 4 + 9001 > 8192 -> 3 chunks
    c.SendVideo(annexb, /*keyframe*/true, /*ptsMs*/0, /*dtsMs*/0);

    Bytes expectedBody = FlvVideoFrame(AnnexBToAvcc(annexb), true, 0);
    ASSERT_GT(expectedBody.size(), 8192u) << "test frame too small to span 3 chunks";

    const Bytes& w = t.written();
    ASSERT_GE(w.size(), 12u);

    ParsedChunks r = ParseSingleMessage(w, /*csid*/5, /*chunkSize*/4096);

    EXPECT_EQ(r.basic0, 0x05);
    EXPECT_EQ(r.msgType, 0x09);
    EXPECT_EQ(r.streamId, 1u);
    EXPECT_EQ(r.msgLen, (uint32_t)expectedBody.size());
    for (size_t i = 0; i < r.contBasics.size(); ++i)
        EXPECT_EQ(r.contBasics[i], 0xC5) << "continuation chunk " << i << " basic header wrong";

    ASSERT_EQ(r.payload.size(), expectedBody.size());
    EXPECT_EQ(r.payload, expectedBody) << "on-the-wire video body does not reassemble to FlvVideoFrame";
}

// Extended-timestamp interop: per the RTMP spec, when a message's timestamp >= 0x00FFFFFF
// the fmt0 chunk writes 0xFFFFFF in the 3-byte timestamp field plus a 4-byte extended
// timestamp -- AND every fmt3 continuation chunk of that message must ALSO repeat the 4-byte
// extended timestamp right after its 1-byte basic header. ffmpeg's demuxer does exactly that
// re-read. RRtmpReader (this codebase's own spec-correct reader) re-reads it too, so it is a
// faithful oracle. This test feeds ChunkEncode's output back through RtmpReader; if the
// encoder omits the ext-ts on continuation chunks, the reader consumes 4 payload bytes as a
// phantom timestamp and the message fails to reassemble -> chunk-stream desync on the wire.
TEST(ChunkMultiChunk, ExtendedTimestampOnContinuationChunks) {
    Bytes payload(10000);
    for (size_t i = 0; i < payload.size(); ++i) payload[i] = (uint8_t)((i * 31 + 7) & 0xFF);
    uint32_t bigTs = 0x01000000;  // >= 0xFFFFFF -> forces the extended-timestamp path

    Bytes w = ChunkEncode(/*csid*/5, /*type*/0x09, /*msid*/1, /*ts*/bigTs, payload, /*chunkSize*/4096);

    RtmpReader rd;
    // Tell the reader the inbound chunk size is 4096 (matches the encoder), the same way a
    // peer announces it via Set Chunk Size; otherwise the reader splits at the 128 default.
    rd.Feed(ChunkEncode(2, 0x01, 0, 0, {0x00,0x00,0x10,0x00}, 128));  // Set Chunk Size = 4096
    uint8_t t0; Bytes p0; ASSERT_TRUE(rd.Next(t0, p0)); ASSERT_EQ(t0, 0x01);
    rd.Feed(w);
    uint8_t type; Bytes got;
    ASSERT_TRUE(rd.Next(type, got)) << "spec-correct reader could not even complete the message";
    EXPECT_EQ(type, 0x09);
    EXPECT_EQ(got.size(), payload.size())
        << "ext-ts missing on fmt3 continuation chunks: reader consumed phantom ts bytes";
    EXPECT_EQ(got, payload)
        << "message corrupted across chunk boundary when timestamp >= 0xFFFFFF";
}

// Phase 1 (config path): feed a realistic SPS/PPS through SendVideoConfig and verify the
// avcC produced by BuildAvcC / FlvVideoSeqHeader is well-formed.
TEST(ChunkMultiChunk, VideoConfigAvcCWellFormed) {
    // A typical 1080p high-profile SPS (profile 0x64=High, level 0x28=40) and a small PPS.
    Bytes sps = {0x67,0x64,0x00,0x28,0xAC,0xD9,0x40,0x78,0x02,0x27,0xE5,0x84,0x00,0x00,0x03,
                 0x00,0x04,0x00,0x00,0x03,0x00,0xF0,0x3C,0x60,0xC9,0x20};
    Bytes pps = {0x68,0xEB,0xE3,0xCB,0x22,0xC0};

    Bytes avcc = BuildAvcC(sps, pps);
    // configurationVersion, profile, compat, level, lengthSizeMinusOne, numSPS
    ASSERT_GE(avcc.size(), 6u + 2u + sps.size() + 1u + 2u + pps.size());
    EXPECT_EQ(avcc[0], 0x01);          // configurationVersion
    EXPECT_EQ(avcc[1], sps[1]);        // AVCProfileIndication = 0x64
    EXPECT_EQ(avcc[2], sps[2]);        // profile_compatibility
    EXPECT_EQ(avcc[3], sps[3]);        // AVCLevelIndication = 0x28
    EXPECT_EQ(avcc[4], 0xFF);          // lengthSizeMinusOne = 3
    EXPECT_EQ(avcc[5], 0xE1);          // numOfSequenceParameterSets = 1
    size_t i = 6;
    uint16_t spsLen = (avcc[i] << 8) | avcc[i+1]; i += 2;
    EXPECT_EQ(spsLen, sps.size());
    EXPECT_EQ(Bytes(avcc.begin()+i, avcc.begin()+i+spsLen), sps); i += spsLen;
    EXPECT_EQ(avcc[i], 0x01); i += 1;  // numOfPictureParameterSets = 1
    uint16_t ppsLen = (avcc[i] << 8) | avcc[i+1]; i += 2;
    EXPECT_EQ(ppsLen, pps.size());
    EXPECT_EQ(Bytes(avcc.begin()+i, avcc.begin()+i+ppsLen), pps); i += ppsLen;
    EXPECT_EQ(i, avcc.size()) << "avcC has trailing/missing bytes";

    // FLV seq header wraps it: 0x17 0x00 00 00 00 + avcc
    Bytes seq = FlvVideoSeqHeader(avcc);
    EXPECT_EQ(seq[0], 0x17);
    EXPECT_EQ(seq[1], 0x00);
    EXPECT_EQ(seq[2], 0x00); EXPECT_EQ(seq[3], 0x00); EXPECT_EQ(seq[4], 0x00);
    EXPECT_EQ(Bytes(seq.begin()+5, seq.end()), avcc);

    // and on the wire SendVideoConfig frames it on csid 5 / type 0x09
    StubTransport t; StreamParams p; p.streamKey = "5";
    RtmpClient c(t, p); ForcePublishing2(c);
    t.clear();
    c.SendVideoConfig(sps, pps);
    ParsedChunks r = ParseSingleMessage(t.written(), 5, 4096);
    EXPECT_EQ(r.basic0, 0x05);
    EXPECT_EQ(r.msgType, 0x09);
    EXPECT_EQ(r.payload, seq) << "on-the-wire video config body mismatch";
}
