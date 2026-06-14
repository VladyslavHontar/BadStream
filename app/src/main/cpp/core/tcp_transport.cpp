#include "tcp_transport.h"
#include <cerrno>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <sys/socket.h>
#include <unistd.h>
#include <string>
namespace ps {
// Suppress SIGPIPE on a write to a dropped connection: Linux/Android use the
// MSG_NOSIGNAL send() flag; macOS/BSD use the SO_NOSIGPIPE socket option. Without
// this a peer disconnect would kill the whole process instead of returning an error.
#ifdef MSG_NOSIGNAL
static constexpr int kSendFlags = MSG_NOSIGNAL;
#else
static constexpr int kSendFlags = 0;
#endif
bool TcpTransport::Connect(const std::string& host, uint16_t port) {
    if (fd_ >= 0) Close();   // re-Connect (e.g. reconnect) must not leak the old fd
    addrinfo hints{}; hints.ai_family = AF_INET; hints.ai_socktype = SOCK_STREAM;
    addrinfo* res = nullptr;
    if (getaddrinfo(host.c_str(), std::to_string(port).c_str(), &hints, &res) != 0) return false;
    for (addrinfo* a = res; a; a = a->ai_next) {
        fd_ = socket(a->ai_family, a->ai_socktype, a->ai_protocol);
        if (fd_ < 0) continue;
        int one = 1; setsockopt(fd_, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
#ifdef SO_NOSIGPIPE
        setsockopt(fd_, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one));   // macOS/BSD
#endif
        if (connect(fd_, a->ai_addr, a->ai_addrlen) == 0) { freeaddrinfo(res); return true; }
        ::close(fd_); fd_ = -1;
    }
    freeaddrinfo(res); return false;
}
bool TcpTransport::Write(const std::vector<uint8_t>& d) {
    size_t off = 0;
    while (off < d.size()) {
        ssize_t n = ::send(fd_, d.data() + off, d.size() - off, kSendFlags);
        if (n <= 0) return false;
        off += (size_t)n;
    }
    return true;
}
int TcpTransport::Read(uint8_t* buf, int maxLen) { return (int)::recv(fd_, buf, maxLen, 0); }
int TcpTransport::ReadNonBlocking(uint8_t* buf, int maxLen) {
    if (fd_ < 0) return -1;
    ssize_t n = ::recv(fd_, buf, maxLen, MSG_DONTWAIT);
    if (n > 0) return (int)n;
    if (n == 0) return -1;                                  // peer closed
    if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;  // nothing available now
    return -1;                                              // real error
}
void TcpTransport::Close() { if (fd_ >= 0) { ::close(fd_); fd_ = -1; } }
}
