#include "ts_muxer.h"
#include "test_helpers.h"
#include <map>
using namespace ps;

namespace {

// A parsed 188-byte TS packet (header fields + payload offset/length).
struct TsPacket {
    int pid = 0;
    bool pusi = false;            // payload_unit_start_indicator
    int cc = 0;                   // continuity_counter
    bool hasAdaptation = false;
    bool hasPayload = false;
    bool hasPcr = false;
    uint64_t pcrBase = 0;         // 33-bit PCR base (27MHz/300 == 90kHz units)
    size_t payloadOff = 0;        // offset of payload within the 188-byte packet
    size_t payloadLen = 0;
};

// Walk a TS byte stream into 188-byte packets, parsing the header + adaptation field.
std::vector<TsPacket> ParseTsPackets(const Bytes& d) {
    std::vector<TsPacket> out;
    for (size_t i = 0; i + 188 <= d.size(); i += 188) {
        const uint8_t* p = d.data() + i;
        EXPECT_EQ(p[0], 0x47) << "sync byte at packet " << (i / 188);
        TsPacket t;
        t.pusi = (p[1] & 0x40) != 0;
        t.pid = ((p[1] & 0x1F) << 8) | p[2];
        int afc = (p[3] >> 4) & 0x3;
        t.cc = p[3] & 0x0F;
        size_t off = 4;
        if (afc == 2 || afc == 3) {
            t.hasAdaptation = true;
            int afLen = p[4];
            if (afLen > 0) {
                uint8_t flags = p[5];
                if (flags & 0x10) {  // PCR_flag
                    t.hasPcr = true;
                    const uint8_t* pcr = p + 6;
                    uint64_t base = ((uint64_t)pcr[0] << 25) | ((uint64_t)pcr[1] << 17) |
                                    ((uint64_t)pcr[2] << 9) | ((uint64_t)pcr[3] << 1) |
                                    ((pcr[4] >> 7) & 1);
                    t.pcrBase = base;
                }
            }
            off = 5 + afLen;
        }
        if (afc == 1 || afc == 3) {
            t.hasPayload = true;
            t.payloadOff = off;
            t.payloadLen = 188 - off;
        }
        out.push_back(t);
    }
    return out;
}

std::vector<TsPacket> PacketsOnPid(const std::vector<TsPacket>& pkts, int pid) {
    std::vector<TsPacket> r;
    for (auto& t : pkts) if (t.pid == pid) r.push_back(t);
    return r;
}

// Reassemble the payload bytes of all packets on a PID (in order).
Bytes AssemblePid(const Bytes& d, const std::vector<TsPacket>& pkts, int pid) {
    Bytes out;
    for (size_t i = 0, pk = 0; i + 188 <= d.size(); i += 188, ++pk) {
        const TsPacket& t = pkts[pk];
        if (t.pid != pid || !t.hasPayload) continue;
        out.insert(out.end(), d.begin() + i + t.payloadOff, d.begin() + i + 188);
    }
    return out;
}

// Minimal Annex-B AVC access unit: SPS + PPS + IDR slice (start-code prefixed).
Bytes AvcKeyframe() {
    return {0,0,0,1, 0x67,0x42,0xC0,0x1F,0xAA,     // SPS
            0,0,0,1, 0x68,0xCE,0x3C,0x80,           // PPS
            0,0,0,1, 0x65,0x88,0x84,0x00,0x33};     // IDR slice
}
Bytes AvcInterframe() {
    return {0,0,0,1, 0x41,0x9A,0x00,0x11};          // non-IDR slice
}

// Discover PMT PID + the video/audio elementary PIDs + stream types from a muxed stream.
struct ProgramInfo {
    int pmtPid = 0, pcrPid = 0;
    int videoPid = -1, videoType = -1;
    int audioPid = -1, audioType = -1;
};
ProgramInfo DiscoverProgram(const Bytes& out, const std::vector<TsPacket>& pkts) {
    ProgramInfo pi;
    Bytes pat = AssemblePid(out, pkts, 0x0000);
    size_t pp = 1 + pat[0]; size_t ploop = pp + 8;
    pi.pmtPid = ((pat[ploop+2] & 0x1F) << 8) | pat[ploop+3];
    Bytes pmt = AssemblePid(out, pkts, pi.pmtPid);
    size_t q = 1 + pmt[0];
    int sectionLen = ((pmt[q+1] & 0x0F) << 8) | pmt[q+2];
    pi.pcrPid = ((pmt[q+8] & 0x1F) << 8) | pmt[q+9];
    int progInfoLen = ((pmt[q+10] & 0x0F) << 8) | pmt[q+11];
    size_t es = q + 12 + progInfoLen;
    size_t sectionEnd = q + 3 + sectionLen - 4;   // up to start of CRC
    while (es + 5 <= sectionEnd) {
        int st = pmt[es];
        int pid = ((pmt[es+1] & 0x1F) << 8) | pmt[es+2];
        int esInfo = ((pmt[es+3] & 0x0F) << 8) | pmt[es+4];
        if (st == 0x1B || st == 0x24) { pi.videoPid = pid; pi.videoType = st; }
        else if (st == 0x0F) { pi.audioPid = pid; pi.audioType = st; }
        es += 5 + esInfo;
    }
    return pi;
}

}  // namespace

TEST(TsMuxer, OutputIsPacketAligned) {
    TsMuxer m;
    m.SetVideo(VideoCodecKind::Avc);
    Bytes out = m.WriteVideo(AvcKeyframe(), /*key*/true, /*pts*/0, /*dts*/0);
    ASSERT_GT(out.size(), 0u);
    EXPECT_EQ(out.size() % 188, 0u);
    for (size_t i = 0; i + 188 <= out.size(); i += 188)
        EXPECT_EQ(out[i], 0x47) << "missing sync at packet " << (i / 188);
}

TEST(TsMuxer, PatDeclaresProgramAndPmtPid) {
    TsMuxer m;
    m.SetVideo(VideoCodecKind::Avc);
    Bytes out = m.WriteVideo(AvcKeyframe(), true, 0, 0);
    auto pkts = ParseTsPackets(out);
    auto pat = PacketsOnPid(pkts, 0x0000);
    ASSERT_FALSE(pat.empty()) << "no PAT packet";
    EXPECT_TRUE(pat[0].pusi);
    // PAT payload: pointer_field(1) + table. skip pointer.
    Bytes payload = AssemblePid(out, pkts, 0x0000);
    ASSERT_GE(payload.size(), 1u);
    size_t p = 1 + payload[0];          // pointer_field
    ASSERT_LT(p, payload.size());
    EXPECT_EQ(payload[p], 0x00);        // table_id PAT
    int sectionLen = ((payload[p+1] & 0x0F) << 8) | payload[p+2];
    // program loop starts at p+8 (after table header + transport_stream_id+version+sect nums)
    size_t loop = p + 8;
    int programNumber = (payload[loop] << 8) | payload[loop+1];
    int pmtPid = ((payload[loop+2] & 0x1F) << 8) | payload[loop+3];
    EXPECT_EQ(programNumber, 1);
    EXPECT_GT(pmtPid, 0);
    (void)sectionLen;
}

TEST(TsMuxer, PmtListsAvcVideoAndPcrPid) {
    TsMuxer m;
    m.SetVideo(VideoCodecKind::Avc);
    Bytes out = m.WriteVideo(AvcKeyframe(), true, 0, 0);
    auto pkts = ParseTsPackets(out);
    // discover PMT PID from PAT
    Bytes pat = AssemblePid(out, pkts, 0x0000);
    size_t pp = 1 + pat[0];
    size_t ploop = pp + 8;
    int pmtPid = ((pat[ploop+2] & 0x1F) << 8) | pat[ploop+3];
    Bytes pmt = AssemblePid(out, pkts, pmtPid);
    ASSERT_GE(pmt.size(), 1u);
    size_t q = 1 + pmt[0];               // pointer field
    EXPECT_EQ(pmt[q], 0x02);             // table_id PMT
    int sectionLen = ((pmt[q+1] & 0x0F) << 8) | pmt[q+2];
    int pcrPid = ((pmt[q+8] & 0x1F) << 8) | pmt[q+9];
    int progInfoLen = ((pmt[q+10] & 0x0F) << 8) | pmt[q+11];
    // ES loop starts after program_info
    size_t es = q + 12 + progInfoLen;
    int streamType = pmt[es];
    int elemPid = ((pmt[es+1] & 0x1F) << 8) | pmt[es+2];
    EXPECT_EQ(streamType, 0x1B);         // AVC
    EXPECT_EQ(pcrPid, elemPid);          // PCR on the video PID
    (void)sectionLen;
}

TEST(TsMuxer, VideoPesHasStartCodeStreamIdAndPts) {
    TsMuxer m;
    m.SetVideo(VideoCodecKind::Avc);
    uint32_t ptsMs = 1000;
    Bytes out = m.WriteVideo(AvcKeyframe(), true, ptsMs, ptsMs);
    auto pkts = ParseTsPackets(out);
    Bytes pat = AssemblePid(out, pkts, 0x0000);
    size_t pp = 1 + pat[0]; size_t ploop = pp + 8;
    int pmtPid = ((pat[ploop+2] & 0x1F) << 8) | pat[ploop+3];
    Bytes pmt = AssemblePid(out, pkts, pmtPid);
    size_t q = 1 + pmt[0];
    int progInfoLen = ((pmt[q+10] & 0x0F) << 8) | pmt[q+11];
    size_t es = q + 12 + progInfoLen;
    int videoPid = ((pmt[es+1] & 0x1F) << 8) | pmt[es+2];

    auto vpkts = PacketsOnPid(pkts, videoPid);
    ASSERT_FALSE(vpkts.empty());
    EXPECT_TRUE(vpkts[0].pusi) << "first video packet must start a PES";
    Bytes pes = AssemblePid(out, pkts, videoPid);
    ASSERT_GE(pes.size(), 14u);
    EXPECT_EQ(pes[0], 0x00); EXPECT_EQ(pes[1], 0x00); EXPECT_EQ(pes[2], 0x01);  // start code
    EXPECT_EQ(pes[3], 0xE0);              // video stream_id
    // PES header flags
    EXPECT_EQ(pes[6] & 0xC0, 0x80);      // marker bits '10'
    int ptsDtsFlags = (pes[7] >> 6) & 0x3;
    EXPECT_NE(ptsDtsFlags, 0) << "PTS must be present";
    // decode 5-byte PTS at pes[9..13]
    size_t o = 9;
    uint64_t pts = (((uint64_t)(pes[o] >> 1) & 0x07) << 30) |
                   ((uint64_t)pes[o+1] << 22) |
                   (((uint64_t)pes[o+2] >> 1) & 0x7F) << 15 |
                   ((uint64_t)pes[o+3] << 7) |
                   ((uint64_t)pes[o+4] >> 1);
    EXPECT_EQ(pts, (uint64_t)ptsMs * 90);
    EXPECT_EQ(pes[o] & 0xF0, 0x20);      // '0010' marker (PTS only) or '0011' (PTS+DTS)
}

TEST(TsMuxer, VideoContinuityCounterIncrements) {
    TsMuxer m;
    m.SetVideo(VideoCodecKind::Avc);
    Bytes out;
    auto k = m.WriteVideo(AvcKeyframe(), true, 0, 0);
    out.insert(out.end(), k.begin(), k.end());
    // a few more frames to span multiple TS packets
    for (uint32_t t = 33; t <= 33 * 5; t += 33) {
        auto f = m.WriteVideo(AvcInterframe(), false, t, t);
        out.insert(out.end(), f.begin(), f.end());
    }
    auto pkts = ParseTsPackets(out);
    Bytes pat = AssemblePid(out, pkts, 0x0000);
    size_t pp = 1 + pat[0]; size_t ploop = pp + 8;
    int pmtPid = ((pat[ploop+2] & 0x1F) << 8) | pat[ploop+3];
    Bytes pmt = AssemblePid(out, pkts, pmtPid);
    size_t q = 1 + pmt[0];
    int progInfoLen = ((pmt[q+10] & 0x0F) << 8) | pmt[q+11];
    size_t es = q + 12 + progInfoLen;
    int videoPid = ((pmt[es+1] & 0x1F) << 8) | pmt[es+2];

    auto vpkts = PacketsOnPid(pkts, videoPid);
    ASSERT_GE(vpkts.size(), 2u);
    int prev = vpkts[0].cc;
    for (size_t i = 1; i < vpkts.size(); ++i) {
        int expected = (prev + 1) & 0x0F;
        EXPECT_EQ(vpkts[i].cc, expected) << "CC discontinuity at video packet " << i;
        prev = vpkts[i].cc;
    }
}

// Decode a 5-byte PTS/DTS field at offset o in a PES buffer.
static uint64_t DecodeTs(const Bytes& pes, size_t o) {
    return (((uint64_t)(pes[o] >> 1) & 0x07) << 30) |
           ((uint64_t)pes[o+1] << 22) |
           (((uint64_t)pes[o+2] >> 1) & 0x7F) << 15 |
           ((uint64_t)pes[o+3] << 7) |
           ((uint64_t)pes[o+4] >> 1);
}

TEST(TsMuxer, AudioPesAdtsAndPtsAndPmtBothStreams) {
    TsMuxer m;
    m.SetVideo(VideoCodecKind::Avc);
    m.SetAudio(44100, 2);
    Bytes out;
    auto v = m.WriteVideo(AvcKeyframe(), true, 0, 0);
    out.insert(out.end(), v.begin(), v.end());
    Bytes aac = {0x21, 0x10, 0x05, 0x60};
    uint32_t aPts = 23;
    auto a = m.WriteAudio(aac, aPts);
    out.insert(out.end(), a.begin(), a.end());

    auto pkts = ParseTsPackets(out);
    auto pi = DiscoverProgram(out, pkts);
    EXPECT_EQ(pi.videoType, 0x1B);
    EXPECT_EQ(pi.audioType, 0x0F);
    ASSERT_GE(pi.audioPid, 0);

    Bytes pes = AssemblePid(out, pkts, pi.audioPid);
    ASSERT_GE(pes.size(), 14u + 7u);
    EXPECT_EQ(pes[0], 0x00); EXPECT_EQ(pes[1], 0x00); EXPECT_EQ(pes[2], 0x01);
    EXPECT_EQ(pes[3], 0xC0);             // audio stream_id
    int hdrLen = pes[8];
    size_t adtsOff = 9 + hdrLen;
    // PTS
    EXPECT_EQ((pes[7] >> 6) & 0x3, 2);   // PTS only
    EXPECT_EQ(DecodeTs(pes, 9), (uint64_t)aPts * 90);
    // ADTS syncword
    EXPECT_EQ(pes[adtsOff], 0xFF);
    EXPECT_EQ(pes[adtsOff+1] & 0xF0, 0xF0);
    // frame length encoded in ADTS == 7 + aac.size()
    int frameLen = ((pes[adtsOff+3] & 0x3) << 11) | (pes[adtsOff+4] << 3) | ((pes[adtsOff+5] >> 5) & 0x7);
    EXPECT_EQ(frameLen, 7 + (int)aac.size());
}

TEST(TsMuxer, PcrPresentAtStartAndBoundedInterval) {
    TsMuxer m;
    m.SetVideo(VideoCodecKind::Avc);
    Bytes out;
    auto k = m.WriteVideo(AvcKeyframe(), true, 0, 0);
    out.insert(out.end(), k.begin(), k.end());
    std::vector<uint32_t> pcrPtsMs;
    pcrPtsMs.push_back(0);
    for (uint32_t t = 33; t <= 33 * 20; t += 33) {
        auto f = m.WriteVideo(AvcInterframe(), false, t, t);
        out.insert(out.end(), f.begin(), f.end());
    }
    auto pkts = ParseTsPackets(out);
    auto pi = DiscoverProgram(out, pkts);
    // collect PCR values on the PCR PID
    std::vector<uint64_t> pcrs;
    for (auto& t : pkts) if (t.pid == pi.pcrPid && t.hasPcr) pcrs.push_back(t.pcrBase);
    ASSERT_GE(pcrs.size(), 2u) << "PCR must appear at least at start and refresh";
    // first PCR corresponds to pts 0
    EXPECT_EQ(pcrs[0], 0u);
    // intervals bounded under 100ms media-time => 9000 in 90kHz
    for (size_t i = 1; i < pcrs.size(); ++i) {
        uint64_t delta = pcrs[i] - pcrs[i-1];
        EXPECT_LE(delta, (uint64_t)9000) << "PCR interval exceeds 100ms at index " << i;
    }
}

TEST(TsMuxer, DtsEqualsPtsOmitsDts) {
    TsMuxer m;
    m.SetVideo(VideoCodecKind::Avc);
    Bytes out = m.WriteVideo(AvcKeyframe(), true, 500, 500);
    auto pkts = ParseTsPackets(out);
    auto pi = DiscoverProgram(out, pkts);
    Bytes pes = AssemblePid(out, pkts, pi.videoPid);
    EXPECT_EQ((pes[7] >> 6) & 0x3, 2) << "DTS==PTS => PTS only";
    EXPECT_EQ(pes[8], 5);                // PES_header_data_length = 5 (PTS only)
    EXPECT_EQ(pes[9] & 0xF0, 0x20);     // '0010'
}

TEST(TsMuxer, DtsLessThanPtsCarriesBoth) {
    TsMuxer m;
    m.SetVideo(VideoCodecKind::Avc);
    uint32_t pts = 600, dts = 560;
    Bytes out = m.WriteVideo(AvcKeyframe(), true, pts, dts);
    auto pkts = ParseTsPackets(out);
    auto pi = DiscoverProgram(out, pkts);
    Bytes pes = AssemblePid(out, pkts, pi.videoPid);
    EXPECT_EQ((pes[7] >> 6) & 0x3, 3) << "PTS+DTS present";
    EXPECT_EQ(pes[8], 10);              // header_data_length = 10
    EXPECT_EQ(pes[9] & 0xF0, 0x30);    // '0011' PTS marker
    EXPECT_EQ(pes[14] & 0xF0, 0x10);   // '0001' DTS marker
    EXPECT_EQ(DecodeTs(pes, 9), (uint64_t)pts * 90);
    EXPECT_EQ(DecodeTs(pes, 14), (uint64_t)dts * 90);
}

// ---- ffprobe cross-check (Task 1.2 correctness gate) ----
#include <cstdio>
#include <cstdlib>
#include <fstream>

namespace {
bool HasFfprobe() { return std::system("command -v ffprobe >/dev/null 2>&1") == 0; }
std::string RunCmd(const std::string& cmd) {
    std::string out;
    FILE* f = popen(cmd.c_str(), "r");
    if (!f) return out;
    char buf[512];
    while (fgets(buf, sizeof(buf), f)) out += buf;
    pclose(f);
    return out;
}
}  // namespace

TEST(TsMuxer, FfprobeCrossCheck) {
    TsMuxer m;
    m.SetVideo(VideoCodecKind::Avc);
    m.SetAudio(44100, 2);
    Bytes out;
    auto k = m.WriteVideo(AvcKeyframe(), true, 0, 0);
    out.insert(out.end(), k.begin(), k.end());
    for (uint32_t t = 33; t <= 33 * 30; t += 33) {
        auto v = m.WriteVideo(AvcInterframe(), false, t, t);
        out.insert(out.end(), v.begin(), v.end());
        Bytes aac = {0x21, 0x10, 0x05, 0x60, 0x00, 0x10};
        auto a = m.WriteAudio(aac, t);
        out.insert(out.end(), a.begin(), a.end());
    }

    // always-on byte gate: alignment + sync
    ASSERT_EQ(out.size() % 188, 0u);

    if (!HasFfprobe()) {
        GTEST_SKIP() << "ffprobe not on PATH; byte-level asserts still gate.";
        return;
    }

    std::string path = std::string(std::tmpnam(nullptr)) + "_psstream.ts";
    { std::ofstream f(path, std::ios::binary); f.write((const char*)out.data(), out.size()); }

    std::string streams = RunCmd(
        "ffprobe -v error -show_entries stream=codec_name,codec_type -of csv=p=0 '" + path + "' 2>&1");
    // expect h264 + aac somewhere in the output
    EXPECT_NE(streams.find("h264"), std::string::npos) << "ffprobe streams: " << streams;
    EXPECT_NE(streams.find("aac"), std::string::npos) << "ffprobe streams: " << streams;

    // monotonic video PTS check via -show_packets on the video stream
    std::string pkts = RunCmd(
        "ffprobe -v error -select_streams v:0 -show_entries packet=pts -of csv=p=0 '" + path + "' 2>&1");
    long long prev = -1; bool monotonic = true; int count = 0;
    size_t pos = 0;
    while (pos < pkts.size()) {
        size_t nl = pkts.find('\n', pos);
        std::string line = pkts.substr(pos, nl == std::string::npos ? std::string::npos : nl - pos);
        pos = (nl == std::string::npos) ? pkts.size() : nl + 1;
        if (line.empty()) continue;
        char* endp = nullptr;
        long long v = strtoll(line.c_str(), &endp, 10);
        if (endp == line.c_str()) continue;   // non-numeric (e.g. N/A)
        if (v < prev) monotonic = false;
        prev = v; ++count;
    }
    EXPECT_TRUE(monotonic) << "video PTS not monotonic; raw: " << pkts;
    EXPECT_GT(count, 0) << "ffprobe reported no video packets; raw: " << pkts;

    std::remove(path.c_str());
}
