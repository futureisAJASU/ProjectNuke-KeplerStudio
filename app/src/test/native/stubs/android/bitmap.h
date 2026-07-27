#pragma once

#include <cstdint>
#include <jni.h>

struct AndroidBitmapInfo {
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t stride = 0;
    std::int32_t format = 0;
    std::uint32_t flags = 0;
};

constexpr int ANDROID_BITMAP_FORMAT_RGBA_8888 = 1;
constexpr int ANDROID_BITMAP_RESULT_SUCCESS = 0;

inline int AndroidBitmap_getInfo(JNIEnv*, jobject, AndroidBitmapInfo*) { return -1; }
inline int AndroidBitmap_lockPixels(JNIEnv*, jobject, void**) { return -1; }
inline int AndroidBitmap_unlockPixels(JNIEnv*, jobject) { return 0; }
