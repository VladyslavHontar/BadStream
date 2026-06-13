#pragma once
#include <cstdint>
#include <cstring>
#include <vector>
namespace ps {
using Bytes = std::vector<uint8_t>;
inline void PutU8(Bytes& b, uint8_t v) { b.push_back(v); }
inline void PutU16BE(Bytes& b, uint16_t v) { b.push_back(v >> 8); b.push_back(v & 0xFF); }
inline void PutU24BE(Bytes& b, uint32_t v) { b.push_back((v >> 16) & 0xFF); b.push_back((v >> 8) & 0xFF); b.push_back(v & 0xFF); }
inline void PutU32BE(Bytes& b, uint32_t v) { b.push_back((v >> 24) & 0xFF); b.push_back((v >> 16) & 0xFF); b.push_back((v >> 8) & 0xFF); b.push_back(v & 0xFF); }
inline void PutU32LE(Bytes& b, uint32_t v) { b.push_back(v & 0xFF); b.push_back((v >> 8) & 0xFF); b.push_back((v >> 16) & 0xFF); b.push_back((v >> 24) & 0xFF); }
inline void PutDoubleBE(Bytes& b, double v) {
    uint64_t bits; std::memcpy(&bits, &v, 8);
    for (int i = 7; i >= 0; --i) b.push_back((bits >> (i * 8)) & 0xFF);
}
inline void PutBytes(Bytes& b, const uint8_t* p, size_t n) { b.insert(b.end(), p, p + n); }
}
