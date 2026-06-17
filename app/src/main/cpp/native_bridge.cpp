#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <string>
#include <vector>

#define LOG_TAG "KeplerPhotoCore"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    std::string sourcePath;
};

static inline float clamp01(float v) {
    if (v < 0.0f) return 0.0f;
    if (v > 1.0f) return 1.0f;
    return v;
}

static inline float clampf(float v, float lo, float hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}

static inline float lerp(float a, float b, float t) {
    return a + (b - a) * t;
}

static inline uint8_t to_u8(float v) {
    v = clamp01(v);
    return static_cast<uint8_t>(std::round(v * 255.0f));
}

static inline float smoothstep(float edge0, float edge1, float x) {
    float t = clamp01((x - edge0) / (edge1 - edge0));
    return t * t * (3.0f - 2.0f * t);
}

static inline float luma_of(float r, float g, float b) {
    return 0.2126f * r + 0.7152f * g + 0.0722f * b;
}

static inline float channel_at(const std::vector<uint8_t>& row, int x, int channel) {
    return row[static_cast<size_t>(x) * 4U + static_cast<size_t>(channel)] / 255.0f;
}

static inline float luma_at(const std::vector<uint8_t>& row, int x) {
    return luma_of(channel_at(row, x, 0), channel_at(row, x, 1), channel_at(row, x, 2));
}

static void copy_row(uint8_t* dst, const uint8_t* src, int stride) {
    std::copy(src, src + stride, dst);
}

static void apply_adjustment_rgba8888(
    uint8_t* base,
    int width,
    int height,
    int stride,
    float exposure,
    float contrast,
    float shadows,
    float highlights,
    float whites,
    float blacks,
    float temperature,
    float tint,
    float saturation,
    float vibrance,
    float clarity,
    float dehaze,
    float noiseReduction
) {
    const float exposureMul = std::pow(2.0f, exposure);
    const float contrastMul = 1.0f + contrast * 0.72f;
    const float shadowStrength = shadows * 0.42f;
    const float highlightStrength = highlights * 0.42f;
    const float whiteStrength = whites * 0.34f;
    const float blackStrength = blacks * 0.34f;
    const float clarityStrength = clarity * 0.42f;
    const float dehazeStrength = dehaze * 0.36f;
    const float chromaDamping = clamp01(noiseReduction) * 0.18f;

    const float tempR = 1.0f + temperature * 0.11f;
    const float tempG = 1.0f;
    const float tempB = 1.0f - temperature * 0.11f;
    const float tintR = 1.0f + tint * 0.045f;
    const float tintG = 1.0f - tint * 0.085f;
    const float tintB = 1.0f + tint * 0.045f;

    for (int y = 0; y < height; ++y) {
        auto* row = base + y * stride;
        for (int x = 0; x < width; ++x) {
            auto* px = row + x * 4;

            float r = px[0] / 255.0f;
            float g = px[1] / 255.0f;
            float b = px[2] / 255.0f;
            const uint8_t a = px[3];

            r *= exposureMul * tempR * tintR;
            g *= exposureMul * tempG * tintG;
            b *= exposureMul * tempB * tintB;

            r = (r - 0.5f) * contrastMul + 0.5f;
            g = (g - 0.5f) * contrastMul + 0.5f;
            b = (b - 0.5f) * contrastMul + 0.5f;

            float luma = luma_of(r, g, b);
            const float shadowMask = 1.0f - smoothstep(0.18f, 0.56f, luma);
            const float highlightMask = smoothstep(0.50f, 0.92f, luma);
            const float whiteMask = smoothstep(0.70f, 0.985f, luma);
            const float blackMask = 1.0f - smoothstep(0.025f, 0.34f, luma);

            r += shadowStrength * shadowMask * (shadowStrength >= 0.0f ? (1.0f - r) : r);
            g += shadowStrength * shadowMask * (shadowStrength >= 0.0f ? (1.0f - g) : g);
            b += shadowStrength * shadowMask * (shadowStrength >= 0.0f ? (1.0f - b) : b);

            r -= highlightStrength * highlightMask * (highlightStrength >= 0.0f ? r : (1.0f - r));
            g -= highlightStrength * highlightMask * (highlightStrength >= 0.0f ? g : (1.0f - g));
            b -= highlightStrength * highlightMask * (highlightStrength >= 0.0f ? b : (1.0f - b));

            r += whiteStrength * whiteMask * (whiteStrength >= 0.0f ? (1.0f - r) : r);
            g += whiteStrength * whiteMask * (whiteStrength >= 0.0f ? (1.0f - g) : g);
            b += whiteStrength * whiteMask * (whiteStrength >= 0.0f ? (1.0f - b) : b);

            r += blackStrength * blackMask * (blackStrength >= 0.0f ? (1.0f - r) : r);
            g += blackStrength * blackMask * (blackStrength >= 0.0f ? (1.0f - g) : g);
            b += blackStrength * blackMask * (blackStrength >= 0.0f ? (1.0f - b) : b);

            r = clamp01(r);
            g = clamp01(g);
            b = clamp01(b);
            luma = luma_of(r, g, b);

            const float midMask = 1.0f - clamp01(std::fabs(luma - 0.5f) * 2.0f);
            const float localContrast = 1.0f + clarityStrength * midMask + dehazeStrength * 0.30f;
            r = luma + (r - luma) * localContrast;
            g = luma + (g - luma) * localContrast;
            b = luma + (b - luma) * localContrast;

            if (std::fabs(dehazeStrength) > 0.0001f) {
                const float hazeCurve = (luma - 0.5f) * dehazeStrength;
                const float hazeSat = 1.0f + dehazeStrength * 0.18f;
                r = (r + hazeCurve - luma) * hazeSat + luma;
                g = (g + hazeCurve - luma) * hazeSat + luma;
                b = (b + hazeCurve - luma) * hazeSat + luma;
            }

            r = clamp01(r);
            g = clamp01(g);
            b = clamp01(b);
            luma = luma_of(r, g, b);

            const float maxC = std::max(r, std::max(g, b));
            const float minC = std::min(r, std::min(g, b));
            const float chroma = clamp01(maxC - minC);
            const float satMul = 1.0f + saturation * 0.82f;
            const float vibMul = 1.0f + vibrance * 0.86f * (1.0f - chroma);
            const float colorMul = clampf(satMul * vibMul, 0.0f, 2.45f);
            r = luma + (r - luma) * colorMul;
            g = luma + (g - luma) * colorMul;
            b = luma + (b - luma) * colorMul;

            if (chromaDamping > 0.0f) {
                const float nrLuma = luma_of(r, g, b);
                r = nrLuma + (r - nrLuma) * (1.0f - chromaDamping);
                g = nrLuma + (g - nrLuma) * (1.0f - chromaDamping);
                b = nrLuma + (b - nrLuma) * (1.0f - chromaDamping);
            }

            px[0] = to_u8(r);
            px[1] = to_u8(g);
            px[2] = to_u8(b);
            px[3] = a;
        }
    }
}

static void apply_edge_aware_noise_reduction_rgba8888(
    uint8_t* base,
    int width,
    int height,
    int stride,
    float noiseReduction
) {
    const float strength = clamp01(noiseReduction);
    if (strength <= 0.001f || width < 3 || height < 3) return;

    std::vector<uint8_t> prev(static_cast<size_t>(stride));
    std::vector<uint8_t> curr(static_cast<size_t>(stride));
    std::vector<uint8_t> next(static_cast<size_t>(stride));

    copy_row(curr.data(), base, stride);
    copy_row(next.data(), base + stride, stride);
    copy_row(prev.data(), curr.data(), stride);

    const float sigma = 0.035f + (1.0f - strength) * 0.145f;
    const float sigma2 = std::max(0.0001f, sigma * sigma * 2.0f);

    for (int y = 0; y < height; ++y) {
        auto* outRow = base + y * stride;
        if (y + 1 >= height) copy_row(next.data(), curr.data(), stride);

        for (int x = 0; x < width; ++x) {
            auto* outPx = outRow + x * 4;
            const float centerR = channel_at(curr, x, 0);
            const float centerG = channel_at(curr, x, 1);
            const float centerB = channel_at(curr, x, 2);
            const float centerL = luma_of(centerR, centerG, centerB);
            const uint8_t alpha = curr[static_cast<size_t>(x) * 4U + 3U];

            float sumR = 0.0f;
            float sumG = 0.0f;
            float sumB = 0.0f;
            float sumW = 0.0f;

            for (int dy = -1; dy <= 1; ++dy) {
                const std::vector<uint8_t>& row = (dy < 0) ? prev : ((dy > 0) ? next : curr);
                for (int dx = -1; dx <= 1; ++dx) {
                    const int nx = std::min(width - 1, std::max(0, x + dx));
                    const float spatial = (dx == 0 && dy == 0) ? 0.38f : ((dx == 0 || dy == 0) ? 0.13f : 0.07f);
                    const float nr = channel_at(row, nx, 0);
                    const float ng = channel_at(row, nx, 1);
                    const float nb = channel_at(row, nx, 2);
                    const float nl = luma_of(nr, ng, nb);
                    const float diff = nl - centerL;
                    const float range = std::exp(-(diff * diff) / sigma2);
                    const float w = spatial * range;
                    sumR += nr * w;
                    sumG += ng * w;
                    sumB += nb * w;
                    sumW += w;
                }
            }

            const float invW = sumW > 0.0001f ? (1.0f / sumW) : 1.0f;
            const float avgR = sumR * invW;
            const float avgG = sumG * invW;
            const float avgB = sumB * invW;
            const float shadowBoost = 0.75f + (1.0f - centerL) * 0.35f;
            const float mix = clamp01(strength * 0.78f * shadowBoost);

            outPx[0] = to_u8(lerp(centerR, avgR, mix));
            outPx[1] = to_u8(lerp(centerG, avgG, mix));
            outPx[2] = to_u8(lerp(centerB, avgB, mix));
            outPx[3] = alpha;
        }

        if (y + 1 < height) {
            prev.swap(curr);
            curr.swap(next);
            if (y + 2 < height) {
                copy_row(next.data(), base + (y + 2) * stride, stride);
            } else {
                copy_row(next.data(), curr.data(), stride);
            }
        }
    }
}

static float guided_estimate_channel(
    const std::vector<uint8_t>& prev,
    const std::vector<uint8_t>& curr,
    const std::vector<uint8_t>& next,
    int width,
    int x,
    int channel,
    float eps
) {
    float meanI = 0.0f;
    float meanP = 0.0f;
    float corrI = 0.0f;
    float corrIP = 0.0f;
    const std::vector<uint8_t>* rows[3] = { &prev, &curr, &next };

    for (const auto* row : rows) {
        for (int dx = -1; dx <= 1; ++dx) {
            const int nx = std::min(width - 1, std::max(0, x + dx));
            const float i = luma_at(*row, nx);
            const float p = channel_at(*row, nx, channel);
            meanI += i;
            meanP += p;
            corrI += i * i;
            corrIP += i * p;
        }
    }

    meanI /= 9.0f;
    meanP /= 9.0f;
    corrI /= 9.0f;
    corrIP /= 9.0f;

    const float varI = std::max(0.0f, corrI - meanI * meanI);
    const float covIP = corrIP - meanI * meanP;
    const float a = covIP / (varI + eps);
    const float b = meanP - a * meanI;
    return clamp01(a * luma_at(curr, x) + b);
}

static void apply_guided_noise_reduction_rgba8888(
    uint8_t* base,
    int width,
    int height,
    int stride,
    float noiseReduction
) {
    const float strength = clamp01(noiseReduction);
    if (strength <= 0.001f || width < 3 || height < 3) return;

    std::vector<uint8_t> prev(static_cast<size_t>(stride));
    std::vector<uint8_t> curr(static_cast<size_t>(stride));
    std::vector<uint8_t> next(static_cast<size_t>(stride));

    copy_row(curr.data(), base, stride);
    copy_row(next.data(), base + stride, stride);
    copy_row(prev.data(), curr.data(), stride);

    const float eps = 0.0008f + (1.0f - strength) * 0.006f;

    for (int y = 0; y < height; ++y) {
        auto* outRow = base + y * stride;
        if (y + 1 >= height) copy_row(next.data(), curr.data(), stride);

        for (int x = 0; x < width; ++x) {
            auto* outPx = outRow + x * 4;
            const float centerR = channel_at(curr, x, 0);
            const float centerG = channel_at(curr, x, 1);
            const float centerB = channel_at(curr, x, 2);
            const float centerL = luma_of(centerR, centerG, centerB);
            const float guidedR = guided_estimate_channel(prev, curr, next, width, x, 0, eps);
            const float guidedG = guided_estimate_channel(prev, curr, next, width, x, 1, eps);
            const float guidedB = guided_estimate_channel(prev, curr, next, width, x, 2, eps);
            const float mix = clamp01(strength * (0.64f + (1.0f - centerL) * 0.24f));
            const uint8_t alpha = curr[static_cast<size_t>(x) * 4U + 3U];

            outPx[0] = to_u8(lerp(centerR, guidedR, mix));
            outPx[1] = to_u8(lerp(centerG, guidedG, mix));
            outPx[2] = to_u8(lerp(centerB, guidedB, mix));
            outPx[3] = alpha;
        }

        if (y + 1 < height) {
            prev.swap(curr);
            curr.swap(next);
            if (y + 2 < height) {
                copy_row(next.data(), base + (y + 2) * stride, stride);
            } else {
                copy_row(next.data(), curr.data(), stride);
            }
        }
    }
}

static void apply_sharpness_rgba8888(
    uint8_t* base,
    int width,
    int height,
    int stride,
    float sharpness,
    float noiseReduction
) {
    const float baseAmount = clamp01(sharpness) * 1.15f;
    if (baseAmount <= 0.001f || width < 3 || height < 3) return;

    std::vector<uint8_t> prev(static_cast<size_t>(stride));
    std::vector<uint8_t> curr(static_cast<size_t>(stride));
    std::vector<uint8_t> next(static_cast<size_t>(stride));

    copy_row(curr.data(), base, stride);
    copy_row(next.data(), base + stride, stride);
    copy_row(prev.data(), curr.data(), stride);

    for (int y = 0; y < height; ++y) {
        auto* outRow = base + y * stride;
        if (y + 1 >= height) copy_row(next.data(), curr.data(), stride);

        for (int x = 0; x < width; ++x) {
            const int lx = std::max(0, x - 1);
            const int rx = std::min(width - 1, x + 1);
            auto* outPx = outRow + x * 4;
            const uint8_t alpha = curr[static_cast<size_t>(x) * 4U + 3U];

            const float centerL = luma_at(curr, x);
            const float blurL = (
                centerL * 4.0f +
                luma_at(curr, lx) +
                luma_at(curr, rx) +
                luma_at(prev, x) +
                luma_at(next, x)
            ) / 8.0f;
            const float detail = std::fabs(centerL - blurL);
            const float textureMask = smoothstep(0.010f + noiseReduction * 0.020f, 0.070f + noiseReduction * 0.025f, detail);
            const float shadowMask = smoothstep(0.045f, 0.28f, centerL);
            const float highlightGuard = 1.0f - 0.50f * smoothstep(0.90f, 1.0f, centerL);
            const float amount = baseAmount * textureMask * shadowMask * highlightGuard;

            for (int c = 0; c < 3; ++c) {
                const float center = channel_at(curr, x, c);
                const float blur = (
                    center * 4.0f +
                    channel_at(curr, lx, c) +
                    channel_at(curr, rx, c) +
                    channel_at(prev, x, c) +
                    channel_at(next, x, c)
                ) / 8.0f;
                const float delta = clampf((center - blur) * amount, -0.105f, 0.105f);
                outPx[c] = to_u8(center + delta);
            }
            outPx[3] = alpha;
        }

        if (y + 1 < height) {
            prev.swap(curr);
            curr.swap(next);
            if (y + 2 < height) {
                copy_row(next.data(), base + (y + 2) * stride, stride);
            } else {
                copy_row(next.data(), curr.data(), stride);
            }
        }
    }
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeVersion(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("PhotoCore C++ v0.4");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeCreateSession(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring sourcePath
) {
    const char* chars = env->GetStringUTFChars(sourcePath, nullptr);
    if (!chars) return 0L;

    auto* session = new Session();
    session->sourcePath = chars;

    env->ReleaseStringUTFChars(sourcePath, chars);
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeReleaseSession(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<Session*>(handle);
    delete session;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeRenderPreviewInPlace(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject bitmap,
    jfloat exposure,
    jfloat contrast,
    jfloat shadows,
    jfloat highlights,
    jfloat whites,
    jfloat blacks,
    jfloat temperature,
    jfloat tint,
    jfloat saturation,
    jfloat vibrance,
    jfloat clarity,
    jfloat dehaze,
    jfloat sharpness,
    jfloat noiseReduction,
    jint noiseEngine,
    jint detailEngine,
    jint toneEngine,
    jint hazeEngine,
    jint revision
) {
    (void)detailEngine;
    (void)toneEngine;
    (void)hazeEngine;

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
        LOGE("AndroidBitmap_lockPixels failed");
        return -3;
    }

    auto* bytes = static_cast<uint8_t*>(pixels);
    apply_adjustment_rgba8888(
        bytes,
        static_cast<int>(info.width),
        static_cast<int>(info.height),
        static_cast<int>(info.stride),
        exposure,
        contrast,
        shadows,
        highlights,
        whites,
        blacks,
        temperature,
        tint,
        saturation,
        vibrance,
        clarity,
        dehaze,
        noiseReduction
    );

    if (noiseEngine == 1) {
        apply_guided_noise_reduction_rgba8888(
            bytes,
            static_cast<int>(info.width),
            static_cast<int>(info.height),
            static_cast<int>(info.stride),
            noiseReduction
        );
    } else if (noiseEngine == 2) {
        apply_edge_aware_noise_reduction_rgba8888(
            bytes,
            static_cast<int>(info.width),
            static_cast<int>(info.height),
            static_cast<int>(info.stride),
            noiseReduction * 0.70f
        );
        apply_guided_noise_reduction_rgba8888(
            bytes,
            static_cast<int>(info.width),
            static_cast<int>(info.height),
            static_cast<int>(info.stride),
            noiseReduction * 0.85f
        );
    } else {
        apply_edge_aware_noise_reduction_rgba8888(
            bytes,
            static_cast<int>(info.width),
            static_cast<int>(info.height),
            static_cast<int>(info.stride),
            noiseReduction
        );
    }

    apply_sharpness_rgba8888(
        bytes,
        static_cast<int>(info.width),
        static_cast<int>(info.height),
        static_cast<int>(info.stride),
        sharpness,
        noiseReduction
    );

    AndroidBitmap_unlockPixels(env, bitmap);
    return revision;
}
