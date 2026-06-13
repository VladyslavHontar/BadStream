#include <gtest/gtest.h>
#include "version.h"
TEST(Smoke, CoreLinks) { EXPECT_STREQ(ps::CoreVersion(), "m1a"); }
