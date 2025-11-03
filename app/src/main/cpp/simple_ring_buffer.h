#ifndef SIMPLE_RING_BUFFER_H
#define SIMPLE_RING_BUFFER_H

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <algorithm>

/**
 * 线程不安全的简易环形缓冲区（仅用于单生产者单消费者在同一线程场景）
 */
class SimpleRingBuffer {
public:
    explicit SimpleRingBuffer(size_t capacity);
    ~SimpleRingBuffer();

    bool write(const void* data, size_t size);
    bool read(void* data, size_t size);

    size_t size() const { return size_; }
    size_t capacity() const { return capacity_; }

private:
    const size_t capacity_;
    uint8_t* buffer_;
    size_t writePos_;
    size_t readPos_;
    size_t size_;
};

#endif // SIMPLE_RING_BUFFER_H


