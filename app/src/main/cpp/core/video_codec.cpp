#include "video_codec.h"
#include "flv.h"
#include "hevc.h"
namespace ps {

Bytes AvcCodec::SequenceHeader(const Bytes& csd) {
    Bytes sps, pps; SplitSpsPps(csd, sps, pps);
    return FlvVideoSeqHeader(BuildAvcC(sps, pps));
}
Bytes AvcCodec::Frame(const Bytes& annexb, bool key, uint32_t cts) {
    return FlvVideoFrame(AnnexBToAvcc(annexb), key, cts);
}

static void PutFourCc(Bytes& b, const char* cc) { for (int i = 0; i < 4; ++i) PutU8(b, (uint8_t)cc[i]); }

Bytes HevcCodec::SequenceHeader(const Bytes& csd) {
    Bytes vps, sps, pps; SplitHevcParams(csd, vps, sps, pps);
    Bytes b;
    PutU8(b, 0x80 | 0x10 | 0x00);               // IsExHeader | key | SequenceStart
    PutFourCc(b, "hvc1");
    Bytes hvcc = BuildHvcC(vps, sps, pps);
    PutBytes(b, hvcc.data(), hvcc.size());
    return b;
}
Bytes HevcCodec::Frame(const Bytes& annexb, bool key, uint32_t cts) {
    Bytes b;
    PutU8(b, 0x80 | (key ? 0x10 : 0x20) | 0x01); // IsExHeader | frameType | CodedFrames
    PutFourCc(b, "hvc1");
    PutU24BE(b, cts);
    Bytes nalus = AnnexBToAvcc(annexb);          // 4-byte length-prefixed NALUs
    PutBytes(b, nalus.data(), nalus.size());
    return b;
}
}
