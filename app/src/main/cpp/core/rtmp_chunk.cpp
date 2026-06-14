#include "rtmp_chunk.h"
#include <algorithm>
namespace ps {
Bytes ChunkEncode(uint8_t csid, uint8_t msgType, uint32_t msgStreamId,
                  uint32_t timestamp, const Bytes& payload, uint32_t chunkSize) {
    Bytes b;
    bool ext = timestamp >= 0xFFFFFF;
    PutU8(b, (0 << 6) | (csid & 0x3F));            // fmt0 basic header
    PutU24BE(b, ext ? 0xFFFFFF : timestamp);       // timestamp / marker
    PutU24BE(b, (uint32_t)payload.size());         // message length
    PutU8(b, msgType);                             // message type id
    PutU32LE(b, msgStreamId);                      // stream id (little-endian!)
    if (ext) PutU32BE(b, timestamp);               // extended timestamp
    // payload, split across chunks
    size_t off = 0, n = payload.size();
    size_t first = std::min((size_t)chunkSize, n);
    PutBytes(b, payload.data(), first); off = first;
    while (off < n) {
        PutU8(b, (3 << 6) | (csid & 0x3F));        // fmt3 continuation
        if (ext) PutU32BE(b, timestamp);           // spec: fmt3 chunks repeat the extended ts
        size_t take = std::min((size_t)chunkSize, n - off);
        PutBytes(b, payload.data() + off, take); off += take;
    }
    return b;
}
}
