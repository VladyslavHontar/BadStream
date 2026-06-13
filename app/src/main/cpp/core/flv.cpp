#include "flv.h"
#include "amf0.h"
#include <vector>
#include <utility>
namespace ps {
// Find start-code positions (3- or 4-byte). Returns NAL byte ranges [start,end).
static std::vector<std::pair<size_t,size_t>> FindNals(const Bytes& d) {
    std::vector<size_t> starts; size_t i = 0, n = d.size();
    while (i + 3 <= n) {
        bool sc4 = (i + 4 <= n && d[i]==0 && d[i+1]==0 && d[i+2]==0 && d[i+3]==1);
        bool sc3 = (d[i]==0 && d[i+1]==0 && d[i+2]==1);
        if (sc4) { starts.push_back(i + 4); i += 4; }
        else if (sc3) { starts.push_back(i + 3); i += 3; }
        else ++i;
    }
    std::vector<std::pair<size_t,size_t>> nals;
    for (size_t k = 0; k < starts.size(); ++k) {
        size_t s = starts[k];
        size_t e = (k + 1 < starts.size()) ? starts[k+1] : n;
        // back up over the next NAL's start code (3 or 4 bytes).
        // NOTE: heuristic — a NAL whose data ends in 0x00,0x00 right before a 3-byte
        // start code may have one trailing data byte mis-trimmed. Safe for MediaCodec
        // output (M1); revisit before feeding arbitrary bitstreams (e.g. M5 feed-in).
        if (k + 1 < starts.size()) {
            bool sc4 = (e >= 4 && d[e-1]==1 && d[e-2]==0 && d[e-3]==0 && d[e-4]==0);
            e -= sc4 ? 4 : 3;
        }
        nals.push_back({s, e});
    }
    return nals;
}
Bytes AnnexBToAvcc(const Bytes& annexb) {
    Bytes out;
    for (auto& r : FindNals(annexb)) {
        size_t s = r.first, e = r.second;
        PutU32BE(out, (uint32_t)(e - s));
        PutBytes(out, annexb.data() + s, e - s);
    }
    return out;
}
void SplitSpsPps(const Bytes& cfg, Bytes& sps, Bytes& pps) {
    for (auto& r : FindNals(cfg)) {
        size_t s = r.first, e = r.second;
        uint8_t type = cfg[s] & 0x1F;
        Bytes nal(cfg.begin() + s, cfg.begin() + e);
        if (type == 7) sps = nal; else if (type == 8) pps = nal;
    }
}
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
Bytes FlvVideoSeqHeader(const Bytes& avcc) {
    Bytes b; PutU8(b, 0x17); PutU8(b, 0x00); PutU24BE(b, 0);
    PutBytes(b, avcc.data(), avcc.size()); return b;
}
Bytes FlvVideoFrame(const Bytes& avccNals, bool keyframe, uint32_t cts) {
    Bytes b; PutU8(b, keyframe ? 0x17 : 0x27); PutU8(b, 0x01); PutU24BE(b, cts);
    PutBytes(b, avccNals.data(), avccNals.size()); return b;
}
Bytes FlvAudioSeqHeader(const Bytes& asc) {
    Bytes b; PutU8(b, 0xAF); PutU8(b, 0x00); PutBytes(b, asc.data(), asc.size()); return b;
}
Bytes FlvAudioFrame(const Bytes& aacRaw) {
    Bytes b; PutU8(b, 0xAF); PutU8(b, 0x01); PutBytes(b, aacRaw.data(), aacRaw.size()); return b;
}
Bytes BuildOnMetaData(int w, int h, double fps, int sampleRate) {
    Bytes b;
    Amf0::String(b, "@setDataFrame");
    Amf0::String(b, "onMetaData");
    Amf0::EcmaArrayBegin(b, 6);
    Amf0::Key(b, "width");           Amf0::Number(b, w);
    Amf0::Key(b, "height");          Amf0::Number(b, h);
    Amf0::Key(b, "framerate");       Amf0::Number(b, fps);
    Amf0::Key(b, "videocodecid");    Amf0::Number(b, 7);   // AVC
    Amf0::Key(b, "audiocodecid");    Amf0::Number(b, 10);  // AAC
    Amf0::Key(b, "audiosamplerate"); Amf0::Number(b, sampleRate);
    Amf0::ObjectEnd(b);
    return b;
}
}
