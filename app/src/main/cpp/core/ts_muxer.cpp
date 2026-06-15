#include "ts_muxer.h"
#include "flv.h"     // SplitSpsPps
#include "hevc.h"    // SplitHevcParams
#include <vector>
#include <utility>

namespace ps {

// ---- CRC32 (MPEG-2 / ISO-13818-1 PSI sections) ----
static uint32_t Crc32Mpeg2(const uint8_t* data, size_t len) {
    uint32_t crc = 0xFFFFFFFFu;
    for (size_t i = 0; i < len; ++i) {
        crc ^= (uint32_t)data[i] << 24;
        for (int b = 0; b < 8; ++b) {
            if (crc & 0x80000000u) crc = (crc << 1) ^ 0x04C11DB7u;
            else crc <<= 1;
        }
    }
    return crc;
}

// ---- Annex-B NAL scanning (local copy mirroring flv.cpp/hevc.cpp) ----
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

static void AppendStartCode4(Bytes& b) { b.push_back(0); b.push_back(0); b.push_back(0); b.push_back(1); }

// ---- ADTS header (7 bytes, no CRC) ----
static int AdtsFreqIndex(int sr) {
    switch (sr) { case 96000: return 0; case 88200: return 1; case 64000: return 2;
        case 48000: return 3; case 44100: return 4; case 32000: return 5;
        case 24000: return 6; case 22050: return 7; case 16000: return 8;
        case 12000: return 9; case 11025: return 10; case 8000: return 11; default: return 4; }
}

static Bytes WrapAdts(const Bytes& aac, int sampleRate, int channels) {
    int profile = 1;                 // AAC-LC -> MPEG-4 Audio Object Type 2, ADTS profile = AOT-1
    int freqIdx = AdtsFreqIndex(sampleRate);
    int chanCfg = channels;
    int frameLen = (int)aac.size() + 7;
    Bytes h;
    h.push_back(0xFF);
    h.push_back(0xF1);               // MPEG-4, layer 0, no CRC (protection_absent=1)
    h.push_back((uint8_t)((profile << 6) | (freqIdx << 2) | ((chanCfg >> 2) & 0x1)));
    h.push_back((uint8_t)(((chanCfg & 0x3) << 6) | ((frameLen >> 11) & 0x3)));
    h.push_back((uint8_t)((frameLen >> 3) & 0xFF));
    h.push_back((uint8_t)(((frameLen & 0x7) << 5) | 0x1F));
    h.push_back(0xFC);               // buffer fullness (VBR) low + 0 raw data blocks
    Bytes out = h;
    out.insert(out.end(), aac.begin(), aac.end());
    return out;
}

TsMuxer::TsMuxer() = default;

void TsMuxer::SetVideo(VideoCodecKind kind) { videoKind_ = kind; hasVideo_ = true; }
void TsMuxer::SetAudio(int sampleRate, int channels) {
    hasAudio_ = true; sampleRate_ = sampleRate; channels_ = channels;
}

// ---- PSI section emission (one full section per 188-byte packet) ----
static void EmitSectionPacket(Bytes& out, int pid, uint8_t& cc, const Bytes& section) {
    // TS header: sync, PUSI=1, PID, afc=01 (payload only), CC
    Bytes pkt;
    pkt.push_back(0x47);
    pkt.push_back((uint8_t)(0x40 | ((pid >> 8) & 0x1F)));
    pkt.push_back((uint8_t)(pid & 0xFF));
    pkt.push_back((uint8_t)(0x10 | (cc & 0x0F)));
    cc = (cc + 1) & 0x0F;
    pkt.push_back(0x00);             // pointer_field = 0
    pkt.insert(pkt.end(), section.begin(), section.end());
    // pad with 0xFF to 188
    while (pkt.size() < 188) pkt.push_back(0xFF);
    out.insert(out.end(), pkt.begin(), pkt.end());
}

void TsMuxer::EmitPat(Bytes& out) {
    // Section body (everything covered by CRC starts at table_id).
    Bytes s;
    s.push_back(0x00);               // table_id = PAT
    // section_syntax(1)=1, '0', reserved '11', section_length(12) -> fill later
    s.push_back(0xB0);
    s.push_back(0x00);               // length placeholder
    PutU16BE(s, 0x0001);             // transport_stream_id
    s.push_back(0xC1);               // reserved'11' version=0 current_next=1
    s.push_back(0x00);               // section_number
    s.push_back(0x00);               // last_section_number
    // program loop
    PutU16BE(s, 0x0001);             // program_number = 1
    PutU16BE(s, (uint16_t)(0xE000 | kPmtPid));  // reserved'111' + PMT PID
    // section_length = bytes from after the length field to end of CRC
    int sectionLen = (int)s.size() - 3 + 4;     // remaining body + CRC32
    s[1] = (uint8_t)(0xB0 | ((sectionLen >> 8) & 0x0F));
    s[2] = (uint8_t)(sectionLen & 0xFF);
    uint32_t crc = Crc32Mpeg2(s.data(), s.size());
    PutU32BE(s, crc);
    EmitSectionPacket(out, kPatPid, ccPat_, s);
}

void TsMuxer::EmitPmt(Bytes& out) {
    Bytes s;
    s.push_back(0x02);               // table_id = PMT
    s.push_back(0xB0);
    s.push_back(0x00);               // length placeholder
    PutU16BE(s, 0x0001);             // program_number
    s.push_back(0xC1);               // version=0 current_next=1
    s.push_back(0x00);               // section_number
    s.push_back(0x00);               // last_section_number
    PutU16BE(s, (uint16_t)(0xE000 | kVideoPid));   // reserved'111' + PCR_PID = video
    PutU16BE(s, 0xF000);             // reserved'1111' + program_info_length = 0
    // ES loop: video
    if (hasVideo_) {
        uint8_t st = (videoKind_ == VideoCodecKind::Hevc) ? 0x24 : 0x1B;
        s.push_back(st);
        PutU16BE(s, (uint16_t)(0xE000 | kVideoPid));   // reserved + elementary PID
        PutU16BE(s, 0xF000);          // reserved + ES_info_length = 0
    }
    if (hasAudio_) {
        s.push_back(0x0F);            // AAC ADTS
        PutU16BE(s, (uint16_t)(0xE000 | kAudioPid));
        PutU16BE(s, 0xF000);
    }
    int sectionLen = (int)s.size() - 3 + 4;
    s[1] = (uint8_t)(0xB0 | ((sectionLen >> 8) & 0x0F));
    s[2] = (uint8_t)(sectionLen & 0xFF);
    uint32_t crc = Crc32Mpeg2(s.data(), s.size());
    PutU32BE(s, crc);
    EmitSectionPacket(out, kPmtPid, ccPmt_, s);
}

void TsMuxer::MaybeEmitPsi(Bytes& out, uint32_t ptsMs) {
    bool due = !psiEmitted_ ||
               lastPsiPtsMs_ < 0 ||
               ((int64_t)ptsMs - lastPsiPtsMs_) >= 100 ||
               (int64_t)ptsMs < lastPsiPtsMs_;   // wrap/restart
    if (!due) return;
    EmitPat(out);
    EmitPmt(out);
    psiEmitted_ = true;
    lastPsiPtsMs_ = ptsMs;
}

// Encode a 5-byte PTS/DTS field with the given marker nibble (0010 or 0011).
static void PutTimestamp(Bytes& b, uint8_t markerNibble, uint64_t ts90) {
    b.push_back((uint8_t)((markerNibble << 4) | (((ts90 >> 30) & 0x7) << 1) | 1));
    b.push_back((uint8_t)((ts90 >> 22) & 0xFF));
    b.push_back((uint8_t)((((ts90 >> 15) & 0x7F) << 1) | 1));
    b.push_back((uint8_t)((ts90 >> 7) & 0xFF));
    b.push_back((uint8_t)(((ts90 & 0x7F) << 1) | 1));
}

void TsMuxer::WritePes(Bytes& out, int pid, uint8_t streamId, const Bytes& payload,
                       bool /*pusiHasPts*/, uint64_t pts90, bool hasDts, uint64_t dts90,
                       bool withPcr, uint64_t pcrBase90) {
    // Build the PES packet (header + payload).
    Bytes pes;
    pes.push_back(0x00); pes.push_back(0x00); pes.push_back(0x01);  // start code prefix
    pes.push_back(streamId);
    int ptsDtsFlags = hasDts ? 3 : 2;
    int headerDataLen = hasDts ? 10 : 5;
    // PES_packet_length: for video (unbounded slices) use 0; for audio set the real length.
    int pesPacketLen = 0;
    if (streamId == 0xC0) {
        pesPacketLen = 3 /*flags+hdrlen*/ + headerDataLen + (int)payload.size();
        if (pesPacketLen > 0xFFFF) pesPacketLen = 0;   // overflow -> unbounded
    }
    PutU16BE(pes, (uint16_t)pesPacketLen);
    pes.push_back(0x80);             // '10' marker, no scrambling/priority
    pes.push_back((uint8_t)(ptsDtsFlags << 6));
    pes.push_back((uint8_t)headerDataLen);
    PutTimestamp(pes, hasDts ? 0x3 : 0x2, pts90);
    if (hasDts) PutTimestamp(pes, 0x1, dts90);
    pes.insert(pes.end(), payload.begin(), payload.end());

    // Packetize into 188-byte TS packets.
    uint8_t& cc = (pid == kVideoPid) ? ccVideo_ : ccAudio_;
    size_t pos = 0;
    bool first = true;
    while (pos < pes.size()) {
        Bytes pkt;
        pkt.push_back(0x47);
        uint8_t b1 = (uint8_t)((pid >> 8) & 0x1F);
        if (first) b1 |= 0x40;       // PUSI
        pkt.push_back(b1);
        pkt.push_back((uint8_t)(pid & 0xFF));

        bool needPcr = first && withPcr;
        size_t remaining = pes.size() - pos;
        // Reserve adaptation field if PCR needed or stuffing needed (last short packet).
        // Compute available payload space without adaptation field.
        size_t headerLen = 4;
        size_t avail = 188 - headerLen;

        bool useAdaptation = needPcr;
        // Determine if we must stuff (payload smaller than available -> need adaptation for padding).
        size_t pcrAfBytes = needPcr ? 8 : 0;   // af_length(1)+flags(1)+6 PCR bytes

        // First compute whether remaining fits and if stuffing is required.
        size_t payloadCapacity;
        if (useAdaptation) {
            payloadCapacity = 188 - headerLen - pcrAfBytes;
        } else {
            payloadCapacity = avail;
        }

        if (remaining < payloadCapacity) {
            // Need stuffing: switch to adaptation field with stuffing bytes.
            useAdaptation = true;
        }

        uint8_t afc;
        if (useAdaptation) afc = 0x30;       // adaptation + payload
        else afc = 0x10;                     // payload only
        pkt.push_back((uint8_t)(afc | (cc & 0x0F)));
        cc = (cc + 1) & 0x0F;

        if (useAdaptation) {
            // Figure out how many payload bytes we will place this packet.
            // Reserve af_length(1). The remaining 187-? bytes split between AF body and payload.
            // Build AF flags + optional PCR; then stuff so that header+AF+payload == 188.
            Bytes af;
            uint8_t flags = 0;
            if (needPcr) flags |= 0x10;       // PCR_flag
            af.push_back(flags);
            if (needPcr) {
                uint64_t base = pcrBase90;     // already in 90kHz (== 27MHz/300) units
                uint32_t ext = 0;
                uint64_t base33 = base & 0x1FFFFFFFFull;
                af.push_back((uint8_t)((base33 >> 25) & 0xFF));
                af.push_back((uint8_t)((base33 >> 17) & 0xFF));
                af.push_back((uint8_t)((base33 >> 9) & 0xFF));
                af.push_back((uint8_t)((base33 >> 1) & 0xFF));
                af.push_back((uint8_t)(((base33 & 1) << 7) | 0x7E | ((ext >> 8) & 0x1)));
                af.push_back((uint8_t)(ext & 0xFF));
            }
            // payload bytes available = 188 - 4(header) - 1(af_length) - af.size()
            size_t maxPayload = 188 - 4 - 1 - af.size();
            size_t take = remaining < maxPayload ? remaining : maxPayload;
            size_t stuffing = maxPayload - take;   // pad to fill packet
            uint8_t afLength = (uint8_t)(af.size() + stuffing);
            pkt.push_back(afLength);
            pkt.insert(pkt.end(), af.begin(), af.end());
            for (size_t k = 0; k < stuffing; ++k) pkt.push_back(0xFF);
            pkt.insert(pkt.end(), pes.begin() + pos, pes.begin() + pos + take);
            pos += take;
        } else {
            size_t take = remaining < payloadCapacity ? remaining : payloadCapacity;
            pkt.insert(pkt.end(), pes.begin() + pos, pes.begin() + pos + take);
            pos += take;
        }
        // pkt is exactly 188 by construction
        out.insert(out.end(), pkt.begin(), pkt.end());
        first = false;
    }
}

Bytes TsMuxer::WriteVideo(const Bytes& annexb, bool keyframe, uint32_t ptsMs, uint32_t dtsMs) {
    Bytes out;
    MaybeEmitPsi(out, ptsMs);

    // Build the PES payload as Annex-B with an AUD prefix; inject in-band params on first keyframe.
    Bytes payload;
    // Access Unit Delimiter (helps some demuxers; codec-specific type).
    if (videoKind_ == VideoCodecKind::Avc) {
        AppendStartCode4(payload);
        payload.push_back(0x09); payload.push_back(0xF0);   // AUD, primary_pic_type=7
    } else {
        AppendStartCode4(payload);
        payload.push_back(0x46); payload.push_back(0x01); payload.push_back(0x50);  // HEVC AUD (type 35)
    }

    bool injectParams = keyframe && !firstVideoKeyframeSent_;
    // Inject parameter sets in-band before the keyframe NALs (extracted from this AU if present;
    // MediaCodec keyframes already carry them, but we ensure presence on the first key AU).
    // We simply pass the access unit NALs through; if it's the first keyframe and contains
    // params, they're already in-band. To be safe, re-prefix split params if found.
    if (injectParams) {
        if (videoKind_ == VideoCodecKind::Avc) {
            Bytes sps, pps; SplitSpsPps(annexb, sps, pps);
            if (!sps.empty()) { AppendStartCode4(payload); payload.insert(payload.end(), sps.begin(), sps.end()); }
            if (!pps.empty()) { AppendStartCode4(payload); payload.insert(payload.end(), pps.begin(), pps.end()); }
        } else {
            Bytes vps, sps, pps; SplitHevcParams(annexb, vps, sps, pps);
            if (!vps.empty()) { AppendStartCode4(payload); payload.insert(payload.end(), vps.begin(), vps.end()); }
            if (!sps.empty()) { AppendStartCode4(payload); payload.insert(payload.end(), sps.begin(), sps.end()); }
            if (!pps.empty()) { AppendStartCode4(payload); payload.insert(payload.end(), pps.begin(), pps.end()); }
        }
        firstVideoKeyframeSent_ = true;
    }

    // Emit the slice/coded NALs (skip param sets to avoid duplication; AUD already added).
    for (auto& r : FindNals(annexb)) {
        size_t s = r.first, e = r.second;
        uint8_t type;
        if (videoKind_ == VideoCodecKind::Avc) {
            type = annexb[s] & 0x1F;
            if (type == 7 || type == 8 || type == 9) continue;  // skip SPS/PPS/AUD (handled)
        } else {
            type = (annexb[s] >> 1) & 0x3F;
            if (type == 32 || type == 33 || type == 34 || type == 35) continue; // VPS/SPS/PPS/AUD
        }
        AppendStartCode4(payload);
        payload.insert(payload.end(), annexb.begin() + s, annexb.begin() + e);
    }

    uint64_t pts90 = (uint64_t)ptsMs * 90;
    uint64_t dts90 = (uint64_t)dtsMs * 90;
    bool hasDts = (dtsMs != ptsMs);

    // PCR refreshed at a bounded media-time interval (<100ms) and on the first packet.
    bool withPcr = (lastPcrPtsMs_ < 0) || ((int64_t)ptsMs - lastPcrPtsMs_) >= 40 ||
                   (int64_t)ptsMs < lastPcrPtsMs_;
    uint64_t pcrBase = hasDts ? dts90 : pts90;
    if (withPcr) lastPcrPtsMs_ = ptsMs;

    WritePes(out, kVideoPid, 0xE0, payload, true, pts90, hasDts, dts90, withPcr, pcrBase);
    return out;
}

Bytes TsMuxer::WriteAudio(const Bytes& aac, uint32_t ptsMs) {
    Bytes out;
    MaybeEmitPsi(out, ptsMs);
    Bytes adts = WrapAdts(aac, sampleRate_, channels_);
    uint64_t pts90 = (uint64_t)ptsMs * 90;
    WritePes(out, kAudioPid, 0xC0, adts, true, pts90, false, 0, false, 0);
    return out;
}

}  // namespace ps
