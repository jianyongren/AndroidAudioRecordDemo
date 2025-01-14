#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>
#include <fstream>
#include <atomic>
#include <vector>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "DemoJni", __VA_ARGS__)

class OboeRecorder: public oboe::AudioStreamCallback {
public:
    explicit OboeRecorder(const char* filePath, int32_t sampleRate, bool isStereo, bool isFloat, int32_t deviceId = oboe::kUnspecified) 
        : mFilePath(filePath), mShouldRecord(false), 
          mSampleRate(sampleRate), 
          mChannelCount(isStereo ? oboe::ChannelCount::Stereo : oboe::ChannelCount::Mono),
          mFormat(isFloat ? oboe::AudioFormat::Float : oboe::AudioFormat::I16),
          mDeviceId(deviceId) {}

    bool start() {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(mFormat)
            ->setChannelCount(mChannelCount)
            ->setSampleRate(mSampleRate)
            ->setDeviceId(mDeviceId)
            ->setCallback(this);

        oboe::Result result = builder.openStream(mStream);
        if (result != oboe::Result::OK) {
            LOG("Failed to create stream. Error: %s", oboe::convertToText(result));
            return false;
        }

        mOutFile.open(mFilePath, std::ios::binary);
        if (!mOutFile.is_open()) {
            LOG("Failed to open file: %s", mFilePath);
            return false;
        }

        mShouldRecord = true;
        result = mStream->requestStart();
        if (result != oboe::Result::OK) {
            LOG("Failed to start stream. Error: %s", oboe::convertToText(result));
            return false;
        }

        return true;
    }

    void stop() {
        if (mStream) {
            mShouldRecord = false;
            mStream->requestStop();
            mStream->close();
            mStream.reset();
        }
        if (mOutFile.is_open()) {
            mOutFile.close();
        }
    }

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames) override {
        if (mShouldRecord && mOutFile.is_open()) {
            size_t bytesPerSample = (mFormat == oboe::AudioFormat::Float) ? sizeof(float) : sizeof(int16_t);
            size_t bytesToWrite = numFrames * audioStream->getChannelCount() * bytesPerSample;
            mOutFile.write(static_cast<char*>(audioData), bytesToWrite);
        }
        return oboe::DataCallbackResult::Continue;
    }

private:
    std::shared_ptr<oboe::AudioStream> mStream;
    const char* mFilePath;
    std::ofstream mOutFile;
    std::atomic<bool> mShouldRecord;
    int32_t mSampleRate;
    int32_t mChannelCount;
    oboe::AudioFormat mFormat;
    int32_t mDeviceId;
};

static std::unique_ptr<OboeRecorder> gRecorder;

extern "C"
JNIEXPORT void JNICALL
Java_me_rjy_oboe_record_demo_RecorderViewModel_native_1start_1record(
    JNIEnv *env, 
    jobject thiz,
    jstring path,
    jint sampleRate,
    jboolean isStereo,
    jboolean isFloat,
    jint deviceId
) {
    const char *str = env->GetStringUTFChars(path, nullptr);
    LOG("start record... %s, sampleRate: %d, isStereo: %d, isFloat: %d, deviceId: %d", 
        str, sampleRate, isStereo, isFloat, deviceId);
    
    gRecorder = std::make_unique<OboeRecorder>(str, sampleRate, isStereo, isFloat, deviceId);
    gRecorder->start();
    
    env->ReleaseStringUTFChars(path, str);
}

extern "C"
JNIEXPORT void JNICALL
Java_me_rjy_oboe_record_demo_RecorderViewModel_native_1stop_1record(JNIEnv *env, jobject thiz) {
    LOG("stop record...");
    if (gRecorder) {
        gRecorder->stop();
        gRecorder.reset();
    }
}
