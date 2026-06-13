#include <cstdio>
#include <cstdlib>
#include <chrono>
#include <fstream>
#include <iterator>
#include <thread>
#include <vector>
#include "rtmp_client.h"
#include "tcp_transport.h"
#include "flv.h"
using namespace ps;

static Bytes ReadFile(const char* path) {
    std::ifstream f(path, std::ios::binary);
    return Bytes((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

// Split an Annex-B elementary stream into raw NAL units (start codes stripped).
static std::vector<Bytes> SplitNals(const Bytes& d) {
    std::vector<size_t> starts;
    size_t i = 0, n = d.size();
    while (i + 3 <= n) {
        bool sc4 = (i + 4 <= n && d[i]==0 && d[i+1]==0 && d[i+2]==0 && d[i+3]==1);
        bool sc3 = (d[i]==0 && d[i+1]==0 && d[i+2]==1);
        if (sc4) { starts.push_back(i + 4); i += 4; }
        else if (sc3) { starts.push_back(i + 3); i += 3; }
        else ++i;
    }
    std::vector<Bytes> nals;
    for (size_t k = 0; k < starts.size(); ++k) {
        size_t s = starts[k];
        size_t e = (k + 1 < starts.size()) ? starts[k + 1] : n;
        if (k + 1 < starts.size()) {
            bool sc4 = (e >= 4 && d[e-1]==1 && d[e-2]==0 && d[e-3]==0 && d[e-4]==0);
            e -= sc4 ? 4 : 3;
        }
        if (e > s) nals.push_back(Bytes(d.begin() + s, d.begin() + e));
    }
    return nals;
}

// Replays an H.264 Annex-B elementary stream to a real RTMP ingest to prove the egress
// core end-to-end: handshake -> connect -> publish, send the SPS/PPS sequence header, then
// pace one VCL NAL per frame at the requested fps. Video-only (audio arrives via MediaCodec
// in M1-B). The acceptance gate is the ingest dashboard going "live / receiving".
int main(int argc, char** argv) {
    if (argc < 5) {
        fprintf(stderr, "usage: rtmp_harness <host> <app> <streamKey> <h264AnnexBFile> [fps=30] [loops=1]\n");
        fprintf(stderr, "  e.g.  rtmp_harness live.twitch.tv app YOUR_KEY /tmp/test.h264 30 3\n");
        return 2;
    }
    StreamParams p;
    p.host = argv[1]; p.app = argv[2]; p.streamKey = argv[3];
    p.tcUrl = std::string("rtmp://") + p.host + "/" + p.app;
    p.port = 1935;
    int fps = (argc >= 6) ? std::atoi(argv[5]) : 30;
    if (fps <= 0) fps = 30;
    int loops = (argc >= 7) ? std::atoi(argv[6]) : 1;
    if (loops <= 0) loops = 1;
    const uint32_t frameMs = (uint32_t)(1000 / fps);

    Bytes h264 = ReadFile(argv[4]);
    if (h264.empty()) { fprintf(stderr, "could not read %s\n", argv[4]); return 1; }
    Bytes sps, pps; SplitSpsPps(h264, sps, pps);
    if (sps.empty() || pps.empty()) { fprintf(stderr, "no SPS/PPS found in %s\n", argv[4]); return 1; }
    std::vector<Bytes> nals = SplitNals(h264);

    TcpTransport t;
    if (!t.Connect(p.host, p.port)) { fprintf(stderr, "connect failed\n"); return 1; }
    RtmpClient c(t, p);
    c.Begin();
    uint8_t buf[8192];
    while (c.state() != RtmpState::Publishing && c.state() != RtmpState::Error) {
        int n = t.Read(buf, sizeof(buf));
        if (n <= 0) { fprintf(stderr, "read failed during setup (state=%d)\n", (int)c.state()); return 1; }
        if (getenv("RTMP_DEBUG")) {
            fprintf(stderr, "[recv %d bytes] first: ", n);
            for (int j = 0; j < n && j < 16; ++j) fprintf(stderr, "%02x ", buf[j]);
            int before = (int)c.state();
            c.OnBytes(Bytes(buf, buf + n));
            fprintf(stderr, "| state %d -> %d\n", before, (int)c.state());
            continue;
        }
        c.OnBytes(Bytes(buf, buf + n));
    }
    if (c.state() == RtmpState::Error) { fprintf(stderr, "RTMP setup rejected by server\n"); return 1; }
    fprintf(stderr, "PUBLISHING streamId=%d\n", c.streamId());

    c.SendVideoConfig(sps, pps);
    fprintf(stderr, "sent video config (sps=%zu pps=%zu bytes), streaming %zu NALs x%d loop(s) @ %d fps\n",
            sps.size(), pps.size(), nals.size(), loops, fps);

    using clock = std::chrono::steady_clock;
    auto start = clock::now();
    uint32_t frameIdx = 0;
    for (int loop = 0; loop < loops; ++loop) {
        for (const Bytes& nal : nals) {
            uint8_t type = nal.empty() ? 0 : (nal[0] & 0x1F);
            if (type != 1 && type != 5) continue;     // only VCL slices are frames
            bool key = (type == 5);                   // IDR
            uint32_t ts = frameIdx * frameMs;
            Bytes annexb = {0, 0, 0, 1};              // wrap as a single Annex-B NAL
            annexb.insert(annexb.end(), nal.begin(), nal.end());
            c.SendVideo(annexb, key, ts, ts);
            if (!t.connected()) { fprintf(stderr, "connection dropped after %u frames\n", frameIdx); return 1; }
            ++frameIdx;
            // pace to wall clock
            auto target = start + std::chrono::milliseconds(ts);
            std::this_thread::sleep_until(target);
        }
    }
    fprintf(stderr, "done: sent %u frames\n", frameIdx);
    return 0;
}
