#pragma once
#include "byte_writer.h"
namespace ps {
class VideoCodec {
public:
    virtual ~VideoCodec() = default;
    virtual Bytes SequenceHeader(const Bytes& csd) = 0;                  // FLV video message payload
    virtual Bytes Frame(const Bytes& annexb, bool key, uint32_t cts) = 0;
    virtual const char* FourCc() const = 0;
};
class AvcCodec : public VideoCodec {
public:
    Bytes SequenceHeader(const Bytes& csd) override;
    Bytes Frame(const Bytes& annexb, bool key, uint32_t cts) override;
    const char* FourCc() const override { return "avc1"; }
};
class HevcCodec : public VideoCodec {
public:
    Bytes SequenceHeader(const Bytes& csd) override;
    Bytes Frame(const Bytes& annexb, bool key, uint32_t cts) override;
    const char* FourCc() const override { return "hvc1"; }
};
}
