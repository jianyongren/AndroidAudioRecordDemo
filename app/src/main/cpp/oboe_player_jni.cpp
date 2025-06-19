#include <jni.h>
#include <string>
#include "oboe_player.h"
#include "logging.h"
#include <android/log.h>

#define LOG_TAG "OboePlayerJNI"

// 声明外部变量
extern JavaVM* javaVm;
static jmethodID onPlaybackCompleteMethodId = nullptr;

extern "C" {

// 创建OboePlayer实例
JNIEXPORT jlong JNICALL
Java_me_rjy_oboe_record_demo_OboePlayer_createNativePlayer(
        JNIEnv* env, jobject thiz, jstring filePath, jint sampleRate,
        jboolean isStereo, jboolean isFloat, jint audioApi, jint deviceId) {
    
    const char* path = env->GetStringUTFChars(filePath, nullptr);
    if (!path) {
        LOGE("Failed to get file path");
        return 0;
    }

    auto* player = new OboePlayer(path, sampleRate, isStereo, isFloat, audioApi, deviceId);
    env->ReleaseStringUTFChars(filePath, path);

    if (!player) {
        LOGE("Failed to create OboePlayer");
        return 0;
    }

    return reinterpret_cast<jlong>(player);
}

// 释放OboePlayer实例
JNIEXPORT void JNICALL
Java_me_rjy_oboe_record_demo_OboePlayer_nativeRelease(
        JNIEnv* env, jobject thiz, jlong nativePlayer) {
    
    auto* player = reinterpret_cast<OboePlayer*>(nativePlayer);
    if (player) {
        delete player;
    }
}

// 开始播放
JNIEXPORT jboolean JNICALL
Java_me_rjy_oboe_record_demo_OboePlayer_nativeStart(
        JNIEnv* env, jobject thiz, jlong nativePlayer) {
    
    auto* player = reinterpret_cast<OboePlayer*>(nativePlayer);
    if (!player) {
        LOGE("Native player is null");
        return JNI_FALSE;
    }

    return player->start() ? JNI_TRUE : JNI_FALSE;
}

// 停止播放
JNIEXPORT void JNICALL
Java_me_rjy_oboe_record_demo_OboePlayer_nativeStop(
        JNIEnv* env, jobject thiz, jlong nativePlayer) {
    
    auto* player = reinterpret_cast<OboePlayer*>(nativePlayer);
    if (player) {
        player->stop();
    }
}

// 设置回调对象
JNIEXPORT void JNICALL
Java_me_rjy_oboe_record_demo_OboePlayer_setCallbackObject(
        JNIEnv* env, jobject thiz, jobject callbackObject) {
    
    auto* player = reinterpret_cast<OboePlayer*>(
            env->GetLongField(thiz, env->GetFieldID(
                    env->GetObjectClass(thiz), "nativePlayer", "J")));
    
    if (player) {
        // 获取回调方法ID
        jclass clazz = env->GetObjectClass(callbackObject);
        onPlaybackCompleteMethodId = env->GetMethodID(clazz, "onPlaybackComplete", "()V");
        
        // 创建全局引用
        jobject globalRef = env->NewGlobalRef(callbackObject);
        
        // 设置到C++对象
        player->setCallbackObject(globalRef, onPlaybackCompleteMethodId);
    }
}

// 获取播放进度
JNIEXPORT jfloat JNICALL
Java_me_rjy_oboe_record_demo_OboePlayer_nativeGetPlaybackProgress(
        JNIEnv* env, jobject thiz, jlong nativePlayer) {
    
    auto* player = reinterpret_cast<OboePlayer*>(nativePlayer);
    if (!player) {
        LOGE("Native player is null");
        return 0.0f;
    }

    return player->getPlaybackProgress();
}

} // extern "C" 