#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <deque>
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

static std::vector<float> build_luma_plane(const std::vector<uint8_t>& src, int width, int height, int stride) {
    std::vector<float> luma(static_cast<size_t>(width) * static_cast<size_t>(height), 0.0f);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const uint8_t* p = pixel_at(src, stride, x, y);
            luma[static_cast<size_t>(y) * width + x] = luma_of(p[0], p[1], p[2]);
        }
    }
    return luma;
}

static void sliding_max_row(const float* input, float* output, int length, int radius) {
    std::deque<int> deque;
    int rightAdded = -1;
    for (int i = 0; i < length; ++i) {
        const int left = std::max(0, i - radius);
        const int right = std::min(length - 1, i + radius);
        while (rightAdded < right) {
            ++rightAdded;
            while (!deque.empty() && input[deque.back()] <= input[rightAdded]) {
                deque.pop_back();
            }
            deque.push_back(rightAdded);
        }
        while (!deque.empty() && deque.front() < left) {
            deque.pop_front();
        }
        output[i] = deque.empty() ? 0.0f : input[deque.front()];
    }
}

static void sliding_max_column(const std::vector<float>& input, std::vector<float>& output, int width, int height, int x, int radius) {
    std::deque<int> deque;
    int bottomAdded = -1;
    for (int y = 0; y < height; ++y) {
        const int top = std::max(0, y - radius);
        const int bottom = std::min(height - 1, y + radius);
        while (bottomAdded < bottom) {
            ++bottomAdded;
            const float candidate = input[static_cast<size_t>(bottomAdded) * width + x];
            while (!deque.empty() && input[static_cast<size_t>(deque.back()) * width + x] <= candidate) {
                deque.pop_back();
            }
            deque.push_back(bottomAdded);
        }
        while (!deque.empty() && deque.front() < top) {
            deque.pop_front();
        }
        output[static_cast<size_t>(y) * width + x] =
            deque.empty() ? 0.0f : input[static_cast<size_t>(deque.front()) * width + x];
    }
}

static std::vector<float> local_max_luma_plane(const std::vector<uint8_t>& src, int width, int height, int stride, int radius) {
    if (width <= 0 || height <= 0) {
        return {};
    }
    const int safeRadius = std::max(0, radius);
    std::vector<float> luma = build_luma_plane(src, width, height, stride);
    std::vector<float> temp(static_cast<size_t>(width) * static_cast<size_t>(height), 0.0f);
    std::vector<float> localMax(static_cast<size_t>(width) * static_cast<size_t>(height), 0.0f);

    for (int y = 0; y < height; ++y) {
        const size_t rowStart = static_cast<size_t>(y) * width;
        sliding_max_row(luma.data() + rowStart, temp.data() + rowStart, width, safeRadius);
    }
    for (int x = 0; x < width; ++x) {
        sliding_max_column(temp, localMax, width, height, x, safeRadius);
    }
    return localMax;
}

static void apply_night_flare(uint8_t* dst, const std::vector<uint8_t>& src, int width, int height, int stride, float strength) {
    if (width <= 0 || height <= 0) return;
    const float s = clamp01(strength);
    const int radius = std::max(3, std::max(width, height) / 160);
    const std::vector<float> localMax = local_max_luma_plane(src, width, height, stride, radius);
    for (int y = 0; y < height; ++y) {
        auto* outRow = dst + static_cast<size_t>(y) * stride;
        for (int x = 0; x < width; ++x) {
            const uint8_t* in = pixel_at(src, stride, x, y);
            auto* out = outRow + static_cast<size_t>(x) * 4U;
            float r = in[0] / 255.0f;
            float g = in[1] / 255.0f;
            float b = in[2] / 255.0f;
            const float l = luma_of(in[0], in[1], in[2]);
            const float local = localMax[static_cast<size_t>(y) * width + x];
            const float halo = smoothstep(0.70f, 0.97f, local);
            const float coreProtect = smoothstep(0.90f, 1.0f, l);
            const float amount = halo * s * (1.0f - 0.88f * coreProtect);
            const float desat = amount * 0.16f;
            const float darken = amount * 0.08f;
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
    if (width <= 0 || height <= 0) return;
    const float s = clamp01(strength);
    const int radius = std::max(6, std::max(width, height) / 96);
    const std::vector<float> localMax = local_max_luma_plane(src, width, height, stride, radius);
    for (int y = 0; y < height; ++y) {
        auto* outRow = dst + static_cast<size_t>(y) * stride;
        for (int x = 0; x < width; ++x) {
            const uint8_t* in = pixel_at(src, stride, x, y);
            auto* out = outRow + static_cast<size_t>(x) * 4U;
            float r = in[0] / 255.0f;
            float g = in[1] / 255.0f;
            float b = in[2] / 255.0f;
            const float l = luma_of(in[0], in[1], in[2]);
            const float local = localMax[static_cast<size_t>(y) * width + x];
            const float veil = smoothstep(0.64f, 0.95f, local) * smoothstep(0.42f, 0.88f, l);
            const float coreProtect = smoothstep(0.86f, 1.0f, l);
            const float amount = veil * s * (1.0f - 0.90f * coreProtect);
            const float contrast = 1.0f + amount * 0.16f;
            const float sat = 1.0f + amount * 0.08f;
            const float darken = amount * 0.03f;
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
