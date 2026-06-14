#pragma once
#include <algorithm>
#include <deque>
#include "transport.h"
namespace ps {
class StubTransport : public Transport {
public:
    bool Connect(const std::string&, uint16_t) override { connected_ = true; return true; }
    bool Write(const std::vector<uint8_t>& d) override { written_.insert(written_.end(), d.begin(), d.end()); return true; }
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
    void Close() override { connected_ = false; }
    bool connected() const override { return connected_; }
    // test helpers
    const std::vector<uint8_t>& written() const { return written_; }
    void clear() { written_.clear(); }
    void FeedIncoming(const std::vector<uint8_t>& d) { seg_.push_back(d); }
private:
    bool connected_ = false;
    std::vector<uint8_t> written_;
    std::deque<std::vector<uint8_t>> seg_;
    size_t spos_ = 0;
};
}
