#pragma once
#include "byte_writer.h"
namespace ps {
// Encode a full RTMP message (type-0 first chunk + type-3 continuations) into bytes.
Bytes ChunkEncode(uint8_t csid, uint8_t msgType, uint32_t msgStreamId,
                  uint32_t timestamp, const Bytes& payload, uint32_t chunkSize);
}
