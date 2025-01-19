#ifndef DATA_WRITER_H
#define DATA_WRITER_H

#include <cstdio>

/**
 * @brief 文件写入器类
 * 用于将音频数据写入文件
 */
class DataWriter {
public:
    /**
     * @brief 构造函数
     * @param filePath 文件路径
     */
    explicit DataWriter(const char* filePath);
    
    /**
     * @brief 析构函数
     */
    ~DataWriter();
    
    /**
     * @brief 写入数据到文件
     * @param data 数据指针
     * @param size 数据大小
     */
    void write(const void* data, size_t size);
    
private:
    FILE* file_;
};

#endif // DATA_WRITER_H 