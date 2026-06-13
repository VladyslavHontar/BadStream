#include "amf0.h"
#include "test_helpers.h"
using namespace ps;
TEST(Amf0, Number)  { Bytes b; Amf0::Number(b, 1.0);  EXPECT_BYTES(b, {0x00,0x3F,0xF0,0,0,0,0,0,0}); }
TEST(Amf0, BoolT)   { Bytes b; Amf0::Boolean(b, true);  EXPECT_BYTES(b, {0x01,0x01}); }
TEST(Amf0, BoolF)   { Bytes b; Amf0::Boolean(b, false); EXPECT_BYTES(b, {0x01,0x00}); }
TEST(Amf0, String)  { Bytes b; Amf0::String(b, "1234"); EXPECT_BYTES(b, {0x02,0x00,0x04,'1','2','3','4'}); }
TEST(Amf0, Null)    { Bytes b; Amf0::Null(b);           EXPECT_BYTES(b, {0x05}); }
TEST(Amf0, Object)  {
    Bytes b; Amf0::ObjectBegin(b);
    Amf0::Key(b, "1"); Amf0::String(b, "2");
    Amf0::ObjectEnd(b);
    EXPECT_BYTES(b, {0x03, 0x00,0x01,'1', 0x02,0x00,0x01,'2', 0x00,0x00,0x09});
}
TEST(Amf0, EcmaArray) {
    Bytes b; Amf0::EcmaArrayBegin(b, 2);
    Amf0::Key(b, "foo"); Amf0::Boolean(b, true);
    Amf0::Key(b, "bar"); Amf0::String(b, "fie");
    Amf0::ObjectEnd(b);
    EXPECT_BYTES(b, {0x08, 0,0,0,2,
        0x00,0x03,'f','o','o', 0x01,0x01,
        0x00,0x03,'b','a','r', 0x02,0x00,0x03,'f','i','e',
        0x00,0x00,0x09});
}
TEST(Amf0Decode, ReadCommandName) {
    // "_result", number 1, then null
    Bytes msg = {0x02,0x00,0x07,'_','r','e','s','u','l','t', 0x00,0x3F,0xF0,0,0,0,0,0,0, 0x05};
    Amf0Reader r(msg.data(), msg.size());
    EXPECT_EQ(r.ReadString(), "_result");
    EXPECT_EQ(r.ReadNumber(), 1.0);
}
TEST(Amf0Decode, FindStatusCode) {
    // onStatus info object {level:"status", code:"NetStream.Publish.Start"}
    Bytes obj = {0x03,
        0x00,0x05,'l','e','v','e','l', 0x02,0x00,0x06,'s','t','a','t','u','s',
        0x00,0x04,'c','o','d','e', 0x02,0x00,0x17,
        'N','e','t','S','t','r','e','a','m','.','P','u','b','l','i','s','h','.','S','t','a','r','t',
        0x00,0x00,0x09};
    EXPECT_EQ(Amf0::FindStringValue(obj, "code"), "NetStream.Publish.Start");
}
TEST(Amf0Decode, TruncatedStringDoesNotOverread) {
    // 0x02 marker, length says 10, but only 3 bytes follow
    Bytes msg = {0x02,0x00,0x0A,'a','b','c'};
    Amf0Reader r(msg.data(), msg.size());
    EXPECT_EQ(r.ReadString(), "");   // refuses, no overread
}
TEST(Amf0Decode, TruncatedNumberDoesNotOverread) {
    Bytes msg = {0x00,0x3F,0xF0};    // number marker but only 2 of 8 bytes
    Amf0Reader r(msg.data(), msg.size());
    EXPECT_EQ(r.ReadNumber(), 0);
}
TEST(Amf0Decode, FindStringValueTruncatedNoOverread) {
    // key "code" then 0x02 string marker with length 20 but truncated body
    Bytes obj = {0x03, 0x00,0x04,'c','o','d','e', 0x02,0x00,0x14,'N','e','t'};
    EXPECT_EQ(Amf0::FindStringValue(obj, "code"), "");
}
