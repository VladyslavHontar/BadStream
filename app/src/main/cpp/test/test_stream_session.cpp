#include <gtest/gtest.h>
#include <thread>
#include <chrono>
#include <mutex>
#include <condition_variable>
#include <memory>
#include "stream_session.h"
#include "stub_transport.h"
#include "rtmp_chunk.h"
#include "amf0.h"
using namespace ps;

// Canned server-byte builders copied verbatim from test_rtmp_client.cpp (renamed with a
// "Sess" prefix to avoid ODR clashes across translation units that link together).
static Bytes SessMakeCommand(const Bytes& body) { return ChunkEncode(3, 0x14, 0, 0, body, 128); }
static Bytes SessMakeResultSuccess() {
    Bytes b; Amf0::String(b,"_result"); Amf0::Number(b,1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b,"level"); Amf0::String(b,"status");
    Amf0::Key(b,"code");  Amf0::String(b,"NetConnection.Connect.Success");
    Amf0::ObjectEnd(b);
    return SessMakeCommand(b);
}
static Bytes SessMakeCreateStreamResult(int streamId) {
    Bytes b; Amf0::String(b,"_result"); Amf0::Number(b,4); Amf0::Null(b); Amf0::Number(b, streamId);
    return SessMakeCommand(b);
}
static Bytes SessMakePublishStart() {
    Bytes b; Amf0::String(b,"onStatus"); Amf0::Number(b,0); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b,"level"); Amf0::String(b,"status");
    Amf0::Key(b,"code");  Amf0::String(b,"NetStream.Publish.Start");
    Amf0::ObjectEnd(b);
    return SessMakeCommand(b);
}

// PreloadPublishHandshake: FeedIncoming the same canned server bytes that
// test_rtmp_client.cpp uses to drive RtmpClient to Publishing. All bytes are queued up
// front so a single Read (< 8192) + one OnBytes advances straight to Publishing.
static void PreloadPublishHandshake(StubTransport& stub) {
    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;   // S0 + S1 + S2
    stub.FeedIncoming(s0s1s2);
    stub.FeedIncoming(SessMakeResultSuccess());        // connect _result success
    stub.FeedIncoming(SessMakeCreateStreamResult(1));  // createStream _result, streamId=1
    stub.FeedIncoming(SessMakePublishStart());         // onStatus NetStream.Publish.Start
}

TEST(StreamSession, ReachesLiveAndWritesVideo) {
    auto owned = std::make_unique<StubTransport>();
    StubTransport* stub = owned.get();
    PreloadPublishHandshake(*stub);

    StreamParams p; p.host = "h"; p.app = "app"; p.streamKey = "k"; p.tcUrl = "rtmp://h/app";
    // std::function requires a copyable target, so wrap the moved transport in a shared_ptr;
    // ownership is handed out (moved) on the first factory invocation.
    std::shared_ptr<std::unique_ptr<StubTransport>> held =
        std::make_shared<std::unique_ptr<StubTransport>>(std::move(owned));
    StreamSession s(p, [held]() mutable -> std::unique_ptr<Transport> { return std::move(*held); });
    s.Start();

    for (int i = 0; i < 200 && s.state() != SessionState::Live; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    ASSERT_EQ(s.state(), SessionState::Live);

    size_t before = stub->written().size();
    s.SendVideoConfig({0,0,0,1, 0x67,0x42,0x00,0x1e, 0,0,0,1, 0x68,0xce,0x3c,0x80});
    s.SendVideo({0,0,0,1, 0x65, 0x88}, true, 0, 0);
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    EXPECT_GT(stub->written().size(), before);

    s.Stop();
    EXPECT_EQ(s.state(), SessionState::Idle);
}

// A transport whose Read blocks until Close() is called — models a silent/hung server.
namespace {
struct BlockingTransport : ps::Transport {
    std::mutex m; std::condition_variable cv; bool closed = false; bool conn = false;
    bool Connect(const std::string&, uint16_t) override { conn = true; return true; }
    bool Write(const std::vector<uint8_t>&) override { return true; }
    int Read(uint8_t*, int) override {
        std::unique_lock<std::mutex> lk(m);
        cv.wait(lk, [&] { return closed; });
        return 0; // closed -> report EOF
    }
    void Close() override { { std::lock_guard<std::mutex> lk(m); closed = true; } cv.notify_all(); conn = false; }
    bool connected() const override { return conn; }
};
}

TEST(StreamSession, StopInterruptsBlockingRead) {
    auto owned = std::make_unique<BlockingTransport>();
    auto sp = std::make_shared<std::unique_ptr<BlockingTransport>>(std::move(owned));
    ps::StreamParams p; p.host = "h"; p.app = "a"; p.streamKey = "k";
    ps::StreamSession s(p, [sp]() mutable -> std::unique_ptr<ps::Transport> {
        return std::unique_ptr<ps::Transport>(std::move(*sp));
    });
    s.Start();
    std::this_thread::sleep_for(std::chrono::milliseconds(30)); // let the thread enter Read()
    auto t0 = std::chrono::steady_clock::now();
    s.Stop(); // must return promptly because Close() interrupts the blocking Read
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();
    EXPECT_LT(ms, 1000);
    EXPECT_EQ(s.state(), ps::SessionState::Idle);
}
