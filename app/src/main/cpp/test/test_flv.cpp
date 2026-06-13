#include "flv.h"
#include "test_helpers.h"
using namespace ps;
TEST(Flv, AvcCFromSpsPps) {
    Bytes sps = {0x67, 0x42, 0xC0, 0x1F, 0xAA};  // [0]=nal hdr, [1..3]=profile/compat/level
    Bytes pps = {0x68, 0xCE, 0x3C, 0x80};
    Bytes avcc = BuildAvcC(sps, pps);
    EXPECT_BYTES(avcc, {0x01, 0x42, 0xC0, 0x1F, 0xFF, 0xE1,
                        0x00,0x05, 0x67,0x42,0xC0,0x1F,0xAA,
                        0x01, 0x00,0x04, 0x68,0xCE,0x3C,0x80});
}
TEST(Flv, Asc44kStereo) { EXPECT_BYTES(BuildAsc(44100, 2), {0x12, 0x10}); }
TEST(Flv, Asc48kStereo) { EXPECT_BYTES(BuildAsc(48000, 2), {0x11, 0x90}); }
TEST(Flv, Asc44kMono)   { EXPECT_BYTES(BuildAsc(44100, 1), {0x12, 0x08}); }
TEST(Flv, AnnexBToAvcc) {
    Bytes in = {0,0,0,1, 0x65,0xAA,0xBB, 0,0,1, 0x41,0xCC};
    EXPECT_BYTES(AnnexBToAvcc(in), {0,0,0,3, 0x65,0xAA,0xBB, 0,0,0,2, 0x41,0xCC});
}
TEST(Flv, SplitSpsPps) {
    Bytes cfg = {0,0,0,1, 0x67,0x42,0xC0,0x1F,0xAA, 0,0,0,1, 0x68,0xCE,0x3C,0x80};
    Bytes sps, pps; SplitSpsPps(cfg, sps, pps);
    EXPECT_BYTES(sps, {0x67,0x42,0xC0,0x1F,0xAA});
    EXPECT_BYTES(pps, {0x68,0xCE,0x3C,0x80});
}
TEST(Flv, VideoSeqHeader) {
    Bytes avcc = {0x01,0x42,0xC0,0x1F};
    EXPECT_BYTES(FlvVideoSeqHeader(avcc),
        {0x17, 0x00, 0x00,0x00,0x00, 0x01,0x42,0xC0,0x1F});
}
TEST(Flv, VideoKeyFrame) {
    Bytes avccNals = {0,0,0,2, 0x65,0xAA};
    EXPECT_BYTES(FlvVideoFrame(avccNals, /*key*/true, /*cts*/0),
        {0x17, 0x01, 0x00,0x00,0x00, 0,0,0,2, 0x65,0xAA});
}
TEST(Flv, VideoInterFrameCts) {
    Bytes avccNals = {0,0,0,1, 0x41};
    EXPECT_BYTES(FlvVideoFrame(avccNals, false, /*cts*/40),
        {0x27, 0x01, 0x00,0x00,0x28, 0,0,0,1, 0x41});
}
TEST(Flv, AudioSeqHeader) {
    Bytes asc = {0x12,0x10};
    EXPECT_BYTES(FlvAudioSeqHeader(asc), {0xAF, 0x00, 0x12, 0x10});
}
TEST(Flv, AudioRaw) {
    Bytes aac = {0xDE,0xAD,0xBE,0xEF};
    EXPECT_BYTES(FlvAudioFrame(aac), {0xAF, 0x01, 0xDE,0xAD,0xBE,0xEF});
}
TEST(Flv, OnMetaDataShape) {
    Bytes m = BuildOnMetaData(1280, 720, 30.0, 44100);
    // starts with AMF0 string "@setDataFrame"
    EXPECT_EQ(m[0], 0x02); EXPECT_EQ(m[1], 0x00); EXPECT_EQ(m[2], 13);
    EXPECT_EQ(std::string((char*)&m[3], 13), "@setDataFrame");
    // contains "onMetaData" and "videocodecid"
    std::string s((char*)m.data(), m.size());
    EXPECT_NE(s.find("onMetaData"), std::string::npos);
    EXPECT_NE(s.find("videocodecid"), std::string::npos);
    // ends with object-end 00 00 09
    ASSERT_GE(m.size(), 3u);
    EXPECT_EQ(m[m.size()-1], 0x09);
    EXPECT_EQ(m[m.size()-2], 0x00);
    EXPECT_EQ(m[m.size()-3], 0x00);
}
