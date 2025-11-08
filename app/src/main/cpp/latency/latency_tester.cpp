#include <jni.h>
#include <string>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include <chrono>
#include <memory>
#include <cmath>
#include <climits>

#include <oboe/Oboe.h>
#include <thread>
#include <vector>
#include <mutex>

#include "audio/AudioRingBuffer.h"
#include "ffmpeg/AudioTranscode.h"
#include "logging.h"
#include "config.h"

#define LOG_TAG "RecordLatency"

#define LATENCY_EVENTS_CLASS "me/rjy/oboe/record/demo/LatencyEvents"

// 延迟测试器类：封装所有延迟测试相关的状态和功能
class LatencyTester {
public:
    LatencyTester() {
        // 初始化前3个窗口信息
        for (int i = 0; i < 3; ++i) {
            top3Delays_[i] = -1.0;
            top3Correlations_[i] = -1.0;
        }
        detectedDelayMs_ = -1.0;
    }
    
    ~LatencyTester() {
        stop();  // 确保清理所有资源
        cleanup();
    }
    
    // 禁止拷贝和移动
    LatencyTester(const LatencyTester&) = delete;
    LatencyTester& operator=(const LatencyTester&) = delete;
    LatencyTester(LatencyTester&&) = delete;
    LatencyTester& operator=(LatencyTester&&) = delete;

    // 播放回调类：从内存中的PCM数据填充音频缓冲区
    class PlayCallback : public oboe::AudioStreamCallback {
    public:
        explicit PlayCallback(LatencyTester* tester) : tester_(tester) {}
        
        oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) override {
            if (!tester_ || !tester_->running_.load()) {
                LOGI("PlayCallback: not running, stop");
                return oboe::DataCallbackResult::Stop;
            }
            
            const int ch = tester_->workingChannelCount_ > 0 ? tester_->workingChannelCount_ : kChannelCount;
            const bool fmtFloat = tester_->outFormatFloat_;
            const size_t bytesPerSample = fmtFloat ? sizeof(float) : kBytesPerSample;
            const size_t bytesPerFrame = static_cast<size_t>(ch) * bytesPerSample;
            const size_t bytesNeeded = static_cast<size_t>(numFrames) * bytesPerFrame;
            size_t currentPos = tester_->pcmPosition_.load();
            
            if (currentPos >= tester_->pcmBuffer_.size()) {
                // 播放完成，填充静音
                memset(audioData, 0, bytesNeeded);
                tester_->running_.store(false);
                LOGI("PlayCallback: reached end of file, fill silence, stop");
                return oboe::DataCallbackResult::Stop;
            }
            
            // 计算可以读取的字节数
            size_t bytesAvailable = tester_->pcmBuffer_.size() - currentPos;
            size_t bytesToRead = (bytesNeeded < bytesAvailable) ? bytesNeeded : bytesAvailable;
            
            // 复制PCM数据到输出缓冲区（已严格匹配格式，无需转换）
            memcpy(audioData, tester_->pcmBuffer_.data() + currentPos, bytesToRead);
            
            // 如果数据不足，填充剩余部分为静音
            if (bytesToRead < bytesNeeded) {
                memset(static_cast<uint8_t*>(audioData) + bytesToRead, 0, bytesNeeded - bytesToRead);
            }
            
            // 同时写入环形缓冲（只写入实际读取的PCM数据，不包括静音）
            if (tester_->origRb_ && bytesToRead > 0) {
                tester_->origRb_->writeBytes(tester_->pcmBuffer_.data() + currentPos, bytesToRead);
            }
            
            // 更新播放位置（按实际需要的字节数更新，播放完成后停止）
            size_t newPos = currentPos + bytesNeeded;
            if (newPos >= tester_->pcmBuffer_.size()) {
                newPos = tester_->pcmBuffer_.size();
                tester_->pcmPosition_.store(newPos);
                tester_->running_.store(false);
                LOGI("PlayCallback: reached end of file, newPos=%zu, stop", newPos);
                return oboe::DataCallbackResult::Stop;
            }
            tester_->pcmPosition_.store(newPos);
            
            return oboe::DataCallbackResult::Continue;
        }
        
        void onErrorBeforeClose(oboe::AudioStream* audioStream, oboe::Result error) override {
            if (!tester_) return;
            
            // 检查是否已经处理过错误，避免重复处理
            bool expected = false;
            if (!tester_->errorOccurred_.compare_exchange_strong(expected, true)) {
                LOGW("PlayCallback: Error already handled, ignoring duplicate error");
                return;
            }
            
            const char* errorText = oboe::convertToText(error);
            LOGE("PlayCallback: onErrorBeforeClose - error=%s (%d), stream=%s", 
                 errorText, static_cast<int>(error), 
                 oboe::convertToText(audioStream));
            
            // 停止所有处理
            tester_->running_.store(false);
            
            // 在后台线程中安全地停止所有资源
            std::thread([this, errorText, error]() {
                // 给一点时间让当前回调完成
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                // 停止音频流和清理资源
                if (tester_->inputStream_) {
                    tester_->inputStream_->requestStop();
                    tester_->inputStream_->close();
                    tester_->inputStream_.reset();
                }
                if (tester_->outputStream_) {
                    tester_->outputStream_->requestStop();
                    tester_->outputStream_->close();
                    tester_->outputStream_.reset();
                }
                // 通知Java层错误信息
                std::string errorMsg = std::string("Play Error: ") + errorText;
                tester_->notifyJavaError(errorMsg, static_cast<int>(error));
            }).detach();
        }
        
    private:
        LatencyTester* tester_;
    };

    // 录音回调类
    class RecCallback : public oboe::AudioStreamCallback {
    public:
        explicit RecCallback(LatencyTester* tester) : tester_(tester) {}
        
        oboe::DataCallbackResult onAudioReady(oboe::AudioStream* audioStream, void* audioData, int32_t numFrames) override {
            if (!tester_ || !tester_->running_.load()) return oboe::DataCallbackResult::Stop;
            const int ch = tester_->workingChannelCount_ > 0 ? tester_->workingChannelCount_ : kChannelCount;
            // 严格按录音流参数写入原始数据到环形缓冲（不做格式转换）
            if (audioStream->getFormat() == oboe::AudioFormat::I16) {
                size_t bytes = static_cast<size_t>(numFrames) * ch * kBytesPerSample;
                if (tester_->recRb_) tester_->recRb_->writeBytes(reinterpret_cast<uint8_t*>(audioData), bytes);
            } else {
                size_t bytes = static_cast<size_t>(numFrames) * ch * sizeof(float);
                if (tester_->recRb_) tester_->recRb_->writeBytes(reinterpret_cast<const uint8_t*>(audioData), bytes);
            }
            return oboe::DataCallbackResult::Continue;
        }
        
        void onErrorBeforeClose(oboe::AudioStream* audioStream, oboe::Result error) override {
            if (!tester_) return;
            
            // 检查是否已经处理过错误，避免重复处理
            bool expected = false;
            if (!tester_->errorOccurred_.compare_exchange_strong(expected, true)) {
                LOGW("RecCallback: Error already handled, ignoring duplicate error");
                return;
            }
            
            const char* errorText = oboe::convertToText(error);
            LOGE("RecCallback: onErrorBeforeClose - error=%s (%d), stream=%s", 
                 errorText, static_cast<int>(error),
                 oboe::convertToText(audioStream));
            
            // 停止所有处理
            tester_->running_.store(false);
            
            // 在后台线程中安全地停止所有资源
            std::thread([this, errorText, error]() {
                // 给一点时间让当前回调完成
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                // 停止音频流和清理资源
                if (tester_->inputStream_) {
                    tester_->inputStream_->requestStop();
                    tester_->inputStream_->close();
                    tester_->inputStream_.reset();
                }
                if (tester_->outputStream_) {
                    tester_->outputStream_->requestStop();
                    tester_->outputStream_->close();
                    tester_->outputStream_.reset();
                }
                // 通知Java层错误信息
                std::string errorMsg = std::string("Record Error: ") + errorText;
                tester_->notifyJavaError(errorMsg, static_cast<int>(error));
            }).detach();
        }
        
    private:
        LatencyTester* tester_;
    };

    // 启动延迟测试
    int start(JNIEnv* env, const std::string& inputPath, const std::string& cacheDir, const std::string& outputM4a) {
        if (running_.load()) {
            LOGW("LatencyTester already running");
            return 0;
        }
        
        // 重置延迟值和错误标志
        detectedDelayMs_ = -1.0;
        errorOccurred_.store(false);
        for (int i = 0; i < 3; ++i) {
            top3Delays_[i] = -1.0;
            top3Correlations_[i] = -1.0;
        }
        
        // 确保之前的线程已经完全清理（处理自动播放结束的情况）
        if (mergeThread_.joinable()) {
            LOGI("Previous mergeThread still joinable, joining before start");
            mergeThread_.join();
        }
        
        // 运行前的参数校验与归一化，保持低延迟假设
        if (workingSampleRate_ <= 0) {
            LOGW("Invalid sampleRate=%d, fallback to 48000", workingSampleRate_);
            workingSampleRate_ = 48000;
        }
        if (workingChannelCount_ <= 0 || workingChannelCount_ > 2) {
            LOGW("Unsupported channelCount=%d, normalize to mono", workingChannelCount_);
            workingChannelCount_ = 1;
        }
        
        // Step 1: 解码原始音频为严格匹配播放配置的交错 PCM（S16 或 Float）
        // 显式指定输出PCM文件名，避免函数签名不匹配
        std::string outPcmName = outFormatFloat_ ? std::string("orig_f32le.pcm") : std::string("orig_s16le.pcm");
        decodedPcmPath_ = decode_to_pcm_interleaved(
                inputPath.c_str(),
                cacheDir.c_str(),
                workingSampleRate_,
                workingChannelCount_,
                outPcmName.c_str(),
                outFormatFloat_);
        // 标记解码格式（用于播放路径的健壮处理）
        decodedIsFloat_ = outFormatFloat_;
        if (decodedPcmPath_.empty()) {
            return -1;
        }
        
        // 保存输出路径
        outputM4aPath_ = outputM4a;
        
        // 缓存 JavaVM 以便合成线程回调 Java 层
        if (vm_ == nullptr) {
            env->GetJavaVM(&vm_);
        }
        if (latencyEventsClass_ == nullptr) {
            jclass local = env->FindClass(LATENCY_EVENTS_CLASS);
            if (local) {
                latencyEventsClass_ = (jclass)env->NewGlobalRef(local);
                env->DeleteLocalRef(local);
                LOGI("Cached LatencyEvents class global ref");
            } else {
                LOGE("Failed to find LatencyEvents at start");
            }
        }
        
        // 初始化环形缓冲（按字节容量分配），并设置输入/输出格式
        size_t bytesPerSec = static_cast<size_t>(workingSampleRate_) * workingChannelCount_ * (outFormatFloat_ ? sizeof(float) : kBytesPerSample);
        size_t capBytes = bytesPerSec * kRingBufferMs / 1000;
        origRb_ = new AudioRingBuffer(capBytes);
        recRb_  = new AudioRingBuffer(capBytes);
        if (origRb_) {
            origRb_->init({workingSampleRate_, workingChannelCount_, outFormatFloat_},
                          {48000, 1, true});
        }
        if (recRb_) {
            recRb_->init({workingSampleRate_, workingChannelCount_, inFormatFloat_},
                         {48000, 1, true});
        }
        
        // 读取PCM文件到内存（最大50M）
        if (!loadPcmFile()) {
            cleanup();
            return -2;
        }
        
        // 创建回调对象
        playCb_ = std::make_unique<PlayCallback>(this);
        recCb_ = std::make_unique<RecCallback>(this);
        
        // 打开输出流（使用回调方式）
        oboe::AudioStreamBuilder outBuilder;
        outBuilder.setDirection(oboe::Direction::Output)
                ->setSharingMode(outExclusive_ ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
                ->setPerformanceMode(outLowLatency_ ? oboe::PerformanceMode::LowLatency : oboe::PerformanceMode::None)
                ->setSampleRate(workingSampleRate_)
                ->setChannelCount(workingChannelCount_)
                ->setFormat(outFormatFloat_ ? oboe::AudioFormat::Float : oboe::AudioFormat::I16)
                ->setCallback(playCb_.get());
        oboe::AudioStream* outRaw = nullptr;
        if (outBuilder.openStream(&outRaw) != oboe::Result::OK) {
            cleanup();
            return -2;
        }
        outputStream_.reset(outRaw);
        
        // 优化输出流缓冲区大小以降低延迟
        int32_t outFramesPerBurst = outputStream_->getFramesPerBurst();
        int32_t outInitialBufferSize = outputStream_->getBufferSizeInFrames();
        int32_t outTargetBufferSize = outFramesPerBurst * 2; // 2倍突发大小（约4ms延迟）
        if (outInitialBufferSize > outTargetBufferSize) {
            auto outBufResult = outputStream_->setBufferSizeInFrames(outTargetBufferSize);
            if (outBufResult) {
                LOGI("Output buffer optimized: %d -> %d frames (%.2f ms -> %.2f ms)",
                     outInitialBufferSize,
                     outBufResult.value(),
                     outInitialBufferSize * 1000.0 / workingSampleRate_,
                     outBufResult.value() * 1000.0 / workingSampleRate_);
            }

        }
        LOGI("Open Output stream: %s", oboe::convertToText(outputStream_.get()));
        
        // 在启动输出流之前设置 running_，因为 requestStart() 可能会立即触发回调
        running_.store(true);
        startTime_ = std::chrono::steady_clock::now();
        outputStream_->requestStart();
        
        // 打开输入流
        oboe::AudioStreamBuilder inBuilder;
        inBuilder.setDirection(oboe::Direction::Input)
                ->setSharingMode(inExclusive_ ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
                ->setPerformanceMode(inLowLatency_ ? oboe::PerformanceMode::LowLatency : oboe::PerformanceMode::None)
                ->setSampleRate(workingSampleRate_)
                ->setChannelCount(workingChannelCount_)
                ->setFormat(inFormatFloat_ ? oboe::AudioFormat::Float : oboe::AudioFormat::I16)
                ->setCallback(recCb_.get());
        oboe::AudioStream* inRaw = nullptr;
        if (inBuilder.openStream(&inRaw) != oboe::Result::OK) {
            running_.store(false);
            outputStream_->requestStop();
            outputStream_->close();
            outputStream_.reset();
            cleanup();
            return -3;
        }
        inputStream_.reset(inRaw);
        
        // 优化输入流缓冲区大小以降低延迟（关键：当前默认62ms太高）
        int32_t inFramesPerBurst = inputStream_->getFramesPerBurst();
        int32_t inInitialBufferSize = inputStream_->getBufferSizeInFrames();
        int32_t inTargetBufferSize = inFramesPerBurst * 2; // 2倍突发大小（约4ms延迟）
        if (inInitialBufferSize > inTargetBufferSize) {
            auto inBufResult = inputStream_->setBufferSizeInFrames(inTargetBufferSize);
            if (inBufResult) {
                LOGI("Input buffer optimized: %d -> %d frames (%.2f ms -> %.2f ms)",
                     inInitialBufferSize,
                     inBufResult.value(),
                     inInitialBufferSize * 1000.0 / workingSampleRate_,
                     inBufResult.value() * 1000.0 / workingSampleRate_);
            } else {
                // 如果2倍失败，尝试4倍（仍比默认值好）
                inTargetBufferSize = inFramesPerBurst * 4;
                auto inBufResult2 = inputStream_->setBufferSizeInFrames(inTargetBufferSize);
                if (inBufResult2) {
                    LOGI("Input buffer set to 4x burst: %d frames (%.2f ms)",
                         inBufResult2.value(),
                         inBufResult2.value() * 1000.0 / kSampleRate);
                } else {
                    LOGW("Failed to optimize input buffer, using default: %d frames (%.2f ms)",
                         inInitialBufferSize,
                         inInitialBufferSize * 1000.0 / workingSampleRate_);
                }
            }
        }

        LOGI("Open Input stream: %s", oboe::convertToText(inputStream_.get()));
        
        inputStream_->requestStart();

        // 报告实际的设备配置到Java层
        notifyJavaConfig(buildStreamConfigString(outputStream_.get()), buildStreamConfigString(inputStream_.get()));
        
        // 启动合成线程
        std::string mergedPathLocal = joinPath(cacheDir, "merged_lr_f32le.pcm");
        LOGI("mergedPathLocal=%s", mergedPathLocal.c_str());
        mergeThread_ = std::thread([this, mergedPathLocal]() {
            this->mergeThreadProc(mergedPathLocal);
        });
        
        return 0;
    }
    
    // 停止延迟测试
    void stop() {
        bool wasRunning = running_.load();
        running_.store(false);
        
        // 无论running_状态如何，都要join线程（处理自动播放结束的情况）
        if (mergeThread_.joinable()) {
            mergeThread_.join();
        }
        
        // 只有在实际运行时才需要停止音频流
        if (wasRunning) {
            if (inputStream_) {
                inputStream_->requestStop();
                inputStream_->close();
                inputStream_.reset();
            }
            if (outputStream_) {
                outputStream_->requestStop();
                outputStream_->close();
                outputStream_.reset();
            }
        }
    }
    
    bool isRunning() const {
        return running_.load();
    }
    
    // 配置更新接口（供 JNI 调用）
    void setWorkingSampleRate(int sr) { workingSampleRate_ = sr; }
    void setWorkingChannelCount(int ch) { workingChannelCount_ = ch; }
    void setOutExclusive(bool v) { outExclusive_ = v; }
    void setOutLowLatency(bool v) { outLowLatency_ = v; }
    void setOutFormatFloat(bool v) { outFormatFloat_ = v; }
    void setInExclusive(bool v) { inExclusive_ = v; }
    void setInLowLatency(bool v) { inLowLatency_ = v; }
    void setInFormatFloat(bool v) { inFormatFloat_ = v; }

private:
    // 允许回调类访问私有成员
    friend class PlayCallback;
    friend class RecCallback;
    static std::string joinPath(const std::string& a, const std::string& b) {
        if (a.empty()) return b;
        if (a.back() == '/') return a + b;
        return a + "/" + b;
    }
    
    bool loadPcmFile() {
        const size_t kMaxPcmSize = 50 * 1024 * 1024; // 50MB
        pcmBuffer_.clear();
        pcmPosition_.store(0);
        
        FILE* fp = fopen(decodedPcmPath_.c_str(), "rb");
        if (!fp) {
            LOGE("Failed to open PCM file: %s", decodedPcmPath_.c_str());
            return false;
        }
        
        // 获取文件大小
        fseek(fp, 0, SEEK_END);
        long fileSize = ftell(fp);
        fseek(fp, 0, SEEK_SET);
        if (fileSize < 0) {
            LOGE("Failed to get PCM file size");
            fclose(fp);
            return false;
        }
        
        // 确定读取大小：如果文件小于50MB则读取整个文件，否则只读取前50MB
        size_t readSize = static_cast<size_t>(fileSize);
        if (readSize > kMaxPcmSize) {
            readSize = kMaxPcmSize;
            LOGI("PCM file size (%zu bytes) exceeds 50MB limit, reading first 50MB only", static_cast<size_t>(fileSize));
        }
        
        // 计算预热所需的静音数据量（在有效PCM数据之前填充）
        const int sr = workingSampleRate_ > 0 ? workingSampleRate_ : kSampleRate;
        const int ch = workingChannelCount_ > 0 ? workingChannelCount_ : kChannelCount;
        const size_t bytesPerSample = decodedIsFloat_ ? sizeof(float) : kBytesPerSample;
        size_t preheatSilenceBytes = static_cast<size_t>(sr) * ch * bytesPerSample * kPreheatMs / 1000;
        size_t totalBufferSize = preheatSilenceBytes + readSize;
        
        // 分配缓冲区：静音数据 + 有效PCM数据
        pcmBuffer_.resize(totalBufferSize);
        
        // 填充静音数据（全0）
        if (preheatSilenceBytes > 0) {
            memset(pcmBuffer_.data(), 0, preheatSilenceBytes);
            LOGI("Added preheat silence: %zu bytes (%.2f ms)", 
                 preheatSilenceBytes, 
                 preheatSilenceBytes * 1000.0 / (sr * ch * bytesPerSample));
        }
        
        // 读取有效PCM数据到静音数据之后
        size_t actualRead = fread(pcmBuffer_.data() + preheatSilenceBytes, 1, readSize, fp);
        fclose(fp);
        
        if (actualRead == 0) {
            LOGE("Failed to read PCM file or file is empty");
            pcmBuffer_.clear();
            return false;
        }
        
        // 调整缓冲区到实际大小（静音 + 实际读取的数据）
        pcmBuffer_.resize(preheatSilenceBytes + actualRead);
        LOGI("Loaded PCM file: %zu bytes silence + %zu bytes audio = %zu bytes total", 
             preheatSilenceBytes, actualRead, pcmBuffer_.size());
        return true;
    }
    
    // 辅助函数：根据配置将多声道音频转换为单声道（兼容单声道和双声道输入）
    void channelsToMono(const int16_t* input, size_t inputSamples, int16_t* mono, size_t& outMonoSamples) {
        const int ch = workingChannelCount_ > 0 ? workingChannelCount_ : kChannelCount;
        if (ch == 2) {
            // 双声道配置：每2个样本（L,R）合并为1个单声道样本（取平均值）
            if (inputSamples % 2 != 0) {
                // 数据不完整（奇数个样本），丢弃最后一个样本
                inputSamples = inputSamples - 1;
            }
            size_t monoFrames = inputSamples / 2;
            for (size_t i = 0; i < monoFrames; ++i) {
                int32_t left = static_cast<int32_t>(input[2 * i]);
                int32_t right = static_cast<int32_t>(input[2 * i + 1]);
                mono[i] = static_cast<int16_t>((left + right) >> 1);  // 取平均值，防止溢出
            }
            outMonoSamples = monoFrames;
        } else if (ch == 1) {
            // 单声道配置：已经是单声道，直接复制
            for (size_t i = 0; i < inputSamples; ++i) {
                mono[i] = input[i];
            }
            outMonoSamples = inputSamples;
        } else {
            // 其他多声道配置：暂时不支持，直接复制第一个声道
            LOGE("Unsupported channel count: %d, using first channel only", ch);
            for (size_t i = 0; i < inputSamples; ++i) {
                mono[i] = input[i * ch];
            }
            outMonoSamples = inputSamples / ch;
        }
    }
    
    void mergeThreadProc(const std::string& mergedPath) {
        // 如果发生错误，不进行后续处理（包括录音检测和编码等操作）
        if (!running_.load() || errorOccurred_.load()) {
            LOGW("mergeThreadProc: stopped before starting (likely due to error)");
            return;
        }
        
        FILE* fp = fopen(mergedPath.c_str(), "wb");
        if (!fp) return;
        
        // 统一转换参数：48kHz / float / mono
        const int targetSr = 48000;
        const int chunkMs = 20;
        const size_t outFramesPerChunk = static_cast<size_t>(targetSr) * chunkMs / 1000;
        //使用2倍大小的缓存，防止越界
        std::vector<float> leftMonoF(outFramesPerChunk * 2);
        std::vector<float> rightMonoF(outFramesPerChunk * 2);
        std::vector<float> interleavedFloat(outFramesPerChunk * 2 * 2); // 立体声，所以是2倍帧数
        // 剩余数据缓存：保存上次未处理完的数据
        size_t leftRemainingFrames = 0;
        size_t rightRemainingFrames = 0; 
        
        bool started = false;
        
        while (running_.load()) {
            // 预热门控：等待预热期结束
            if (!started) {
                auto now = std::chrono::steady_clock::now();
                auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - startTime_).count();
                if (elapsed < kPreheatMs) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(100));
                    continue;
                }
                // 预热结束，清空两路 ring 以对齐起点
                if (origRb_) origRb_->clear();
                if (recRb_)  recRb_->clear();
                started = true;
                LOGI("preheat done, start merging");
            }

            // 从ring buffer读取数据到剩余数据后面
            size_t lNewFrames = 0;
            size_t rNewFrames = 0;
            
            if (origRb_) {
                lNewFrames = origRb_->readConvert(leftMonoF.data() + leftRemainingFrames, outFramesPerChunk - leftRemainingFrames);
            }
            if (recRb_) {
                rNewFrames = recRb_->readConvert(rightMonoF.data() + rightRemainingFrames, outFramesPerChunk - rightRemainingFrames);
            }

            // 读取并转换到统一格式
            size_t lFrames = leftRemainingFrames + lNewFrames;
            size_t rFrames = rightRemainingFrames + rNewFrames;
            
            size_t frames = std::min(lFrames, rFrames);
            if (frames <= 0) { // 如果两路中有一路无数据，稍后重试
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
                continue;
            }

            // 对齐后分别写入左右声道（不混合），再交错为 float 立体声
            for (size_t i = 0; i < frames; ++i) {
                float l = leftMonoF[i];
                float r = rightMonoF[i];
                interleavedFloat[2 * i] = l;
                interleavedFloat[2 * i + 1] = r;
            }

            // 直接写入 float 数据到文件
            fwrite(interleavedFloat.data(), sizeof(float), frames * 2, fp);

            // 处理剩余数据：将未使用的数据移到缓冲区头部
            leftRemainingFrames = lFrames - frames;
            rightRemainingFrames = rFrames - frames;
            if (leftRemainingFrames > 0) {
                std::memmove(leftMonoF.data(), leftMonoF.data() + frames, leftRemainingFrames * sizeof(float));
            }
            if (rightRemainingFrames > 0) {
                std::memmove(rightMonoF.data(), rightMonoF.data() + frames, rightRemainingFrames * sizeof(float));
            }
        }
        
        fclose(fp);
        
        // 如果发生错误，不进行后续处理（包括录音检测和编码等操作）
        if (errorOccurred_.load()) {
            LOGW("mergeThreadProc: Error occurred, skipping detection and encoding");
            return;
        }
        
        // 通知Java层：开始检测延迟
        notifyJavaDetecting();
        
        // 自动增益处理：如果右声道音量过低，则放大右声道使其与左声道匹配
        // 在applyAutoGain中会调用detectDelay进行延迟检测
        if (!mergedPath.empty() && !errorOccurred_.load()) {
            applyAutoGain(mergedPath);
        }
        
        // 如果发生错误，不进行编码
        if (errorOccurred_.load()) {
            LOGW("mergeThreadProc: Error occurred, skipping encoding");
            return;
        }
        
        // 自动编码合成的 PCM
        int rc = -1;
        if (!mergedPath.empty() && !outputM4aPath_.empty()) {
            // 合成输出为 S16 交错 PCM；applyAutoGain 已转换为 int16 格式
            rc = encode_pcm_to_m4a(mergedPath.c_str(), outputM4aPath_.c_str(), workingSampleRate_, 2, false);
            LOGI("auto encode result=%d out=%s", rc, outputM4aPath_.c_str());
        } else {
            LOGE("auto encode skipped: path empty");
        }
        
        // 回调 Java 通知完成（只有在没有错误时才通知）
        if (!errorOccurred_.load()) {
            notifyJavaCompleted(rc);
        }
    }
    
    // 单窗口延迟检测辅助函数：检测指定窗口内的延迟
    // 使用归一化互相关(NCC)方法，在浮点数据上计算左右声道的延迟
    // 返回是否成功检测，通过输出参数返回延迟值（样本）和相关度
    bool detectDelayInWindow(
        const std::vector<float>& left,
        const std::vector<float>& right,
        size_t windowStart,
        size_t windowSize,
        size_t totalFrames,
        size_t& outDelaySamples,
        double& outCorrelation) {
        
        // 搜索范围：0到500ms（约24000样本@48kHz）
        const size_t maxDelaySamples = static_cast<size_t>(workingSampleRate_ * 0.5);  // 500ms
        const size_t searchEnd = std::min(maxDelaySamples, totalFrames - windowStart - windowSize);
        
        if (searchEnd < 100 || windowSize < 1000) {
            return false;
        }
        
        // 归一化互相关：搜索最佳延迟
        double bestCorr = -1.0;
        size_t bestDelaySamples = 0;
        
        // 为了提高精度，先进行粗搜索（步进10样本），然后对最佳位置附近进行精细搜索
        const size_t coarseStep = 10;  // 约0.2ms @48kHz
        
        // 粗搜索
        for (size_t delay = 0; delay <= searchEnd && (windowStart + windowSize + delay) < totalFrames; delay += coarseStep) {
            double corr = 0.0;
            double leftNorm = 0.0;
            double rightNorm = 0.0;
            size_t validSamples = 0;
            
            for (size_t i = 0; i < windowSize; ++i) {
                size_t leftIdx = windowStart + i;
                size_t rightIdx = windowStart + i + delay;
                
                if (leftIdx < totalFrames && rightIdx < totalFrames) {
                    double lVal = static_cast<double>(left[leftIdx]);
                    double rVal = static_cast<double>(right[rightIdx]);
                    corr += lVal * rVal;
                    leftNorm += lVal * lVal;
                    rightNorm += rVal * rVal;
                    validSamples++;
                }
            }
            
            // 归一化互相关（NCC）
            if (validSamples > 0 && leftNorm > 0 && rightNorm > 0) {
                double normalizedCorr = corr / std::sqrt(leftNorm * rightNorm);
                if (normalizedCorr > bestCorr) {
                    bestCorr = normalizedCorr;
                    bestDelaySamples = delay;
                }
            }
        }
        
        if (bestCorr < 0) {
            return false;
        }
        
        // 精细搜索：在最佳位置附近进行样本级精确搜索
        size_t fineSearchStart = (bestDelaySamples > coarseStep) ? (bestDelaySamples - coarseStep) : 0;
        size_t fineSearchEnd = std::min(bestDelaySamples + coarseStep, searchEnd);
        size_t refinedBestDelay = bestDelaySamples;
        double refinedBestCorr = bestCorr;
        
        for (size_t delay = fineSearchStart; delay <= fineSearchEnd && (windowStart + windowSize + delay) < totalFrames; ++delay) {
            double corr = 0.0;
            double leftNorm = 0.0;
            double rightNorm = 0.0;
            size_t validSamples = 0;
            
            for (size_t i = 0; i < windowSize; ++i) {
                size_t leftIdx = windowStart + i;
                size_t rightIdx = windowStart + i + delay;
                
                if (leftIdx < totalFrames && rightIdx < totalFrames) {
                    double lVal = static_cast<double>(left[leftIdx]);
                    double rVal = static_cast<double>(right[rightIdx]);
                    corr += lVal * rVal;
                    leftNorm += lVal * lVal;
                    rightNorm += rVal * rVal;
                    validSamples++;
                }
            }
            
            if (validSamples > 0 && leftNorm > 0 && rightNorm > 0) {
                double normalizedCorr = corr / std::sqrt(leftNorm * rightNorm);
                if (normalizedCorr > refinedBestCorr) {
                    refinedBestCorr = normalizedCorr;
                    refinedBestDelay = delay;
                }
            }
        }
        
        outDelaySamples = refinedBestDelay;
        outCorrelation = refinedBestCorr;
        return true;
    }

    // float 版本的 findHighEnergyWindowStarts 函数
    std::vector<size_t> findHighEnergyWindowStarts(
        const std::vector<float>& left,
        size_t totalFrames,
        size_t windowSize,
        size_t startOffset) {
        std::vector<size_t> candidates;
        if (totalFrames <= startOffset + windowSize) return candidates;

        // 参数：短时窗30ms，扫描步长10ms，命中后跳过700ms
        const size_t energyWindow = static_cast<size_t>(workingSampleRate_ * 0.03);   // 30ms
        const size_t energyStep   = static_cast<size_t>(workingSampleRate_ * 0.01);   // 10ms
        const size_t skipGap      = static_cast<size_t>(workingSampleRate_ * 0.70);   // 命中后跳过700ms

        if (energyWindow == 0 || energyStep == 0) return candidates;

        // -30 dBFS 阈值（使用均方能量比较，避免开方）：
        // float 范围是 [-1.0, 1.0]，所以 FS = 1.0
        const double fullScaleSq = 1.0 * 1.0;
        const double thresholdMeanSq = fullScaleSq * 0.001; // -30 dBFS

        size_t s = startOffset;
        while (s + energyWindow <= totalFrames) {
            double sumSq = 0.0;
            const size_t end = s + energyWindow;
            for (size_t i = s; i < end; ++i) {
                const double v = static_cast<double>(left[i]);
                sumSq += v * v;
            }
            const double meanSq = sumSq / static_cast<double>(energyWindow);

            if (meanSq >= thresholdMeanSq) {
                // 命中一个高能量起点
                if (s + windowSize <= totalFrames) {
                    candidates.push_back(s);
                }
//                if (candidates.size() >= 30) break; // 限制候选数量
                // 跳过700ms，避免选到同一数字内部的重复峰
                s += skipGap;
            } else {
                // 正常推进
                s += energyStep;
            }
        }

        return candidates;
    }

    // float 版本的 detectDelay 函数
    double detectDelay(const std::vector<float>& left, const std::vector<float>& right, size_t totalFrames) {
        // 参数配置
        const size_t windowSize = static_cast<size_t>(workingSampleRate_ * 0.7);  // 700ms秒窗口
        const size_t startOffset = static_cast<size_t>(workingSampleRate_ * 0.1); // 从0.1秒开始，避开预热期

        // 初始化前3个窗口信息
        for (int i = 0; i < 3; ++i) {
            top3Delays_[i] = -1.0;
            top3Correlations_[i] = -1.0;
        }
        
        if (totalFrames < startOffset + windowSize) {
            LOGW("detectDelay: Not enough data, totalFrames=%zu, need at least %zu", 
                 totalFrames, startOffset + windowSize);
            return -1.0;
        }
        
        // 存储每个窗口的检测结果：延迟值（样本）和相关度
        struct WindowResult {
            size_t delaySamples;
            double correlation;
            
            // 用于排序：按相关度降序
            bool operator<(const WindowResult& other) const {
                return correlation > other.correlation;  // 降序排序
            }
        };
        std::vector<WindowResult> allResults;
        const double earlyStopThreshold = 0.5;  // 早期停止阈值：相关度大于此值的窗口数达到3个即可停止
        const size_t earlyStopCount = 3;        // 早期停止所需的高质量窗口数
        
        // 基于能量的候选窗口起点（针对"数字+停顿"的语音结构）
        const std::vector<size_t> windowStarts = findHighEnergyWindowStarts(left, totalFrames, windowSize, startOffset);

        // 遍历候选窗口进行检测
        size_t windowCount = 0;
        size_t highCorrelationCount = 0;  // 相关度>0.5的窗口数
        for (size_t windowStart : windowStarts) {
            if (windowStart + windowSize > totalFrames) continue;
            windowCount++;
            size_t delaySamples = 0;
            double correlation = 0.0;
            if (detectDelayInWindow(left, right, windowStart, windowSize, totalFrames, delaySamples, correlation)) {
                allResults.push_back({delaySamples, correlation});
                LOGI("detectDelay: Candidate %zu (start=%.2fs): delay=%zu samples (%.2f ms), correlation=%.4f",
                     windowCount, windowStart * 1000.0 / workingSampleRate_,
                     delaySamples, delaySamples * 1000.0 / workingSampleRate_, correlation);
                if (correlation > earlyStopThreshold) {
                    highCorrelationCount++;
                    if (highCorrelationCount >= earlyStopCount) {
                        LOGI("detectDelay: Early stop triggered: found %zu windows with correlation > %.2f",
                             highCorrelationCount, earlyStopThreshold);
                        break;
                    }
                }
            }
        }

        // 如果未获取足够的结果，回退到原先的均匀滑窗策略
        if (allResults.size() < 3) {
            LOGW("detectDelay: Not enough results, using uniform sliding window strategy");
            const size_t windowStep = static_cast<size_t>(workingSampleRate_ * 0.5);   // 50%重叠，0.5秒步进
            for (size_t windowStart = startOffset; windowStart + windowSize <= totalFrames; windowStart += windowStep) {
                size_t delaySamples = 0; double correlation = 0.0;
                if (detectDelayInWindow(left, right, windowStart, windowSize, totalFrames, delaySamples, correlation)) {
                    allResults.push_back({delaySamples, correlation});
                }
            }
        }
        
        if (allResults.empty()) {
            LOGW("detectDelay: No valid windows found (total windows=%zu)", windowCount);
            return -1.0;
        }
        
        // 按相关度降序排序
        std::sort(allResults.begin(), allResults.end());
        
        // 使用相关度最高的3个窗口进行统计
        size_t windowsToUse = std::min(static_cast<size_t>(3), allResults.size());
        
        std::vector<WindowResult> selectedResults(allResults.begin(), allResults.begin() + windowsToUse);
        
        LOGI("detectDelay: Using top %zu windows (correlation range: %.4f - %.4f) out of %zu total windows",
             windowsToUse, selectedResults.back().correlation, selectedResults.front().correlation, allResults.size());
        
        // 存储前3个窗口的详细信息（用于UI显示）
        for (size_t i = 0; i < 3 && i < selectedResults.size(); ++i) {
            top3Delays_[i] = selectedResults[i].delaySamples * 1000.0 / workingSampleRate_;  // 转换为毫秒
            top3Correlations_[i] = selectedResults[i].correlation;
            LOGI("detectDelay: Top window #%zu: delay=%.2f ms, correlation=%.4f",
                 i + 1, top3Delays_[i], top3Correlations_[i]);
        }
        
        // 使用加权平均聚合结果：权重基于相关度
        // 相关度越高，权重越大
        double totalWeight = 0.0;
        double weightedDelaySum = 0.0;
        
        for (const auto& result : selectedResults) {
            // 权重：相关度的平方，使高相关度窗口影响更大
            double weight = result.correlation * result.correlation;
            weightedDelaySum += static_cast<double>(result.delaySamples) * weight;
            totalWeight += weight;
        }
        
        if (totalWeight <= 0.0) {
            LOGW("detectDelay: Total weight is zero");
            return -1.0;
        }
        
        size_t averageDelaySamples = static_cast<size_t>(weightedDelaySum / totalWeight + 0.5);
        double delayMs = averageDelaySamples * 1000.0 / workingSampleRate_;
        
        // 计算标准差，验证一致性
        double variance = 0.0;
        for (const auto& result : selectedResults) {
            double weight = result.correlation * result.correlation;
            double diff = static_cast<double>(result.delaySamples) - static_cast<double>(averageDelaySamples);
            variance += weight * diff * diff;
        }
        double stdDevSamples = std::sqrt(variance / totalWeight);
        double stdDevMs = stdDevSamples * 1000.0 / workingSampleRate_;
        
        // 计算平均相关度
        double avgCorrelation = 0.0;
        for (const auto& result : selectedResults) {
            avgCorrelation += result.correlation;
        }
        avgCorrelation /= selectedResults.size();
        
        LOGI("detectDelay: Multi-window result - using %zu/%zu windows, average delay=%.2f ms (std=%.2f ms), avg correlation=%.4f",
             windowsToUse, allResults.size(), delayMs, stdDevMs, avgCorrelation);
        
        // 如果标准差过大，警告可能不准确
        if (stdDevMs > 5.0) {
            LOGW("detectDelay: High standard deviation (%.2f ms), delay may be inaccurate", stdDevMs);
        }
        
        return delayMs;
    }

    // float 版本的 detectDelay 函数（立体声交错）
    double detectDelay(const std::vector<float>& interleaved, size_t totalFrames) {
        std::vector<float> leftChannel(totalFrames);
        std::vector<float> rightChannel(totalFrames);
        for (size_t i = 0; i < totalFrames; ++i) {
            leftChannel[i] = interleaved[i * 2];
            rightChannel[i] = interleaved[i * 2 + 1];
        }
        return detectDelay(leftChannel, rightChannel, totalFrames);
    }

    
    // 自动增益处理：分析左右声道音量，如果右声道过低则放大
    void applyAutoGain(const std::string& pcmPath) {
        FILE* fp = fopen(pcmPath.c_str(), "rb");
        if (!fp) {
            LOGE("applyAutoGain: Failed to open PCM file: %s", pcmPath.c_str());
            return;
        }
        
        // 获取文件大小
        fseek(fp, 0, SEEK_END);
        long fileSize = ftell(fp);
        fseek(fp, 0, SEEK_SET);
        if (fileSize <= 0 || fileSize % (kChannelCount * sizeof(float)) != 0) {
            LOGE("applyAutoGain: Invalid PCM file size: %ld", fileSize);
            fclose(fp);
            return;
        }
        
        size_t totalFrames = static_cast<size_t>(fileSize) / (kChannelCount * sizeof(float));
        
        // 读取整个文件到内存（float 格式）
        std::vector<float> interleavedFloat(totalFrames * kChannelCount);
        size_t readFrames = fread(interleavedFloat.data(), sizeof(float), totalFrames * kChannelCount, fp);
        fclose(fp);
        
        if (readFrames != totalFrames * kChannelCount) {
            LOGE("applyAutoGain: Failed to read PCM data, expected %zu frames, got %zu", 
                 totalFrames * kChannelCount, readFrames);
            return;
        }
        
        // 检测延迟：右声道相对左声道的延迟（使用 float 数据）
        detectedDelayMs_ = detectDelay(interleavedFloat, totalFrames);
        if (detectedDelayMs_ >= 0) {
            LOGI("applyAutoGain: Detected delay = %.2f ms", detectedDelayMs_);
        } else {
            LOGW("applyAutoGain: Delay detection failed");
        }
        
        // 分离左右声道并计算RMS和峰值
        double leftSumSquares = 0.0;
        double rightSumSquares = 0.0;
        float leftPeak = 0.0f;
        float rightPeak = 0.0f;
        
        for (size_t i = 0; i < totalFrames; ++i) {
            float leftSample = interleavedFloat[i * kChannelCount];
            float rightSample = interleavedFloat[i * kChannelCount + 1];
            
            leftSumSquares += static_cast<double>(leftSample) * leftSample;
            rightSumSquares += static_cast<double>(rightSample) * rightSample;
            
            float leftAbs = std::abs(leftSample);
            float rightAbs = std::abs(rightSample);
            if (leftAbs > leftPeak) leftPeak = leftAbs;
            if (rightAbs > rightPeak) rightPeak = rightAbs;
        }
        
        // 计算RMS
        double leftRms = std::sqrt(leftSumSquares / totalFrames);
        double rightRms = std::sqrt(rightSumSquares / totalFrames);
        
        LOGI("applyAutoGain: Left RMS=%.2f, Peak=%.4f | Right RMS=%.2f, Peak=%.4f", 
             leftRms, leftPeak, rightRms, rightPeak);
        
        // 如果右声道RMS太小（小于左声道的20%），则进行增益处理
        const double kMinRatio = 0.2;  // 最小音量比例阈值
        if (leftRms > 0 && rightRms > 0 && rightRms < leftRms * kMinRatio) {
            // 计算基于RMS的增益
            double gainRms = leftRms / rightRms;
            
            // 峰值保护：确保放大后不会超过1.0
            double maxGainFromPeak = 1.0;
            if (rightPeak > 0) {
                maxGainFromPeak = 1.0 / rightPeak;  // float 范围是 [-1.0, 1.0]
            }
            
            // 使用较小的增益值，避免削波
            double finalGain = std::min(gainRms, maxGainFromPeak * 0.95);  // 留5%余量
            
            LOGI("applyAutoGain: Applying gain %.2fx (RMS-based=%.2fx, Peak-limited=%.2fx)", 
                 finalGain, gainRms, maxGainFromPeak);
            
            // 应用增益到右声道
            for (size_t i = 0; i < totalFrames; ++i) {
                float newSample = interleavedFloat[i * kChannelCount + 1] * static_cast<float>(finalGain);
                // 限制在float范围内 [-1.0, 1.0]
                if (newSample > 1.0f) {
                    interleavedFloat[i * kChannelCount + 1] = 1.0f;
                } else if (newSample < -1.0f) {
                    interleavedFloat[i * kChannelCount + 1] = -1.0f;
                } else {
                    interleavedFloat[i * kChannelCount + 1] = newSample;
                }
            }
            
            // 重新验证增益后的RMS和峰值
            double newRightSumSquares = 0.0;
            float newRightPeak = 0.0f;
            for (size_t i = 0; i < totalFrames; ++i) {
                float rightSample = interleavedFloat[i * kChannelCount + 1];
                newRightSumSquares += static_cast<double>(rightSample) * rightSample;
                float rightAbs = std::abs(rightSample);
                if (rightAbs > newRightPeak) newRightPeak = rightAbs;
            }
            double newRightRms = std::sqrt(newRightSumSquares / totalFrames);
            LOGI("applyAutoGain: After gain - Right RMS=%.2f, Peak=%.4f", newRightRms, newRightPeak);
            
            // 将 float 转换为 int16 并写回文件
            std::vector<int16_t> interleavedS16(totalFrames * kChannelCount);
            auto clamp16 = [](float x) {
                if (x > 1.0f) x = 1.0f; if (x < -1.0f) x = -1.0f;
                return static_cast<int16_t>(std::lrintf(x * 32767.0f));
            };
            for (size_t i = 0; i < totalFrames * kChannelCount; ++i) {
                interleavedS16[i] = clamp16(interleavedFloat[i]);
            }
            
            FILE* outFp = fopen(pcmPath.c_str(), "wb");
            if (!outFp) {
                LOGE("applyAutoGain: Failed to open PCM file for writing: %s", pcmPath.c_str());
                return;
            }
            fwrite(interleavedS16.data(), sizeof(int16_t), totalFrames * kChannelCount, outFp);
            fclose(outFp);
            LOGI("applyAutoGain: Successfully applied gain, converted to int16 and saved to file");
        } else {
            LOGI("applyAutoGain: Right channel volume is sufficient (ratio=%.2f), no gain applied", 
                 leftRms > 0 ? rightRms / leftRms : 0.0);
        }
    }
    
    void notifyJavaDetecting() {
        if (!vm_) return;
        JNIEnv* envCb = nullptr;
        bool needDetach = false;
        if (vm_->GetEnv(reinterpret_cast<void**>(&envCb), JNI_VERSION_1_6) != JNI_OK) {
            if (vm_->AttachCurrentThread(&envCb, nullptr) == JNI_OK) needDetach = true;
        }
        if (envCb) {
            jclass cls = latencyEventsClass_ ? latencyEventsClass_ : envCb->FindClass(LATENCY_EVENTS_CLASS);
            if (cls) {
                jmethodID mid = envCb->GetStaticMethodID(cls, "notifyDetecting", "()V");
                if (mid) {
                    envCb->CallStaticVoidMethod(cls, mid);
                } else {
                    LOGE("notifyDetecting not found");
                }
                if (!latencyEventsClass_) envCb->DeleteLocalRef(cls);
            } else {
                LOGE("LatencyEvents class not found");
            }
        }
        if (needDetach) vm_->DetachCurrentThread();
    }
    
    void notifyJavaCompleted(int rc) {
        if (!vm_) return;
        JNIEnv* envCb = nullptr;
        bool needDetach = false;
        if (vm_->GetEnv(reinterpret_cast<void**>(&envCb), JNI_VERSION_1_6) != JNI_OK) {
            if (vm_->AttachCurrentThread(&envCb, nullptr) == JNI_OK) needDetach = true;
        }
        if (envCb) {
            jclass cls = latencyEventsClass_ ? latencyEventsClass_ : envCb->FindClass(LATENCY_EVENTS_CLASS);
            if (cls) {
                // 方法签名: notifyCompleted(String outputPath, int resultCode, double avgDelay, 
                //                            double delay1, double corr1, double delay2, double corr2, double delay3, double corr3)
                jmethodID mid = envCb->GetStaticMethodID(cls, "notifyCompleted", "(Ljava/lang/String;IDDDDDDD)V");
                if (mid) {
                    jstring jOut = envCb->NewStringUTF(outputM4aPath_.c_str());
                    envCb->CallStaticVoidMethod(cls, mid, jOut, 
                                                 (jint)rc, 
                                                 (jdouble)detectedDelayMs_,
                                                 (jdouble)top3Delays_[0],
                                                 (jdouble)top3Correlations_[0],
                                                 (jdouble)top3Delays_[1],
                                                 (jdouble)top3Correlations_[1],
                                                 (jdouble)top3Delays_[2],
                                                 (jdouble)top3Correlations_[2]);
                    envCb->DeleteLocalRef(jOut);
                } else {
                    LOGE("notifyCompleted not found");
                }
                if (!latencyEventsClass_) envCb->DeleteLocalRef(cls);
            } else {
                LOGE("LatencyEvents class not found");
            }
        }
        if (needDetach) vm_->DetachCurrentThread();
    }
    
    void notifyJavaError(const std::string& errorMessage, int errorCode) {
        if (!vm_) return;
        JNIEnv* envCb = nullptr;
        bool needDetach = false;
        if (vm_->GetEnv(reinterpret_cast<void**>(&envCb), JNI_VERSION_1_6) != JNI_OK) {
            if (vm_->AttachCurrentThread(&envCb, nullptr) == JNI_OK) needDetach = true;
        }
        if (envCb) {
            jclass cls = latencyEventsClass_ ? latencyEventsClass_ : envCb->FindClass(LATENCY_EVENTS_CLASS);
            if (cls) {
                // 方法签名: notifyError(String errorMessage, int errorCode)
                jmethodID mid = envCb->GetStaticMethodID(cls, "notifyError", "(Ljava/lang/String;I)V");
                if (mid) {
                    jstring jErrorMsg = envCb->NewStringUTF(errorMessage.c_str());
                    envCb->CallStaticVoidMethod(cls, mid, jErrorMsg, (jint)errorCode);
                    envCb->DeleteLocalRef(jErrorMsg);
                } else {
                    LOGE("notifyError not found");
                }
                if (!latencyEventsClass_) envCb->DeleteLocalRef(cls);
            } else {
                LOGE("LatencyEvents class not found");
            }
        }
        if (needDetach) vm_->DetachCurrentThread();
    }

    std::string buildStreamConfigString(oboe::AudioStream* stream) {
        if (!stream) return std::string("<null>");
        std::string s;
        s += "SR=" + std::to_string(stream->getSampleRate());
        s += " CH=" + std::to_string(stream->getChannelCount());
        s += " FMT=" + std::string(stream->getFormat() == oboe::AudioFormat::I16 ? "I16" : (stream->getFormat() == oboe::AudioFormat::Float ? "Float" : "Other"));
        s += " MODE=" + std::string(stream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared");
        s += " PERF=" + std::string(stream->getPerformanceMode() == oboe::PerformanceMode::LowLatency ? "LowLatency" : "None");
        // 附加设备突发帧数和当前缓冲区大小（帧）
        int32_t fpb = stream->getFramesPerBurst();
        int32_t buf = stream->getBufferSizeInFrames();
        s += " FPB=" + std::to_string(fpb);
        s += " BUF=" + std::to_string(buf);
        return s;
    }

    void notifyJavaConfig(const std::string& outputConfig, const std::string& inputConfig) {
        if (!vm_) return;
        JNIEnv* envCb = nullptr;
        bool needDetach = false;
        if (vm_->GetEnv(reinterpret_cast<void**>(&envCb), JNI_VERSION_1_6) != JNI_OK) {
            if (vm_->AttachCurrentThread(&envCb, nullptr) == JNI_OK) needDetach = true;
        }
        if (envCb) {
            jclass cls = latencyEventsClass_ ? latencyEventsClass_ : envCb->FindClass(LATENCY_EVENTS_CLASS);
            if (cls) {
                jmethodID mid = envCb->GetStaticMethodID(cls, "notifyConfig", "(Ljava/lang/String;Ljava/lang/String;)V");
                if (mid) {
                    jstring jOut = envCb->NewStringUTF(outputConfig.c_str());
                    jstring jIn  = envCb->NewStringUTF(inputConfig.c_str());
                    envCb->CallStaticVoidMethod(cls, mid, jOut, jIn);
                    envCb->DeleteLocalRef(jOut);
                    envCb->DeleteLocalRef(jIn);
                } else {
                    LOGE("notifyConfig not found");
                }
                if (!latencyEventsClass_) envCb->DeleteLocalRef(cls);
            } else {
                LOGE("LatencyEvents class not found");
            }
        }
        if (needDetach) vm_->DetachCurrentThread();
    }
    
    void cleanup() {
        // 清理音频流
        if (inputStream_) {
            inputStream_->requestStop();
            inputStream_->close();
            inputStream_.reset();
        }
        if (outputStream_) {
            outputStream_->requestStop();
            outputStream_->close();
            outputStream_.reset();
        }
        
        // 清理环形缓冲
        delete origRb_; origRb_ = nullptr;
        delete recRb_; recRb_ = nullptr;
        
        // 清理PCM缓冲区
        pcmBuffer_.clear();
        pcmPosition_.store(0);
        
        // 清理回调对象
        playCb_.reset();
        recCb_.reset();
        
        // 清理Java全局引用
        if (latencyEventsClass_) {
            if (vm_) {
                JNIEnv* env = nullptr;
                if (vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
                    env->DeleteGlobalRef(latencyEventsClass_);
                }
            }
            latencyEventsClass_ = nullptr;
        }
        
        // 清空路径
        decodedPcmPath_.clear();
        outputM4aPath_.clear();
        detectedDelayMs_ = -1.0;  // 重置延迟值
        errorOccurred_.store(false);  // 重置错误标志
        for (int i = 0; i < 3; ++i) {
            top3Delays_[i] = -1.0;
            top3Correlations_[i] = -1.0;
        }
    }
    
    // 成员变量
    std::atomic<bool> running_{false};
    std::atomic<bool> errorOccurred_{false};  // 错误标志，避免重复处理错误
    std::thread mergeThread_;
    double detectedDelayMs_{-1.0};  // 检测到的延迟值（毫秒），-1表示未检测或检测失败
    double top3Delays_[3];          // 前3个最高相关度窗口的延迟值（毫秒）
    double top3Correlations_[3];    // 前3个最高相关度窗口的相关度
    std::unique_ptr<oboe::AudioStream> inputStream_;
    std::unique_ptr<oboe::AudioStream> outputStream_;
    std::chrono::steady_clock::time_point startTime_;
    AudioRingBuffer* origRb_ = nullptr;   // 原始PCM（输入格式存储，输出为统一格式）
    AudioRingBuffer* recRb_  = nullptr;   // 录音PCM（输入格式存储，输出为统一格式）
    std::string decodedPcmPath_;     // 解码后的原始PCM
    std::string outputM4aPath_;     // 目标输出m4a
    JavaVM* vm_ = nullptr;           // JavaVM for callbacks
    jclass latencyEventsClass_ = nullptr; // GlobalRef to LatencyEvents
    std::vector<uint8_t> pcmBuffer_; // PCM音频数据内存缓冲区
    std::atomic<size_t> pcmPosition_{0};  // 当前播放位置（字节偏移）
    std::unique_ptr<PlayCallback> playCb_; // 播放回调
    std::unique_ptr<RecCallback> recCb_;   // 录音回调
    int workingSampleRate_{kSampleRate};
    int workingChannelCount_{kChannelCount};
    bool outExclusive_{true};
    bool outLowLatency_{true};
    bool outFormatFloat_{false};
    bool inExclusive_{true};
    bool inLowLatency_{true};
    bool inFormatFloat_{false};
    bool decodedIsFloat_{false};
    };

// 全局对象指针：Java层通过JNI持有
static LatencyTester* gLatencyTester = nullptr;

// JNI函数：创建LatencyTester实例
extern "C" JNIEXPORT jlong JNICALL
Java_me_rjy_oboe_record_demo_LatencyTesterActivity_createLatencyTester(
        JNIEnv* env,
        jobject /* thiz */) {
    if (gLatencyTester != nullptr) {
        LOGW("LatencyTester already exists, deleting old instance");
        delete gLatencyTester;
        gLatencyTester = nullptr;
    }
    gLatencyTester = new LatencyTester();
    LOGI("Created LatencyTester instance: %p", gLatencyTester);
    return reinterpret_cast<jlong>(gLatencyTester);
}

// JNI函数：销毁LatencyTester实例
extern "C" JNIEXPORT void JNICALL
Java_me_rjy_oboe_record_demo_LatencyTesterActivity_destroyLatencyTester(
        JNIEnv* env,
        jobject /* thiz */,
        jlong nativeHandle) {
    LatencyTester* tester = reinterpret_cast<LatencyTester*>(nativeHandle);
    if (tester != nullptr) {
        LOGI("Destroying LatencyTester instance: %p", tester);
        delete tester;
        if (gLatencyTester == tester) {
            gLatencyTester = nullptr;
        }
    }
}

// JNI函数：启动延迟测试
extern "C" JNIEXPORT jint JNICALL
Java_me_rjy_oboe_record_demo_LatencyTesterActivity_startLatencyTest(
        JNIEnv* env,
        jobject /* thiz */,
        jlong nativeHandle,
        jstring jInputPath,
        jstring jCacheDir,
        jstring jOutputM4a,
        jboolean outExclusive,
        jboolean outLowLatency,
        jint outSampleRate,
        jint outChannels,
        jboolean outFormatFloat,
        jboolean inExclusive,
        jboolean inLowLatency,
        jint inSampleRate,
        jint inChannels,
        jboolean inFormatFloat) {
    LatencyTester* tester = reinterpret_cast<LatencyTester*>(nativeHandle);
    if (tester == nullptr) {
        LOGE("LatencyTester instance is null");
        return -1;
    }
    
    if (tester->isRunning()) {
        LOGW("LatencyTester already running");
        return 0;
    }
    
    const char* inPath = env->GetStringUTFChars(jInputPath, nullptr);
    const char* cacheDir = env->GetStringUTFChars(jCacheDir, nullptr);
    const char* outPath = env->GetStringUTFChars(jOutputM4a, nullptr);
    
    // 应用配置（为了合并和检测的一致性，使用统一的工作采样率/声道）
    tester->setWorkingSampleRate(static_cast<int>(outSampleRate));
    tester->setWorkingChannelCount(static_cast<int>(outChannels));
    tester->setOutExclusive(outExclusive == JNI_TRUE);
    tester->setOutLowLatency(outLowLatency == JNI_TRUE);
    tester->setOutFormatFloat(outFormatFloat == JNI_TRUE);
    tester->setInExclusive(inExclusive == JNI_TRUE);
    tester->setInLowLatency(inLowLatency == JNI_TRUE);
    tester->setInFormatFloat(inFormatFloat == JNI_TRUE);

    // 忽略输入端采样率/声道参数，强制与工作参数保持一致，避免合并时采样率/通道不一致
    (void)inSampleRate; (void)inChannels;

    int result = tester->start(env, std::string(inPath), std::string(cacheDir), std::string(outPath));
    
    env->ReleaseStringUTFChars(jInputPath, inPath);
    env->ReleaseStringUTFChars(jCacheDir, cacheDir);
    env->ReleaseStringUTFChars(jOutputM4a, outPath);
    
    return result;
}

// JNI函数：停止延迟测试
extern "C" JNIEXPORT jint JNICALL
Java_me_rjy_oboe_record_demo_LatencyTesterActivity_stopLatencyTest(
        JNIEnv* env,
        jobject /* thiz */,
        jlong nativeHandle) {
    LatencyTester* tester = reinterpret_cast<LatencyTester*>(nativeHandle);
    if (tester == nullptr) {
        LOGW("stopLatencyTest called but LatencyTester instance is null");
        return 0;
    }
    
    if (!tester->isRunning()) {
        LOGW("stopLatencyTest called but not running");
        return 0;
    }
    
    tester->stop();
    // 编码已在合成线程结束后自动执行
    return 0;
}
