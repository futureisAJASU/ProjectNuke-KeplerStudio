#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <algorithm>
#include <exception>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <limits>
#include <new>
#include <string>
#include <vector>

#define LOG_TAG "KeplerPhotoCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    std::string sourcePath;
};

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

constexpr size_t kMaxTemporaryBytes = 256ull * 1024ull * 1024ull;

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

static bool validateRgbaBitmapLayout(const AndroidBitmapInfo& info) {
    if (info.width == 0 || info.height == 0 || info.stride == 0) return false;
    if (info.width > static_cast<uint32_t>(std::numeric_limits<int>::max())) return false;
    if (info.height > static_cast<uint32_t>(std::numeric_limits<int>::max())) return false;
    if (info.stride > static_cast<uint32_t>(std::numeric_limits<int>::max())) return false;
    if (static_cast<size_t>(info.width) > std::numeric_limits<size_t>::max() / 4ULL) return false;
    const size_t minStride = static_cast<size_t>(info.width) * 4ULL;
    return static_cast<size_t>(info.stride) >= minStride;
}

static bool checkedBitmapByteCount(const AndroidBitmapInfo& info, size_t& out) {
    if (!validateRgbaBitmapLayout(info)) return false;
    const size_t stride = static_cast<size_t>(info.stride);
    const size_t height = static_cast<size_t>(info.height);
    if (height != 0 && stride > std::numeric_limits<size_t>::max() / height) return false;
    out = stride * height;
    return true;
}

static bool hasRowBufferBudget(const AndroidBitmapInfo& info, size_t rowBuffers) {
    if (!validateRgbaBitmapLayout(info) || rowBuffers == 0) return false;
    const size_t stride = static_cast<size_t>(info.stride);
    return stride <= kMaxTemporaryBytes / rowBuffers;
}

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

static inline float local_luma_detail(
    const std::vector<uint8_t>& prev,
    const std::vector<uint8_t>& curr,
    const std::vector<uint8_t>& next,
    int width,
    int x,
    float centerL
) {
    const int lx = std::max(0, x - 1);
    const int rx = std::min(width - 1, x + 1);
    const float horiz = std::fabs(luma_at(curr, rx) - luma_at(curr, lx));
    const float vert = std::fabs(luma_at(next, x) - luma_at(prev, x));
    const float crossMean = (
        luma_at(curr, lx) +
        luma_at(curr, rx) +
        luma_at(prev, x) +
        luma_at(next, x)
    ) * 0.25f;
    const float lap = std::fabs(centerL - crossMean);
    return std::max(lap, (horiz + vert) * 0.50f);
}

static inline float shadow_mask_for_luma(float centerL) {
    return 1.0f - smoothstep(0.12f, 0.55f, centerL);
}

static inline float detail_mask_for_detail(float detail) {
    return smoothstep(0.008f, 0.065f, detail);
}

static inline float flat_mask_for_detail(float detail) {
    return 1.0f - detail_mask_for_detail(detail);
}

static inline float detail_guard_for_detail(float detail, float detailProtection) {
    return 1.0f - clamp01(detailProtection) * detail_mask_for_detail(detail);
}

static inline float chroma_cb_at(const std::vector<uint8_t>& row, int x) {
    const float r = channel_at(row, x, 0);
    const float g = channel_at(row, x, 1);
    const float b = channel_at(row, x, 2);
    return b - luma_of(r, g, b);
}

static inline float chroma_cr_at(const std::vector<uint8_t>& row, int x) {
    const float r = channel_at(row, x, 0);
    const float g = channel_at(row, x, 1);
    const float b = channel_at(row, x, 2);
    return r - luma_of(r, g, b);
}

// Sparse 5x5 chroma cleanup targets color blotches without broad desaturation.
static void sparse_chroma_estimate(
    const std::vector<uint8_t>& prev2,
    const std::vector<uint8_t>& prev,
    const std::vector<uint8_t>& curr,
    const std::vector<uint8_t>& next,
    const std::vector<uint8_t>& next2,
    int width,
    int x,
    float centerL,
    float sigma2,
    float& outCb,
    float& outCr
) {
    const std::vector<uint8_t>* rows[5] = { &prev2, &prev, &curr, &next, &next2 };
    float sumCb = 0.0f;
    float sumCr = 0.0f;
    float sumW = 0.0f;

    for (int dy = -2; dy <= 2; ++dy) {
        const auto& row = *rows[dy + 2];
        for (int dx = -2; dx <= 2; ++dx) {
            const int adx = std::abs(dx);
            const int ady = std::abs(dy);
            const bool useSample = (adx <= 1 && ady <= 1) ||
                (dy == 0 && adx == 2) ||
                (dx == 0 && ady == 2) ||
                (adx == 2 && ady == 2);
            if (!useSample) continue;

            const int nx = std::min(width - 1, std::max(0, x + dx));
            const float nl = luma_at(row, nx);
            const float range = std::exp(-((nl - centerL) * (nl - centerL)) / sigma2);
            const float distance = static_cast<float>(adx + ady);
            const float spatial = (dx == 0 && dy == 0) ? 0.12f : (1.0f / (1.0f + distance));
            const float w = spatial * range;
            sumCb += chroma_cb_at(row, nx) * w;
            sumCr += chroma_cr_at(row, nx) * w;
            sumW += w;
        }
    }

    const float invW = sumW > 0.0001f ? (1.0f / sumW) : 1.0f;
    outCb = sumCb * invW;
    outCr = sumCr * invW;
}

static void local_neighbor_estimate_adaptive_5x5(
    const std::vector<uint8_t>& prev2,
    const std::vector<uint8_t>& prev,
    const std::vector<uint8_t>& curr,
    const std::vector<uint8_t>& next,
    const std::vector<uint8_t>& next2,
    int width,
    int x,
    float centerL,
    float& outL,
    float& outCb,
    float& outCr,
    float& outRange
) {
    const std::vector<uint8_t>* rows[5] = { &prev2, &prev, &curr, &next, &next2 };
    float sumL = 0.0f;
    float sumCb = 0.0f;
    float sumCr = 0.0f;
    float sumW = 0.0f;
    float rangeSumL = 0.0f;
    float rangeSumL2 = 0.0f;
    float rangeSumW = 0.0f;
    float minL = 1.0f;
    float maxL = 0.0f;

    for (int dy = -2; dy <= 2; ++dy) {
        const auto& row = *rows[dy + 2];
        for (int dx = -2; dx <= 2; ++dx) {
            const int nx = std::min(width - 1, std::max(0, x + dx));
            const float nl = luma_at(row, nx);
            const float diff = nl - centerL;
            const float range = std::exp(-(diff * diff) / 0.0180f);
            const float distance = static_cast<float>(std::abs(dx) + std::abs(dy));
            const float spatial = (dx == 0 && dy == 0) ? 0.035f : (1.0f / (1.0f + distance));
            const float w = spatial * range;
            sumL += nl * w;
            sumCb += chroma_cb_at(row, nx) * w;
            sumCr += chroma_cr_at(row, nx) * w;
            sumW += w;
            if (dx != 0 || dy != 0) {
                rangeSumL += nl * w;
                rangeSumL2 += nl * nl * w;
                rangeSumW += w;
                minL = std::min(minL, nl);
                maxL = std::max(maxL, nl);
            }
        }
    }

    const float invW = sumW > 0.0001f ? (1.0f / sumW) : 1.0f;
    outL = sumL * invW;
    outCb = sumCb * invW;
    outCr = sumCr * invW;
    if (rangeSumW > 0.0001f) {
        const float invRangeW = 1.0f / rangeSumW;
        const float rangeMean = rangeSumL * invRangeW;
        const float variance = std::max(0.0f, rangeSumL2 * invRangeW - rangeMean * rangeMean);
        outRange = std::max((maxL - minL) * 0.65f, std::sqrt(variance) * 2.35f);
    } else {
        outRange = 1.0f;
    }
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
    float /*colorNoiseReduction*/
) {
    const float exposureMul = std::pow(2.0f, exposure);
    const float contrastMul = 1.0f + contrast * 0.72f;
    const float shadowStrength = shadows * 0.42f;
    const float highlightStrength = highlights * 0.42f;
    const float whiteStrength = whites * 0.34f;
    const float blackStrength = blacks * 0.34f;
    const float clarityStrength = clarity * 0.42f;
    const float dehazeStrength = dehaze * 0.36f;

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
    float luminanceNoiseReduction,
    float colorNoiseReduction,
    float noiseDetailProtection
) {
    const float lumaStrength = clamp01(luminanceNoiseReduction);
    const float chromaStrength = clamp01(colorNoiseReduction);
    const float detailProtection = clamp01(noiseDetailProtection);
    if ((lumaStrength <= 0.001f && chromaStrength <= 0.001f) || width < 3 || height < 3) return;

    std::vector<uint8_t> prev2(static_cast<size_t>(stride));
    std::vector<uint8_t> prev(static_cast<size_t>(stride));
    std::vector<uint8_t> curr(static_cast<size_t>(stride));
    std::vector<uint8_t> next(static_cast<size_t>(stride));
    std::vector<uint8_t> next2(static_cast<size_t>(stride));

    copy_row(curr.data(), base, stride);
    copy_row(next.data(), base + stride, stride);
    copy_row(next2.data(), (height > 2) ? (base + stride * 2) : (base + stride), stride);
    copy_row(prev.data(), curr.data(), stride);
    copy_row(prev2.data(), curr.data(), stride);

    const float strength = std::max(lumaStrength, chromaStrength);
    const float sigma = 0.030f + (1.0f - strength) * 0.150f;
    const float sigma2 = std::max(0.0001f, sigma * sigma * 2.0f);

    for (int y = 0; y < height; ++y) {
        auto* outRow = base + y * stride;
        if (y + 1 >= height) copy_row(next.data(), curr.data(), stride);
        if (y + 2 >= height) copy_row(next2.data(), next.data(), stride);

        for (int x = 0; x < width; ++x) {
            auto* outPx = outRow + x * 4;
            const float centerR = channel_at(curr, x, 0);
            const float centerG = channel_at(curr, x, 1);
            const float centerB = channel_at(curr, x, 2);
            const float centerL = luma_of(centerR, centerG, centerB);
            const float centerCb = centerB - centerL;
            const float centerCr = centerR - centerL;
            const uint8_t alpha = curr[static_cast<size_t>(x) * 4U + 3U];

            float sumL = 0.0f;
            float sumCb = 0.0f;
            float sumCr = 0.0f;
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
                    sumL += nl * w;
                    sumCb += (nb - nl) * w;
                    sumCr += (nr - nl) * w;
                    sumW += w;
                }
            }

            const float invW = sumW > 0.0001f ? (1.0f / sumW) : 1.0f;
            const float avgL = sumL * invW;
            const float avgCb = sumCb * invW;
            const float avgCr = sumCr * invW;
            const float detail = local_luma_detail(prev, curr, next, width, x, centerL);
            const float shadowMask = shadow_mask_for_luma(centerL);
            const float flatMask = flat_mask_for_detail(detail);
            const float detailGuard = detail_guard_for_detail(detail, detailProtection);
            const float lumaMix = clamp01(lumaStrength * (0.56f + 0.30f * shadowMask + 0.28f * flatMask) * detailGuard);
            const float chromaMix = clamp01(chromaStrength * (0.68f + 0.34f * shadowMask + 0.24f * flatMask) * (0.65f + 0.35f * detailGuard));
            float outL = lerp(centerL, avgL, lumaMix);
            float outCb = lerp(centerCb, avgCb, chromaMix);
            float outCr = lerp(centerCr, avgCr, chromaMix);

            if (chromaStrength > 0.010f) {
                float blotchCb = centerCb;
                float blotchCr = centerCr;
                sparse_chroma_estimate(prev2, prev, curr, next, next2, width, x, centerL, sigma2, blotchCb, blotchCr);
                const float blotchMix = clamp01(chromaStrength * (0.20f + 0.34f * shadowMask + 0.28f * flatMask) * (0.55f + 0.45f * detailGuard));
                outCb = lerp(outCb, blotchCb, blotchMix);
                outCr = lerp(outCr, blotchCr, blotchMix);
            }

            const float cleanupStrength = clamp01(std::max(lumaStrength * 0.42f, chromaStrength * 0.58f));
            if (cleanupStrength > 0.010f) {
                float neighL = centerL;
                float neighCb = centerCb;
                float neighCr = centerCr;
                float neighRange = 1.0f;
                // 5x5 cleanup targets isolated speckles while range weighting protects edges.
                local_neighbor_estimate_adaptive_5x5(prev2, prev, curr, next, next2, width, x, centerL, neighL, neighCb, neighCr, neighRange);
                const float lumaOutlier = std::fabs(centerL - neighL);
                const float chromaOutlier = std::max(std::fabs(centerCb - neighCb), std::fabs(centerCr - neighCr));
                const float isolatedMask = (1.0f - smoothstep(0.055f, 0.145f, neighRange)) * (0.35f + 0.65f * flatMask);
                const float chromaSpeck = smoothstep(0.070f, 0.180f, chromaOutlier);
                const float lumaSpeck = smoothstep(0.160f, 0.310f, lumaOutlier) * (0.35f + 0.65f * chromaSpeck);
                const float highlightGuard = (centerL > neighL && centerL > 0.78f && chromaOutlier < 0.060f) ? 0.25f : 1.0f;
                const float speckMix = clamp01(cleanupStrength * isolatedMask * std::max(chromaSpeck, lumaSpeck) * highlightGuard);
                outL = lerp(outL, neighL, speckMix * 0.45f * lumaStrength);
                outCb = lerp(outCb, neighCb, speckMix * (0.62f + 0.28f * chromaStrength));
                outCr = lerp(outCr, neighCr, speckMix * (0.62f + 0.28f * chromaStrength));
            }

            const float outB = outL + outCb;
            const float outR = outL + outCr;
            const float outG = (outL - 0.2126f * outR - 0.0722f * outB) / 0.7152f;

            outPx[0] = to_u8(outR);
            outPx[1] = to_u8(outG);
            outPx[2] = to_u8(outB);
            outPx[3] = alpha;
        }

        if (y + 1 < height) {
            prev2.swap(prev);
            prev.swap(curr);
            curr.swap(next);
            next.swap(next2);
            if (y + 2 < height) {
                copy_row(next2.data(), base + (y + 3 < height ? y + 3 : height - 1) * stride, stride);
            } else {
                copy_row(next2.data(), next.data(), stride);
            }
        }
    }
}

static float guided_component_at(const std::vector<uint8_t>& row, int x, int component) {
    const float r = channel_at(row, x, 0);
    const float g = channel_at(row, x, 1);
    const float b = channel_at(row, x, 2);
    const float y = luma_of(r, g, b);
    if (component == 0) return y;
    if (component == 1) return b - y;
    return r - y;
}

static float guided_estimate_component(
    const std::vector<uint8_t>& prev,
    const std::vector<uint8_t>& curr,
    const std::vector<uint8_t>& next,
    int width,
    int x,
    int component,
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
            const float p = guided_component_at(*row, nx, component);
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
    return a * luma_at(curr, x) + b;
}

static void apply_guided_noise_reduction_rgba8888(
    uint8_t* base,
    int width,
    int height,
    int stride,
    float luminanceNoiseReduction,
    float colorNoiseReduction,
    float noiseDetailProtection
) {
    const float lumaStrength = clamp01(luminanceNoiseReduction);
    const float chromaStrength = clamp01(colorNoiseReduction);
    const float detailProtection = clamp01(noiseDetailProtection);
    if ((lumaStrength <= 0.001f && chromaStrength <= 0.001f) || width < 3 || height < 3) return;

    std::vector<uint8_t> prev2(static_cast<size_t>(stride));
    std::vector<uint8_t> prev(static_cast<size_t>(stride));
    std::vector<uint8_t> curr(static_cast<size_t>(stride));
    std::vector<uint8_t> next(static_cast<size_t>(stride));
    std::vector<uint8_t> next2(static_cast<size_t>(stride));

    copy_row(curr.data(), base, stride);
    copy_row(next.data(), base + stride, stride);
    copy_row(next2.data(), (height > 2) ? (base + stride * 2) : (base + stride), stride);
    copy_row(prev.data(), curr.data(), stride);
    copy_row(prev2.data(), curr.data(), stride);

    const float maxStrength = std::max(lumaStrength, chromaStrength);
    const float lumaEps = 0.0005f + (1.0f - lumaStrength) * 0.0045f;
    const float chromaEps = 0.0012f + (1.0f - maxStrength) * 0.0075f;

    for (int y = 0; y < height; ++y) {
        auto* outRow = base + y * stride;
        if (y + 1 >= height) copy_row(next.data(), curr.data(), stride);
        if (y + 2 >= height) copy_row(next2.data(), next.data(), stride);

        for (int x = 0; x < width; ++x) {
            auto* outPx = outRow + x * 4;
            const float centerR = channel_at(curr, x, 0);
            const float centerG = channel_at(curr, x, 1);
            const float centerB = channel_at(curr, x, 2);
            const float centerL = luma_of(centerR, centerG, centerB);
            const float centerCb = centerB - centerL;
            const float centerCr = centerR - centerL;
            const float detail = local_luma_detail(prev, curr, next, width, x, centerL);
            const float shadowMask = shadow_mask_for_luma(centerL);
            const float flatMask = flat_mask_for_detail(detail);
            const float detailGuard = detail_guard_for_detail(detail, detailProtection);
            const float guidedL = clamp01(guided_estimate_component(prev, curr, next, width, x, 0, lumaEps));
            const float guidedCb = guided_estimate_component(prev, curr, next, width, x, 1, chromaEps);
            const float guidedCr = guided_estimate_component(prev, curr, next, width, x, 2, chromaEps);
            const float lumaMix = clamp01(lumaStrength * (0.46f + 0.22f * shadowMask + 0.28f * flatMask) * (0.88f + 0.12f * detailGuard));
            const float chromaMix = clamp01(chromaStrength * (0.76f + 0.48f * shadowMask + 0.36f * flatMask) * (0.54f + 0.46f * detailGuard));
            float outL = lerp(centerL, guidedL, lumaMix);
            float outCb = lerp(centerCb, guidedCb, chromaMix);
            float outCr = lerp(centerCr, guidedCr, chromaMix);

            if (chromaStrength > 0.010f) {
                float blotchCb = centerCb;
                float blotchCr = centerCr;
                sparse_chroma_estimate(prev2, prev, curr, next, next2, width, x, centerL, chromaEps * 18.0f, blotchCb, blotchCr);
                const float blotchMix = clamp01(chromaStrength * (0.24f + 0.46f * shadowMask + 0.42f * flatMask) * (0.46f + 0.54f * detailGuard));
                outCb = lerp(outCb, blotchCb, blotchMix);
                outCr = lerp(outCr, blotchCr, blotchMix);
            }

            const float cleanupStrength = clamp01(std::max(lumaStrength * 0.34f, chromaStrength * 0.62f));
            if (cleanupStrength > 0.010f) {
                float neighL = centerL;
                float neighCb = centerCb;
                float neighCr = centerCr;
                float neighRange = 1.0f;
                // 5x5 cleanup targets isolated speckles while range weighting protects edges.
                local_neighbor_estimate_adaptive_5x5(prev2, prev, curr, next, next2, width, x, centerL, neighL, neighCb, neighCr, neighRange);
                const float lumaOutlier = std::fabs(centerL - neighL);
                const float chromaOutlier = std::max(std::fabs(centerCb - neighCb), std::fabs(centerCr - neighCr));
                const float isolatedMask = (1.0f - smoothstep(0.045f, 0.120f, neighRange)) * (0.36f + 0.64f * flatMask) * (0.72f + 0.28f * shadowMask);
                const float chromaSpeck = smoothstep(0.055f, 0.150f, chromaOutlier);
                const float lumaSpeck = smoothstep(0.190f, 0.345f, lumaOutlier) * (0.24f + 0.76f * chromaSpeck);
                const float highlightGuard = (centerL > neighL && centerL > 0.78f && chromaOutlier < 0.060f) ? 0.22f : 1.0f;
                const float speckMix = clamp01(cleanupStrength * isolatedMask * std::max(chromaSpeck, lumaSpeck) * highlightGuard);
                outL = lerp(outL, neighL, speckMix * 0.30f * lumaStrength);
                outCb = lerp(outCb, neighCb, speckMix * (0.68f + 0.24f * chromaStrength));
                outCr = lerp(outCr, neighCr, speckMix * (0.68f + 0.24f * chromaStrength));
            }

            const float outB = outL + outCb;
            const float outR = outL + outCr;
            const float outG = (outL - 0.2126f * outR - 0.0722f * outB) / 0.7152f;
            const uint8_t alpha = curr[static_cast<size_t>(x) * 4U + 3U];

            outPx[0] = to_u8(outR);
            outPx[1] = to_u8(outG);
            outPx[2] = to_u8(outB);
            outPx[3] = alpha;
        }

        if (y + 1 < height) {
            prev2.swap(prev);
            prev.swap(curr);
            curr.swap(next);
            next.swap(next2);
            if (y + 2 < height) {
                copy_row(next2.data(), base + (y + 3 < height ? y + 3 : height - 1) * stride, stride);
            } else {
                copy_row(next2.data(), next.data(), stride);
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
            const float textureMask = smoothstep(0.012f + noiseReduction * 0.028f, 0.075f + noiseReduction * 0.032f, detail);
            const float shadowMask = smoothstep(0.080f, 0.34f, centerL);
            const float flatShadowGuard = 1.0f - (1.0f - shadowMask) * (1.0f - textureMask) * (0.55f + 0.35f * clamp01(noiseReduction));
            const float highlightGuard = 1.0f - 0.50f * smoothstep(0.90f, 1.0f, centerL);
            const float amount = baseAmount * textureMask * flatShadowGuard * highlightGuard;

            for (int c = 0; c < 3; ++c) {
                const float center = channel_at(curr, x, c);
                const float blur = (
                    center * 4.0f +
                    channel_at(curr, lx, c) +
                    channel_at(curr, rx, c) +
                    channel_at(prev, x, c) +
                    channel_at(next, x, c)
                ) / 8.0f;
                const float delta = clampf((center - blur) * amount, -0.080f, 0.080f);
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
    jfloat luminanceNoiseReduction,
    jfloat colorNoiseReduction,
    jfloat noiseDetailProtection,
    jint noiseEngine,
    jint detailEngine,
    jint toneEngine,
    jint hazeEngine,
    jint revision
) {
    return runNativeGuarded("nativeRenderPreviewInPlace", [&]() -> jint {
        (void)detailEngine;
        (void)toneEngine;
        (void)hazeEngine;
        (void)noiseReduction;

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
            LOGE("Bitmap byte count overflow");
            return -11;
        }
        (void)bitmapByteCount;
        if (!hasRowBufferBudget(info, 5)) {
            LOGE("Temporary row buffers too large");
            return -12;
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
        const auto totalStart = std::chrono::steady_clock::now();

        const auto toneStart = std::chrono::steady_clock::now();
        apply_adjustment_rgba8888(
            bytes,
            width,
            height,
            stride,
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
            colorNoiseReduction
        );
        const auto toneEnd = std::chrono::steady_clock::now();

        const auto denoiseStart = std::chrono::steady_clock::now();
        if (noiseEngine == 1) {
            apply_guided_noise_reduction_rgba8888(
                bytes,
                width,
                height,
                stride,
                luminanceNoiseReduction,
                colorNoiseReduction,
                noiseDetailProtection
            );
        } else if (noiseEngine == 2) {
            // Kotlin-side NonLocalMeansLite maps to a lightweight approximation here:
            // edge-aware/guided refinement plus 5x5 speckle and chroma cleanup, not a full NLM search.
            apply_edge_aware_noise_reduction_rgba8888(
                bytes,
                width,
                height,
                stride,
                luminanceNoiseReduction * 0.70f,
                colorNoiseReduction * 0.80f,
                noiseDetailProtection
            );
            apply_guided_noise_reduction_rgba8888(
                bytes,
                width,
                height,
                stride,
                luminanceNoiseReduction * 0.75f,
                colorNoiseReduction * 0.90f,
                noiseDetailProtection
            );
        } else {
            apply_edge_aware_noise_reduction_rgba8888(
                bytes,
                width,
                height,
                stride,
                luminanceNoiseReduction,
                colorNoiseReduction,
                noiseDetailProtection
            );
        }
        const auto denoiseEnd = std::chrono::steady_clock::now();

        const auto sharpenStart = std::chrono::steady_clock::now();
        apply_sharpness_rgba8888(
            bytes,
            width,
            height,
            stride,
            sharpness,
            luminanceNoiseReduction
        );
        const auto sharpenEnd = std::chrono::steady_clock::now();
        const auto totalEnd = std::chrono::steady_clock::now();

        LOGD(
            "render %dx%d engine=%d denoise=%.2fms tone=%.2fms sharpen=%.2fms total=%.2fms",
            width,
            height,
            noiseEngine,
            std::chrono::duration<double, std::milli>(denoiseEnd - denoiseStart).count(),
            std::chrono::duration<double, std::milli>(toneEnd - toneStart).count(),
            std::chrono::duration<double, std::milli>(sharpenEnd - sharpenStart).count(),
            std::chrono::duration<double, std::milli>(totalEnd - totalStart).count()
        );

        return revision;
    });
}
