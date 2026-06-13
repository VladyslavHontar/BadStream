#pragma once
#include "byte_writer.h"
namespace ps {
class RtmpHandshake {
public:
    static constexpr int kSig = 1536;
    Bytes BuildC0C1();                     // 1537 bytes: C0(1) + C1(1536)
    Bytes BuildC2(const Bytes& s0s1);      // 1536 bytes; returns {} if s0s1 < 1537
};
}
