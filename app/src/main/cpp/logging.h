//
// Created by nilai huang on 2019-06-14.
//

#ifndef POKEMEDIA_LOGGING_H
#define POKEMEDIA_LOGGING_H

#include <cstdio>
#include <android/log.h>
#include <vector>

#define DEBUG true

#define APP_NAME "AudioRecorderDemo"

#define LOGV(fmt, ...) if(DEBUG)((void)__android_log_print(ANDROID_LOG_VERBOSE, APP_NAME, "%s:%d %s " fmt , __FILE_NAME__, __LINE__, __FUNCTION__, ## __VA_ARGS__))
#define LOGD(fmt, ...) if(DEBUG)((void)__android_log_print(ANDROID_LOG_DEBUG, APP_NAME, "%s:%d %s " fmt , __FILE_NAME__, __LINE__, __FUNCTION__, ## __VA_ARGS__))
#define LOGI(fmt, ...) if(DEBUG)((void)__android_log_print(ANDROID_LOG_INFO, APP_NAME, "%s:%d %s " fmt , __FILE_NAME__, __LINE__, __FUNCTION__, ## __VA_ARGS__))
#define LOGW(fmt, ...) if(DEBUG)((void)__android_log_print(ANDROID_LOG_WARN, APP_NAME, "%s:%d %s " fmt , __FILE_NAME__, __LINE__, __FUNCTION__, ## __VA_ARGS__))
#define LOGE(fmt, ...) if(DEBUG)((void)__android_log_print(ANDROID_LOG_ERROR, APP_NAME, "%s:%d %s " fmt , __FILE_NAME__, __LINE__, __FUNCTION__, ## __VA_ARGS__))

#endif //POKEMEDIA_LOGGING_H
