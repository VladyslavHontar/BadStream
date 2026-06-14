#pragma once
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <mutex>
#include <vector>
namespace ps {

struct MediaItem {
    enum Kind { Video, Audio, VideoConfig, AudioConfig };
    Kind kind;
    std::vector<uint8_t> data;   // annexb (video) / raw aac (audio) / csd (video config)
    bool keyframe = false;       // video only
    uint32_t ptsMs = 0;
    uint32_t dtsMs = 0;
    int sampleRate = 0;          // audio config only
    int channels = 0;            // audio config only
};

// Thread-safe bounded queue. When full, Push evicts the oldest item (drop-oldest keeps
// latency bounded on a slow uplink). Pop blocks until an item is available or Close().
class MediaQueue {
public:
    explicit MediaQueue(size_t capacity) : cap_(capacity) {}

    void Push(MediaItem item) {
        std::unique_lock<std::mutex> lk(m_);
        if (q_.size() >= cap_) { q_.pop_front(); ++dropped_; }
        q_.push_back(std::move(item));
        cv_.notify_one();
    }

    bool Pop(MediaItem& out) {
        std::unique_lock<std::mutex> lk(m_);
        cv_.wait(lk, [&] { return closed_ || !q_.empty(); });
        if (q_.empty()) return false;     // closed + drained
        out = std::move(q_.front());
        q_.pop_front();
        return true;
    }

    void Close() {
        std::unique_lock<std::mutex> lk(m_);
        closed_ = true;
        cv_.notify_all();
    }

    uint64_t dropped() const {
        std::unique_lock<std::mutex> lk(m_);
        return dropped_;
    }

private:
    mutable std::mutex m_;
    std::condition_variable cv_;
    std::deque<MediaItem> q_;
    size_t cap_;
    bool closed_ = false;
    uint64_t dropped_ = 0;
};
}
