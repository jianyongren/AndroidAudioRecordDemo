#include "oboe_player.h"
#include <vector>
#include <android/log.h>
#include <jni.h>
#include "logging.h"

#define LOG_TAG "OboePlayerNative"


// 声明外部变量
extern JavaVM* javaVm;
extern jmethodID onPlaybackCompleteMethodId;
extern jobject recorderViewModel;

// 定义静态成员变量
constexpr size_t OboePlayer::BUFFER_CAPACITY;

// 通用的JNI线程安全执行方法
template<typename F>
static void executeInJniThread(F&& callback) {
    JNIEnv* env;
    jint result = javaVm->GetEnv((void**)&env, JNI_VERSION_1_6);
    bool needDetach = false;
    
    if (result == JNI_EDETACHED) {
        // 当前线程未附加到JVM，需要附加
        JavaVMAttachArgs args;
        args.version = JNI_VERSION_1_6;
        args.name = "OboePlayerThread";
        args.group = nullptr;

        if (javaVm->AttachCurrentThread(&env, &args) == JNI_OK) {
            needDetach = true;
        } else {
            LOGE("Failed to attach thread to JVM");
            return;
        }
    } else if (result != JNI_OK) {
        LOGE("Failed to get JNIEnv");
        return;
    }

    try {
        callback(env);
    } catch (...) {
        LOGE("Exception occurred in JNI callback");
    }

    if (needDetach) {
        javaVm->DetachCurrentThread();
    }
}

OboePlayer::OboePlayer(const char* filePath, int32_t sampleRate, bool isStereo, bool isFloat, int32_t audioApi)
    : file_(fopen(filePath, "rb"), fclose)
    , isFloat(isFloat)
    , sampleRate(sampleRate)
    , isStereo(isStereo)
    , samplesPerFrame(isStereo ? 2 : 1)
    , audioApi(audioApi)
    , ringBuffer_(std::make_unique<ThreadSafeRingBuffer>(BUFFER_CAPACITY))
    , isRunning_(false)
    , onPlaybackCompleteMethodId_(nullptr)
    , callbackObject_(nullptr)
    , totalBytes_(0)
    , playbackProgress_(0.0f)
    , framesPlayed_(0)
    , totalFrames_(0) {
    
    if (!file_) {
        LOGD("Failed to open file: %s", filePath);
        return;
    }

    bytesRead_ = 0;
}

OboePlayer::~OboePlayer() {
    stop();
    // 清理JNI引用
    if (callbackObject_) {
        executeInJniThread([this](JNIEnv* env) {
            LOGD("delete callbackObject_");
            env->DeleteGlobalRef(callbackObject_);
        });
    }
}

void OboePlayer::producerThreadFunc() {
    const size_t bytesPerSample = isFloat ? 4 : 2;
    const size_t bytesPerFrame = bytesPerSample * samplesPerFrame * 8;
    std::vector<uint8_t> buffer(bytesPerFrame);

    while (isRunning_) {
        size_t bytesRead = fread(buffer.data(), 1, buffer.size(), file_.get());
        if (bytesRead > 0) {
            bytesRead_ += bytesRead;
            if (!ringBuffer_->write(buffer.data(), bytesRead)) {
                // 写入失败，可能是被release了
                isRunning_ = false;
                break;
            }
        } else {
            LOGI("file read finished");
            isRunning_ = false;
            break;
        }
    }
}

void OboePlayer::notifyPlaybackComplete() {
    if (onPlaybackCompleteMethodId_ && callbackObject_) {
        executeInJniThread([this](JNIEnv* env) {
            LOGI("notifyPlaybackComplete");
            env->CallVoidMethod(callbackObject_, onPlaybackCompleteMethodId_);
        });
    }
}

float OboePlayer::getPlaybackProgress() const {
    return playbackProgress_.load();
}

oboe::DataCallbackResult OboePlayer::onAudioReady(
        oboe::AudioStream *audioStream,
        void *audioData,
        int32_t numFrames) {

    const size_t bytesPerSample = isFloat ? 4 : 2;
    const size_t bytesPerFrame = bytesPerSample * samplesPerFrame;
    const size_t bytesToRead = numFrames * bytesPerFrame;

    std::vector<uint8_t> buffer(bytesToRead);
    if (!ringBuffer_->read(buffer.data(), bytesToRead)) {
        // 没有数据可读，且生产者线程已经结束，说明播放完成
        if (!isRunning_) {
            playbackProgress_.store(1.0f);  // 播放完成时设置进度为1.0
            notifyPlaybackComplete();
            return oboe::DataCallbackResult::Stop;
        }
        return oboe::DataCallbackResult::Continue;
    }

    // 更新播放进度
    if (totalFrames_ > 0) {
        framesPlayed_ += numFrames;
        float progress = static_cast<float>(framesPlayed_.load()) / totalFrames_;
        playbackProgress_.store(progress);
    }

    if (isFloat) {
        auto* floatData = static_cast<float*>(audioData);
        const auto* floatBuffer = reinterpret_cast<const float*>(buffer.data());
        std::copy(floatBuffer, floatBuffer + numFrames * samplesPerFrame, floatData);
    } else {
        auto* shortData = static_cast<int16_t*>(audioData);
        const auto* shortBuffer = reinterpret_cast<const int16_t*>(buffer.data());
        std::copy(shortBuffer, shortBuffer + numFrames * samplesPerFrame, shortData);
    }

    return oboe::DataCallbackResult::Continue;
}

bool OboePlayer::start() {
    if (!file_) {
        LOGE("File not opened");
        return false;
    }

    // 获取文件总大小和总帧数
    fseek(file_.get(), 0, SEEK_END);
    totalBytes_ = ftell(file_.get());
    fseek(file_.get(), 0, SEEK_SET);
    bytesRead_ = 0;
    framesPlayed_.store(0);
    playbackProgress_.store(0.0f);

    // 计算总帧数
    const size_t bytesPerSample = isFloat ? 4 : 2;
    const size_t bytesPerFrame = bytesPerSample * samplesPerFrame;
    totalFrames_ = totalBytes_ / bytesPerFrame;

    isRunning_ = true;
    producerThread_ = std::make_unique<std::thread>(&OboePlayer::producerThreadFunc, this);

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(isFloat ? oboe::AudioFormat::Float : oboe::AudioFormat::I16)
            ->setSampleRate(sampleRate)
            ->setChannelCount(isStereo ? 2 : 1)
            ->setDataCallback(this)
            ->setAudioApi(getAudioApi(audioApi));

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open stream. Error: %s", oboe::convertToText(result));
        return false;
    }

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start stream. Error: %s", oboe::convertToText(result));
        return false;
    }

    return true;
}

void OboePlayer::stop() {
    if (producerThread_ && producerThread_->joinable()) {
        isRunning_ = false;
        ringBuffer_->release();  // 通知阻塞的写入操作退出
        producerThread_->join();
        producerThread_.reset();
    }

    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

oboe::AudioApi OboePlayer::getAudioApi(int32_t api) {
    switch (api) {
        case 1: return oboe::AudioApi::AAudio;
        case 2: return oboe::AudioApi::OpenSLES;
        default: return oboe::AudioApi::Unspecified;
    }
} 