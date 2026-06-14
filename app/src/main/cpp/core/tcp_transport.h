#pragma once
#include "transport.h"
namespace ps {
class TcpTransport : public Transport {
public:
    ~TcpTransport() override { Close(); }
    bool Connect(const std::string& host, uint16_t port) override;
    bool Write(const std::vector<uint8_t>& data) override;
    int  Read(uint8_t* buf, int maxLen) override;
    int  ReadNonBlocking(uint8_t* buf, int maxLen) override;
    void Close() override;
    bool connected() const override { return fd_ >= 0; }
private:
    int fd_ = -1;
};
}
