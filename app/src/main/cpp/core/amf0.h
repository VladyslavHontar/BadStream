#pragma once
#include <string>
#include <functional>
#include "byte_writer.h"
namespace ps {
struct Amf0 {
    static void Number(Bytes& b, double v);
    static void Boolean(Bytes& b, bool v);
    static void String(Bytes& b, const std::string& s);     // with 0x02 marker
    static void Key(Bytes& b, const std::string& s);        // bare u16-len string, NO marker
    static void Null(Bytes& b);
    static void ObjectBegin(Bytes& b);                      // 0x03
    static void EcmaArrayBegin(Bytes& b, uint32_t count);   // 0x08 + count
    static void StrictArrayBegin(Bytes& b, uint32_t count);  // AMF0 strict array: 0x0A + U32 count, then `count` values
    static void ObjectEnd(Bytes& b);                        // 00 00 09
    static std::string FindStringValue(const Bytes& obj, const std::string& key);
};

class Amf0Reader {
public:
    Amf0Reader(const uint8_t* p, size_t n) : p_(p), n_(n), i_(0) {}
    std::string ReadString();   // expects 0x02 marker
    double ReadNumber();        // expects 0x00 marker
    void SkipValue();
    bool eof() const { return i_ >= n_; }
    uint8_t PeekMarker() const { return (i_ < n_) ? p_[i_] : 0xFF; }

    // Enumerate key->value pairs of an AMF0 object (0x03) or ECMA-array (0x08) that starts
    // at the current position (marker byte already consumed by caller, ECMA count also consumed
    // by caller). For each pair the visitor receives the key string and this reader positioned
    // at the value marker. The visitor must consume or skip the value (e.g. call SkipValue()).
    // Returns false early if the visitor returns false. Stops at 0x00 0x00 0x09 object-end
    // or truncation.
    void ForEachProperty(std::function<bool(const std::string&, Amf0Reader&)> visitor);

    // Read a strict array (0x0A). The marker byte must NOT yet be consumed — this method
    // consumes it, then the U32 count, then calls visitor(reader) for each element positioned
    // at the element's marker byte. Visitor must consume or skip the value.
    // Returns early if visitor returns false or input is truncated.
    void ForEachArrayElement(std::function<bool(Amf0Reader&)> visitor);

    // Consume one byte (the already-peeked marker). No-op if at end.
    void ConsumeMarker() { if (i_ < n_) ++i_; }
    // Read a big-endian U32; bounds-checked (returns 0 and sets eof on truncation).
    uint32_t ReadU32BE() { return u32(); }

private:
    const uint8_t* p_; size_t n_; size_t i_;
    uint16_t u16() { if (i_ + 2 > n_) { i_ = n_; return 0; } uint16_t v = (p_[i_] << 8) | p_[i_+1]; i_ += 2; return v; }
    uint32_t u32() {
        if (i_ + 4 > n_) { i_ = n_; return 0; }
        uint32_t v = ((uint32_t)p_[i_]<<24)|((uint32_t)p_[i_+1]<<16)|((uint32_t)p_[i_+2]<<8)|p_[i_+3];
        i_ += 4; return v;
    }
};

// True if the connect _result AMF0 payload advertises the given video FourCC (e.g. "hvc1")
// in either the v1 fourCcList strict-array or the v2 videoFourCcInfoMap object/ECMA property.
// Walks AMF0 command values after skipping the "_result" name and transaction number.
bool VideoCodecAdvertised(const Bytes& resultPayload, const std::string& fourCc);
}
