#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#define LOG_TAG "KeplerNativeFlare"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

static inline float clamp01(float v) {
    return std::max(0.0f, std::min(1.0f, v));
}

static inline uint8_t to_u8(float v) {
    return static_cast<uint8_t>(std::round(clamp01(v) * 255.0f));
}

static inline float smoothstep(float edge0, float edge1, float x) {
    const float t = clamp01((x - edge0) / (edge1 - edge0));
    return t * t * (3.0f - 2.0f * t);
}

static inline float luma_of(uint8_t r, uint8_t g, uint8_t b) {
    return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255.0f;
}

static inline const uint8_t* pixel_at(const std::vector<uint8_t>& src, int stride, int x, int y) {
    return src.data() + static_cast<size_t>(y) * stride + static_cast<size_t>(x) * 4U;
}

static float local_max_luma(const std::vector<uint8_t>& src, int width, int height, int stride, int x, int y, int radius) {
    float maxL = 0.0f;
    const int left = std::max(0, x - radius);
    const int right = std::min(width - 1, x + radius);
    const int top = std::max(0, y - radius);
    const int bottom = std::min(height - 1, y + radius);
    for (int yy = top; yy <= bottom; ++yy) {
        for (int xx = left; xx <= right; ++xx) {
            const uint8_t* p = pixel_at(src, stride, xx, yy);
            maxL = std::max(maxL, luma_of(p[0], p[1], p[2]));
        }
    }
    return maxL;
}

static void apply_night_flare(uint8_t* dst, const std::vector<uint8_t>& src, int width, int height, int stride, float strength) {
    const float s = clamp01(strength);
    const int radius = std::max(3, std::max(width, height) / 160);
    for (int y = 0; y < height; ++y) {
        auto* outRow = dst + static_cast<size_t>(y) * stride;
        for (int x = 0; x < width; ++x) {
            const uint8_t* in = pixel_at(src, stride, x, y);
            auto* out = outRow + static_cast<size_t>(x) * 4U;
            float r = in[0] / 255.0f;
            float g = in[1] / 255.0f;
            float b = in[2] / 255.0f;
            const float l = luma_of(in[0], in[1], in[2]);
            const float local = local_max_luma(src, width, height, stride, x, y, radius);
            const float halo = smoothstep(0.64f, 0.96f, local);
            const float coreProtect = smoothstep(0.92f, 1.0f, l);
            const float amount = halo * s * (1.0f - 0.76f * coreProtect);
            const float desat = amount * 0.28f;
            const float darken = amount * 0.16f;
            r = (r + (l - r) * desat) * (1.0f - darken);
            g = (g + (l - g) * desat) * (1.0f - darken);
            b = (b + (l - b) * desat) * (1.0f - darken);
            out[0] = to_u8(r);
            out[1] = to_u8(g);
            out[2] = to_u8(b);
            out[3] = in[3];
        }
    }
}

static void apply_day_sun_flare(uint8_t* dst, const std::vector<uint8_t>& src, int width, int height, int stride, float strength) {
    const float s = clamp01(strength);
    const int radius = std::max(6, std::max(width, height) / 96);
    for (int y = 0; y < height; ++y) {
        auto* outRow = dst + static_cast<size_t>(y) * stride;
        for (int x = 0; x < width; ++x) {
            const uint8_t* in = pixel_at(src, stride, x, y);
            auto* out = outRow + static_cast<size_t>(x) * 4U;
            float r = in[0] / 255.0f;
            float g = in[1] / 255.0f;
            float b = in[2] / 255.0f;
            const float l = luma_of(in[0], in[1], in[2]);
            const float local = local_max_luma(src, width, height, stride, x, y, radius);
            const float veil = smoothstep(0.58f, 0.94f, local) * smoothstep(0.36f, 0.86f, l);
            const float coreProtect = smoothstep(0.88f, 1.0f, l);
            const float amount = veil * s * (1.0f - 0.84f * coreProtect);
            const float contrast = 1.0f + amount * 0.24f;
            const float sat = 1.0f + amount * 0.12f;
            const float darken = amount * 0.07f;
            r = ((r - 0.5f) * contrast + 0.5f);
            g = ((g - 0.5f) * contrast + 0.5f);
            b = ((b - 0.5f) * contrast + 0.5f);
            const float nl = clamp01(0.2126f * r + 0.7152f * g + 0.0722f * b);
            r = (nl + (r - nl) * sat) * (1.0f - darken);
            g = (nl + (g - nl) * sat) * (1.0f - darken);
            b = (nl + (b - nl) * sat) * (1.0f - darken);
            out[0] = to_u8(r);
            out[1] = to_u8(g);
            out[2] = to_u8(b);
            out[3] = in[3];
        }
    }
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeApplyFlareGuardInPlaceNative(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject bitmap,
    jint mode,
    jfloat strength,
    jint revision
) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed");
        return -1;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported bitmap format: %d", info.format);
        return -2;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
        return -3;
    }
    auto* bytes = static_cast<uint8_t*>(pixels);
    const size_t byteCount = static_cast<size_t>(info.stride) * static_cast<size_t>(info.height);
    std::vector<uint8_t> src(byteCount);
    std::copy(bytes, bytes + byteCount, src.data());
    const int width = static_cast<int>(info.width);
    const int height = static_cast<int>(info.height);
    const int stride = static_cast<int>(info.stride);
    if (mode == 1) {
        apply_day_sun_flare(bytes, src, width, height, stride, strength);
    } else {
        apply_night_flare(bytes, src, width, height, stride, strength);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return revision;
}
