#include "rtmp_chunk.h"
#include "test_helpers.h"
using namespace ps;
TEST(Chunk, Type0SmallMessage) {
    // csid=3, type=0x14, msgStreamId=0, ts=0, payload {0xAA,0xBB,0xCC}
    Bytes out = ChunkEncode(/*csid*/3, /*type*/0x14, /*msgStreamId*/0, /*ts*/0,
                            {0xAA,0xBB,0xCC}, /*chunkSize*/128);
    EXPECT_BYTES(out, {0x03,            // fmt0 | csid3
                       0x00,0x00,0x00,  // ts
                       0x00,0x00,0x03,  // length 3
                       0x14,            // type
                       0x00,0x00,0x00,0x00, // streamId LE
                       0xAA,0xBB,0xCC});
}
TEST(Chunk, FragmentsAtChunkSize) {
    Bytes payload(200, 0x55);
    Bytes out = ChunkEncode(5, 0x09, 1, 0, payload, /*chunkSize*/128);
    // header(12) + 128 + type3 basic header(1) + 72 = 213
    EXPECT_EQ(out.size(), 12u + 128u + 1u + 72u);
    EXPECT_EQ(out[0], 0x05);             // fmt0|csid5
    EXPECT_EQ(out[12 + 128], 0xC5);      // fmt3|csid5 continuation
}
TEST(Chunk, ExtendedTimestamp) {
    Bytes out = ChunkEncode(4, 0x08, 1, 0x1000000, {0x01}, 128);
    EXPECT_EQ(out[1], 0xFF); EXPECT_EQ(out[2], 0xFF); EXPECT_EQ(out[3], 0xFF); // marker
    // ext ts (4 BE) sits right after basic header(1) + 11-byte type0 header = index 12
    EXPECT_EQ(out[12], 0x01); EXPECT_EQ(out[13], 0x00);
    EXPECT_EQ(out[14], 0x00); EXPECT_EQ(out[15], 0x00);
}
