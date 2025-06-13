#ifndef THREAD_SAFE_RING_BUFFER_H
#define THREAD_SAFE_RING_BUFFER_H

#include <cstddef>
#include <cstring>
#include <algorithm>
#include <mutex>
#include <condition_variable>
#include <atomic>

/**
 * @brief 线程安全的环形缓冲区实现
 * 用于音频数据的生产者-消费者模式
 */
class ThreadSafeRingBuffer {
public:
    /**
     * @brief 构造函数
     * @param capacity 缓冲区容量（字节）
     */
    explicit ThreadSafeRingBuffer(size_t capacity);
    
    /**
     * @brief 析构函数
     */
    ~ThreadSafeRingBuffer();
    
    /**
     * @brief 写入数据到缓冲区
     * @param data 要写入的数据指针
     * @param size 要写入的数据大小（字节）
     * @return 是否写入成功
     */
    bool write(const void* data, size_t size);
    
    /**
     * @brief 从缓冲区读取数据
     * @param data 读取数据的目标缓冲区
     * @param size 要读取的数据大小（字节）
     * @return 是否读取成功
     */
    bool read(void* data, size_t size);
    
    /**
     * @brief 获取当前缓冲区中的数据大小
     * @return 当前数据大小（字节）
     */
    size_t size() const;
    
    /**
     * @brief 获取缓冲区容量
     * @return 缓冲区容量（字节）
     */
    size_t capacity() const;

    /**
     * @brief 释放缓冲区，通知所有阻塞的写入操作退出
     */
    void release();

private:
    const size_t capacity_;  // 缓冲区容量
    uint8_t* buffer_;       // 缓冲区数据
    std::atomic<size_t> writePos_;  // 写入位置
    std::atomic<size_t> readPos_;   // 读取位置
    std::atomic<size_t> size_;      // 当前数据大小
    std::atomic<bool> released_;    // 是否已释放

    mutable std::mutex mutex_;              // 互斥锁
    std::condition_variable spaceAvailable_; // 空间可用的条件变量
    std::condition_variable dataAvailable_;  // 数据可用的条件变量

    /**
     * @brief 等待直到缓冲区有足够空间
     * @param size 需要的空间大小
     * @return 是否成功等待（false表示被release）
     */
    bool waitForSpace(size_t size);

    /**
     * @brief 等待直到缓冲区有足够数据
     * @param size 需要的数据大小
     * @param timeout_ms 超时时间（毫秒），0表示无限等待
     * @return 是否有足够数据
     */
    bool waitForData(size_t size);
};

#endif // THREAD_SAFE_RING_BUFFER_H 