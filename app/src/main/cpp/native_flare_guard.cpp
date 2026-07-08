#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <exception>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <deque>
#include <limits>
#include <new>
#include <vector>

#define LOG_TAG "KeplerNativeFlare"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
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

constexpr size_t kMaxTemporaryBytes = 256ull * 1024ull * 1024ull;

static bool validateRgbaBitmapLayout(const AndroidBitmapInfo& info) {
    if (info.width == 0 || info.height == 0 || info.stride == 0) return false;
    if (info.width > static_cast<uint32_t>(std::numeric_limits<int>::max())) return false;
    if (info.height > static_cast<uint32_t>(std::numeric_limits<int>::max())) return false;
    if (info.stride > static_cast<uint32_t>(std::numeric_limits<int>::max())) return false;
    if (static_cast<size_t>(info.width) > std::numeric_limits<size_t>::max() / 4ULL) return false;
    return static_cast<size_t>(info.stride) >= static_cast<size_t>(info.width) * 4ULL;
}

static bool checkedBitmapByteCount(const AndroidBitmapInfo& info, size_t& out) {
    if (!validateRgbaBitmapLayout(info)) return false;
    const size_t stride = static_cast<size_t>(info.stride);
    const size_t height = static_cast<size_t>(info.height);
    if (height != 0 && stride > std::numeric_limits<size_t>::max() / height) return false;
    out = stride * height;
    return true;
}

static bool checkedFloatPlaneByteCount(int width, int height, size_t& out) {
    if (width <= 0 || height <= 0) return false;
    const size_t w = static_cast<size_t>(width);
    const size_t h = static_cast<size_t>(height);
    if (w != 0 && h > std::numeric_limits<size_t>::max() / w) return false;
    const size_t count = w * h;
    if (count > std::numeric_limits<size_t>::max() / sizeof(float)) return false;
    out = count * sizeof(float);
    return true;
}

static bool hasFlareTemporaryBudget(size_t bitmapBytes, size_t planeBytes) {
    if (planeBytes > std::numeric_limits<size_t>::max() / 3ULL) return false;
    const size_t planeTotal = planeBytes * 3ULL;
    if (bitmapBytes > std::numeric_limits<size_t>::max() - planeTotal) return false;
    return bitmapBytes + planeTotal <= kMaxTemporaryBytes;
}

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

static void sliding_max_row(const float* input, float* output, int length, int radius);
static void sliding_max_column(const std::vector<float>& input, std::vector<float>& output, int width, int height, int x, int radius);

static std::vector<float> build_luma_plane(const std::vector<uint8_t>& src, int width, int height, int stride, int scale = 1) {
    const int safeScale = std::max(1, scale);
    const int scaledWidth = std::max(1, (width + safeScale - 1) / safeScale);
    const int scaledHeight = std::max(1, (height + safeScale - 1) / safeScale);
    std::vector<float> luma(static_cast<size_t>(scaledWidth) * static_cast<size_t>(scaledHeight), 0.0f);
    if (safeScale == 1) {
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                const uint8_t* p = pixel_at(src, stride, x, y);
                luma[static_cast<size_t>(y) * width + x] = luma_of(p[0], p[1], p[2]);
            }
        }
        return luma;
    }

    for (int y = 0; y < scaledHeight; ++y) {
        const int srcTop = y * safeScale;
        const int srcBottom = std::min(height, srcTop + safeScale);
        for (int x = 0; x < scaledWidth; ++x) {
            const int srcLeft = x * safeScale;
            const int srcRight = std::min(width, srcLeft + safeScale);
            float maxLuma = 0.0f;
            for (int yy = srcTop; yy < srcBottom; ++yy) {
                for (int xx = srcLeft; xx < srcRight; ++xx) {
                    const uint8_t* p = pixel_at(src, stride, xx, yy);
                    maxLuma = std::max(maxLuma, luma_of(p[0], p[1], p[2]));
                }
            }
            luma[static_cast<size_t>(y) * scaledWidth + x] = maxLuma;
        }
    }
    return luma;
}

static int flare_luma_scale_for_size(int width, int height) {
    const int maxDim = std::max(width, height);
    if (maxDim > 6144) return 4;
    if (maxDim > 3072) return 2;
    return 1;
}

static inline float sample_local_max(const std::vector<float>& localMax, int scaledWidth, int scaledHeight, int scale, int x, int y) {
    const int safeScale = std::max(1, scale);
    if (safeScale == 1) {
        const int sx = std::min(scaledWidth - 1, std::max(0, x));
        const int sy = std::min(scaledHeight - 1, std::max(0, y));
        return localMax[static_cast<size_t>(sy) * scaledWidth + sx];
    }
    const float fx = std::max(0.0f, static_cast<float>(x) / static_cast<float>(safeScale));
    const float fy = std::max(0.0f, static_cast<float>(y) / static_cast<float>(safeScale));
    const int x0 = std::min(scaledWidth - 1, static_cast<int>(std::floor(fx)));
    const int y0 = std::min(scaledHeight - 1, static_cast<int>(std::floor(fy)));
    const int x1 = std::min(scaledWidth - 1, x0 + 1);
    const int y1 = std::min(scaledHeight - 1, y0 + 1);
    const float tx = clamp01(fx - static_cast<float>(x0));
    const float ty = clamp01(fy - static_cast<float>(y0));
    const float a = localMax[static_cast<size_t>(y0) * scaledWidth + x0];
    const float b = localMax[static_cast<size_t>(y0) * scaledWidth + x1];
    const float c = localMax[static_cast<size_t>(y1) * scaledWidth + x0];
    const float d = localMax[static_cast<size_t>(y1) * scaledWidth + x1];
    return (a + (b - a) * tx) + ((c + (d - c) * tx) - (a + (b - a) * tx)) * ty;
}

static std::vector<float> build_local_max_plane(
    const std::vector<uint8_t>& src,
    int width,
    int height,
    int stride,
    int radius,
    int scale,
    int& outScaledWidth,
    int& outScaledHeight
) {
    const int safeScale = std::max(1, scale);
    outScaledWidth = std::max(1, (width + safeScale - 1) / safeScale);
    outScaledHeight = std::max(1, (height + safeScale - 1) / safeScale);
    const std::vector<float> luma = build_luma_plane(src, width, height, stride, safeScale);
    std::vector<float> temp(static_cast<size_t>(outScaledWidth) * static_cast<size_t>(outScaledHeight), 0.0f);
    std::vector<float> localMax(static_cast<size_t>(outScaledWidth) * static_cast<size_t>(outScaledHeight), 0.0f);
    const int scaledRadius = std::max(0, (radius + safeScale - 1) / safeScale);
    for (int y = 0; y < outScaledHeight; ++y) {
        const size_t rowStart = static_cast<size_t>(y) * outScaledWidth;
        sliding_max_row(luma.data() + rowStart, temp.data() + rowStart, outScaledWidth, scaledRadius);
    }
    for (int x = 0; x < outScaledWidth; ++x) {
        sliding_max_column(temp, localMax, outScaledWidth, outScaledHeight, x, scaledRadius);
    }
    return localMax;
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

static void apply_night_flare(uint8_t* dst, const std::vector<uint8_t>& src, int width, int height, int stride, float strength) {
    if (width <= 0 || height <= 0) return;
    const float s = clamp01(strength);
    const int radius = std::max(3, std::max(width, height) / 160);
    const int lumaScale = flare_luma_scale_for_size(width, height);
    int scaledWidth = 1;
    int scaledHeight = 1;
    const auto localMaxStart = std::chrono::steady_clock::now();
    const std::vector<float> localMax = build_local_max_plane(src, width, height, stride, radius, lumaScale, scaledWidth, scaledHeight);
    const auto localMaxEnd = std::chrono::steady_clock::now();
    const auto pixelLoopStart = std::chrono::steady_clock::now();
    for (int y = 0; y < height; ++y) {
        auto* outRow = dst + static_cast<size_t>(y) * stride;
        for (int x = 0; x < width; ++x) {
            const uint8_t* in = pixel_at(src, stride, x, y);
            auto* out = outRow + static_cast<size_t>(x) * 4U;
            float r = in[0] / 255.0f;
            float g = in[1] / 255.0f;
            float b = in[2] / 255.0f;
            const float l = luma_of(in[0], in[1], in[2]);
            const float local = sample_local_max(localMax, scaledWidth, scaledHeight, lumaScale, x, y);
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
    const auto pixelLoopEnd = std::chrono::steady_clock::now();
    LOGD(
        "night flare %dx%d radius=%d scale=%d localMaxBuild=%.2fms pixels=%.2fms",
        width,
        height,
        radius,
        lumaScale,
        std::chrono::duration<double, std::milli>(localMaxEnd - localMaxStart).count(),
        std::chrono::duration<double, std::milli>(pixelLoopEnd - pixelLoopStart).count()
    );
}

static void apply_day_sun_flare(uint8_t* dst, const std::vector<uint8_t>& src, int width, int height, int stride, float strength) {
    if (width <= 0 || height <= 0) return;
    const float s = clamp01(strength);
    const int radius = std::max(6, std::max(width, height) / 96);
    const int lumaScale = flare_luma_scale_for_size(width, height);
    int scaledWidth = 1;
    int scaledHeight = 1;
    const auto localMaxStart = std::chrono::steady_clock::now();
    const std::vector<float> localMax = build_local_max_plane(src, width, height, stride, radius, lumaScale, scaledWidth, scaledHeight);
    const auto localMaxEnd = std::chrono::steady_clock::now();
    const auto pixelLoopStart = std::chrono::steady_clock::now();
    for (int y = 0; y < height; ++y) {
        auto* outRow = dst + static_cast<size_t>(y) * stride;
        for (int x = 0; x < width; ++x) {
            const uint8_t* in = pixel_at(src, stride, x, y);
            auto* out = outRow + static_cast<size_t>(x) * 4U;
            float r = in[0] / 255.0f;
            float g = in[1] / 255.0f;
            float b = in[2] / 255.0f;
            const float l = luma_of(in[0], in[1], in[2]);
            const float local = sample_local_max(localMax, scaledWidth, scaledHeight, lumaScale, x, y);
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
    const auto pixelLoopEnd = std::chrono::steady_clock::now();
    LOGD(
        "day flare %dx%d radius=%d scale=%d localMaxBuild=%.2fms pixels=%.2fms",
        width,
        height,
        radius,
        lumaScale,
        std::chrono::duration<double, std::milli>(localMaxEnd - localMaxStart).count(),
        std::chrono::duration<double, std::milli>(pixelLoopEnd - pixelLoopStart).count()
    );
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
    return runNativeGuarded("nativeApplyFlareGuardInPlaceNative", [&]() -> jint {
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("AndroidBitmap_getInfo failed");
            return -1;
        }
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            LOGE("Unsupported bitmap format: %d", info.format);
            return -2;
        }
        if (!validateRgbaBitmapLayout(info)) {
            LOGE("Invalid bitmap dimensions or stride");
            return -11;
        }
        size_t bitmapByteCount = 0;
        if (!checkedBitmapByteCount(info, bitmapByteCount)) {
            LOGE("Temporary bitmap copy too large");
            return -12;
        }
        const int width = static_cast<int>(info.width);
        const int height = static_cast<int>(info.height);
        const int lumaScale = flare_luma_scale_for_size(width, height);
        const int scaledWidth = std::max(1, (width + lumaScale - 1) / lumaScale);
        const int scaledHeight = std::max(1, (height + lumaScale - 1) / lumaScale);
        size_t planeBytes = 0;
        if (!checkedFloatPlaneByteCount(scaledWidth, scaledHeight, planeBytes) ||
            !hasFlareTemporaryBudget(bitmapByteCount, planeBytes)) {
            LOGE("Temporary flare buffers too large");
            return -12;
        }
        LockedBitmap locked(env, bitmap);
        if (locked.lock() != 0) {
            return -3;
        }
        auto* bytes = static_cast<uint8_t*>(locked.pixels);
        const size_t byteCount = bitmapByteCount;
        std::vector<uint8_t> src(byteCount);
        std::copy(bytes, bytes + byteCount, src.data());
        const int stride = static_cast<int>(info.stride);
        if (mode == 1) {
            apply_day_sun_flare(bytes, src, width, height, stride, strength);
        } else {
            apply_night_flare(bytes, src, width, height, stride, strength);
        }
        return revision;
    });
}
