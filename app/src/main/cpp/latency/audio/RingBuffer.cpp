#include "RingBuffer.h"

RingBuffer::RingBuffer(size_t capacityBytes)
    : buffer_(capacityBytes), capacity_(capacityBytes) {}

size_t RingBuffer::write(const uint8_t* data, size_t bytes) {
    if (bytes <= 0 || data == nullptr) return 0;
    std::lock_guard<std::mutex> _l(mutex_);
    size_t space = (capacity_ + readIndex_.load() - writeIndex_.load()) % capacity_;
    if (space == 0) space = capacity_;
    size_t can = bytes < (space - 1) ? bytes : (space - 1);
    size_t wi = writeIndex_.load();
    size_t first = std::min(can, capacity_ - wi);
    std::memcpy(buffer_.data() + wi, data, first);
    if (can > first) std::memcpy(buffer_.data(), data + first, can - first);
    writeIndex_.store((wi + can) % capacity_);
    return can;
}

size_t RingBuffer::read(uint8_t* out, size_t bytes) {
    if (bytes <= 0 || out == nullptr) return 0;
    std::lock_guard<std::mutex> _l(mutex_);
    size_t avail = (capacity_ + writeIndex_.load() - readIndex_.load()) % capacity_;
    size_t can = bytes < avail ? bytes : avail;
    size_t ri = readIndex_.load();
    size_t first = std::min(can, capacity_ - ri);
    std::memcpy(out, buffer_.data() + ri, first);
    if (can > first) std::memcpy(out + first, buffer_.data(), can - first);
    readIndex_.store((ri + can) % capacity_);
    return can;
}

void RingBuffer::clear() {
    std::lock_guard<std::mutex> _l(mutex_);
    readIndex_.store(0);
    writeIndex_.store(0);
}


