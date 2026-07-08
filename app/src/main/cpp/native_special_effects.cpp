#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <exception>
#include <cmath>
#include <cstdint>
#include <new>
#include <vector>

#define LOG_TAG "KeplerNativeFx"
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
    if (v < 0.0f) return 0.0f;
    if (v > 1.0f) return 1.0f;
    return v;
}

static inline uint8_t to_u8(float v) {
    return static_cast<uint8_t>(std::round(clamp01(v) * 255.0f));
}

static inline float luma(float r, float g, float b) {
    return 0.2126f * r + 0.7152f * g + 0.0722f * b;
}

static inline float smoothstep(float edge0, float edge1, float x) {
    const float t = clamp01((x - edge0) / (edge1 - edge0));
    return t * t * (3.0f - 2.0f * t);
}

static void apply_chroma_fringe_reduce(uint8_t* base, int width, int height, int stride, float strength) {
    const float s = clamp01(strength);
    if (s <= 0.001f) return;
    for (int y = 0; y < height; ++y) {
        auto* row = base + y * stride;
        for (int x = 0; x < width; ++x) {
            auto* px = row + x * 4;
            float r = px[0] / 255.0f;
            float g = px[1] / 255.0f;
            float b = px[2] / 255.0f;
            const float yv = luma(r, g, b);
            const float rb = std::fabs(r - b);
            const float magenta = std::max(0.0f, std::min(r, b) - g);
            const float cyan = std::max(0.0f, std::min(g, b) - r);
            const float mask = clamp01((smoothstep(0.06f, 0.24f, rb) + smoothstep(0.025f, 0.18f, magenta + cyan)) * 0.5f) * s;
            r = r + (yv - r) * mask * 0.72f;
            g = g + (yv - g) * mask * 0.42f;
            b = b + (yv - b) * mask * 0.72f;
            px[0] = to_u8(r);
            px[1] = to_u8(g);
            px[2] = to_u8(b);
        }
    }
}

static void apply_vignette_correction(uint8_t* base, int width, int height, int stride, float strength) {
    const float s = clamp01(strength);
    if (s <= 0.001f) return;
    const float cx = (width - 1) * 0.5f;
    const float cy = (height - 1) * 0.5f;
    const float invMax = 1.0f / std::max(1.0f, std::sqrt(cx * cx + cy * cy));
    for (int y = 0; y < height; ++y) {
        auto* row = base + y * stride;
        for (int x = 0; x < width; ++x) {
            const float dx = x - cx;
            const float dy = y - cy;
            const float d = std::sqrt(dx * dx + dy * dy) * invMax;
            const float mask = smoothstep(0.38f, 1.0f, d) * s;
            const float gain = 1.0f + mask * 0.32f;
            auto* px = row + x * 4;
            px[0] = to_u8((px[0] / 255.0f) * gain);
            px[1] = to_u8((px[1] / 255.0f) * gain);
            px[2] = to_u8((px[2] / 255.0f) * gain);
        }
    }
}

static void apply_soft_blur(uint8_t* base, int width, int height, int stride, float strength) {
    const float s = clamp01(strength);
    if (s <= 0.001f || width < 3 || height < 3) return;
    std::vector<uint8_t> src(static_cast<size_t>(stride) * static_cast<size_t>(height));
    std::copy(base, base + static_cast<size_t>(stride) * static_cast<size_t>(height), src.data());
    for (int y = 0; y < height; ++y) {
        auto* outRow = base + y * stride;
        for (int x = 0; x < width; ++x) {
            float sum[3] = {0.0f, 0.0f, 0.0f};
            float weightSum = 0.0f;
            for (int dy = -1; dy <= 1; ++dy) {
                const int yy = std::min(height - 1, std::max(0, y + dy));
                const auto* row = src.data() + yy * stride;
                for (int dx = -1; dx <= 1; ++dx) {
                    const int xx = std::min(width - 1, std::max(0, x + dx));
                    const float w = (dx == 0 && dy == 0) ? 4.0f : ((dx == 0 || dy == 0) ? 2.0f : 1.0f);
                    const auto* p = row + xx * 4;
                    sum[0] += (p[0] / 255.0f) * w;
                    sum[1] += (p[1] / 255.0f) * w;
                    sum[2] += (p[2] / 255.0f) * w;
                    weightSum += w;
                }
            }
            auto* out = outRow + x * 4;
            for (int c = 0; c < 3; ++c) {
                const float original = src[static_cast<size_t>(y) * stride + static_cast<size_t>(x) * 4U + c] / 255.0f;
                const float blurred = sum[c] / std::max(0.0001f, weightSum);
                out[c] = to_u8(original + (blurred - original) * s);
            }
        }
    }
}

static void apply_small_spot_cleanup(uint8_t* base, int width, int height, int stride, float strength) {
    const float s = clamp01(strength);
    if (s <= 0.001f || width < 3 || height < 3) return;
    std::vector<uint8_t> src(static_cast<size_t>(stride) * static_cast<size_t>(height));
    std::copy(base, base + static_cast<size_t>(stride) * static_cast<size_t>(height), src.data());
    for (int y = 0; y < height; ++y) {
        auto* outRow = base + y * stride;
        for (int x = 0; x < width; ++x) {
            const auto* center = src.data() + y * stride + x * 4;
            float cr = center[0] / 255.0f;
            float cg = center[1] / 255.0f;
            float cb = center[2] / 255.0f;
            const float cy = luma(cr, cg, cb);
            float sum[3] = {0.0f, 0.0f, 0.0f};
            float sumY = 0.0f;
            float sumW = 0.0f;
            float rangeSumY = 0.0f;
            float rangeSumY2 = 0.0f;
            float rangeSumW = 0.0f;
            float minY = 1.0f;
            float maxY = 0.0f;
            for (int dy = -2; dy <= 2; ++dy) {
                const int yy = std::min(height - 1, std::max(0, y + dy));
                for (int dx = -2; dx <= 2; ++dx) {
                    if (dx == 0 && dy == 0) continue;
                    const int xx = std::min(width - 1, std::max(0, x + dx));
                    if (xx == x && yy == y) continue;
                    const auto* p = src.data() + yy * stride + xx * 4;
                    const float r = p[0] / 255.0f;
                    const float g = p[1] / 255.0f;
                    const float b = p[2] / 255.0f;
                    const float py = luma(r, g, b);
                    const float diff = py - cy;
                    const float spatial = 1.0f / (1.0f + static_cast<float>(std::abs(dx) + std::abs(dy)));
                    const float range = 0.08f + 0.92f * std::exp(-(diff * diff) / 0.020f);
                    const float w = spatial * range;
                    sum[0] += r * w;
                    sum[1] += g * w;
                    sum[2] += b * w;
                    sumY += py * w;
                    sumW += w;
                    rangeSumY += py * w;
                    rangeSumY2 += py * py * w;
                    rangeSumW += w;
                    minY = std::min(minY, py);
                    maxY = std::max(maxY, py);
                }
            }
            if (sumW <= 0.0001f || rangeSumW <= 0.0001f) continue;
            const float inv = 1.0f / sumW;
            const float avg[3] = { sum[0] * inv, sum[1] * inv, sum[2] * inv };
            const float avgY = sumY * inv;
            const float rangeMean = rangeSumY / rangeSumW;
            const float variance = std::max(0.0f, rangeSumY2 / rangeSumW - rangeMean * rangeMean);
            const float localRange = std::max((maxY - minY) * 0.65f, std::sqrt(variance) * 2.35f);
            const float chromaOutlier = std::max(
                std::fabs((cr - cy) - (avg[0] - avgY)),
                std::fabs((cb - cy) - (avg[2] - avgY))
            );
            const float isolated = std::max(
                smoothstep(0.10f, 0.26f, std::fabs(cy - avgY)),
                smoothstep(0.08f, 0.22f, chromaOutlier)
            );
            const float detailGuard = 1.0f - smoothstep(0.055f, 0.180f, localRange);
            const float mix = isolated * detailGuard * s * 0.72f;
            auto* out = outRow + x * 4;
            out[0] = to_u8(cr + (avg[0] - cr) * mix);
            out[1] = to_u8(cg + (avg[1] - cg) * mix);
            out[2] = to_u8(cb + (avg[2] - cb) * mix);
        }
    }
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeApplySpecialEffectInPlaceNative(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject bitmap,
    jint effect,
    jfloat strength,
    jint revision
) {
    return runNativeGuarded("nativeApplySpecialEffectInPlaceNative", [&]() -> jint {
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
            LOGE("AndroidBitmap_getInfo failed");
            return -1;
        }
        if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            LOGE("Unsupported bitmap format: %d", info.format);
            return -2;
        }
        LockedBitmap locked(env, bitmap);
        if (locked.lock() != 0) {
            LOGE("AndroidBitmap_lockPixels failed");
            return -3;
        }
        auto* bytes = static_cast<uint8_t*>(locked.pixels);
        const int width = static_cast<int>(info.width);
        const int height = static_cast<int>(info.height);
        const int stride = static_cast<int>(info.stride);
        switch (effect) {
            case 0: apply_small_spot_cleanup(bytes, width, height, stride, strength); break;
            case 1: apply_chroma_fringe_reduce(bytes, width, height, stride, strength); break;
            case 2: apply_vignette_correction(bytes, width, height, stride, strength); break;
            case 3: apply_soft_blur(bytes, width, height, stride, strength); break;
            default:
                return -10;
        }
        return revision;
    });
}
