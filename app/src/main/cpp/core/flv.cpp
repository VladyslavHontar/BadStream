#include "flv.h"
namespace ps {
Bytes BuildAvcC(const Bytes& sps, const Bytes& pps) {
    if (sps.size() < 4) return {};   // a real SPS is well over 4 bytes; guard codec/network input
    Bytes b;
    PutU8(b, 0x01);            // configurationVersion
    PutU8(b, sps[1]);          // AVCProfileIndication
    PutU8(b, sps[2]);          // profile_compatibility
    PutU8(b, sps[3]);          // AVCLevelIndication
    PutU8(b, 0xFF);            // 6 bits reserved + lengthSizeMinusOne=3
    PutU8(b, 0xE1);            // 3 bits reserved + numSPS=1
    PutU16BE(b, (uint16_t)sps.size()); PutBytes(b, sps.data(), sps.size());
    PutU8(b, 0x01);            // numPPS=1
    PutU16BE(b, (uint16_t)pps.size()); PutBytes(b, pps.data(), pps.size());
    return b;
}
}
