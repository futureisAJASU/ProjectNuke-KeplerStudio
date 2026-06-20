#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>

#define LOG_TAG "KeplerNativeCrop"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Bounds {
    float minX;
    float minY;
    float maxX;
    float maxY;
};

static inline float clampf(float v, float lo, float hi) {
    return std::max(lo, std::min(hi, v));
}

static inline uint8_t lerp_u8(uint8_t a, uint8_t b, float t) {
    return static_cast<uint8_t>(std::round(a + (b - a) * t));
}

static void transformed_point(
    float x,
    float y,
    float cx,
    float cy,
    float cosT,
    float sinT,
    bool flip,
    float& outX,
    float& outY
) {
    float dx = x - cx;
    float dy = y - cy;
    if (flip) dx = -dx;
    const float rx = cosT * dx - sinT * dy;
    const float ry = sinT * dx + cosT * dy;
    outX = rx + cx;
    outY = ry + cy;
}

static Bounds compute_bounds(int width, int height, float radians, bool flip) {
    const float cx = (width - 1) * 0.5f;
    const float cy = (height - 1) * 0.5f;
    const float cosT = std::cos(radians);
    const float sinT = std::sin(radians);
    const float corners[4][2] = {
        {0.0f, 0.0f},
        {static_cast<float>(width - 1), 0.0f},
        {0.0f, static_cast<float>(height - 1)},
        {static_cast<float>(width - 1), static_cast<float>(height - 1)}
    };
    Bounds b{1e9f, 1e9f, -1e9f, -1e9f};
    for (const auto& corner : corners) {
        float tx = 0.0f;
        float ty = 0.0f;
        transformed_point(corner[0], corner[1], cx, cy, cosT, sinT, flip, tx, ty);
        b.minX = std::min(b.minX, tx);
        b.minY = std::min(b.minY, ty);
        b.maxX = std::max(b.maxX, tx);
        b.maxY = std::max(b.maxY, ty);
    }
    return b;
}

static inline const uint8_t* src_px(const uint8_t* src, int stride, int x, int y) {
    return src + static_cast<size_t>(y) * stride + static_cast<size_t>(x) * 4U;
}

static void sample_bilinear(
    const uint8_t* src,
    int width,
    int height,
    int stride,
    float x,
    float y,
    uint8_t* out
) {
    x = clampf(x, 0.0f, static_cast<float>(width - 1));
    y = clampf(y, 0.0f, static_cast<float>(height - 1));
    const int x0 = static_cast<int>(std::floor(x));
    const int y0 = static_cast<int>(std::floor(y));
    const int x1 = std::min(width - 1, x0 + 1);
    const int y1 = std::min(height - 1, y0 + 1);
    const float tx = x - x0;
    const float ty = y - y0;
    const uint8_t* p00 = src_px(src, stride, x0, y0);
    const uint8_t* p10 = src_px(src, stride, x1, y0);
    const uint8_t* p01 = src_px(src, stride, x0, y1);
    const uint8_t* p11 = src_px(src, stride, x1, y1);
    for (int c = 0; c < 4; ++c) {
        const uint8_t a = lerp_u8(p00[c], p10[c], tx);
        const uint8_t b = lerp_u8(p01[c], p11[c], tx);
        out[c] = lerp_u8(a, b, ty);
    }
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeRenderCropTransformNative(
    JNIEnv* env,
    jobject /*thiz*/,
    jobject sourceBitmap,
    jobject destinationBitmap,
    jfloat cropLeft,
    jfloat cropTop,
    jfloat cropRight,
    jfloat cropBottom,
    jfloat rotationDegrees,
    jboolean flipHorizontal,
    jint revision
) {
    AndroidBitmapInfo srcInfo{};
    AndroidBitmapInfo dstInfo{};
    if (AndroidBitmap_getInfo(env, sourceBitmap, &srcInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        AndroidBitmap_getInfo(env, destinationBitmap, &dstInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed");
        return -1;
    }
    if (srcInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || dstInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Unsupported bitmap format");
        return -2;
    }
    void* srcPixels = nullptr;
    void* dstPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, sourceBitmap, &srcPixels) != ANDROID_BITMAP_RESULT_SUCCESS || srcPixels == nullptr) return -3;
    if (AndroidBitmap_lockPixels(env, destinationBitmap, &dstPixels) != ANDROID_BITMAP_RESULT_SUCCESS || dstPixels == nullptr) {
        AndroidBitmap_unlockPixels(env, sourceBitmap);
        return -4;
    }

    const int srcW = static_cast<int>(srcInfo.width);
    const int srcH = static_cast<int>(srcInfo.height);
    const int dstW = static_cast<int>(dstInfo.width);
    const int dstH = static_cast<int>(dstInfo.height);
    const float radians = rotationDegrees * 3.14159265358979323846f / 180.0f;
    const bool flip = flipHorizontal == JNI_TRUE;
    const Bounds bounds = compute_bounds(srcW, srcH, radians, flip);
    const float wholeW = std::max(1.0f, bounds.maxX - bounds.minX);
    const float wholeH = std::max(1.0f, bounds.maxY - bounds.minY);
    const float cl = clampf(std::min(cropLeft, cropRight), 0.0f, 1.0f);
    const float cr = clampf(std::max(cropLeft, cropRight), cl + 0.001f, 1.0f);
    const float ct = clampf(std::min(cropTop, cropBottom), 0.0f, 1.0f);
    const float cb = clampf(std::max(cropTop, cropBottom), ct + 0.001f, 1.0f);
    const float cropX = cl * wholeW;
    const float cropY = ct * wholeH;
    const float cropW = (cr - cl) * wholeW;
    const float cropH = (cb - ct) * wholeH;
    const float cx = (srcW - 1) * 0.5f;
    const float cy = (srcH - 1) * 0.5f;
    const float cosT = std::cos(radians);
    const float sinT = std::sin(radians);

    auto* src = static_cast<const uint8_t*>(srcPixels);
    auto* dst = static_cast<uint8_t*>(dstPixels);
    for (int y = 0; y < dstH; ++y) {
        auto* outRow = dst + static_cast<size_t>(y) * dstInfo.stride;
        const float ty = cropY + (static_cast<float>(y) + 0.5f) * cropH / std::max(1, dstH);
        for (int x = 0; x < dstW; ++x) {
            const float tx = cropX + (static_cast<float>(x) + 0.5f) * cropW / std::max(1, dstW);
            float dx = tx + bounds.minX - cx;
            float dy = ty + bounds.minY - cy;
            const float rx = cosT * dx + sinT * dy;
            const float ry = -sinT * dx + cosT * dy;
            float sx = flip ? -rx : rx;
            float sy = ry;
            sx += cx;
            sy += cy;
            sample_bilinear(src, srcW, srcH, static_cast<int>(srcInfo.stride), sx, sy, outRow + static_cast<size_t>(x) * 4U);
        }
    }

    AndroidBitmap_unlockPixels(env, destinationBitmap);
    AndroidBitmap_unlockPixels(env, sourceBitmap);
    return revision;
}
