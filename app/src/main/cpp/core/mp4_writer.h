#pragma once
#include <cstdint>
#include <fstream>
#include <string>
#include <vector>
#include "byte_writer.h"

namespace ps {

// Writes a single crash-safe fragmented-MP4 (.mp4) file incrementally: an init segment
// (ftyp + moov with empty sample tables + mvex/trex) once, then a moof+mdat fragment roughly
// every ~2s aligned to video keyframes. Reuses flv.cpp / hevc.cpp helpers for AVCC conversion
// and the avcC/hvcC/ASC decoder-config records. The class owns no threads; callers feed
// already-encoded samples (the same onConfig/onFrame taps used for RTMP egress).
//
// Codec int matches the project's Codec enum / VideoCodecType.nativeFlag: 0 = AVC, 1 = HEVC.
class FragmentedMp4Writer {
public:
    enum VideoCodec { kAvc = 0, kHevc = 1 };

    FragmentedMp4Writer() = default;
    ~FragmentedMp4Writer() { Stop(); }

    // Opens `path` for writing. Returns false if the file can't be created.
    bool Start(const std::string& path, int codec, int width, int height, int fps,
               int sampleRate, int channels);

    // Stash the video decoder-config record from the encoder CSD (raw SPS+PPS / VPS+SPS+PPS
    // Annex-B blob). Must be called before the first WriteVideo.
    void WriteVideoConfig(const Bytes& csd);

    // Stash the audio decoder-config record (AAC AudioSpecificConfig from BuildAsc).
    void WriteAudioConfig(int sampleRate, int channels);

    // Append one encoded video sample (Annex-B). The first keyframe starts recording; frames
    // before it are dropped (can't begin mid-GOP). A keyframe >= ~2000ms after the current
    // fragment start opens a new fragment (the previous one is flushed to disk first).
    void WriteVideo(const Bytes& annexb, bool keyframe, uint32_t ptsMs);

    // Append one encoded AAC access unit, bucketed into the current fragment by PTS.
    void WriteAudio(const Bytes& aac, uint32_t ptsMs);

    // Flush the open fragment (if any) and close the file.
    void Stop();

    // --- Test seams (pure byte builders; no file I/O) ---
    // Returns the init segment (ftyp + moov) for the current params/configs.
    Bytes InitSegmentForTest() const { return BuildInitSegment(); }

    struct Sample { Bytes data; uint32_t pts; bool key; };

private:
    Bytes BuildInitSegment() const;
    void WriteVideoTrak(class BoxWriter& w) const;
    void WriteAudioTrak(class BoxWriter& w) const;
    void FlushFragment();           // emit moof+mdat for the buffered samples, then clear
    void OpenInitIfNeeded();        // write ftyp+moov to file exactly once

    std::ofstream file_;
    int codec_ = kAvc;
    int width_ = 0, height_ = 0, fps_ = 30;
    int sampleRate_ = 44100, channels_ = 2;
    Bytes videoConfig_;             // avcC or hvcC
    Bytes audioConfig_;             // ASC

    bool initWritten_ = false;
    bool fragmentOpen_ = false;
    uint32_t fragStartPts_ = 0;
    uint32_t seq_ = 0;              // moof sequence_number (1-based)
    uint64_t videoBaseDecode_ = 0; // baseMediaDecodeTime accumulator (video track)
    uint64_t audioBaseDecode_ = 0; // baseMediaDecodeTime accumulator (audio track)
    std::vector<Sample> video_;    // current fragment video samples (AVCC bytes)
    std::vector<Sample> audio_;    // current fragment audio samples (raw AAC bytes)
};

}  // namespace ps
