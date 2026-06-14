#pragma once
#include <cstdint>
#include <string>
#include <vector>
namespace ps {
// Byte pipe to a server. Implementations: TcpTransport (real), StubTransport (tests).
class Transport {
public:
    virtual ~Transport() = default;
    virtual bool Connect(const std::string& host, uint16_t port) = 0;
    virtual bool Write(const std::vector<uint8_t>& data) = 0;
    virtual int  Read(uint8_t* buf, int maxLen) = 0;   // blocking; >0 bytes, 0 closed, <0 error
    // Non-blocking read: >0 bytes available now, 0 nothing available right now (not an error),
    // <0 peer closed or error. Used to service the inbound control channel while publishing
    // without blocking the egress thread.
    virtual int ReadNonBlocking(uint8_t* buf, int maxLen) = 0;
    virtual void Close() = 0;
    virtual bool connected() const = 0;
};
}
