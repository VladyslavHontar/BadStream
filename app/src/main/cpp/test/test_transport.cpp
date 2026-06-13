#include "stub_transport.h"
#include "test_helpers.h"
using namespace ps;
TEST(StubTransport, CapturesWrites) {
    StubTransport t;
    EXPECT_TRUE(t.Connect("ignored", 1935));
    t.Write({0xDE,0xAD}); t.Write({0xBE,0xEF});
    EXPECT_BYTES(t.written(), {0xDE,0xAD,0xBE,0xEF});
    EXPECT_TRUE(t.connected());
    t.Close();
    EXPECT_FALSE(t.connected());
}
TEST(StubTransport, FeedsReads) {
    StubTransport t; t.Connect("x", 1);
    t.FeedIncoming({0x01,0x02,0x03});
    uint8_t buf[8]; int n = t.Read(buf, sizeof(buf));
    EXPECT_EQ(n, 3); EXPECT_EQ(buf[0], 0x01); EXPECT_EQ(buf[2], 0x03);
}
