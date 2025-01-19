#include "ring_buffer.h"

RingBuffer::RingBuffer(size_t capacity) 
    : capacity_(capacity)
    , buffer_(new uint8_t[capacity])
    , writePos_(0)
    , readPos_(0)
    , size_(0) {}

RingBuffer::~RingBuffer() {
    delete[] buffer_;
}

bool RingBuffer::write(const void* data, size_t size) {
    if (size > capacity_ - size_) {
        return false;
    }

    const uint8_t* src = static_cast<const uint8_t*>(data);
    size_t firstPart = std::min(size, capacity_ - writePos_);
    size_t secondPart = size - firstPart;

    // 写入第一部分
    std::memcpy(buffer_ + writePos_, src, firstPart);
    
    // 如果有需要，写入第二部分（环绕到缓冲区开始）
    if (secondPart > 0) {
        std::memcpy(buffer_, src + firstPart, secondPart);
    }

    writePos_ = (writePos_ + size) % capacity_;
    size_ += size;
    return true;
}

bool RingBuffer::read(void* data, size_t size) {
    if (size > size_) {
        return false;
    }

    uint8_t* dst = static_cast<uint8_t*>(data);
    size_t firstPart = std::min(size, capacity_ - readPos_);
    size_t secondPart = size - firstPart;

    // 读取第一部分
    std::memcpy(dst, buffer_ + readPos_, firstPart);
    
    // 如果有需要，读取第二部分（环绕到缓冲区开始）
    if (secondPart > 0) {
        std::memcpy(dst + firstPart, buffer_, secondPart);
    }

    readPos_ = (readPos_ + size) % capacity_;
    size_ -= size;
    return true;
} 