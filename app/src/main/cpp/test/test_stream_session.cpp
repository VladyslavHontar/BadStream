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
    int ReadNonBlocking(uint8_t*, int) override {
        std::lock_guard<std::mutex> lk(m); return closed ? -1 : 0;
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

// Walk RTMP chunks (every message is fmt0-led per ChunkEncode) and return each message's
// type, chunk timestamp, and reassembled payload.
namespace {
struct Msg { uint8_t type; uint32_t ts; Bytes payload; };
std::vector<Msg> ParseMessages(const Bytes& w, uint32_t chunkSize = 4096) {
    std::vector<Msg> out;
    size_t i = 0, n = w.size();
    while (i + 12 <= n) {
        if ((w[i] >> 6) != 0) break;                       // expect a fmt0 chunk
        uint32_t ts  = (uint32_t(w[i+1])<<16)|(uint32_t(w[i+2])<<8)|w[i+3];
        uint32_t len = (uint32_t(w[i+4])<<16)|(uint32_t(w[i+5])<<8)|w[i+6];
        uint8_t  type = w[i+7];
        i += 12;
        bool ext = (ts == 0xFFFFFF);
        if (ext) { if (i+4 > n) break; ts = (uint32_t(w[i])<<24)|(uint32_t(w[i+1])<<16)|(uint32_t(w[i+2])<<8)|w[i+3]; i += 4; }
        Bytes payload; uint32_t remaining = len; bool first = true;
        while (remaining > 0) {
            if (!first) { if (i+1 > n) break; ++i; if (ext) { if (i+4 > n) break; i += 4; } }
            uint32_t take = std::min(chunkSize, remaining);
            if (i + take > n) break;
            payload.insert(payload.end(), w.begin()+i, w.begin()+i+take);
            i += take; remaining -= take; first = false;
        }
        out.push_back({type, ts, std::move(payload)});
    }
    return out;
}
}

// Encoders stamp samples with a boot-based clock (huge); the session must rebase the stream
// to start near 0 while preserving the relative offset between frames.
TEST(StreamSession, NormalizesTimestampsToStreamStart) {
    auto owned = std::make_unique<StubTransport>();
    StubTransport* stub = owned.get();
    PreloadPublishHandshake(*stub);
    StreamParams p; p.host="h"; p.app="app"; p.streamKey="k"; p.tcUrl="rtmp://h/app";
    auto held = std::make_shared<std::unique_ptr<StubTransport>>(std::move(owned));
    StreamSession s(p, [held]() mutable -> std::unique_ptr<Transport> { return std::move(*held); });
    s.Start();
    for (int i = 0; i < 200 && s.state() != SessionState::Live; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    ASSERT_EQ(s.state(), SessionState::Live);

    stub->clear();
    const uint32_t T = 5000000;   // huge boot-based base timestamp
    s.SendVideoConfig({0,0,0,1, 0x67,0x42,0x00,0x1e, 0,0,0,1, 0x68,0xce,0x3c,0x80});
    s.SendVideo({0,0,0,1, 0x65, 0x01}, true, T, T);            // first frame -> establishes base
    s.SendVideo({0,0,0,1, 0x41, 0x02}, false, T + 100, T + 100); // +100 ms
    std::this_thread::sleep_for(std::chrono::milliseconds(80));

    // Capture the wire bytes BEFORE Stop() — Stop() resets (destroys) the transport.
    Bytes wire = stub->written();
    s.Stop();

    std::vector<uint32_t> frameTs;
    for (const auto& m : ParseMessages(wire))
        if (m.type == 0x09 && m.payload.size() >= 2 && m.payload[1] == 0x01) // video NALU (not seq header)
            frameTs.push_back(m.ts);
    ASSERT_EQ(frameTs.size(), 2u);
    EXPECT_EQ(frameTs[0], 0u);     // rebased to stream start
    EXPECT_EQ(frameTs[1], 100u);   // relative offset preserved
}

TEST(StreamSession, BytesSentIncreasesAfterLive) {
    auto owned = std::make_unique<StubTransport>();
    StubTransport* stub = owned.get();
    PreloadPublishHandshake(*stub);
    StreamParams p; p.host="h"; p.app="app"; p.streamKey="k"; p.tcUrl="rtmp://h/app";
    auto held = std::make_shared<std::unique_ptr<StubTransport>>(std::move(owned));
    StreamSession s(p, [held]() mutable -> std::unique_ptr<Transport> { return std::move(*held); });
    s.Start();
    for (int i = 0; i < 200 && s.state() != SessionState::Live; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    ASSERT_EQ(s.state(), SessionState::Live);
    s.SendVideoConfig({0,0,0,1, 0x67,0x42,0x00,0x1e, 0,0,0,1, 0x68,0xce,0x3c,0x80});
    s.SendVideo({0,0,0,1, 0x65, 0x88}, true, 0, 0);
    uint64_t sent = 0;
    for (int i = 0; i < 100 && sent == 0; ++i) {
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        sent = s.bytesSent();
    }
    EXPECT_GT(sent, 0u);
    s.Stop();
}

// An `_error` command — a server rejection at connect (e.g. nginx-rtmp style).
static Bytes SessMakeConnectError() {
    Bytes b; Amf0::String(b,"_error"); Amf0::Number(b,1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b,"level"); Amf0::String(b,"error");
    Amf0::Key(b,"code");  Amf0::String(b,"NetConnection.Connect.Rejected");
    Amf0::ObjectEnd(b);
    return SessMakeCommand(b);
}

// A `_result` whose info object carries level=="error" — the other common connect-rejection
// shape (some servers reject this way instead of via an `_error` command).
static Bytes SessMakeConnectResultError() {
    Bytes b; Amf0::String(b,"_result"); Amf0::Number(b,1); Amf0::Null(b);
    Amf0::ObjectBegin(b);
    Amf0::Key(b,"level"); Amf0::String(b,"error");
    Amf0::Key(b,"code");  Amf0::String(b,"NetConnection.Connect.Rejected");
    Amf0::ObjectEnd(b);
    return SessMakeCommand(b);
}

TEST(StreamSession, ConnectRejectionEndsRejected) {
    auto owned = std::make_unique<StubTransport>();
    StubTransport* stub = owned.get();
    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;
    stub->FeedIncoming(s0s1s2);
    stub->FeedIncoming(SessMakeConnectError());          // server rejects connect
    StreamParams p; p.host="h"; p.app="app"; p.streamKey="k"; p.tcUrl="rtmp://h/app";
    auto held = std::make_shared<std::unique_ptr<StubTransport>>(std::move(owned));
    StreamSession s(p, [held]() mutable -> std::unique_ptr<Transport> { return std::move(*held); });
    s.Start();
    for (int i = 0; i < 200 && s.state() == SessionState::Connecting; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    EXPECT_EQ(s.state(), SessionState::Rejected);
    s.Stop();
}

TEST(StreamSession, ConnectResultLevelErrorEndsRejected) {
    auto owned = std::make_unique<StubTransport>();
    StubTransport* stub = owned.get();
    Bytes s0s1s2(1537 + 1536, 0); s0s1s2[0] = 0x03;
    stub->FeedIncoming(s0s1s2);
    stub->FeedIncoming(SessMakeConnectResultError());    // _result with level==error
    StreamParams p; p.host="h"; p.app="app"; p.streamKey="k"; p.tcUrl="rtmp://h/app";
    auto held = std::make_shared<std::unique_ptr<StubTransport>>(std::move(owned));
    StreamSession s(p, [held]() mutable -> std::unique_ptr<Transport> { return std::move(*held); });
    s.Start();
    for (int i = 0; i < 200 && s.state() == SessionState::Connecting; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    EXPECT_EQ(s.state(), SessionState::Rejected);
    s.Stop();
}

TEST(StreamSession, MidPublishWriteFailureEndsDropped) {
    auto owned = std::make_unique<StubTransport>();
    StubTransport* stub = owned.get();
    PreloadPublishHandshake(*stub);
    StreamParams p; p.host="h"; p.app="app"; p.streamKey="k"; p.tcUrl="rtmp://h/app";
    auto held = std::make_shared<std::unique_ptr<StubTransport>>(std::move(owned));
    StreamSession s(p, [held]() mutable -> std::unique_ptr<Transport> { return std::move(*held); });
    s.Start();
    for (int i = 0; i < 200 && s.state() != SessionState::Live; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    ASSERT_EQ(s.state(), SessionState::Live);
    stub->SetWriteFails(true);                           // socket goes dead
    s.SendVideoConfig({0,0,0,1, 0x67,0x42,0x00,0x1e});
    s.SendVideo({0,0,0,1, 0x65, 0x88}, true, 0, 0);      // this write fails -> Dropped
    for (int i = 0; i < 200 && s.state() == SessionState::Live; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    EXPECT_EQ(s.state(), SessionState::Dropped);
    s.Stop();
}

TEST(StreamSession, GracefulStopWhilePublishingSendsUnpublish) {
    auto owned = std::make_unique<StubTransport>();
    StubTransport* stub = owned.get();
    PreloadPublishHandshake(*stub);
    std::vector<uint8_t> sink;          // test-owned; outlives the session's transport
    stub->SetSink(&sink);
    StreamParams p; p.host="h"; p.app="app"; p.streamKey="streamkey"; p.tcUrl="rtmp://h/app";
    auto held = std::make_shared<std::unique_ptr<StubTransport>>(std::move(owned));
    StreamSession s(p, [held]() mutable -> std::unique_ptr<Transport> { return std::move(*held); });
    s.Start();
    for (int i = 0; i < 200 && s.state() != SessionState::Live; ++i)
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    ASSERT_EQ(s.state(), SessionState::Live);
    // The egress thread sends FCUnpublish/deleteStream/closeStream during Stop(), before close.
    s.Stop();
    EXPECT_EQ(s.state(), SessionState::Idle);
    auto contains = [&](const std::string& cmd) {
        Bytes n = {0x02, (uint8_t)(cmd.size()>>8), (uint8_t)(cmd.size()&0xFF)};
        n.insert(n.end(), cmd.begin(), cmd.end());
        for (size_t i = 0; i + n.size() <= sink.size(); ++i)
            if (std::equal(n.begin(), n.end(), sink.begin()+i)) return true;
        return false;
    };
    EXPECT_TRUE(contains("FCUnpublish"));
    EXPECT_TRUE(contains("deleteStream"));
}
