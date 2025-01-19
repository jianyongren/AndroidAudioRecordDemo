#include "oboe_recorder.h"
#include <vector>
#include <android/log.h>
#include <jni.h>

#define LOG(...) __android_log_print(ANDROID_LOG_DEBUG, "OboeRecorder", __VA_ARGS__)

// 声明外部变量
extern JavaVM* javaVm;
extern jmethodID onAudioDataMethodId;
extern jobject recorderViewModel;

// 定义静态成员变量
constexpr size_t OboeRecorder::BUFFER_CAPACITY;

OboeRecorder::OboeRecorder(const char* filePath, int32_t sampleRate, bool isStereo, bool isFloat,
                         int32_t deviceId, int32_t audioSource, int32_t audioApi)
    : writer(std::make_unique<DataWriter>(filePath))
    , isFloat(isFloat)
    , sampleRate(sampleRate)
    , isStereo(isStereo)
    , samplesPerFrame(isStereo ? 2 : 1)
    , deviceId(deviceId)
    , audioSource(audioSource)
    , audioApi(audioApi)
    , ringBuffer_(std::make_unique<RingBuffer>(BUFFER_CAPACITY))
    , isRunning_(false) {
}

OboeRecorder::~OboeRecorder() {
    stop();
}

void OboeRecorder::sendAudioDataToJava(const void* audioData, int32_t numFrames) {
    JNIEnv *env;
    if (javaVm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
        size_t bytesPerSample = isFloat ? sizeof(float) : sizeof(int16_t);
        size_t channelCount = isStereo ? 2 : 1;
        size_t totalBytes = numFrames * channelCount * bytesPerSample;

        jbyteArray audioArray = env->NewByteArray(totalBytes);
        env->SetByteArrayRegion(audioArray, 0, totalBytes, 
                              static_cast<const jbyte*>(audioData));

        env->CallVoidMethod(recorderViewModel, onAudioDataMethodId, 
                          audioArray, static_cast<jint>(totalBytes));

        env->DeleteLocalRef(audioArray);
        javaVm->DetachCurrentThread();
    }
}

void OboeRecorder::consumerThreadFunc() {
    std::vector<uint8_t> tempBuffer(16 * 1024); // 16KB的临时缓冲区

    while (isRunning_) {
        std::unique_lock<std::mutex> lock(mutex_);
        
        dataReady_.wait(lock, [this] {
            return ringBuffer_->size() > 0 || !isRunning_;
        });

        if (!isRunning_) break;

        size_t dataSize = std::min(ringBuffer_->size(), tempBuffer.size());
        
        if (dataSize > 0) {
            if (ringBuffer_->read(tempBuffer.data(), dataSize)) {
                lock.unlock();
                sendAudioDataToJava(tempBuffer.data(), 
                                  dataSize / (samplesPerFrame * (isFloat ? sizeof(float) : sizeof(int16_t))));
            }
        }
    }
}

oboe::DataCallbackResult OboeRecorder::onAudioReady(
        oboe::AudioStream *audioStream,
        void *audioData,
        int32_t numFrames) {
    size_t bytesPerSample = isFloat ? sizeof(float) : sizeof(int16_t);
    size_t totalBytes = numFrames * samplesPerFrame * bytesPerSample;
    writer->write(audioData, totalBytes);

    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (ringBuffer_->write(audioData, totalBytes)) {
            dataReady_.notify_one();
        }
    }

    return oboe::DataCallbackResult::Continue;
}

bool OboeRecorder::start() {
    isRunning_ = true;
    consumerThread_ = std::make_unique<std::thread>(&OboeRecorder::consumerThreadFunc, this);

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(isFloat ? oboe::AudioFormat::Float : oboe::AudioFormat::I16)
            ->setSampleRate(sampleRate)
            ->setChannelCount(isStereo ? 2 : 1)
            ->setDataCallback(this)
            ->setAudioApi(getAudioApi(audioApi));

    if (deviceId != 0) {
        builder.setDeviceId(deviceId);
    }

    builder.setInputPreset(getInputPreset(audioSource));

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        LOG("Failed to open stream. Error: %s", oboe::convertToText(result));
        return false;
    }

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOG("Failed to start stream. Error: %s", oboe::convertToText(result));
        return false;
    }

    return true;
}

void OboeRecorder::stop() {
    if (consumerThread_ && consumerThread_->joinable()) {
        isRunning_ = false;
        dataReady_.notify_one();
        consumerThread_->join();
        consumerThread_.reset();
    }

    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

oboe::InputPreset OboeRecorder::getInputPreset(int32_t audioSource) {
    switch (audioSource) {
        case 0: return oboe::InputPreset::Generic;
        case 1: return oboe::InputPreset::Generic;
        case 7: return oboe::InputPreset::VoiceCommunication;
        case 6: return oboe::InputPreset::VoiceRecognition;
        case 5: return oboe::InputPreset::Camcorder;
        case 9: return oboe::InputPreset::Unprocessed;
        case 10: return oboe::InputPreset::VoicePerformance;
        default: return oboe::InputPreset::Generic;
    }
}

oboe::AudioApi OboeRecorder::getAudioApi(int32_t audioApi) {
    switch (audioApi) {
        case 1: return oboe::AudioApi::AAudio;
        case 2: return oboe::AudioApi::OpenSLES;
        default: return oboe::AudioApi::Unspecified;
    }
} 