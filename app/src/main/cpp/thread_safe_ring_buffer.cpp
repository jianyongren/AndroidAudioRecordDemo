#include "thread_safe_ring_buffer.h"

ThreadSafeRingBuffer::ThreadSafeRingBuffer(size_t capacity)
    : capacity_(capacity)
    , buffer_(new uint8_t[capacity])
    , writePos_(0)
    , readPos_(0)
    , size_(0)
    , released_(false) {
}

ThreadSafeRingBuffer::~ThreadSafeRingBuffer() {
    release();
    delete[] buffer_;
}

bool ThreadSafeRingBuffer::write(const void* data, size_t size) {
    if (size > capacity_) {
        return false;
    }

    // 等待直到有足够空间
    if (!waitForSpace(size)) {
        return false;
    }

    // 计算需要写入的数据量
    size_t firstPart = std::min(size, capacity_ - writePos_);
    size_t secondPart = size - firstPart;

    // 写入第一部分数据
    memcpy(buffer_ + writePos_, data, firstPart);
    
    // 如果需要，写入第二部分数据（环绕到缓冲区开始）
    if (secondPart > 0) {
        memcpy(buffer_, static_cast<const uint8_t*>(data) + firstPart, secondPart);
    }

    // 更新写入位置和大小
    writePos_ = (writePos_ + size) % capacity_;
    size_ += size;

    return true;
}

bool ThreadSafeRingBuffer::read(void* data, size_t size) {
    if (size > capacity_) {
        return false;
    }

    // 检查是否有足够的数据
    if (size_ < size) {
        return false;
    }

    // 计算需要读取的数据量
    size_t firstPart = std::min(size, capacity_ - readPos_);
    size_t secondPart = size - firstPart;

    // 读取第一部分数据
    memcpy(data, buffer_ + readPos_, firstPart);
    
    // 如果需要，读取第二部分数据（环绕到缓冲区开始）
    if (secondPart > 0) {
        memcpy(static_cast<uint8_t*>(data) + firstPart, buffer_, secondPart);
    }

    // 更新读取位置和大小
    readPos_ = (readPos_ + size) % capacity_;
    size_ -= size;

    spaceAvailable_.notify_one();
    return true;
}

size_t ThreadSafeRingBuffer::size() const {
    return size_;
}

size_t ThreadSafeRingBuffer::capacity() const {
    return capacity_;
}

void ThreadSafeRingBuffer::release() {
    if (!released_) {
        released_ = true;
        spaceAvailable_.notify_all();
    }
}

bool ThreadSafeRingBuffer::waitForSpace(size_t size) {
    std::unique_lock<std::mutex> lock(mutex_);
    auto predicate = [this, size]() {
        return (capacity_ - size_) >= size || released_;
    };
    spaceAvailable_.wait(lock, predicate);
    return !released_;
} 