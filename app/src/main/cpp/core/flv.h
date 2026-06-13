#pragma once
#include "byte_writer.h"
namespace ps {
Bytes BuildAvcC(const Bytes& sps, const Bytes& pps);
Bytes BuildAsc(int sampleRate, int channels);
Bytes AnnexBToAvcc(const Bytes& annexb);
void  SplitSpsPps(const Bytes& cfg, Bytes& sps, Bytes& pps);
}
