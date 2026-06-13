#include "byte_writer.h"
#include "test_helpers.h"
using namespace ps;
TEST(ByteWriter, BigEndianWidths) {
    std::vector<uint8_t> b;
    PutU8(b, 0x03);
    PutU16BE(b, 0x0102);
    PutU24BE(b, 0x010203);
    PutU32BE(b, 0x01020304);
    EXPECT_BYTES(b, {0x03, 0x01,0x02, 0x01,0x02,0x03, 0x01,0x02,0x03,0x04});
}
TEST(ByteWriter, StreamIdIsLittleEndian) {
    std::vector<uint8_t> b; PutU32LE(b, 0x01020304);
    EXPECT_BYTES(b, {0x04,0x03,0x02,0x01});
}
TEST(ByteWriter, DoubleIsBigEndianIEEE754) {
    std::vector<uint8_t> b; PutDoubleBE(b, 1.0);
    EXPECT_BYTES(b, {0x3F,0xF0,0x00,0x00,0x00,0x00,0x00,0x00});
}
