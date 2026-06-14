#include <gtest/gtest.h>
#include "hevc.h"
using namespace ps;

// Minimal Annex-B blob: VPS(type32) + SPS(type33) + PPS(type34), 4-byte start codes.
static Bytes SampleCsd() {
    return {
        0,0,0,1, 0x40,0x01, 0x0c,0x01,            // VPS: nal[0]=0x40 -> type=(0x40>>1)&0x3f=32
        0,0,0,1, 0x42,0x01, 0x01,0x60,0x00,       // SPS: 0x42>>1=33
        0,0,0,1, 0x44,0x01, 0xc0,0xf7,            // PPS: 0x44>>1=34
    };
}

TEST(Hevc, SplitsVpsSpsPps) {
    Bytes vps, sps, pps;
    SplitHevcParams(SampleCsd(), vps, sps, pps);
    EXPECT_EQ(vps, (Bytes{0x40,0x01,0x0c,0x01}));
    EXPECT_EQ(sps, (Bytes{0x42,0x01,0x01,0x60,0x00}));
    EXPECT_EQ(pps, (Bytes{0x44,0x01,0xc0,0xf7}));
}

TEST(Hevc, BuildsHvccWithThreeArrays) {
    Bytes vps{0x40,0x01,0x0c,0x01}, sps{0x42,0x01,0x01,0x60,0x00}, pps{0x44,0x01,0xc0,0xf7};
    Bytes hvcc = BuildHvcC(vps, sps, pps);
    ASSERT_GE(hvcc.size(), 23u);
    EXPECT_EQ(hvcc[0], 0x01);                 // configurationVersion
    EXPECT_EQ(hvcc[22], 0x03);                // numOfArrays = 3 (VPS, SPS, PPS)
    EXPECT_EQ(hvcc[23] & 0x3f, 32);           // first array: VPS nal type
}
