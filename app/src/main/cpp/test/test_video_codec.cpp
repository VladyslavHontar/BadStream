#include <gtest/gtest.h>
#include "video_codec.h"
#include "flv.h"
using namespace ps;

TEST(AvcCodec, MatchesLegacyFlv) {
    AvcCodec avc;
    Bytes sps{0x67,0x42,0x00,0x1e,0x11}, pps{0x68,0xce,0x3c,0x80};
    Bytes csd; csd.insert(csd.end(), {0,0,0,1}); csd.insert(csd.end(), sps.begin(), sps.end());
    csd.insert(csd.end(), {0,0,0,1}); csd.insert(csd.end(), pps.begin(), pps.end());
    EXPECT_EQ(avc.SequenceHeader(csd), FlvVideoSeqHeader(BuildAvcC(sps, pps)));
    Bytes frame{0,0,0,1, 0x65, 0x88};
    EXPECT_EQ(avc.Frame(frame, true, 0), FlvVideoFrame(AnnexBToAvcc(frame), true, 0));
    EXPECT_STREQ(avc.FourCc(), "avc1");
}

TEST(HevcCodec, SequenceStartTagShape) {
    HevcCodec hevc;
    Bytes csd{0,0,0,1, 0x40,0x01,0x0c,0x01, 0,0,0,1, 0x42,0x01,0x01, 0,0,0,1, 0x44,0x01,0xc0};
    Bytes sh = hevc.SequenceHeader(csd);
    EXPECT_EQ(sh[0], 0x80 | 0x10 | 0x00);     // IsExHeader | key | SequenceStart
    EXPECT_EQ(sh[1], 'h'); EXPECT_EQ(sh[2], 'v'); EXPECT_EQ(sh[3], 'c'); EXPECT_EQ(sh[4], '1');
    EXPECT_EQ(sh[5], 0x01);                   // hvcC configurationVersion
    EXPECT_STREQ(hevc.FourCc(), "hvc1");
}

TEST(HevcCodec, CodedFrameTagShape) {
    HevcCodec hevc;
    Bytes frame{0,0,0,1, 0x26, 0x01, 0xaa};   // an IDR-ish NAL
    Bytes f = hevc.Frame(frame, true, 33);
    EXPECT_EQ(f[0], 0x80 | 0x10 | 0x01);      // IsExHeader | key | CodedFrames
    EXPECT_EQ(f[1], 'h'); EXPECT_EQ(f[4], '1');
    EXPECT_EQ((f[5]<<16)|(f[6]<<8)|f[7], 33); // 3-byte cts
    EXPECT_EQ((f[8]<<24)|(f[9]<<16)|(f[10]<<8)|f[11], 3); // NAL length = 3 (0x26,0x01,0xaa)
}
