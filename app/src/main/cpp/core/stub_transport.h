#pragma once
#include <algorithm>
#include <deque>
#include "transport.h"
namespace ps {
class StubTransport : public Transport {
public:
    bool Connect(const std::string&, uint16_t) override { connected_ = true; return true; }
    bool Write(const std::vector<uint8_t>& d) override {
        if (failWrites_) return false;
        written_.insert(written_.end(), d.begin(), d.end());
        if (sink_) sink_->insert(sink_->end(), d.begin(), d.end());   // mirror to test-owned sink
        return true;
    }
    // Read returns at most one queued FeedIncoming segment per call, mimicking a real
    // socket that delivers bytes in arrival-sized chunks (so a buffered-then-process state
    // machine advances one chunk per read). Returns 0 once all segments are drained.
    int Read(uint8_t* buf, int maxLen) override {
        if (seg_.empty()) return 0;
        std::vector<uint8_t>& front = seg_.front();
        int n = std::min((int)(front.size() - spos_), maxLen);
        for (int i = 0; i < n; ++i) buf[i] = front[spos_++];
        if (spos_ >= front.size()) { seg_.pop_front(); spos_ = 0; }
        return n;
    }
    // Same delivery model as Read: returns queued bytes or 0 when nothing is queued.
    // 0 means "nothing now" (not a drop), so the publish loop keeps running.
    int ReadNonBlocking(uint8_t* buf, int maxLen) override { return Read(buf, maxLen); }
    void Close() override { connected_ = false; }
    bool connected() const override { return connected_; }
    // test helpers
    const std::vector<uint8_t>& written() const { return written_; }
    void clear() { written_.clear(); }
    void FeedIncoming(const std::vector<uint8_t>& d) { seg_.push_back(d); }
    void SetWriteFails(bool v) { failWrites_ = v; }
    // Mirror every Write into a test-owned buffer that outlives this transport — lets a test
    // assert on bytes written *during* StreamSession::Stop() (which destroys the transport).
    void SetSink(std::vector<uint8_t>* s) { sink_ = s; }
private:
    bool connected_ = false;
    std::vector<uint8_t> written_;
    std::deque<std::vector<uint8_t>> seg_;
    size_t spos_ = 0;
    bool failWrites_ = false;
    std::vector<uint8_t>* sink_ = nullptr;
};
}
