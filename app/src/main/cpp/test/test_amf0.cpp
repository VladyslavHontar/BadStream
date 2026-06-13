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
