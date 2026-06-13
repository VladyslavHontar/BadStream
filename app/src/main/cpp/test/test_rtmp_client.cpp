#include "rtmp_client.h"
#include "stub_transport.h"
#include "test_helpers.h"
using namespace ps;
TEST(RtmpClient, ConnectCommandObject) {
    StreamParams p; p.app = "live"; p.tcUrl = "rtmp://h/live"; p.streamKey = "key";
    Bytes payload = BuildConnectCommand(p, /*txn*/1);
    // command name "connect"
    EXPECT_EQ(payload[0], 0x02); EXPECT_EQ(payload[2], 7);
    EXPECT_EQ(std::string((char*)&payload[3], 7), "connect");
    // transaction id number 1.0 follows
    size_t i = 3 + 7;
    EXPECT_EQ(payload[i], 0x00);                 // number marker
    EXPECT_EQ(payload[i+1], 0x3F); EXPECT_EQ(payload[i+2], 0xF0);
    // object marker then content
    EXPECT_EQ(payload[i+9], 0x03);
    std::string s((char*)payload.data(), payload.size());
    EXPECT_NE(s.find("app"), std::string::npos);
    EXPECT_NE(s.find("live"), std::string::npos);
    EXPECT_NE(s.find("tcUrl"), std::string::npos);
    EXPECT_NE(s.find("flashVer"), std::string::npos);
}
