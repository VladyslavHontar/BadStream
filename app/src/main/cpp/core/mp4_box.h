#pragma once
#include "byte_writer.h"
namespace ps {

// Minimal ISO-BMFF box writer. Each box is `size(4) + type(4) + body`. Because a box's
// size isn't known until its children are written, Begin() emits a placeholder size and
// returns the offset; End(offset) backpatches size = (current end - offset).
class BoxWriter {
public:
    explicit BoxWriter(Bytes& out) : b_(out) {}

    // Writes placeholder size 0x00000000 + the 4-byte type; returns the box's start offset.
    size_t Begin(const char tag[4]) {
        size_t pos = b_.size();
        PutU32BE(b_, 0);                                  // size placeholder
        b_.push_back((uint8_t)tag[0]); b_.push_back((uint8_t)tag[1]);
        b_.push_back((uint8_t)tag[2]); b_.push_back((uint8_t)tag[3]);
        return pos;
    }

    // Like Begin() but immediately appends a FullBox header: 1-byte version + 24-bit flags.
    size_t BeginFull(const char tag[4], uint8_t version, uint32_t flags) {
        size_t pos = Begin(tag);
        PutU8(b_, version);
        PutU24BE(b_, flags);
        return pos;
    }

    // Backpatch the 4-byte size field at `pos` with the total bytes written since.
    void End(size_t pos) {
        uint32_t size = (uint32_t)(b_.size() - pos);
        b_[pos]     = (uint8_t)((size >> 24) & 0xFF);
        b_[pos + 1] = (uint8_t)((size >> 16) & 0xFF);
        b_[pos + 2] = (uint8_t)((size >> 8) & 0xFF);
        b_[pos + 3] = (uint8_t)(size & 0xFF);
    }

    Bytes& bytes() { return b_; }

private:
    Bytes& b_;
};

}  // namespace ps
