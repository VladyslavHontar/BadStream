#pragma once
#include <algorithm>
#include "transport.h"
namespace ps {
class StubTransport : public Transport {
public:
    bool Connect(const std::string&, uint16_t) override { connected_ = true; return true; }
    bool Write(const std::vector<uint8_t>& d) override { written_.insert(written_.end(), d.begin(), d.end()); return true; }
    int Read(uint8_t* buf, int maxLen) override {
        int n = std::min((int)(incoming_.size() - rpos_), maxLen);
        for (int i = 0; i < n; ++i) buf[i] = incoming_[rpos_++];
        return n;
    }
    void Close() override { connected_ = false; }
    bool connected() const override { return connected_; }
    // test helpers
    const std::vector<uint8_t>& written() const { return written_; }
    void clear() { written_.clear(); }
    void FeedIncoming(const std::vector<uint8_t>& d) { incoming_.insert(incoming_.end(), d.begin(), d.end()); }
private:
    bool connected_ = false;
    std::vector<uint8_t> written_, incoming_;
    size_t rpos_ = 0;
};
}
