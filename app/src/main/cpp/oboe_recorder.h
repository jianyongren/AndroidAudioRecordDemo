#ifndef OBOE_RECORDER_H
#define OBOE_RECORDER_H

#include <memory>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <jni.h>
#include <oboe/Oboe.h>
#include "ring_buffer.h"
#include "data_writer.h"

/**
 * @brief Oboe音频录制器类
 * 负责音频数据的采集、缓存和回调
 */
class OboeRecorder : public oboe::AudioStreamDataCallback {
public:
    /**
     * @brief 构造函数
     * @param filePath 录音文件保存路径
     * @param sampleRate 采样率
     * @param isStereo 是否为立体声
     * @param isFloat 是否使用浮点数格式
     * @param deviceId 音频设备ID
     * @param audioSource 音频源类型
     * @param audioApi 音频API类型
     */
    OboeRecorder(const char* filePath, int32_t sampleRate, bool isStereo, bool isFloat,
                int32_t deviceId, int32_t audioSource, int32_t audioApi);
    
    /**
     * @brief 析构函数
     */
    ~OboeRecorder();

    /**
     * @brief 音频数据回调函数
     */
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames) override;

    /**
     * @brief 开始录音
     * @return 是否成功启动
     */
    bool start();

    /**
     * @brief 停止录音
     */
    void stop();

private:
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<DataWriter> writer;
    bool isFloat;
    int32_t sampleRate;
    bool isStereo;
    int32_t samplesPerFrame;
    int32_t deviceId;
    int32_t audioSource;
    int32_t audioApi;

    // 缓冲区相关
    static constexpr size_t BUFFER_CAPACITY = 1024 * 1024; // 1MB 缓冲区
    std::unique_ptr<RingBuffer> ringBuffer_;
    std::unique_ptr<std::thread> consumerThread_;
    std::mutex mutex_;
    std::condition_variable dataReady_;
    std::atomic<bool> isRunning_;

    /**
     * @brief 发送音频数据到Java层
     */
    void sendAudioDataToJava(const void* audioData, int32_t numFrames);

    /**
     * @brief 消费者线程函数
     */
    void consumerThreadFunc();

    /**
     * @brief 获取输入预设
     */
    oboe::InputPreset getInputPreset(int32_t audioSource);

    /**
     * @brief 获取音频API
     */
    oboe::AudioApi getAudioApi(int32_t audioApi);
};

#endif // OBOE_RECORDER_H 