#include "hevc.h"
namespace ps {

// Find Annex-B NAL ranges [start,end) (3- or 4-byte start codes).
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
        if (k + 1 < starts.size()) {
            bool sc4 = (e >= 4 && d[e-1]==1 && d[e-2]==0 && d[e-3]==0 && d[e-4]==0);
            e -= sc4 ? 4 : 3;
        }
        if (e > s) nals.push_back({s, e});
    }
    return nals;
}

void SplitHevcParams(const Bytes& csd, Bytes& vps, Bytes& sps, Bytes& pps) {
    for (auto& r : FindNals(csd)) {
        if (r.second <= r.first) continue;
        uint8_t type = (csd[r.first] >> 1) & 0x3f;   // HEVC: 2-byte NAL header
        Bytes nal(csd.begin() + r.first, csd.begin() + r.second);
        if (type == 32) vps = nal; else if (type == 33) sps = nal; else if (type == 34) pps = nal;
    }
}

static void PutParamArray(Bytes& b, uint8_t nalType, const Bytes& nal) {
    PutU8(b, nalType & 0x3f);          // array_completeness(0)|reserved(0)|NAL_unit_type
    PutU16BE(b, 1);                    // numNalus = 1
    PutU16BE(b, (uint16_t)nal.size());
    PutBytes(b, nal.data(), nal.size());
}

Bytes BuildHvcC(const Bytes& vps, const Bytes& sps, const Bytes& pps) {
    Bytes b;
    PutU8(b, 0x01);                    // configurationVersion
    PutU8(b, 0x01);                    // general_profile_space(0)+tier(0)+profile_idc(1=Main)
    PutU32BE(b, 0x60000000);           // general_profile_compatibility_flags
    PutU8(b, 0x90); PutU8(b, 0); PutU8(b, 0); PutU8(b, 0); PutU8(b, 0); PutU8(b, 0); // constraint flags
    PutU8(b, 0x5d);                    // general_level_idc
    PutU16BE(b, 0xf000);               // reserved+min_spatial_segmentation_idc
    PutU8(b, 0xfc);                    // reserved+parallelismType
    PutU8(b, 0xfd);                    // reserved+chromaFormat(4:2:0)
    PutU8(b, 0xf8);                    // reserved+bitDepthLumaMinus8
    PutU8(b, 0xf8);                    // reserved+bitDepthChromaMinus8
    PutU16BE(b, 0);                    // avgFrameRate
    PutU8(b, 0x0f);                    // constantFrameRate+numTemporalLayers+temporalIdNested+lengthSizeMinusOne(3)
    PutU8(b, 0x03);                    // numOfArrays = 3
    PutParamArray(b, 32, vps);
    PutParamArray(b, 33, sps);
    PutParamArray(b, 34, pps);
    return b;
}
}
