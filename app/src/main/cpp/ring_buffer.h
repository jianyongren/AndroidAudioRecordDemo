#ifndef RING_BUFFER_H
#define RING_BUFFER_H

#include <cstddef>
#include <cstring>
#include <algorithm>

/**
 * @brief 线程安全的环形缓冲区实现
 * 用于音频数据的生产者-消费者模式
 */
class RingBuffer {
public:
    /**
     * @brief 构造函数
     * @param capacity 缓冲区容量（字节）
     */
    explicit RingBuffer(size_t capacity);
    
    /**
     * @brief 析构函数
     */
    ~RingBuffer();
    
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
    size_t size() const { return size_; }
    
    /**
     * @brief 获取缓冲区容量
     * @return 缓冲区容量（字节）
     */
    size_t capacity() const { return capacity_; }

private:
    const size_t capacity_;  // 缓冲区容量
    uint8_t* buffer_;       // 缓冲区数据
    size_t writePos_;       // 写入位置
    size_t readPos_;        // 读取位置
    size_t size_;          // 当前数据大小
};

#endif // RING_BUFFER_H 