#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <exception>
#include <cmath>
#include <cstdint>
#include <new>

#define LOG_TAG "KeplerSelectionBlend"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LockedBitmap {
    JNIEnv* env;
    jobject bitmap;
    void* pixels = nullptr;
    bool locked = false;

    LockedBitmap(JNIEnv* env, jobject bitmap) : env(env), bitmap(bitmap) {}

    int lock() {
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
            pixels = nullptr;
            locked = false;
            return -1;
        }
        locked = true;
        return 0;
    }

    ~LockedBitmap() {
        if (locked) {
            AndroidBitmap_unlockPixels(env, bitmap);
        }
    }

    LockedBitmap(const LockedBitmap&) = delete;
    LockedBitmap& operator=(const LockedBitmap&) = delete;
};

template <typename Fn>
jint runNativeGuarded(const char* functionName, Fn&& fn) {
    try {
        return fn();
    } catch (const std::bad_alloc&) {
        LOGE("%s failed: bad_alloc", functionName);
        return -20;
    } catch (const std::exception& e) {
        LOGE("%s failed: %s", functionName, e.what());
        return -21;
    } catch (...) {
        LOGE("%s failed: unknown exception", functionName);
        return -22;
    }
}

static inline float clamp01(float v) {
    return std::max(0.0f, std::min(1.0f, v));
}

static inline uint8_t to_u8(float v) {
    return static_cast<uint8_t>(std::round(clamp01(v) * 255.0f));
}

static inline const uint8_t* px_at(const uint8_t* base, int stride, int x, int y) {
    return base + static_cast<size_t>(y) * stride + static_cast<size_t>(x) * 4U;
}

static float sample_mask(const uint8_t* mask, int maskW, int maskH, int maskStride, int targetW, int targetH, int x, int y) {
    const float u = targetW <= 1 ? 0.0f : static_cast<float>(x) / static_cast<float>(targetW - 1);
    const float v = targetH <= 1 ? 0.0f : static_cast<float>(y) / static_cast<float>(targetH - 1);
    const float mx = u * static_cast<float>(maskW - 1);
    const float my = v * static_cast<float>(maskH - 1);
    const int x0 = static_cast<int>(std::floor(mx));
    const int y0 = static_cast<int>(std::floor(my));
    const int x1 = std::min(maskW - 1, x0 + 1);
    const int y1 = std::min(maskH - 1, y0 + 1);
    const float tx = mx - x0;
    const float ty = my - y0;
    const float p00 = px_at(mask, maskStride, x0, y0)[0] / 255.0f;
    const float p10 = px_at(mask, maskStride, x1, y0)[0] / 255.0f;
    const float p01 = px_at(mask, maskStride, x0, y1)[0] / 255.0f;
    const float p11 = px_at(mask, maskStride, x1, y1)[0] / 255.0f;
    const float a = p00 + (p10 - p00) * tx;
    const float b = p01 + (p11 - p01) * tx;
    return a + (b - a) * ty;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeBlendSelectionLayerInPlaceNative(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject targetBitmap,
    jobject localBitmap,
    jobject maskBitmap,
    jboolean inverted,
    jfloat opacity
) {
    return runNativeGuarded("nativeBlendSelectionLayerInPlaceNative", [&]() -> jint {
        AndroidBitmapInfo targetInfo{};
        AndroidBitmapInfo localInfo{};
        AndroidBitmapInfo maskInfo{};
        if (AndroidBitmap_getInfo(env, targetBitmap, &targetInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
            AndroidBitmap_getInfo(env, localBitmap, &localInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
            AndroidBitmap_getInfo(env, maskBitmap, &maskInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("AndroidBitmap_getInfo failed");
            return -1;
        }
        if (targetInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            localInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
            maskInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            LOGE("Unsupported bitmap format");
            return -2;
        }
        if (targetInfo.width != localInfo.width || targetInfo.height != localInfo.height) {
            LOGE("Target/local size mismatch");
            return -3;
        }

        LockedBitmap targetLock(env, targetBitmap);
        if (targetLock.lock() != 0) return -4;
        LockedBitmap localLock(env, localBitmap);
        if (localLock.lock() != 0) return -5;
        LockedBitmap maskLock(env, maskBitmap);
        if (maskLock.lock() != 0) return -6;

        auto* target = static_cast<uint8_t*>(targetLock.pixels);
        const auto* local = static_cast<const uint8_t*>(localLock.pixels);
        const auto* mask = static_cast<const uint8_t*>(maskLock.pixels);
        const int width = static_cast<int>(targetInfo.width);
        const int height = static_cast<int>(targetInfo.height);
        const int maskW = static_cast<int>(maskInfo.width);
        const int maskH = static_cast<int>(maskInfo.height);
        const bool inv = inverted == JNI_TRUE;
        const float op = clamp01(opacity);

        for (int y = 0; y < height; ++y) {
            auto* targetRow = target + static_cast<size_t>(y) * targetInfo.stride;
            const auto* localRow = local + static_cast<size_t>(y) * localInfo.stride;
            for (int x = 0; x < width; ++x) {
                float alpha = sample_mask(mask, maskW, maskH, static_cast<int>(maskInfo.stride), width, height, x, y);
                if (inv) alpha = 1.0f - alpha;
                alpha *= op;
                alpha = clamp01(alpha);
                const float invAlpha = 1.0f - alpha;
                auto* dst = targetRow + static_cast<size_t>(x) * 4U;
                const auto* src = localRow + static_cast<size_t>(x) * 4U;
                dst[0] = to_u8((src[0] / 255.0f) * alpha + (dst[0] / 255.0f) * invAlpha);
                dst[1] = to_u8((src[1] / 255.0f) * alpha + (dst[1] / 255.0f) * invAlpha);
                dst[2] = to_u8((src[2] / 255.0f) * alpha + (dst[2] / 255.0f) * invAlpha);
                dst[3] = 255;
            }
        }

        return 0;
    });
}
