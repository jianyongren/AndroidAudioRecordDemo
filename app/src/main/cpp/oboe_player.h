#ifndef OBOE_PLAYER_H
#define OBOE_PLAYER_H

#include <memory>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <jni.h>
#include <oboe/Oboe.h>
#include "thread_safe_ring_buffer.h"

/**
 * @brief Oboe音频播放器类
 * 负责PCM文件的播放
 */
class OboePlayer : public oboe::AudioStreamDataCallback {
public:
    /**
     * @brief 构造函数
     * @param filePath PCM文件路径
     * @param sampleRate 采样率
     * @param isStereo 是否为立体声
     * @param isFloat 是否使用浮点数格式
     * @param audioApi 音频API类型
     */
    OboePlayer(const char* filePath, int32_t sampleRate, bool isStereo, bool isFloat, int32_t audioApi);
    
    /**
     * @brief 析构函数
     */
    ~OboePlayer() override;

    /**
     * @brief 音频数据回调函数
     */
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames) override;

    /**
     * @brief 开始播放
     * @return 是否成功启动
     */
    bool start();

    /**
     * @brief 停止播放
     */
    void stop();

    // 设置回调对象
    void setCallbackObject(jobject obj, jmethodID methodId) {
        callbackObject_ = obj;
        onPlaybackCompleteMethodId_ = methodId;
    }

    float getPlaybackProgress() const;  // 获取播放进度的方法

private:
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<FILE, decltype(&fclose)> file_;
    bool isFloat;
    int32_t sampleRate;
    bool isStereo;
    int32_t samplesPerFrame;
    int32_t audioApi;
    size_t bytesRead_;
    size_t totalBytes_;  // 文件总字节数
    std::atomic<float> playbackProgress_;  // 播放进度
    std::atomic<int64_t> framesPlayed_;  // 已播放的帧数
    int64_t totalFrames_;  // 总帧数

    // 缓冲区相关
    static constexpr size_t BUFFER_CAPACITY = 1024 * 1024; // 1MB 缓冲区
    std::unique_ptr<ThreadSafeRingBuffer> ringBuffer_;
    std::unique_ptr<std::thread> producerThread_;
    std::atomic<bool> isRunning_;

    // 播放完成的回调
    void notifyPlaybackComplete();

    // 回调相关的成员变量
    jmethodID onPlaybackCompleteMethodId_ = nullptr;
    jobject callbackObject_ = nullptr;

    void producerThreadFunc();
    static oboe::AudioApi getAudioApi(int32_t api);
};

#endif // OBOE_PLAYER_H 