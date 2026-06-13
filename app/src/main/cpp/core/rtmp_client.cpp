#include "rtmp_client.h"
#include "amf0.h"
namespace ps {
Bytes BuildConnectCommand(const StreamParams& p, int txn) {
    Bytes b;
    Amf0::String(b, "connect");
    Amf0::Number(b, txn);
    Amf0::ObjectBegin(b);
    Amf0::Key(b, "app");            Amf0::String(b, p.app);
    Amf0::Key(b, "flashVer");       Amf0::String(b, "FMLE/3.0 (compatible; FMSc/1.0)");
    Amf0::Key(b, "swfUrl");         Amf0::Null(b);
    Amf0::Key(b, "tcUrl");          Amf0::String(b, p.tcUrl);
    Amf0::Key(b, "fpad");           Amf0::Boolean(b, false);
    Amf0::Key(b, "capabilities");   Amf0::Number(b, 239);
    Amf0::Key(b, "audioCodecs");    Amf0::Number(b, 1024); // AAC
    Amf0::Key(b, "videoCodecs");    Amf0::Number(b, 128);  // H.264
    Amf0::Key(b, "videoFunction");  Amf0::Number(b, 1);
    Amf0::Key(b, "pageUrl");        Amf0::Null(b);
    Amf0::Key(b, "objectEncoding"); Amf0::Number(b, 0);
    Amf0::ObjectEnd(b);
    return b;
}
}
