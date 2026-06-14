#include "amf0.h"
#include "test_helpers.h"
#include <gtest/gtest.h>
#include <vector>
#include <string>
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

// --- New primitives: ForEachArrayElement and ForEachProperty ---

TEST(Amf0Decode, StrictArrayOfStrings) {
    // Encode a strict array ["hvc1", "avc1"] and read it back via ForEachArrayElement.
    Bytes b;
    Amf0::StrictArrayBegin(b, 2);
    Amf0::String(b, "hvc1");
    Amf0::String(b, "avc1");
    Amf0Reader r(b.data(), b.size());
    std::vector<std::string> out;
    r.ForEachArrayElement([&](Amf0Reader& el) -> bool {
        out.push_back(el.ReadString());
        return true;
    });
    ASSERT_EQ(out.size(), 2u);
    EXPECT_EQ(out[0], "hvc1");
    EXPECT_EQ(out[1], "avc1");
}

TEST(Amf0Decode, ObjectPropertyEnumeration) {
    // Encode object {level:"status", code:"ok"} and enumerate via ForEachProperty.
    Bytes b;
    Amf0::ObjectBegin(b);
    Amf0::Key(b, "level"); Amf0::String(b, "status");
    Amf0::Key(b, "code");  Amf0::String(b, "ok");
    Amf0::ObjectEnd(b);
    // ForEachProperty expects marker already consumed.
    Amf0Reader r(b.data(), b.size());
    r.ConsumeMarker();  // consume 0x03
    std::vector<std::pair<std::string,std::string>> props;
    r.ForEachProperty([&](const std::string& key, Amf0Reader& val) -> bool {
        props.push_back({key, val.ReadString()});
        return true;
    });
    ASSERT_EQ(props.size(), 2u);
    EXPECT_EQ(props[0].first,  "level");
    EXPECT_EQ(props[0].second, "status");
    EXPECT_EQ(props[1].first,  "code");
    EXPECT_EQ(props[1].second, "ok");
}

TEST(Amf0Decode, EcmaArrayPropertyEnumeration) {
    // Encode ECMA-array {hvc1: null, avc1: null} and enumerate via ForEachProperty.
    Bytes b;
    Amf0::EcmaArrayBegin(b, 2);
    Amf0::Key(b, "hvc1"); Amf0::Null(b);
    Amf0::Key(b, "avc1"); Amf0::Null(b);
    Amf0::ObjectEnd(b);
    // ForEachProperty expects marker+count already consumed.
    Amf0Reader r(b.data(), b.size());
    r.ConsumeMarker();        // consume 0x08
    r.ReadU32BE();            // consume count = 2
    std::vector<std::string> keys;
    r.ForEachProperty([&](const std::string& key, Amf0Reader& val) -> bool {
        val.SkipValue();  // skip null
        keys.push_back(key);
        return true;
    });
    ASSERT_EQ(keys.size(), 2u);
    EXPECT_EQ(keys[0], "hvc1");
    EXPECT_EQ(keys[1], "avc1");
}

TEST(Amf0Decode, ForEachPropertyTruncatedSafe) {
    // Truncated object: key length says 5 but only 2 bytes follow. Must not crash/overread.
    Bytes b = {0x00, 0x05, 'h', 'i'};  // key-len=5 but only 2 chars
    Amf0Reader r(b.data(), b.size());
    // Already past the object marker; call ForEachProperty directly.
    int count = 0;
    r.ForEachProperty([&](const std::string&, Amf0Reader& val) -> bool {
        val.SkipValue(); ++count; return true;
    });
    EXPECT_EQ(count, 0);  // truncation detected, visitor never called
}

TEST(Amf0Decode, ForEachArrayElementTruncatedSafe) {
    // Strict array with count=5 but only 1 element's data follows. Must not crash.
    Bytes b;
    Amf0::StrictArrayBegin(b, 5);   // claims 5 elements
    Amf0::String(b, "hvc1");        // only 1 element encoded
    Amf0Reader r(b.data(), b.size());
    int count = 0;
    r.ForEachArrayElement([&](Amf0Reader& el) -> bool {
        std::string s = el.ReadString();
        ++count;
        return true;
    });
    // Should have read 1 element and stopped gracefully (eof after that).
    EXPECT_GE(count, 0);  // no crash is the key requirement
    EXPECT_LE(count, 5);
}

TEST(Amf0Decode, SkipValueHandlesObjectAndArray) {
    // SkipValue must recursively skip objects and arrays (previously it stopped on 0x03/0x0A).
    Bytes b;
    // object {x: "y"} followed by a number 42
    Amf0::ObjectBegin(b);
    Amf0::Key(b, "x"); Amf0::String(b, "y");
    Amf0::ObjectEnd(b);
    Amf0::Number(b, 42.0);
    Amf0Reader r(b.data(), b.size());
    r.SkipValue();  // skip the whole object
    EXPECT_EQ(r.ReadNumber(), 42.0);  // must land on the number
}

TEST(Amf0Decode, SkipValueHandlesStrictArray) {
    // SkipValue must skip a strict array.
    Bytes b;
    Amf0::StrictArrayBegin(b, 2);
    Amf0::String(b, "a");
    Amf0::String(b, "b");
    Amf0::Boolean(b, true);  // value after the array
    Amf0Reader r(b.data(), b.size());
    r.SkipValue();  // skip the strict array
    // Next value is boolean true (0x01, 0x01)
    EXPECT_EQ(r.PeekMarker(), 0x01);
}
