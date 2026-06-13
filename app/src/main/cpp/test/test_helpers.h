#pragma once
#include <cstdint>
#include <string>
#include <vector>
#include <gtest/gtest.h>
inline std::string Hex(const std::vector<uint8_t>& b) {
    static const char* d = "0123456789abcdef";
    std::string s;
    for (uint8_t c : b) { s += d[c >> 4]; s += d[c & 0xF]; s += ' '; }
    return s;
}
#define EXPECT_BYTES(actual, ...) \
    do { std::vector<uint8_t> _a = (actual); std::vector<uint8_t> _e = __VA_ARGS__; \
         EXPECT_EQ(_a, _e) << "\n  got: " << Hex(_a) << "\n  exp: " << Hex(_e); } while (0)
