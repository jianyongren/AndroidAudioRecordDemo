#pragma once

#include <cstdint>
#include <vector>
#include <atomic>
#include <mutex>
#include <algorithm>
#include <cstring>

class RingBuffer {
public:
    explicit RingBuffer(size_t capacityBytes);
    size_t write(const uint8_t* data, size_t bytes);
    size_t read(uint8_t* out, size_t bytes);
    void clear();

private:
    std::vector<uint8_t> buffer_;
    std::atomic<size_t> readIndex_{0};
    std::atomic<size_t> writeIndex_{0};
    size_t capacity_;
    std::mutex mutex_;
};


