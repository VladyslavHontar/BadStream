#include <cstdio>
#include <fstream>
#include <iterator>
#include <vector>
#include "rtmp_client.h"
#include "tcp_transport.h"
#include "flv.h"
using namespace ps;
static Bytes ReadFile(const char* path) {
    std::ifstream f(path, std::ios::binary);
    return Bytes((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}
// Proves the core against a real RTMP ingest: completes handshake + connect + publish,
// then sends the H.264 sequence header (SPS/PPS). Full paced AU + AAC streaming is the
// next increment; the acceptance gate is the ingest dashboard reporting "receiving".
int main(int argc, char** argv) {
    if (argc < 5) {
        fprintf(stderr, "usage: rtmp_harness <host> <app> <streamKey> <h264AnnexBFile>\n");
        fprintf(stderr, "  e.g.  rtmp_harness live.twitch.tv app YOUR_KEY /tmp/test.h264\n");
        return 2;
    }
    StreamParams p;
    p.host = argv[1]; p.app = argv[2]; p.streamKey = argv[3];
    p.tcUrl = std::string("rtmp://") + p.host + "/" + p.app;
    p.port = 1935;
    TcpTransport t;
    if (!t.Connect(p.host, p.port)) { fprintf(stderr, "connect failed\n"); return 1; }
    RtmpClient c(t, p);
    c.Begin();
    uint8_t buf[8192];
    while (c.state() != RtmpState::Publishing && c.state() != RtmpState::Error) {
        int n = t.Read(buf, sizeof(buf));
        if (n <= 0) { fprintf(stderr, "read failed during setup (state=%d)\n", (int)c.state()); return 1; }
        c.OnBytes(Bytes(buf, buf + n));
    }
    if (c.state() == RtmpState::Error) { fprintf(stderr, "RTMP setup error\n"); return 1; }
    fprintf(stderr, "PUBLISHING streamId=%d\n", c.streamId());
    Bytes h264 = ReadFile(argv[4]);
    Bytes sps, pps; SplitSpsPps(h264, sps, pps);
    if (sps.empty() || pps.empty()) { fprintf(stderr, "no SPS/PPS found in %s\n", argv[4]); return 1; }
    c.SendVideoConfig(sps, pps);
    fprintf(stderr, "sent video config (sps=%zu pps=%zu bytes)\n", sps.size(), pps.size());
    return 0;
}
