#pragma once
#include "byte_writer.h"
namespace ps {
Bytes BuildAvcC(const Bytes& sps, const Bytes& pps);
Bytes BuildAsc(int sampleRate, int channels);
Bytes AnnexBToAvcc(const Bytes& annexb);
void  SplitSpsPps(const Bytes& cfg, Bytes& sps, Bytes& pps);
Bytes FlvVideoSeqHeader(const Bytes& avcc);
Bytes FlvVideoFrame(const Bytes& avccNals, bool keyframe, uint32_t compositionTimeMs);
}
