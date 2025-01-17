#include <jni.h>
#include <string>
#include <memory>
#include <android/log.h>
#include <oboe/Oboe.h>
#include <cmath>

#define LOG(...) __android_log_print(ANDROID_LOG_DEBUG, "DemoJNI", __VA_ARGS__)

static JavaVM* javaVm = nullptr;
static jmethodID updateWaveformMethodId = nullptr;
static jobject recorderViewModel = nullptr;

// 用于写入文件的辅助类
class DataWriter {
public:
    explicit DataWriter(const char* filePath) : file_(fopen(filePath, "wb")) {}
    ~DataWriter() {
        if (file_) {
            fclose(file_);
        }
    }
    
    void write(const void* data, size_t size) {
        if (file_) {
            fwrite(data, 1, size, file_);
        }
    }
    
private:
    FILE* file_;
};

class OboeRecorder : public oboe::AudioStreamDataCallback {
private:
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<DataWriter> writer;
    bool isFloat;
    int32_t sampleRate;
    bool isStereo;
    int32_t samplesPerFrame;
    float maxLeftAmplitude = 0.0f;
    float maxRightAmplitude = 0.0f;
    int32_t accumulatedSamples = 0;
    int32_t samplesPerUpdate;
    int32_t deviceId;
    int32_t audioSource;
    int32_t audioApi;

    void updateWaveform(float leftAmplitude, float rightAmplitude) {
        JNIEnv *env;
        if (javaVm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            env->CallVoidMethod(recorderViewModel, updateWaveformMethodId, leftAmplitude, rightAmplitude);
            javaVm->DetachCurrentThread();
        }
    }

    oboe::InputPreset getInputPreset(int32_t audioSource) {
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

    oboe::AudioApi getAudioApi(int32_t audioApi) {
        switch (audioApi) {
            case 1: return oboe::AudioApi::AAudio;
            case 2: return oboe::AudioApi::OpenSLES;
            default: return oboe::AudioApi::Unspecified;
        }
    }

public:
    explicit OboeRecorder(const char* filePath, int32_t sampleRate, bool isStereo, bool isFloat, 
                         int32_t deviceId, int32_t audioSource, int32_t audioApi)
            : isFloat(isFloat), sampleRate(sampleRate), isStereo(isStereo), 
              deviceId(deviceId), audioSource(audioSource), audioApi(audioApi) {
        samplesPerFrame = isStereo ? 2 : 1;
        // 计算每20ms需要的采样点数
        samplesPerUpdate = (sampleRate * 0.02);
        writer = std::make_unique<DataWriter>(filePath);
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override {
        writer->write(audioData, numFrames * samplesPerFrame * (isFloat ? sizeof(float) : sizeof(int16_t)));

        // 计算波形数据
        if (isFloat) {
            const auto* floatData = static_cast<const float*>(audioData);
            if (isStereo) {
                // 立体声模式，分别计算左右声道
                for (int i = 0; i < numFrames * 2; i += 2) {
                    float leftSample = floatData[i];
                    float rightSample = floatData[i + 1];
                    
                    if (std::abs(leftSample) > std::abs(maxLeftAmplitude)) {
                        maxLeftAmplitude = leftSample;
                    }
                    if (std::abs(rightSample) > std::abs(maxRightAmplitude)) {
                        maxRightAmplitude = rightSample;
                    }
                }
            } else {
                // 单声道模式
                for (int i = 0; i < numFrames; i++) {
                    float sample = floatData[i];
                    if (std::abs(sample) > std::abs(maxLeftAmplitude)) {
                        maxLeftAmplitude = sample;
                    }
                }
                maxRightAmplitude = maxLeftAmplitude;  // 单声道时左右声道相同
            }
        } else {
            const int16_t* shortData = static_cast<const int16_t*>(audioData);
            if (isStereo) {
                // 立体声模式，分别计算左右声道
                for (int i = 0; i < numFrames * 2; i += 2) {
                    float leftSample = shortData[i] / 32768.0f;
                    float rightSample = shortData[i + 1] / 32768.0f;
                    
                    if (std::abs(leftSample) > std::abs(maxLeftAmplitude)) {
                        maxLeftAmplitude = leftSample;
                    }
                    if (std::abs(rightSample) > std::abs(maxRightAmplitude)) {
                        maxRightAmplitude = rightSample;
                    }
                }
            } else {
                // 单声道模式
                for (int i = 0; i < numFrames; i++) {
                    float sample = shortData[i] / 32768.0f;
                    if (std::abs(sample) > std::abs(maxLeftAmplitude)) {
                        maxLeftAmplitude = sample;
                    }
                }
                maxRightAmplitude = maxLeftAmplitude;  // 单声道时左右声道相同
            }
        }

        accumulatedSamples += numFrames;
        if (accumulatedSamples >= samplesPerUpdate) {
            // 更新波形数据
            updateWaveform(maxLeftAmplitude, maxRightAmplitude);
            // 重置状态
            maxLeftAmplitude = 0.0f;
            maxRightAmplitude = 0.0f;
            accumulatedSamples = 0;
        }

        return oboe::DataCallbackResult::Continue;
    }

    bool start() {
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

        // 设置音频源
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

    void stop() {
        if (stream_) {
            stream_->stop();
            stream_->close();
            stream_.reset();
        }
    }
};

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    javaVm = vm;
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // 获取RecorderViewModel类
    jclass viewModelClass = env->FindClass("me/rjy/oboe/record/demo/RecorderViewModel");
    if (viewModelClass == nullptr) {
        return JNI_ERR;
    }

    // 获取updateWaveformFromNative方法ID
    updateWaveformMethodId = env->GetMethodID(viewModelClass, "updateWaveformFromNative", "(FF)V");
    if (updateWaveformMethodId == nullptr) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

static std::unique_ptr<OboeRecorder> gRecorder;

extern "C" JNIEXPORT jboolean JNICALL
Java_me_rjy_oboe_record_demo_RecorderViewModel_native_1start_1record(
        JNIEnv* env,
        jobject thiz,
        jstring path,
        jint sampleRate,
        jboolean isStereo,
        jboolean isFloat,
        jint deviceId,
        jint audioSource,
        jint audioApi) {
    // 保存RecorderViewModel的全局引用
    recorderViewModel = env->NewGlobalRef(thiz);
    
    const char* str = env->GetStringUTFChars(path, nullptr);
    gRecorder = std::make_unique<OboeRecorder>(str, sampleRate, isStereo, isFloat, deviceId, audioSource, audioApi);
    env->ReleaseStringUTFChars(path, str);

    return gRecorder->start();
}

extern "C" JNIEXPORT void JNICALL
Java_me_rjy_oboe_record_demo_RecorderViewModel_native_1stop_1record(
        JNIEnv* env,
        jobject /* this */) {
    if (gRecorder) {
        gRecorder->stop();
        gRecorder.reset();
    }
    
    // 释放全局引用
    if (recorderViewModel != nullptr) {
        env->DeleteGlobalRef(recorderViewModel);
        recorderViewModel = nullptr;
    }
}
