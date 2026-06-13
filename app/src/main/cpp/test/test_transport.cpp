#include "stub_transport.h"
#include "tcp_transport.h"
#include "test_helpers.h"
#include <thread>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>
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
TEST(TcpTransport, LoopbackWriteRead) {
    int srv = socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in addr{}; addr.sin_family = AF_INET; addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK); addr.sin_port = 0;
    ASSERT_EQ(bind(srv, (sockaddr*)&addr, sizeof(addr)), 0);
    socklen_t al = sizeof(addr); getsockname(srv, (sockaddr*)&addr, &al);
    ASSERT_EQ(listen(srv, 1), 0);
    uint16_t port = ntohs(addr.sin_port);
    std::thread server([&]{
        int c = accept(srv, nullptr, nullptr);
        uint8_t buf[4]; int n = (int)recv(c, buf, 4, 0);
        send(c, buf, n, 0);   // echo
        close(c);
    });
    ps::TcpTransport t;
    ASSERT_TRUE(t.Connect("127.0.0.1", port));
    ASSERT_TRUE(t.Write({1,2,3,4}));
    uint8_t in[4]; int n = t.Read(in, 4);
    EXPECT_EQ(n, 4); EXPECT_EQ(in[0], 1); EXPECT_EQ(in[3], 4);
    t.Close(); server.join(); close(srv);
}
