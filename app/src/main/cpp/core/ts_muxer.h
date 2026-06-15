#pragma once
#include "byte_writer.h"
#include <cstdint>

namespace ps {

enum class VideoCodecKind { Avc, Hevc };

// Single-program MPEG-TS muxer. Produces 188-byte transport packets from encoded
// Annex-B video access units and raw AAC frames. Host-testable: NO libsrt, NO networking.
//
// PIDs: PAT=0x0000, PMT=0x0FFF, video=0x0100 (PCR PID), audio=0x0101.
// stream_type: AVC=0x1B, HEVC=0x24, AAC-ADTS=0x0F.
class TsMuxer {
public:
    TsMuxer();

    void SetVideo(VideoCodecKind kind);
    void SetAudio(int sampleRate, int channels);

    // Append a video access unit (Annex-B). Returns produced TS bytes (multiple of 188).
    // PTS = ptsMs*90, DTS = dtsMs*90 (90kHz). DTS is emitted only when dtsMs != ptsMs.
    Bytes WriteVideo(const Bytes& annexb, bool keyframe, uint32_t ptsMs, uint32_t dtsMs);

    // Append a raw AAC frame; wrapped in a 7-byte ADTS header. PTS = ptsMs*90.
    Bytes WriteAudio(const Bytes& aac, uint32_t ptsMs);

private:
    static constexpr int kPatPid = 0x0000;
    static constexpr int kPmtPid = 0x0FFF;
    static constexpr int kVideoPid = 0x0100;
    static constexpr int kAudioPid = 0x0101;

    void MaybeEmitPsi(Bytes& out, uint32_t ptsMs);
    void EmitPat(Bytes& out);
    void EmitPmt(Bytes& out);

    // Packetize one PES payload onto a PID, optionally carrying PCR (video PID only).
    void WritePes(Bytes& out, int pid, uint8_t streamId, const Bytes& payload,
                  bool pusiHasPts, uint64_t pts90, bool hasDts, uint64_t dts90,
                  bool withPcr, uint64_t pcrBase90);

    VideoCodecKind videoKind_ = VideoCodecKind::Avc;
    bool hasVideo_ = false;
    bool hasAudio_ = false;
    int sampleRate_ = 44100;
    int channels_ = 2;

    bool psiEmitted_ = false;
    int64_t lastPsiPtsMs_ = -1;
    int64_t lastPcrPtsMs_ = -1;

    uint8_t ccPat_ = 0;
    uint8_t ccPmt_ = 0;
    uint8_t ccVideo_ = 0;
    uint8_t ccAudio_ = 0;
    bool firstVideoKeyframeSent_ = false;
};

}  // namespace ps
