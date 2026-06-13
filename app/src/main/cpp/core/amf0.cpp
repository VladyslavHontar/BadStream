#include "amf0.h"
#include <cstring>
namespace ps {
void Amf0::Key(Bytes& b, const std::string& s) {
    PutU16BE(b, (uint16_t)s.size());
    PutBytes(b, (const uint8_t*)s.data(), s.size());
}
void Amf0::Number(Bytes& b, double v) { PutU8(b, 0x00); PutDoubleBE(b, v); }
void Amf0::Boolean(Bytes& b, bool v) { PutU8(b, 0x01); PutU8(b, v ? 1 : 0); }
void Amf0::String(Bytes& b, const std::string& s) { PutU8(b, 0x02); Key(b, s); }
void Amf0::Null(Bytes& b) { PutU8(b, 0x05); }
void Amf0::ObjectBegin(Bytes& b) { PutU8(b, 0x03); }
void Amf0::EcmaArrayBegin(Bytes& b, uint32_t count) { PutU8(b, 0x08); PutU32BE(b, count); }
void Amf0::ObjectEnd(Bytes& b) { PutU8(b, 0x00); PutU8(b, 0x00); PutU8(b, 0x09); }

std::string Amf0Reader::ReadString() {
    if (i_ >= n_ || p_[i_] != 0x02) return {};
    ++i_; uint16_t len = u16();
    if (i_ + len > n_) { i_ = n_; return {}; }
    std::string s((const char*)p_ + i_, len); i_ += len; return s;
}
double Amf0Reader::ReadNumber() {
    if (i_ >= n_ || p_[i_] != 0x00) return 0; ++i_;
    if (i_ + 8 > n_) { i_ = n_; return 0; }
    uint64_t bits = 0; for (int k = 0; k < 8; ++k) bits = (bits << 8) | p_[i_++];
    double d; std::memcpy(&d, &bits, 8); return d;
}
void Amf0Reader::SkipValue() {
    if (i_ >= n_) return;
    uint8_t m = p_[i_++];
    if (m == 0x00) { i_ = (i_ + 8 <= n_) ? i_ + 8 : n_; }            // number
    else if (m == 0x01) { i_ = (i_ + 1 <= n_) ? i_ + 1 : n_; }       // bool
    else if (m == 0x02) { uint16_t l = u16(); i_ = (i_ + l <= n_) ? i_ + l : n_; } // string
    else if (m == 0x05 || m == 0x06) { /* null/undefined: marker only */ }
    else { i_ = n_; }                                                 // object/other: stop
}
// Scan an AMF0 payload for `key` whose value is a string. Finds the first object
// (0x03 marker) in the payload, then scans its key-value pairs. Handles
// string/number/bool/null values so it can skip past them to find the wanted key.
std::string Amf0::FindStringValue(const Bytes& obj, const std::string& key) {
    size_t i = 0, n = obj.size();
    // Scan forward to find a 0x03 object marker
    while (i < n && obj[i] != 0x03) ++i;
    if (i >= n) return {};
    ++i;                                          // skip the 0x03 marker
    while (i + 2 <= n) {
        uint16_t klen = (obj[i] << 8) | obj[i+1]; i += 2;
        if (klen == 0) break;                     // object end
        if (i + klen > n) break;
        std::string k((const char*)&obj[i], klen); i += klen;
        if (i >= n) break;
        uint8_t marker = obj[i++];
        if (marker == 0x02) {                     // string value
            if (i + 2 > n) break;
            uint16_t vlen = (obj[i] << 8) | obj[i+1]; i += 2;
            if (i + vlen > n) break;
            std::string v((const char*)&obj[i], vlen); i += vlen;
            if (k == key) return v;
        } else if (marker == 0x00) { if (i + 8 > n) break; i += 8; }  // number
        else if (marker == 0x01) { if (i + 1 > n) break; i += 1; }    // bool
        else if (marker == 0x05) { /* null */ }
        else break;                                // unknown -> stop
    }
    return {};
}
}
