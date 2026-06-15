#include "mp4_writer.h"
#include "flv.h"
#include "hevc.h"
#include "test_helpers.h"
#include <cstdio>
#include <fstream>
#include <string>
using namespace ps;

// ---------------------------------------------------------------------------
// Minimal ISO-BMFF box walker for assertions.
// ---------------------------------------------------------------------------
namespace {
struct Box {
    std::string type;
    size_t start;        // offset of the box's size field
    size_t size;         // total box size (header + body)
    size_t bodyStart;    // offset of the first body byte (after size+type, or +12 for fullbox)
};

uint32_t Rd32(const Bytes& b, size_t o) {
    return (uint32_t(b[o]) << 24) | (uint32_t(b[o+1]) << 16) | (uint32_t(b[o+2]) << 8) | b[o+3];
}
uint16_t Rd16(const Bytes& b, size_t o) { return (uint16_t(b[o]) << 8) | b[o+1]; }
uint64_t Rd64(const Bytes& b, size_t o) {
    return (uint64_t(Rd32(b, o)) << 32) | Rd32(b, o + 4);
}

// Walk direct children of the byte range [begin,end).
std::vector<Box> Children(const Bytes& b, size_t begin, size_t end) {
    std::vector<Box> out;
    size_t o = begin;
    while (o + 8 <= end) {
        size_t sz = Rd32(b, o);
        if (sz < 8 || o + sz > end) break;
        Box box;
        box.type = std::string((const char*)&b[o + 4], 4);
        box.start = o;
        box.size = sz;
        box.bodyStart = o + 8;
        out.push_back(box);
        o += sz;
    }
    return out;
}

// Find the first child box of `type` directly under [begin,end).
bool Find(const Bytes& b, size_t begin, size_t end, const std::string& type, Box& out) {
    for (auto& c : Children(b, begin, end)) {
        if (c.type == type) { out = c; return true; }
    }
    return false;
}

// Find a box by a path of nested types (e.g. {"moov","trak","mdia"}). Recurses into bodies.
bool FindPath(const Bytes& b, size_t begin, size_t end,
              const std::vector<std::string>& path, Box& out) {
    size_t s = begin, e = end;
    Box cur{};
    for (size_t i = 0; i < path.size(); ++i) {
        if (!Find(b, s, e, path[i], cur)) return false;
        s = cur.bodyStart;
        e = cur.start + cur.size;
    }
    out = cur;
    return true;
}

Bytes ReadFile(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    return Bytes((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

std::string TmpPath(const char* name) {
    return std::string("/tmp/m2c_") + name + "_" + std::to_string((long)::getpid()) + ".mp4";
}

// AVC fixtures (mirror test_flv.cpp).
Bytes Sps() { return {0x67, 0x42, 0xC0, 0x1F, 0xAA}; }
Bytes Pps() { return {0x68, 0xCE, 0x3C, 0x80}; }
Bytes AvcCsd() {
    return {0,0,0,1, 0x67,0x42,0xC0,0x1F,0xAA, 0,0,0,1, 0x68,0xCE,0x3C,0x80};
}
// One Annex-B video frame: a single NAL with the given type byte + payload.
Bytes Frame(uint8_t nalType, std::initializer_list<uint8_t> payload) {
    Bytes f = {0,0,0,1, nalType};
    for (uint8_t p : payload) f.push_back(p);
    return f;
}

// HEVC fixtures (mirror test_hevc.cpp).
Bytes HevcCsd() {
    return {
        0,0,0,1, 0x40,0x01, 0x0c,0x01,
        0,0,0,1, 0x42,0x01, 0x01,0x60,0x00,
        0,0,0,1, 0x44,0x01, 0xc0,0xf7,
    };
}
}  // namespace

// ---------------------------------------------------------------------------
// Init segment (Task 2)
// ---------------------------------------------------------------------------
TEST(Mp4Writer, InitSegmentFtypBrands) {
    FragmentedMp4Writer w;
    w.Start("/dev/null", FragmentedMp4Writer::kAvc, 1280, 720, 30, 44100, 2);
    w.WriteVideoConfig(AvcCsd());
    w.WriteAudioConfig(44100, 2);
    Bytes init = w.InitSegmentForTest();

    Box ftyp;
    ASSERT_TRUE(Find(init, 0, init.size(), "ftyp", ftyp));
    EXPECT_EQ(std::string((const char*)&init[ftyp.bodyStart], 4), "iso5");  // major brand
    // compatible brands include mp42
    std::string s((const char*)init.data(), init.size());
    EXPECT_NE(s.find("mp42"), std::string::npos);
}

TEST(Mp4Writer, MoovHasTwoTraksAndMvexWithTwoTrex) {
    FragmentedMp4Writer w;
    w.Start("/dev/null", FragmentedMp4Writer::kAvc, 1280, 720, 30, 44100, 2);
    w.WriteVideoConfig(AvcCsd());
    w.WriteAudioConfig(44100, 2);
    Bytes init = w.InitSegmentForTest();

    Box moov;
    ASSERT_TRUE(Find(init, 0, init.size(), "moov", moov));
    int traks = 0, mvex = 0;
    for (auto& c : Children(init, moov.bodyStart, moov.start + moov.size)) {
        if (c.type == "trak") ++traks;
        if (c.type == "mvex") ++mvex;
    }
    EXPECT_EQ(traks, 2);
    EXPECT_EQ(mvex, 1);

    Box mvexBox;
    ASSERT_TRUE(Find(init, moov.bodyStart, moov.start + moov.size, "mvex", mvexBox));
    int trex = 0;
    for (auto& c : Children(init, mvexBox.bodyStart, mvexBox.start + mvexBox.size))
        if (c.type == "trex") ++trex;
    EXPECT_EQ(trex, 2);
}

TEST(Mp4Writer, VideoStsdHasAvc1WithMatchingAvcC) {
    FragmentedMp4Writer w;
    w.Start("/dev/null", FragmentedMp4Writer::kAvc, 1280, 720, 30, 44100, 2);
    w.WriteVideoConfig(AvcCsd());
    w.WriteAudioConfig(44100, 2);
    Bytes init = w.InitSegmentForTest();

    // moov > trak(video, first) > mdia > minf > stbl > stsd
    Box stsd;
    ASSERT_TRUE(FindPath(init, 0, init.size(),
        {"moov","trak","mdia","minf","stbl","stsd"}, stsd));
    // stsd is a FullBox: 4 bytes ver+flags, 4 bytes entry_count, then the sample entry.
    size_t entries = stsd.bodyStart + 8;
    Box avc1;
    ASSERT_TRUE(Find(init, entries, stsd.start + stsd.size, "avc1", avc1));
    Box avcc;
    ASSERT_TRUE(Find(init, avc1.bodyStart + 78, avc1.start + avc1.size, "avcC", avcc));
    Bytes got(init.begin() + avcc.bodyStart, init.begin() + avcc.start + avcc.size);
    EXPECT_BYTES(got, BuildAvcC(Sps(), Pps()));
}

TEST(Mp4Writer, AudioStsdHasMp4aWithEsdsContainingAsc) {
    FragmentedMp4Writer w;
    w.Start("/dev/null", FragmentedMp4Writer::kAvc, 1280, 720, 30, 48000, 2);
    w.WriteVideoConfig(AvcCsd());
    w.WriteAudioConfig(48000, 2);
    Bytes init = w.InitSegmentForTest();

    // The audio trak is the second trak; FindPath returns the first, so locate it manually.
    Box moov; ASSERT_TRUE(Find(init, 0, init.size(), "moov", moov));
    std::vector<Box> traks;
    for (auto& c : Children(init, moov.bodyStart, moov.start + moov.size))
        if (c.type == "trak") traks.push_back(c);
    ASSERT_EQ(traks.size(), 2u);
    Box& audio = traks[1];

    Box stsd;
    ASSERT_TRUE(FindPath(init, audio.bodyStart, audio.start + audio.size,
        {"mdia","minf","stbl","stsd"}, stsd));
    Box mp4a;
    ASSERT_TRUE(Find(init, stsd.bodyStart + 8, stsd.start + stsd.size, "mp4a", mp4a));
    Box esds;
    ASSERT_TRUE(Find(init, mp4a.bodyStart + 28, mp4a.start + mp4a.size, "esds", esds));

    // The ASC bytes must appear inside the esds payload.
    Bytes asc = BuildAsc(48000, 2);
    Bytes esdsBytes(init.begin() + esds.bodyStart, init.begin() + esds.start + esds.size);
    bool found = false;
    for (size_t i = 0; i + asc.size() <= esdsBytes.size(); ++i)
        if (std::equal(asc.begin(), asc.end(), esdsBytes.begin() + i)) { found = true; break; }
    EXPECT_TRUE(found) << "ASC not found in esds: " << Hex(esdsBytes);
}

TEST(Mp4Writer, SampleTablesAreEmpty) {
    FragmentedMp4Writer w;
    w.Start("/dev/null", FragmentedMp4Writer::kAvc, 1280, 720, 30, 44100, 2);
    w.WriteVideoConfig(AvcCsd());
    w.WriteAudioConfig(44100, 2);
    Bytes init = w.InitSegmentForTest();

    Box stbl;
    ASSERT_TRUE(FindPath(init, 0, init.size(),
        {"moov","trak","mdia","minf","stbl"}, stbl));
    for (const char* t : {"stts","stsc","stsz","stco"}) {
        Box x;
        ASSERT_TRUE(Find(init, stbl.bodyStart, stbl.start + stbl.size, t, x)) << t;
        // entry_count is the last 4 bytes for stts/stsc/stco; for stsz it's sample_count
        // at body+8 (size,count). All must be zero.
        uint32_t count = (std::string(t) == "stsz")
            ? Rd32(init, x.bodyStart + 8)
            : Rd32(init, x.bodyStart + 4);
        EXPECT_EQ(count, 0u) << t;
    }
}

// ---------------------------------------------------------------------------
// HEVC init path (Task 4)
// ---------------------------------------------------------------------------
TEST(Mp4Writer, HevcStsdHasHvc1WithMatchingHvcC) {
    FragmentedMp4Writer w;
    w.Start("/dev/null", FragmentedMp4Writer::kHevc, 1920, 1080, 30, 44100, 2);
    w.WriteVideoConfig(HevcCsd());
    w.WriteAudioConfig(44100, 2);
    Bytes init = w.InitSegmentForTest();

    Box stsd;
    ASSERT_TRUE(FindPath(init, 0, init.size(),
        {"moov","trak","mdia","minf","stbl","stsd"}, stsd));
    Box hvc1;
    ASSERT_TRUE(Find(init, stsd.bodyStart + 8, stsd.start + stsd.size, "hvc1", hvc1));
    Box hvcc;
    ASSERT_TRUE(Find(init, hvc1.bodyStart + 78, hvc1.start + hvc1.size, "hvcC", hvcc));
    Bytes got(init.begin() + hvcc.bodyStart, init.begin() + hvcc.start + hvcc.size);
    Bytes vps, sps, pps; SplitHevcParams(HevcCsd(), vps, sps, pps);
    EXPECT_BYTES(got, BuildHvcC(vps, sps, pps));
}

// ---------------------------------------------------------------------------
// Single fragment (Task 3)
// ---------------------------------------------------------------------------
TEST(Mp4Writer, SingleFragmentTrunShape) {
    std::string path = TmpPath("single");
    {
        FragmentedMp4Writer w;
        ASSERT_TRUE(w.Start(path, FragmentedMp4Writer::kAvc, 320, 240, 30, 44100, 2));
        w.WriteVideoConfig(AvcCsd());
        w.WriteAudioConfig(44100, 2);
        // keyframe @0, inter @33, inter @66 (all within 2s) + 2 audio frames
        w.WriteVideo(Frame(0x65, {0xAA, 0xBB}), true, 0);
        w.WriteAudio({0xDE, 0xAD, 0xBE, 0xEF}, 0);
        w.WriteVideo(Frame(0x41, {0xCC}), false, 33);
        w.WriteAudio({0x11, 0x22}, 23);
        w.WriteVideo(Frame(0x41, {0xDD, 0xEE, 0xFF}), false, 66);
        w.Stop();
    }
    Bytes f = ReadFile(path);
    ::remove(path.c_str());

    // exactly one moof and one mdat
    int moofs = 0, mdats = 0;
    Box firstMoof{}; bool haveMoof = false;
    for (auto& c : Children(f, 0, f.size())) {
        if (c.type == "moof") { ++moofs; if (!haveMoof) { firstMoof = c; haveMoof = true; } }
        if (c.type == "mdat") ++mdats;
    }
    EXPECT_EQ(moofs, 1);
    EXPECT_EQ(mdats, 1);

    // video traf > trun
    Box vtraf;
    ASSERT_TRUE(Find(f, firstMoof.bodyStart, firstMoof.start + firstMoof.size, "traf", vtraf));
    Box trun;
    ASSERT_TRUE(Find(f, vtraf.bodyStart, vtraf.start + vtraf.size, "trun", trun));
    // FullBox(4) then sample_count(4), data_offset(4), then 3 fields per sample.
    uint32_t sampleCount = Rd32(f, trun.bodyStart + 4);
    EXPECT_EQ(sampleCount, 3u);
    size_t rec = trun.bodyStart + 4 + 4 + 4;   // first sample record
    // sample 0: duration, size, flags
    uint32_t size0 = Rd32(f, rec + 4);
    uint32_t flags0 = Rd32(f, rec + 8);
    EXPECT_EQ(size0, AnnexBToAvcc(Frame(0x65, {0xAA, 0xBB})).size());
    EXPECT_EQ(flags0, 0x02000000u);   // sync
    uint32_t flags1 = Rd32(f, rec + 12 + 8);
    EXPECT_EQ(flags1, 0x01010000u);   // non-sync

    // tfdt baseMediaDecodeTime == 0 for the first fragment.
    Box tfdt;
    ASSERT_TRUE(Find(f, vtraf.bodyStart, vtraf.start + vtraf.size, "tfdt", tfdt));
    EXPECT_EQ(Rd64(f, tfdt.bodyStart + 4), 0u);

    // audio traf trun has 2 samples.
    std::vector<Box> trafs;
    for (auto& c : Children(f, firstMoof.bodyStart, firstMoof.start + firstMoof.size))
        if (c.type == "traf") trafs.push_back(c);
    ASSERT_EQ(trafs.size(), 2u);
    Box atrun;
    ASSERT_TRUE(Find(f, trafs[1].bodyStart, trafs[1].start + trafs[1].size, "trun", atrun));
    EXPECT_EQ(Rd32(f, atrun.bodyStart + 4), 2u);
}

TEST(Mp4Writer, MdatSizeMatchesSampleBytes) {
    std::string path = TmpPath("mdat");
    {
        FragmentedMp4Writer w;
        ASSERT_TRUE(w.Start(path, FragmentedMp4Writer::kAvc, 320, 240, 30, 44100, 2));
        w.WriteVideoConfig(AvcCsd());
        w.WriteAudioConfig(44100, 2);
        w.WriteVideo(Frame(0x65, {0xAA, 0xBB}), true, 0);
        w.WriteAudio({0xDE, 0xAD, 0xBE, 0xEF}, 0);
        w.WriteVideo(Frame(0x41, {0xCC}), false, 33);
        w.Stop();
    }
    Bytes f = ReadFile(path);
    ::remove(path.c_str());

    Box mdat;
    ASSERT_TRUE(Find(f, 0, f.size(), "mdat", mdat));
    size_t expected = 8;   // header
    expected += AnnexBToAvcc(Frame(0x65, {0xAA, 0xBB})).size();
    expected += AnnexBToAvcc(Frame(0x41, {0xCC})).size();
    expected += 4;         // audio AU
    EXPECT_EQ(mdat.size, expected);
}

TEST(Mp4Writer, DropsFramesBeforeFirstKeyframe) {
    std::string path = TmpPath("drop");
    {
        FragmentedMp4Writer w;
        ASSERT_TRUE(w.Start(path, FragmentedMp4Writer::kAvc, 320, 240, 30, 44100, 2));
        w.WriteVideoConfig(AvcCsd());
        w.WriteAudioConfig(44100, 2);
        w.WriteVideo(Frame(0x41, {0x01}), false, 0);   // inter before any key -> dropped
        w.WriteVideo(Frame(0x65, {0x02}), true, 33);   // first keyframe opens fragment
        w.WriteVideo(Frame(0x41, {0x03}), false, 66);
        w.Stop();
    }
    Bytes f = ReadFile(path);
    ::remove(path.c_str());

    Box moof; ASSERT_TRUE(Find(f, 0, f.size(), "moof", moof));
    Box traf; ASSERT_TRUE(Find(f, moof.bodyStart, moof.start + moof.size, "traf", traf));
    Box trun; ASSERT_TRUE(Find(f, traf.bodyStart, traf.start + traf.size, "trun", trun));
    EXPECT_EQ(Rd32(f, trun.bodyStart + 4), 2u);   // only the 2 frames from the keyframe on
}

// ---------------------------------------------------------------------------
// Multi-fragment boundary + durations (Task 4)
// ---------------------------------------------------------------------------
TEST(Mp4Writer, KeyframeAfter2sOpensNewFragment) {
    std::string path = TmpPath("multi");
    {
        FragmentedMp4Writer w;
        ASSERT_TRUE(w.Start(path, FragmentedMp4Writer::kAvc, 320, 240, 30, 44100, 2));
        w.WriteVideoConfig(AvcCsd());
        w.WriteAudioConfig(44100, 2);
        w.WriteVideo(Frame(0x65, {0x01}), true, 0);       // frag 1 start
        w.WriteVideo(Frame(0x41, {0x02}), false, 1000);
        w.WriteVideo(Frame(0x65, {0x03}), true, 2000);    // >=2000ms -> frag 2
        w.WriteVideo(Frame(0x41, {0x04}), false, 3000);
        w.Stop();
    }
    Bytes f = ReadFile(path);
    ::remove(path.c_str());

    std::vector<Box> moofs;
    for (auto& c : Children(f, 0, f.size()))
        if (c.type == "moof") moofs.push_back(c);
    ASSERT_EQ(moofs.size(), 2u);

    // mfhd sequence numbers 1, 2
    Box m0, m1;
    ASSERT_TRUE(Find(f, moofs[0].bodyStart, moofs[0].start + moofs[0].size, "mfhd", m0));
    ASSERT_TRUE(Find(f, moofs[1].bodyStart, moofs[1].start + moofs[1].size, "mfhd", m1));
    EXPECT_EQ(Rd32(f, m0.bodyStart + 4), 1u);
    EXPECT_EQ(Rd32(f, m1.bodyStart + 4), 2u);

    // second fragment's video tfdt baseMediaDecodeTime == sum of frag1 durations.
    // frag1: sample0 dur = 1000-0 = 1000; sample1 (last) dur = default(1000/30=33).
    Box vtraf;
    ASSERT_TRUE(Find(f, moofs[1].bodyStart, moofs[1].start + moofs[1].size, "traf", vtraf));
    Box tfdt;
    ASSERT_TRUE(Find(f, vtraf.bodyStart, vtraf.start + vtraf.size, "tfdt", tfdt));
    EXPECT_EQ(Rd64(f, tfdt.bodyStart + 4), 1000u + 33u);

    // first sample of frag2 is a sync sample.
    Box trun;
    ASSERT_TRUE(Find(f, vtraf.bodyStart, vtraf.start + vtraf.size, "trun", trun));
    size_t rec = trun.bodyStart + 4 + 4 + 4;
    EXPECT_EQ(Rd32(f, rec + 8), 0x02000000u);
}

TEST(Mp4Writer, SampleDurationsFromPtsDiffs) {
    std::string path = TmpPath("dur");
    {
        FragmentedMp4Writer w;
        ASSERT_TRUE(w.Start(path, FragmentedMp4Writer::kAvc, 320, 240, 30, 44100, 2));
        w.WriteVideoConfig(AvcCsd());
        w.WriteAudioConfig(44100, 2);
        w.WriteVideo(Frame(0x65, {0x01}), true, 100);
        w.WriteVideo(Frame(0x41, {0x02}), false, 140);   // delta 40
        w.WriteVideo(Frame(0x41, {0x03}), false, 175);   // delta 35, last -> default 33
        w.Stop();
    }
    Bytes f = ReadFile(path);
    ::remove(path.c_str());

    Box moof; ASSERT_TRUE(Find(f, 0, f.size(), "moof", moof));
    Box traf; ASSERT_TRUE(Find(f, moof.bodyStart, moof.start + moof.size, "traf", traf));
    Box trun; ASSERT_TRUE(Find(f, traf.bodyStart, traf.start + traf.size, "trun", trun));
    size_t rec = trun.bodyStart + 4 + 4 + 4;
    EXPECT_EQ(Rd32(f, rec + 0), 40u);             // sample 0 duration
    EXPECT_EQ(Rd32(f, rec + 12), 35u);            // sample 1 duration
    EXPECT_EQ(Rd32(f, rec + 24), 33u);            // sample 2 (last) -> default 1000/30
}
