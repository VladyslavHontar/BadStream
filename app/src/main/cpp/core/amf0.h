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
};
}
