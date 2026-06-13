#include "amf0.h"
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
}
