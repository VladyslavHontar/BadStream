#include "mp4_writer.h"
#include "mp4_box.h"
#include "flv.h"
#include "hevc.h"

namespace ps {

namespace {
constexpr uint32_t kTimescale = 1000;   // ms timescale for both tracks (PTS arrive in ms)
constexpr uint32_t kVideoTrackId = 1;
constexpr uint32_t kAudioTrackId = 2;

// trun sample flags (ISO/IEC 14496-12 §8.8.3 sample_flags).
constexpr uint32_t kSyncSampleFlags = 0x02000000;     // sample_depends_on = 2 (I-frame)
constexpr uint32_t kNonSyncSampleFlags = 0x01010000;  // depends_on=1 + sample_is_non_sync=1

// Standard 3x3 unity video transformation matrix (16.16 / 2.30 fixed point).
void PutUnityMatrix(Bytes& b) {
    PutU32BE(b, 0x00010000); PutU32BE(b, 0); PutU32BE(b, 0);
    PutU32BE(b, 0); PutU32BE(b, 0x00010000); PutU32BE(b, 0);
    PutU32BE(b, 0); PutU32BE(b, 0); PutU32BE(b, 0x40000000);
}
}  // namespace

bool FragmentedMp4Writer::Start(const std::string& path, int codec, int width, int height,
                                int fps, int sampleRate, int channels) {
    codec_ = codec; width_ = width; height_ = height; fps_ = fps > 0 ? fps : 30;
    sampleRate_ = sampleRate; channels_ = channels;
    file_.open(path, std::ios::binary | std::ios::trunc);
    return file_.is_open();
}

void FragmentedMp4Writer::WriteVideoConfig(const Bytes& csd) {
    if (codec_ == kHevc) {
        Bytes vps, sps, pps;
        SplitHevcParams(csd, vps, sps, pps);
        videoConfig_ = BuildHvcC(vps, sps, pps);
    } else {
        Bytes sps, pps;
        SplitSpsPps(csd, sps, pps);
        videoConfig_ = BuildAvcC(sps, pps);
    }
}

void FragmentedMp4Writer::WriteAudioConfig(int sampleRate, int channels) {
    sampleRate_ = sampleRate; channels_ = channels;
    audioConfig_ = BuildAsc(sampleRate, channels);
}

// ---------------------------------------------------------------------------
// Init segment: ftyp + moov
// ---------------------------------------------------------------------------

void FragmentedMp4Writer::WriteVideoTrak(BoxWriter& w) const {
    size_t trak = w.Begin("trak");
    {
        // tkhd (v0): flags = track_enabled(1) | track_in_movie(2) = 3.
        size_t tkhd = w.BeginFull("tkhd", 0, 0x000003);
        Bytes& b = w.bytes();
        PutU32BE(b, 0);                       // creation_time
        PutU32BE(b, 0);                       // modification_time
        PutU32BE(b, kVideoTrackId);           // track_ID
        PutU32BE(b, 0);                       // reserved
        PutU32BE(b, 0);                       // duration (unknown in fMP4)
        PutU32BE(b, 0); PutU32BE(b, 0);       // reserved[2]
        PutU16BE(b, 0);                       // layer
        PutU16BE(b, 0);                       // alternate_group
        PutU16BE(b, 0);                       // volume (0 for video)
        PutU16BE(b, 0);                       // reserved
        PutUnityMatrix(b);
        PutU32BE(b, (uint32_t)width_ << 16);  // width  16.16
        PutU32BE(b, (uint32_t)height_ << 16); // height 16.16
        w.End(tkhd);
    }
    {
        size_t mdia = w.Begin("mdia");
        {
            size_t mdhd = w.BeginFull("mdhd", 0, 0);
            Bytes& b = w.bytes();
            PutU32BE(b, 0);                   // creation_time
            PutU32BE(b, 0);                   // modification_time
            PutU32BE(b, kTimescale);
            PutU32BE(b, 0);                   // duration
            PutU16BE(b, 0x55C4);              // language = 'und'
            PutU16BE(b, 0);                   // pre_defined
            w.End(mdhd);
        }
        {
            size_t hdlr = w.BeginFull("hdlr", 0, 0);
            Bytes& b = w.bytes();
            PutU32BE(b, 0);                   // pre_defined
            PutBytes(b, (const uint8_t*)"vide", 4);
            PutU32BE(b, 0); PutU32BE(b, 0); PutU32BE(b, 0);  // reserved[3]
            PutBytes(b, (const uint8_t*)"VideoHandler\0", 13);
            w.End(hdlr);
        }
        {
            size_t minf = w.Begin("minf");
            {
                size_t vmhd = w.BeginFull("vmhd", 0, 1);  // flags=1 (required)
                Bytes& b = w.bytes();
                PutU16BE(b, 0);               // graphicsmode
                PutU16BE(b, 0); PutU16BE(b, 0); PutU16BE(b, 0);  // opcolor
                w.End(vmhd);
            }
            {
                size_t dinf = w.Begin("dinf");
                size_t dref = w.BeginFull("dref", 0, 0);
                PutU32BE(w.bytes(), 1);       // entry_count
                size_t url = w.BeginFull("url ", 0, 1);  // flags=1 self-contained
                w.End(url);
                w.End(dref);
                w.End(dinf);
            }
            {
                size_t stbl = w.Begin("stbl");
                {
                    size_t stsd = w.BeginFull("stsd", 0, 0);
                    PutU32BE(w.bytes(), 1);   // entry_count
                    const char* tag = (codec_ == kHevc) ? "hvc1" : "avc1";
                    size_t se = w.Begin(tag);
                    Bytes& b = w.bytes();
                    // VisualSampleEntry
                    for (int i = 0; i < 6; ++i) PutU8(b, 0);   // reserved[6]
                    PutU16BE(b, 1);           // data_reference_index
                    PutU16BE(b, 0);           // pre_defined
                    PutU16BE(b, 0);           // reserved
                    PutU32BE(b, 0); PutU32BE(b, 0); PutU32BE(b, 0);  // pre_defined[3]
                    PutU16BE(b, (uint16_t)width_);
                    PutU16BE(b, (uint16_t)height_);
                    PutU32BE(b, 0x00480000);  // horizresolution 72dpi
                    PutU32BE(b, 0x00480000);  // vertresolution
                    PutU32BE(b, 0);           // reserved
                    PutU16BE(b, 1);           // frame_count
                    for (int i = 0; i < 32; ++i) PutU8(b, 0);  // compressorname
                    PutU16BE(b, 0x0018);      // depth = 24
                    PutU16BE(b, 0xFFFF);      // pre_defined = -1
                    // codec config box (avcC / hvcC)
                    const char* cfgTag = (codec_ == kHevc) ? "hvcC" : "avcC";
                    size_t cfg = w.Begin(cfgTag);
                    PutBytes(b, videoConfig_.data(), videoConfig_.size());
                    w.End(cfg);
                    w.End(se);
                    w.End(stsd);
                }
                // Empty sample tables (required for fMP4: all samples live in fragments).
                { size_t x = w.BeginFull("stts", 0, 0); PutU32BE(w.bytes(), 0); w.End(x); }
                { size_t x = w.BeginFull("stsc", 0, 0); PutU32BE(w.bytes(), 0); w.End(x); }
                { size_t x = w.BeginFull("stsz", 0, 0); PutU32BE(w.bytes(), 0); PutU32BE(w.bytes(), 0); w.End(x); }
                { size_t x = w.BeginFull("stco", 0, 0); PutU32BE(w.bytes(), 0); w.End(x); }
                w.End(stbl);
            }
            w.End(minf);
        }
        w.End(mdia);
    }
    w.End(trak);
}

void FragmentedMp4Writer::WriteAudioTrak(BoxWriter& w) const {
    size_t trak = w.Begin("trak");
    {
        size_t tkhd = w.BeginFull("tkhd", 0, 0x000003);
        Bytes& b = w.bytes();
        PutU32BE(b, 0); PutU32BE(b, 0);
        PutU32BE(b, kAudioTrackId);
        PutU32BE(b, 0);
        PutU32BE(b, 0);                       // duration
        PutU32BE(b, 0); PutU32BE(b, 0);
        PutU16BE(b, 0);                       // layer
        PutU16BE(b, 0);                       // alternate_group
        PutU16BE(b, 0x0100);                  // volume 1.0 (8.8)
        PutU16BE(b, 0);
        PutUnityMatrix(b);
        PutU32BE(b, 0);                       // width 0
        PutU32BE(b, 0);                       // height 0
        w.End(tkhd);
    }
    {
        size_t mdia = w.Begin("mdia");
        {
            size_t mdhd = w.BeginFull("mdhd", 0, 0);
            Bytes& b = w.bytes();
            PutU32BE(b, 0); PutU32BE(b, 0);
            PutU32BE(b, kTimescale);
            PutU32BE(b, 0);
            PutU16BE(b, 0x55C4);
            PutU16BE(b, 0);
            w.End(mdhd);
        }
        {
            size_t hdlr = w.BeginFull("hdlr", 0, 0);
            Bytes& b = w.bytes();
            PutU32BE(b, 0);
            PutBytes(b, (const uint8_t*)"soun", 4);
            PutU32BE(b, 0); PutU32BE(b, 0); PutU32BE(b, 0);
            PutBytes(b, (const uint8_t*)"SoundHandler\0", 13);
            w.End(hdlr);
        }
        {
            size_t minf = w.Begin("minf");
            {
                size_t smhd = w.BeginFull("smhd", 0, 0);
                PutU16BE(w.bytes(), 0);       // balance
                PutU16BE(w.bytes(), 0);       // reserved
                w.End(smhd);
            }
            {
                size_t dinf = w.Begin("dinf");
                size_t dref = w.BeginFull("dref", 0, 0);
                PutU32BE(w.bytes(), 1);
                size_t url = w.BeginFull("url ", 0, 1);
                w.End(url);
                w.End(dref);
                w.End(dinf);
            }
            {
                size_t stbl = w.Begin("stbl");
                {
                    size_t stsd = w.BeginFull("stsd", 0, 0);
                    PutU32BE(w.bytes(), 1);
                    size_t mp4a = w.Begin("mp4a");
                    Bytes& b = w.bytes();
                    // AudioSampleEntry
                    for (int i = 0; i < 6; ++i) PutU8(b, 0);   // reserved[6]
                    PutU16BE(b, 1);           // data_reference_index
                    PutU32BE(b, 0); PutU32BE(b, 0);            // reserved[2]
                    PutU16BE(b, (uint16_t)channels_);
                    PutU16BE(b, 16);          // samplesize
                    PutU16BE(b, 0);           // pre_defined
                    PutU16BE(b, 0);           // reserved
                    PutU32BE(b, (uint32_t)sampleRate_ << 16);  // samplerate 16.16
                    // esds (ES_Descriptor wrapping the ASC)
                    size_t esds = w.BeginFull("esds", 0, 0);
                    const Bytes& asc = audioConfig_;
                    // ES_Descriptor (tag 0x03)
                    uint8_t dcdPayload = (uint8_t)(13 + 2 + asc.size());  // DecoderConfig body+ASC header
                    PutU8(b, 0x03);
                    PutU8(b, (uint8_t)(3 + 2 + dcdPayload + 3));  // ES_Descriptor length
                    PutU16BE(b, 0);           // ES_ID
                    PutU8(b, 0);              // flags
                    // DecoderConfigDescriptor (tag 0x04)
                    PutU8(b, 0x04);
                    PutU8(b, (uint8_t)(13 + 2 + asc.size()));
                    PutU8(b, 0x40);           // objectTypeIndication = AAC
                    PutU8(b, 0x15);           // streamType=5 audio (<<2)|upStream(0)|reserved(1)
                    PutU24BE(b, 0);           // bufferSizeDB
                    PutU32BE(b, 0);           // maxBitrate
                    PutU32BE(b, 0);           // avgBitrate
                    // DecoderSpecificInfo (tag 0x05) = ASC
                    PutU8(b, 0x05);
                    PutU8(b, (uint8_t)asc.size());
                    PutBytes(b, asc.data(), asc.size());
                    // SLConfigDescriptor (tag 0x06)
                    PutU8(b, 0x06);
                    PutU8(b, 1);
                    PutU8(b, 0x02);
                    w.End(esds);
                    w.End(mp4a);
                    w.End(stsd);
                }
                { size_t x = w.BeginFull("stts", 0, 0); PutU32BE(w.bytes(), 0); w.End(x); }
                { size_t x = w.BeginFull("stsc", 0, 0); PutU32BE(w.bytes(), 0); w.End(x); }
                { size_t x = w.BeginFull("stsz", 0, 0); PutU32BE(w.bytes(), 0); PutU32BE(w.bytes(), 0); w.End(x); }
                { size_t x = w.BeginFull("stco", 0, 0); PutU32BE(w.bytes(), 0); w.End(x); }
                w.End(stbl);
            }
            w.End(minf);
        }
        w.End(mdia);
    }
    w.End(trak);
}

Bytes FragmentedMp4Writer::BuildInitSegment() const {
    Bytes out;
    BoxWriter w(out);
    // ftyp
    {
        size_t ftyp = w.Begin("ftyp");
        PutBytes(out, (const uint8_t*)"iso5", 4);  // major_brand
        PutU32BE(out, 0x00000200);                 // minor_version
        PutBytes(out, (const uint8_t*)"iso5", 4);  // compatible_brands...
        PutBytes(out, (const uint8_t*)"iso6", 4);
        PutBytes(out, (const uint8_t*)"mp41", 4);
        PutBytes(out, (const uint8_t*)"mp42", 4);
        PutBytes(out, (const uint8_t*)"dash", 4);
        w.End(ftyp);
    }
    // moov
    {
        size_t moov = w.Begin("moov");
        {
            size_t mvhd = w.BeginFull("mvhd", 0, 0);
            PutU32BE(out, 0); PutU32BE(out, 0);   // creation/modification time
            PutU32BE(out, kTimescale);
            PutU32BE(out, 0);                     // duration (unknown)
            PutU32BE(out, 0x00010000);            // rate 1.0
            PutU16BE(out, 0x0100);                // volume 1.0
            PutU16BE(out, 0);                     // reserved
            PutU32BE(out, 0); PutU32BE(out, 0);   // reserved[2]
            PutUnityMatrix(out);
            for (int i = 0; i < 6; ++i) PutU32BE(out, 0);  // pre_defined[6]
            PutU32BE(out, 3);                     // next_track_ID
            w.End(mvhd);
        }
        WriteVideoTrak(w);
        WriteAudioTrak(w);
        {
            size_t mvex = w.Begin("mvex");
            for (uint32_t id : {kVideoTrackId, kAudioTrackId}) {
                size_t trex = w.BeginFull("trex", 0, 0);
                PutU32BE(out, id);                // track_ID
                PutU32BE(out, 1);                 // default_sample_description_index
                PutU32BE(out, 0);                 // default_sample_duration
                PutU32BE(out, 0);                 // default_sample_size
                PutU32BE(out, 0);                 // default_sample_flags
                w.End(trex);
            }
            w.End(mvex);
        }
        w.End(moov);
    }
    return out;
}

// ---------------------------------------------------------------------------
// Fragments: moof + mdat
// ---------------------------------------------------------------------------

void FragmentedMp4Writer::OpenInitIfNeeded() {
    if (initWritten_) return;
    // A valid init segment needs the video decoder config (avcC/hvcC). In practice the
    // encoder emits CODEC_CONFIG before any frame, so WriteVideoConfig always runs first;
    // guard defensively so we never emit an init (and fragments) with a zero-length config.
    if (videoConfig_.empty()) return;
    Bytes init = BuildInitSegment();
    if (file_.is_open()) {
        file_.write((const char*)init.data(), (std::streamsize)init.size());
        file_.flush();
    }
    initWritten_ = true;
}

namespace {
// Per-sample durations: diff of consecutive PTS within the fragment; the final sample uses
// the supplied default (so a fragment closes with a sensible duration for its last sample).
std::vector<uint32_t> Durations(const std::vector<FragmentedMp4Writer::Sample>& s,
                                uint32_t defaultDur);
}  // namespace

void FragmentedMp4Writer::FlushFragment() {
    if (video_.empty() && audio_.empty()) return;
    OpenInitIfNeeded();
    if (!initWritten_) return;   // init not ready (no video config yet) — don't emit orphan fragments

    uint32_t videoDefault = 1000u / (uint32_t)(fps_ > 0 ? fps_ : 30);
    if (videoDefault == 0) videoDefault = 1;

    // Audio frame duration default: AAC = 1024 samples / sampleRate, in ms.
    uint32_t audioDefault = sampleRate_ > 0
        ? (uint32_t)((1024.0 * 1000.0) / sampleRate_ + 0.5) : 21;
    if (audioDefault == 0) audioDefault = 1;

    std::vector<uint32_t> vdur = Durations(video_, videoDefault);
    std::vector<uint32_t> adur = Durations(audio_, audioDefault);

    Bytes out;
    BoxWriter w(out);

    // --- moof ---
    size_t moof = w.Begin("moof");
    {
        size_t mfhd = w.BeginFull("mfhd", 0, 0);
        PutU32BE(out, ++seq_);                // sequence_number (1-based)
        w.End(mfhd);
    }

    // We need each trun's data_offset to point at the first byte of that track's samples,
    // relative to the start of the moof. mdat payload starts at (moofSize + 8). Video samples
    // come first, then audio. We backpatch the data_offset fields after we know moofSize.
    // Sentinel (not 0): patching position 0 would corrupt the moof size field. Each is only
    // patched when its track actually wrote a trun (guarded below), so an unset patch stays inert.
    const size_t kNoPatch = static_cast<size_t>(-1);
    size_t videoDataOffsetPatch = kNoPatch;
    size_t audioDataOffsetPatch = kNoPatch;

    // video traf
    if (!video_.empty()) {
        size_t traf = w.Begin("traf");
        {
            // tfhd: default-base-is-moof (0x020000). No optional fields.
            size_t tfhd = w.BeginFull("tfhd", 0, 0x020000);
            PutU32BE(out, kVideoTrackId);
            w.End(tfhd);
        }
        {
            size_t tfdt = w.BeginFull("tfdt", 1, 0);  // version 1 => 64-bit baseMediaDecodeTime
            PutU32BE(out, (uint32_t)(videoBaseDecode_ >> 32));
            PutU32BE(out, (uint32_t)(videoBaseDecode_ & 0xFFFFFFFF));
            w.End(tfdt);
        }
        {
            // trun flags: data-offset(0x1)|sample-duration(0x100)|sample-size(0x200)|sample-flags(0x400)
            size_t trun = w.BeginFull("trun", 0, 0x000701);
            PutU32BE(out, (uint32_t)video_.size());   // sample_count
            videoDataOffsetPatch = out.size();
            PutU32BE(out, 0);                          // data_offset (patched below)
            for (size_t i = 0; i < video_.size(); ++i) {
                PutU32BE(out, vdur[i]);
                PutU32BE(out, (uint32_t)video_[i].data.size());
                PutU32BE(out, video_[i].key ? kSyncSampleFlags : kNonSyncSampleFlags);
            }
            w.End(trun);
        }
        w.End(traf);
    }

    // audio traf
    if (!audio_.empty()) {
        size_t traf = w.Begin("traf");
        {
            size_t tfhd = w.BeginFull("tfhd", 0, 0x020000);
            PutU32BE(out, kAudioTrackId);
            w.End(tfhd);
        }
        {
            size_t tfdt = w.BeginFull("tfdt", 1, 0);
            PutU32BE(out, (uint32_t)(audioBaseDecode_ >> 32));
            PutU32BE(out, (uint32_t)(audioBaseDecode_ & 0xFFFFFFFF));
            w.End(tfdt);
        }
        {
            size_t trun = w.BeginFull("trun", 0, 0x000701);
            PutU32BE(out, (uint32_t)audio_.size());
            audioDataOffsetPatch = out.size();
            PutU32BE(out, 0);
            for (size_t i = 0; i < audio_.size(); ++i) {
                PutU32BE(out, adur[i]);
                PutU32BE(out, (uint32_t)audio_[i].data.size());
                PutU32BE(out, kSyncSampleFlags);       // audio: every frame independent
            }
            w.End(trun);
        }
        w.End(traf);
    }
    w.End(moof);

    uint32_t moofSize = (uint32_t)out.size();
    // mdat payload begins at moofSize + 8 (mdat header) from the start of the moof.
    uint32_t videoDataStart = moofSize + 8;
    uint32_t videoBytes = 0;
    for (auto& s : video_) videoBytes += (uint32_t)s.data.size();
    uint32_t audioDataStart = videoDataStart + videoBytes;

    auto patch32 = [&](size_t pos, uint32_t v) {
        out[pos]   = (uint8_t)((v >> 24) & 0xFF);
        out[pos+1] = (uint8_t)((v >> 16) & 0xFF);
        out[pos+2] = (uint8_t)((v >> 8) & 0xFF);
        out[pos+3] = (uint8_t)(v & 0xFF);
    };
    if (videoDataOffsetPatch != kNoPatch) patch32(videoDataOffsetPatch, videoDataStart);
    if (audioDataOffsetPatch != kNoPatch) patch32(audioDataOffsetPatch, audioDataStart);

    // --- mdat ---
    size_t mdat = w.Begin("mdat");
    for (auto& s : video_) PutBytes(out, s.data.data(), s.data.size());
    for (auto& s : audio_) PutBytes(out, s.data.data(), s.data.size());
    w.End(mdat);

    // Advance per-track decode timelines for the next fragment.
    for (uint32_t d : vdur) videoBaseDecode_ += d;
    for (uint32_t d : adur) audioBaseDecode_ += d;

    if (file_.is_open()) {
        file_.write((const char*)out.data(), (std::streamsize)out.size());
        file_.flush();   // crash-safe: each completed fragment is on disk
    }
    video_.clear();
    audio_.clear();
    fragmentOpen_ = false;
}

void FragmentedMp4Writer::WriteVideo(const Bytes& annexb, bool keyframe, uint32_t ptsMs) {
    if (!fragmentOpen_) {
        if (!keyframe) return;            // can't begin a fragment mid-GOP
        fragmentOpen_ = true;
        fragStartPts_ = ptsMs;
    } else if (keyframe && (ptsMs - fragStartPts_) >= 2000) {
        FlushFragment();                  // close previous fragment
        fragmentOpen_ = true;
        fragStartPts_ = ptsMs;
    }
    video_.push_back(Sample{AnnexBToAvcc(annexb), ptsMs, keyframe});
}

void FragmentedMp4Writer::WriteAudio(const Bytes& aac, uint32_t ptsMs) {
    if (!fragmentOpen_) return;           // drop audio until the first video keyframe opens a frag
    audio_.push_back(Sample{aac, ptsMs, true});
}

void FragmentedMp4Writer::Stop() {
    if (fragmentOpen_ || !video_.empty() || !audio_.empty()) FlushFragment();
    if (file_.is_open()) { file_.flush(); file_.close(); }
}

namespace {
std::vector<uint32_t> Durations(const std::vector<FragmentedMp4Writer::Sample>& s,
                                uint32_t defaultDur) {
    std::vector<uint32_t> d(s.size(), defaultDur);
    for (size_t i = 0; i + 1 < s.size(); ++i) {
        uint32_t delta = (s[i+1].pts >= s[i].pts) ? (s[i+1].pts - s[i].pts) : defaultDur;
        d[i] = delta == 0 ? defaultDur : delta;
    }
    return d;
}
}  // namespace

}  // namespace ps
