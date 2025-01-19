#include <jni.h>
#include <android/log.h>
#include "oboe_recorder.h"

#define LOG(...) __android_log_print(ANDROID_LOG_DEBUG, "DemoJNI", __VA_ARGS__)

// 全局变量
JavaVM* javaVm = nullptr;
jmethodID onAudioDataMethodId = nullptr;
jobject recorderViewModel = nullptr;

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
