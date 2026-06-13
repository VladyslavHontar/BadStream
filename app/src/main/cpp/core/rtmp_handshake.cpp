#include "rtmp_handshake.h"
namespace ps {

Bytes RtmpHandshake::BuildC0C1() {
    Bytes b;
    b.reserve(1 + kSig);
    PutU8(b, 0x03);            // C0: protocol version
    PutU32BE(b, 0);            // C1: time (4 bytes, all zero)
    PutU32BE(b, 0);            // C1: zero (4 bytes, all zero)
    for (int i = 0; i < kSig - 8; ++i)
        PutU8(b, (uint8_t)(i & 0xFF)); // C1: deterministic "random" payload (1528 bytes)
    return b;
}

Bytes RtmpHandshake::BuildC2(const Bytes& s0s1) {
    if (s0s1.size() < 1537u) return {};
    Bytes b;
    b.reserve(kSig);
    PutBytes(b, s0s1.data() + 1, 4);          // S1 time: bytes [1..4] of s0s1
    PutU32BE(b, 0);                            // echo time: 4 zero bytes
    PutBytes(b, s0s1.data() + 9, kSig - 8);   // S1 random: bytes [9..1536] of s0s1 (1528 bytes)
    return b;
}

}
