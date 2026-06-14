#pragma once
#include "byte_writer.h"
namespace ps {
// Split an HEVC Annex-B CSD blob into VPS/SPS/PPS NAL units (start codes stripped).
void SplitHevcParams(const Bytes& csd, Bytes& vps, Bytes& sps, Bytes& pps);
// Build a HEVCDecoderConfigurationRecord (hvcC) from VPS/SPS/PPS.
Bytes BuildHvcC(const Bytes& vps, const Bytes& sps, const Bytes& pps);
}
