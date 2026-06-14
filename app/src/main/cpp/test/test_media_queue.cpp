#include <gtest/gtest.h>
#include <chrono>
#include "media_queue.h"
using namespace ps;

TEST(MediaQueue, PushPopFifo) {
    MediaQueue q(8);
    q.Push(MediaItem{MediaItem::Video, {1}, false, 0, 0});
    q.Push(MediaItem{MediaItem::Video, {2}, false, 1, 1});
    MediaItem out;
    ASSERT_TRUE(q.Pop(out)); EXPECT_EQ(out.data[0], 1);
    ASSERT_TRUE(q.Pop(out)); EXPECT_EQ(out.data[0], 2);
}

TEST(MediaQueue, DropsOldestWhenFull) {
    MediaQueue q(2);
    q.Push(MediaItem{MediaItem::Video, {1}, false, 0, 0});
    q.Push(MediaItem{MediaItem::Video, {2}, false, 0, 0});
    q.Push(MediaItem{MediaItem::Video, {3}, false, 0, 0}); // evicts {1}
    EXPECT_EQ(q.dropped(), 1u);
    MediaItem out;
    ASSERT_TRUE(q.Pop(out)); EXPECT_EQ(out.data[0], 2);
    ASSERT_TRUE(q.Pop(out)); EXPECT_EQ(out.data[0], 3);
}

TEST(MediaQueue, CloseUnblocksPop) {
    MediaQueue q(2);
    q.Close();
    MediaItem out;
    EXPECT_FALSE(q.Pop(out)); // closed + empty -> false, no hang
}

TEST(MediaQueue, SizeReflectsPending) {
    MediaQueue q(8);
    EXPECT_EQ(q.size(), 0u);
    q.Push(MediaItem{MediaItem::Video, {1}, false, 0, 0});
    q.Push(MediaItem{MediaItem::Video, {2}, false, 0, 0});
    EXPECT_EQ(q.size(), 2u);
    MediaItem out;
    ASSERT_TRUE(q.Pop(out));
    EXPECT_EQ(q.size(), 1u);
}

TEST(MediaQueue, PopTimeoutReturnsFalseWhenEmptyAfterTimeout) {
    ps::MediaQueue q(4);
    ps::MediaItem out;
    auto t0 = std::chrono::steady_clock::now();
    bool got = q.PopTimeout(out, 30);
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - t0).count();
    EXPECT_FALSE(got);
    EXPECT_GE(ms, 25);            // actually waited ~the timeout
}

TEST(MediaQueue, PopTimeoutReturnsItemImmediatelyWhenAvailable) {
    ps::MediaQueue q(4);
    q.Push(ps::MediaItem{ps::MediaItem::Audio, {1,2,3}, false, 0, 0});
    ps::MediaItem out;
    EXPECT_TRUE(q.PopTimeout(out, 1000));
    EXPECT_EQ(out.data.size(), 3u);
}
