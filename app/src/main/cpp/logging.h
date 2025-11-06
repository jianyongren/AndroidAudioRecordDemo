#ifndef POKEMEDIA_LOGGING_H
#define POKEMEDIA_LOGGING_H

#include <android/log.h>

// Fallback for compilers/toolchains where __FILE_NAME__ is not defined
#ifndef __FILE_NAME__
#define __FILE_NAME__ __FILE__
#endif

#define LOGV(fmt, ...) (void)__android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, "%s:%d %s " fmt , __FILE_NAME__, __LINE__, __FUNCTION__, ## __VA_ARGS__)
#define LOGD(fmt, ...) (void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "%s:%d %s " fmt , __FILE_NAME__, __LINE__, __FUNCTION__, ## __VA_ARGS__)
#define LOGI(fmt, ...) (void)__android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s:%d %s " fmt , __FILE_NAME__, __LINE__, __FUNCTION__, ## __VA_ARGS__)
#define LOGW(fmt, ...) (void)__android_log_print(ANDROID_LOG_WARN, LOG_TAG, "%s:%d %s " fmt , __FILE_NAME__, __LINE__, __FUNCTION__, ## __VA_ARGS__)
#define LOGE(fmt, ...) (void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s:%d %s " fmt , __FILE_NAME__, __LINE__, __FUNCTION__, ## __VA_ARGS__)

#endif //POKEMEDIA_LOGGING_H
