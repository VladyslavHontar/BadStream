#include "mp4_box.h"
#include "test_helpers.h"
using namespace ps;

TEST(Mp4Box, EmptyBoxIsEightBytes) {
    Bytes out; BoxWriter w(out);
    size_t p = w.Begin("free");
    w.End(p);
    EXPECT_BYTES(out, {0,0,0,8, 'f','r','e','e'});
}

TEST(Mp4Box, BoxWithBodyHasCorrectSize) {
    Bytes out; BoxWriter w(out);
    size_t p = w.Begin("mdat");
    PutU32BE(out, 0xDEADBEEF);   // 4-byte body
    w.End(p);
    EXPECT_BYTES(out, {0,0,0,12, 'm','d','a','t', 0xDE,0xAD,0xBE,0xEF});
}

TEST(Mp4Box, NestedBoxSizeIncludesChild) {
    Bytes out; BoxWriter w(out);
    size_t parent = w.Begin("moov");
    size_t child = w.Begin("free");
    w.End(child);                // child = 8
    w.End(parent);               // parent = 8 + 8 = 16
    // parent size
    EXPECT_EQ(out[0], 0); EXPECT_EQ(out[1], 0); EXPECT_EQ(out[2], 0); EXPECT_EQ(out[3], 16);
    // child size at offset 8
    EXPECT_EQ(out[8], 0); EXPECT_EQ(out[9], 0); EXPECT_EQ(out[10], 0); EXPECT_EQ(out[11], 8);
}

TEST(Mp4Box, FullBoxWritesVersionAndFlags) {
    Bytes out; BoxWriter w(out);
    size_t p = w.BeginFull("tfhd", /*version*/0, /*flags*/0x020000);
    w.End(p);
    EXPECT_BYTES(out, {0,0,0,12, 't','f','h','d', 0x00, 0x02,0x00,0x00});
}
