#include <jni.h>
#include <string>
#include <memory>
#include <android/log.h>
#include <oboe/Oboe.h>
#include <cmath>

#define LOG(...) __android_log_print(ANDROID_LOG_DEBUG, "DemoJNI", __VA_ARGS__)

static JavaVM* javaVm = nullptr;
static jmethodID onAudioDataMethodId = nullptr;
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
    int32_t deviceId;
    int32_t audioSource;
    int32_t audioApi;

    void sendAudioDataToJava(const void* audioData, int32_t numFrames) {
        JNIEnv *env;
        if (javaVm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            // 计算数据大小
            size_t bytesPerSample = isFloat ? sizeof(float) : sizeof(int16_t);
            size_t channelCount = isStereo ? 2 : 1;
            size_t totalBytes = numFrames * channelCount * bytesPerSample;

            // 创建Java字节数组
            jbyteArray audioArray = env->NewByteArray(totalBytes);
            env->SetByteArrayRegion(audioArray, 0, totalBytes, 
                                  static_cast<const jbyte*>(audioData));

            // 调用Java方法
            env->CallVoidMethod(recorderViewModel, onAudioDataMethodId, 
                              audioArray, static_cast<jint>(totalBytes));

            // 清理
            env->DeleteLocalRef(audioArray);
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
    OboeRecorder(const char* filePath, int32_t sampleRate, bool isStereo, bool isFloat,
                int32_t deviceId, int32_t audioSource, int32_t audioApi)
        : writer(std::make_unique<DataWriter>(filePath))
        , isFloat(isFloat)
        , sampleRate(sampleRate)
        , isStereo(isStereo)
        , samplesPerFrame(isStereo ? 2 : 1)
        , deviceId(deviceId)
        , audioSource(audioSource)
        , audioApi(audioApi) {
    }

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames) override {
        // 写入文件
        size_t bytesPerSample = isFloat ? sizeof(float) : sizeof(int16_t);
        size_t totalBytes = numFrames * samplesPerFrame * bytesPerSample;
        writer->write(audioData, totalBytes);

        // 发送数据到Java层进行波形计算
        sendAudioDataToJava(audioData, numFrames);

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

static std::unique_ptr<OboeRecorder> gRecorder;

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
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

    // 获取onAudioData方法ID
    onAudioDataMethodId = env->GetMethodID(viewModelClass, "onAudioData", "([BI)V");
    if (onAudioDataMethodId == nullptr) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

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
    gRecorder = std::make_unique<OboeRecorder>(str, sampleRate, isStereo, isFloat, deviceId, 
                                              audioSource, audioApi);
    env->ReleaseStringUTFChars(path, str);

    return gRecorder->start();
}

extern "C" JNIEXPORT void JNICALL
Java_me_rjy_oboe_record_demo_RecorderViewModel_native_1stop_1record(
        JNIEnv* env,
        jobject thiz) {
    if (gRecorder) {
        gRecorder->stop();
        gRecorder.reset();
    }
    
    if (recorderViewModel) {
        env->DeleteGlobalRef(recorderViewModel);
        recorderViewModel = nullptr;
    }
}
