#pragma once
#include <string>
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
    static void ObjectEnd(Bytes& b);                        // 00 00 09
    static std::string FindStringValue(const Bytes& obj, const std::string& key);
};

class Amf0Reader {
public:
    Amf0Reader(const uint8_t* p, size_t n) : p_(p), n_(n), i_(0) {}
    std::string ReadString();   // expects 0x02 marker
    double ReadNumber();        // expects 0x00 marker
    bool eof() const { return i_ >= n_; }
private:
    const uint8_t* p_; size_t n_; size_t i_;
    uint16_t u16() { if (i_ + 2 > n_) { i_ = n_; return 0; } uint16_t v = (p_[i_] << 8) | p_[i_+1]; i_ += 2; return v; }
};
}
