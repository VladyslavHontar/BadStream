#pragma once
#include <string>
#include "byte_writer.h"
#include "transport.h"
namespace ps {
struct StreamParams {
    std::string host, app, streamKey, tcUrl;
    uint16_t port = 1935;
    int width = 1280, height = 720, sampleRate = 44100;
    double fps = 30.0;
};
// AMF0 body of the `connect` command (without chunk framing). Public for testing.
Bytes BuildConnectCommand(const StreamParams& p, int txn);
}
