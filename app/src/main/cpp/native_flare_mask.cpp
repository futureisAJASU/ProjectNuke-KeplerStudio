#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#define LOG_TAG "KeplerFlareMask"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

static inline float clamp01(float v) {
    return std::max(0.0f, std::min(1.0f, v));
}

static inline uint8_t to_u8(float v) {
    return static_cast<uint8_t>(std::round(clamp01(v) * 255.0f));
}

static inline float luma_of(const uint8_t* p) {
    return (0.2126f * p[0] + 0.7152f * p[1] + 0.0722f * p[2]) / 255.0f;
}

static void box_blur_u8(std::vector<uint8_t>& data, int width, int height, int radius, int passes) {
    if (radius <= 0 || passes <= 0 || width <= 1 || height <= 1) return;
    std::vector<uint8_t> tmp(data.size());
    for (int pass = 0; pass < passes; ++pass) {
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int sum = 0;
                int count = 0;
                const int left = std::max(0, x - radius);
                const int right = std::min(width - 1, x + radius);
                for (int xx = left; xx <= right; ++xx) {
                    sum += data[static_cast<size_t>(y) * width + xx];
                    ++count;
                }
                tmp[static_cast<size_t>(y) * width + x] = static_cast<uint8_t>(sum / std::max(1, count));
            }
        }
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                int sum = 0;
                int count = 0;
                const int top = std::max(0, y - radius);
                const int bottom = std::min(height - 1, y + radius);
                for (int yy = top; yy <= bottom; ++yy) {
                    sum += tmp[static_cast<size_t>(yy) * width + x];
                    ++count;
                }
                data[static_cast<size_t>(y) * width + x] = static_cast<uint8_t>(sum / std::max(1, count));
            }
        }
    }
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeCreateFlareMaskNative(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject sourceBitmap,
    jobject maskBitmap,
    jfloat threshold,
    jint radius,
    jint passes
) {
    AndroidBitmapInfo srcInfo{};
    AndroidBitmapInfo maskInfo{};
    if (AndroidBitmap_getInfo(env, sourceBitmap, &srcInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, maskBitmap, &maskInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed");
        return -1;
    }
    if (srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || maskInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported bitmap format");
        return -2;
    }
    if (srcInfo.width != maskInfo.width || srcInfo.height != maskInfo.height) {
        LOGE("Mask size mismatch");
        return -3;
    }
    void* srcPixels = nullptr;
    void* maskPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, sourceBitmap, &srcPixels) != ANDROID_BITMAP_RESULT_SUCCESS || srcPixels == nullptr) return -4;
    if (AndroidBitmap_lockPixels(env, maskBitmap, &maskPixels) != ANDROID_BITMAP_RESULT_SUCCESS || maskPixels == nullptr) {
        AndroidBitmap_unlockPixels(env, sourceBitmap);
        return -5;
    }

    const int width = static_cast<int>(srcInfo.width);
    const int height = static_cast<int>(srcInfo.height);
    const float safeThreshold = std::max(0.70f, std::min(0.98f, threshold));
    const auto* src = static_cast<const uint8_t*>(srcPixels);
    auto* mask = static_cast<uint8_t*>(maskPixels);
    std::vector<uint8_t> alpha(static_cast<size_t>(width) * height);

    for (int y = 0; y < height; ++y) {
        const auto* row = src + static_cast<size_t>(y) * srcInfo.stride;
        for (int x = 0; x < width; ++x) {
            const uint8_t* p = row + static_cast<size_t>(x) * 4U;
            const float l = luma_of(p);
            alpha[static_cast<size_t>(y) * width + x] = to_u8((l - safeThreshold) / (1.0f - safeThreshold));
        }
    }

    box_blur_u8(alpha, width, height, std::max(0, radius), std::max(0, passes));

    for (int y = 0; y < height; ++y) {
        auto* row = mask + static_cast<size_t>(y) * maskInfo.stride;
        for (int x = 0; x < width; ++x) {
            const uint8_t v = alpha[static_cast<size_t>(y) * width + x];
            auto* out = row + static_cast<size_t>(x) * 4U;
            out[0] = v;
            out[1] = v;
            out[2] = v;
            out[3] = 255;
        }
    }

    AndroidBitmap_unlockPixels(env, maskBitmap);
    AndroidBitmap_unlockPixels(env, sourceBitmap);
    return 0;
}
