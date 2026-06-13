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
static int FreqIndex(int sr) {
    switch (sr) { case 96000: return 0; case 88200: return 1; case 64000: return 2;
        case 48000: return 3; case 44100: return 4; case 32000: return 5;
        case 24000: return 6; case 22050: return 7; case 16000: return 8;
        case 12000: return 9; case 11025: return 10; case 8000: return 11; default: return 4; }
}
Bytes BuildAsc(int sampleRate, int channels) {
    int objectType = 2;                         // AAC-LC
    int f = FreqIndex(sampleRate);
    Bytes b;
    PutU8(b, (uint8_t)((objectType << 3) | (f >> 1)));
    PutU8(b, (uint8_t)(((f & 1) << 7) | (channels << 3)));
    return b;
}
}
