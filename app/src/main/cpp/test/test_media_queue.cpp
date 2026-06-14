#include <gtest/gtest.h>
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
