#include <jni.h>

#include <android/log.h>
#include <oboe/Oboe.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "DemoJni", __VA_ARGS__)


void startRecorder(const char * filePath) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder.setSharingMode(oboe::SharingMode::Shared);
    builder.setFormat(oboe::AudioFormat::I16);
    builder.setChannelCount(oboe::ChannelCount::Stereo);
}


extern "C"
JNIEXPORT void JNICALL
Java_me_rjy_oboe_record_demo_MainActivity_native_1start_1record(JNIEnv *env, jobject thiz,
                                                                jstring path) {
    const char *str = env->GetStringUTFChars(path, nullptr);
    LOG("start record... %s", str);
    env->ReleaseStringUTFChars(path, str);
}
extern "C"
JNIEXPORT void JNICALL
Java_me_rjy_oboe_record_demo_MainActivity_native_1stop_1record(JNIEnv *env, jobject thiz) {
    LOG("stop record...");
}
