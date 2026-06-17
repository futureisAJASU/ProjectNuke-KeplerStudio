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
    const float exposureMul = std::pow(2.0f, exposure); // -1..+1 EV
    const float contrastMul = 1.0f + contrast * 0.75f;
    const float shadowStrength = shadows * 0.45f;
    const float highlightStrength = highlights * 0.45f;
    const float whiteStrength = whites * 0.35f;
    const float blackStrength = blacks * 0.35f;
    const float clarityStrength = clarity * 0.45f;
    const float dehazeStrength = dehaze * 0.40f;
    const float noiseColorDamping = clamp01(noiseReduction) * 0.35f;

    // Simple WB approximation. Positive temperature warms; positive tint moves toward magenta.
    const float tempR = 1.0f + temperature * 0.10f;
    const float tempG = 1.0f;
    const float tempB = 1.0f - temperature * 0.10f;
    const float tintR = 1.0f + tint * 0.05f;
    const float tintG = 1.0f - tint * 0.08f;
    const float tintB = 1.0f + tint * 0.05f;

    for (int y = 0; y < height; ++y) {
        auto* row = base + y * stride;
        for (int x = 0; x < width; ++x) {
            auto* px = row + x * 4;

            float r = px[0] / 255.0f;
            float g = px[1] / 255.0f;
            float b = px[2] / 255.0f;
            const uint8_t a = px[3];

            // Exposure
            r *= exposureMul;
            g *= exposureMul;
            b *= exposureMul;

            // White balance / tint
            r *= tempR * tintR;
            g *= tempG * tintG;
            b *= tempB * tintB;

            // Contrast around 0.5
            r = (r - 0.5f) * contrastMul + 0.5f;
            g = (g - 0.5f) * contrastMul + 0.5f;
            b = (b - 0.5f) * contrastMul + 0.5f;

            float luma = luma_of(r, g, b);
            const float shadowMask = 1.0f - smoothstep(0.18f, 0.55f, luma);
            const float highlightMask = smoothstep(0.50f, 0.92f, luma);
            const float whiteMask = smoothstep(0.68f, 0.98f, luma);
            const float blackMask = 1.0f - smoothstep(0.03f, 0.35f, luma);

            // Shadows: positive lifts dark regions, negative crushes dark regions.
            r += shadowStrength * shadowMask * (shadowStrength >= 0.0f ? (1.0f - r) : r);
            g += shadowStrength * shadowMask * (shadowStrength >= 0.0f ? (1.0f - g) : g);
            b += shadowStrength * shadowMask * (shadowStrength >= 0.0f ? (1.0f - b) : b);

            // Highlights: positive recovers/compresses bright regions, negative pushes them brighter.
            r -= highlightStrength * highlightMask * (highlightStrength >= 0.0f ? r : (1.0f - r));
            g -= highlightStrength * highlightMask * (highlightStrength >= 0.0f ? g : (1.0f - g));
            b -= highlightStrength * highlightMask * (highlightStrength >= 0.0f ? b : (1.0f - b));

            // Whites and blacks are endpoint controls.
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

            // Clarity / dehaze first-pass approximation: midtone contrast and haze compression.
            const float midMask = 1.0f - clamp01(std::fabs(luma - 0.5f) * 2.0f);
            const float localContrast = 1.0f + clarityStrength * midMask + dehazeStrength * 0.35f;
            r = luma + (r - luma) * localContrast;
            g = luma + (g - luma) * localContrast;
            b = luma + (b - luma) * localContrast;

            if (std::fabs(dehazeStrength) > 0.0001f) {
                const float hazeCurve = (luma - 0.5f) * dehazeStrength;
                r += hazeCurve;
                g += hazeCurve;
                b += hazeCurve;
            }

            r = clamp01(r);
            g = clamp01(g);
            b = clamp01(b);
            luma = luma_of(r, g, b);

            // Saturation and vibrance. Vibrance protects already-saturated colors.
            const float maxC = std::max(r, std::max(g, b));
            const float minC = std::min(r, std::min(g, b));
            const float chroma = clamp01(maxC - minC);
            const float satMul = 1.0f + saturation * 0.85f;
            const float vibMul = 1.0f + vibrance * 0.85f * (1.0f - chroma);
            const float colorMul = clampf(satMul * vibMul, 0.0f, 2.5f);
            r = luma + (r - luma) * colorMul;
            g = luma + (g - luma) * colorMul;
            b = luma + (b - luma) * colorMul;

            // Very lightweight chroma noise reduction placeholder.
            if (noiseColorDamping > 0.0f) {
                const float nrLuma = luma_of(r, g, b);
                r = nrLuma + (r - nrLuma) * (1.0f - noiseColorDamping);
                g = nrLuma + (g - nrLuma) * (1.0f - noiseColorDamping);
                b = nrLuma + (b - nrLuma) * (1.0f - noiseColorDamping);
            }

            px[0] = to_u8(r);
            px[1] = to_u8(g);
            px[2] = to_u8(b);
            px[3] = a;
        }
    }
}

static void copy_row(uint8_t* dst, const uint8_t* src, int stride) {
    std::copy(src, src + stride, dst);
}

static float channel_at(const std::vector<uint8_t>& row, int x, int channel) {
    return row[static_cast<size_t>(x) * 4U + static_cast<size_t>(channel)] / 255.0f;
}

static void apply_sharpness_rgba8888(
    uint8_t* base,
    int width,
    int height,
    int stride,
    float sharpness
) {
    const float amount = clamp01(sharpness) * 0.90f;
    if (amount <= 0.001f || width < 3 || height < 3) return;

    std::vector<uint8_t> prev(static_cast<size_t>(stride));
    std::vector<uint8_t> curr(static_cast<size_t>(stride));
    std::vector<uint8_t> next(static_cast<size_t>(stride));

    copy_row(curr.data(), base, stride);
    copy_row(next.data(), base + stride, stride);
    copy_row(prev.data(), curr.data(), stride);

    for (int y = 0; y < height; ++y) {
        auto* outRow = base + y * stride;
        const bool hasNext = y + 1 < height;
        if (!hasNext) copy_row(next.data(), curr.data(), stride);

        for (int x = 0; x < width; ++x) {
            const int lx = std::max(0, x - 1);
            const int rx = std::min(width - 1, x + 1);
            auto* outPx = outRow + x * 4;
            const uint8_t alpha = curr[static_cast<size_t>(x) * 4U + 3U];

            for (int c = 0; c < 3; ++c) {
                const float center = channel_at(curr, x, c);
                const float blur = (
                    center * 4.0f +
                    channel_at(curr, lx, c) +
                    channel_at(curr, rx, c) +
                    channel_at(prev, x, c) +
                    channel_at(next, x, c)
                ) / 8.0f;
                const float sharpened = center + (center - blur) * amount;
                outPx[c] = to_u8(sharpened);
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
    return env->NewStringUTF("PhotoCore C++ v0.2");
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
    apply_sharpness_rgba8888(
        bytes,
        static_cast<int>(info.width),
        static_cast<int>(info.height),
        static_cast<int>(info.stride),
        sharpness
    );

    AndroidBitmap_unlockPixels(env, bitmap);
    return revision;
}
