#include <gtest/gtest.h>
#include <thread>
#include <chrono>
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
