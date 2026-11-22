#pragma once

// The logging ElfImage (elf/, from Vector) expects, as one line in Android's log with the message's own words; the
// values in its braces are left out.
#include <android/log.h>

#define KAV_HOOK_LOG(level, text, ...) __android_log_write(level, "KavHook", text)
#define LOGD(text, ...) ((void)0)
#define LOGV(text, ...) ((void)0)
#define LOGI(text, ...) KAV_HOOK_LOG(ANDROID_LOG_INFO, text)
#define LOGW(text, ...) KAV_HOOK_LOG(ANDROID_LOG_WARN, text)
#define LOGE(text, ...) KAV_HOOK_LOG(ANDROID_LOG_ERROR, text)
#define PLOGE(text, ...) KAV_HOOK_LOG(ANDROID_LOG_ERROR, text)
