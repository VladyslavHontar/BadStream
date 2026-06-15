#include "abr.h"
#include <gtest/gtest.h>
using namespace ps;

namespace {
AbrConfig Cfg() { return AbrConfig{ /*minBps*/ 500'000, /*targetBps*/ 4'000'000, /*maxBps*/ 8'000'000 }; }
AbrStats Healthy() { return AbrStats{ /*rttMs*/ 20.0, /*sndBufPkts*/ 0, /*inflight*/ 2, /*lossPct*/ 0.0 }; }
AbrStats Congested() { return AbrStats{ /*rttMs*/ 300.0, /*sndBufPkts*/ 1500, /*inflight*/ 800, /*lossPct*/ 5.0 }; }
}  // namespace

TEST(Abr, ClampsAboveMax) {
    EXPECT_EQ(AbrNextBitrate(Healthy(), Cfg(), 10'000'000), Cfg().maxBps);
}
TEST(Abr, ClampsBelowMin) {
    EXPECT_EQ(AbrNextBitrate(Congested(), Cfg(), 100'000), Cfg().minBps);
}

TEST(Abr, HealthyClimbsButBounded) {
    int cur = 2'000'000;
    int next = AbrNextBitrate(Healthy(), Cfg(), cur);
    EXPECT_GT(next, cur) << "healthy link should climb";
    EXPECT_LE(next, Cfg().maxBps);
    // bounded step: no more than ~25% jump in one tick
    EXPECT_LE(next - cur, cur / 2) << "increase must be rate-limited";
}

TEST(Abr, HealthyConvergesTowardMax) {
    int cur = 2'000'000;
    for (int i = 0; i < 100; ++i) cur = AbrNextBitrate(Healthy(), Cfg(), cur);
    EXPECT_EQ(cur, Cfg().maxBps) << "sustained healthy link reaches max";
}

TEST(Abr, CongestionDropsBitrate) {
    int cur = 6'000'000;
    int next = AbrNextBitrate(Congested(), Cfg(), cur);
    EXPECT_LT(next, cur) << "congestion should drop bitrate";
    EXPECT_GE(next, Cfg().minBps);
}

TEST(Abr, SustainedCongestionApproachesMin) {
    int cur = 6'000'000;
    for (int i = 0; i < 100; ++i) cur = AbrNextBitrate(Congested(), Cfg(), cur);
    EXPECT_EQ(cur, Cfg().minBps) << "sustained congestion reaches min";
}

TEST(Abr, DropIsFasterThanClimb) {
    int cur = 4'000'000;
    int up = AbrNextBitrate(Healthy(), Cfg(), cur) - cur;        // positive
    int down = cur - AbrNextBitrate(Congested(), Cfg(), cur);    // positive
    EXPECT_GT(down, up) << "BELABOX-style: decrease fast, increase slow";
}

TEST(Abr, NoThrashAtTargetWhenHealthy) {
    // At max with a healthy link, output stays at max (no oscillation).
    int a = AbrNextBitrate(Healthy(), Cfg(), Cfg().maxBps);
    int b = AbrNextBitrate(Healthy(), Cfg(), a);
    EXPECT_EQ(a, Cfg().maxBps);
    EXPECT_EQ(b, Cfg().maxBps);
}

TEST(Abr, LossAloneTriggersDrop) {
    AbrStats s = Healthy();
    s.lossPct = 3.0;            // loss but empty buffer
    int cur = 4'000'000;
    EXPECT_LT(AbrNextBitrate(s, Cfg(), cur), cur);
}

TEST(Abr, SndBufferBuildupTriggersDrop) {
    AbrStats s = Healthy();
    s.sndBufPkts = 1200;       // buffer building, no reported loss yet
    int cur = 4'000'000;
    EXPECT_LT(AbrNextBitrate(s, Cfg(), cur), cur);
}
