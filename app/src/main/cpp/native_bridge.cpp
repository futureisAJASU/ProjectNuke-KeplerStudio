#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <cmath>
#include <cstdint>
#include <string>

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

static inline uint8_t to_u8(float v) {
    v = clamp01(v);
    return static_cast<uint8_t>(std::round(v * 255.0f));
}

static inline float smoothstep(float edge0, float edge1, float x) {
    float t = clamp01((x - edge0) / (edge1 - edge0));
    return t * t * (3.0f - 2.0f * t);
}

static void apply_basic_adjustment_rgba8888(
    uint8_t* base,
    int width,
    int height,
    int stride,
    float exposure,
    float contrast,
    float shadows,
    float highlights
) {
    const float exposureMul = std::pow(2.0f, exposure); // -1..+1 EV
    const float contrastMul = 1.0f + contrast * 0.75f;
    const float shadowStrength = shadows * 0.45f;
    const float highlightStrength = highlights * 0.45f;

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

            // Contrast around 0.5
            r = (r - 0.5f) * contrastMul + 0.5f;
            g = (g - 0.5f) * contrastMul + 0.5f;
            b = (b - 0.5f) * contrastMul + 0.5f;

            const float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            const float shadowMask = 1.0f - smoothstep(0.18f, 0.55f, luma);
            const float highlightMask = smoothstep(0.50f, 0.92f, luma);

            // Shadows: positive lifts dark regions, negative crushes dark regions.
            r += shadowStrength * shadowMask * (1.0f - r);
            g += shadowStrength * shadowMask * (1.0f - g);
            b += shadowStrength * shadowMask * (1.0f - b);

            // Highlights: positive recovers/compresses bright regions, negative pushes them brighter.
            r -= highlightStrength * highlightMask * r;
            g -= highlightStrength * highlightMask * g;
            b -= highlightStrength * highlightMask * b;

            px[0] = to_u8(r);
            px[1] = to_u8(g);
            px[2] = to_u8(b);
            px[3] = a;
        }
    }
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeVersion(JNIEnv* env, jobject /*thiz*/) {
    return env->NewStringUTF("PhotoCore C++ v0.1");
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
    jfloat /*sharpness*/,
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

    apply_basic_adjustment_rgba8888(
        static_cast<uint8_t*>(pixels),
        static_cast<int>(info.width),
        static_cast<int>(info.height),
        static_cast<int>(info.stride),
        exposure,
        contrast,
        shadows,
        highlights
    );

    AndroidBitmap_unlockPixels(env, bitmap);
    return revision;
}
