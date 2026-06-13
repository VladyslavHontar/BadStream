#include "rtmp_handshake.h"
#include "test_helpers.h"
using namespace ps;

TEST(Handshake, C0C1ShapeAndVersion) {
    RtmpHandshake h;
    Bytes c0c1 = h.BuildC0C1();
    ASSERT_EQ(c0c1.size(), 1537u);
    EXPECT_EQ(c0c1[0], 0x03);                 // C0 version
    EXPECT_EQ(c0c1[1], 0); EXPECT_EQ(c0c1[2], 0);
    EXPECT_EQ(c0c1[3], 0); EXPECT_EQ(c0c1[4], 0);   // time = 0
    EXPECT_EQ(c0c1[5], 0); EXPECT_EQ(c0c1[8], 0);   // 4 zero bytes
}

TEST(Handshake, C2EchoesS1) {
    RtmpHandshake h;
    Bytes s0s1(1537, 0);
    s0s1[1]=0x11; s0s1[2]=0x22; s0s1[3]=0x33; s0s1[4]=0x44;  // S1 time
    for (size_t i = 9; i < 1537; ++i) s0s1[i] = (uint8_t)(i & 0xFF); // S1 random
    Bytes c2 = h.BuildC2(s0s1);
    ASSERT_EQ(c2.size(), 1536u);
    EXPECT_EQ(c2[0],0x11); EXPECT_EQ(c2[1],0x22); EXPECT_EQ(c2[2],0x33); EXPECT_EQ(c2[3],0x44);
    EXPECT_EQ(c2[4],0); EXPECT_EQ(c2[7],0);                   // echo time = 0
    EXPECT_EQ(c2[8], (uint8_t)(9 & 0xFF));                    // first random byte echoed
    EXPECT_EQ(c2[1535], (uint8_t)(1536 & 0xFF));
}

TEST(Handshake, C2RejectsShortBuffer) {
    RtmpHandshake h;
    Bytes short_s0s1(100, 0xAB);  // too short
    Bytes c2 = h.BuildC2(short_s0s1);
    EXPECT_TRUE(c2.empty());
}
