#include <android/bitmap.h>
#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <utility>
#include <vector>

#include "native_cancellation.h"
#include "native_common.h"
#include "native_corrections_v2.h"

namespace {

constexpr std::size_t kMaximumScratchBytes = 256ULL * 1024ULL * 1024ULL;

struct CorrectionParams {
    float detail;
    float luminanceNoise;
    float chromaNoise;
    float highlightProtection;
    float shadowProtection;
    float chromaticAberration;
    float vignette;
    float spotCleanup;
};

float clamp01(float value) noexcept {
    return std::clamp(value, 0.0F, 1.0F);
}

std::uint8_t toByte(float value) noexcept {
    return static_cast<std::uint8_t>(
        std::lround(std::clamp(value, 0.0F, 255.0F)));
}

float luma(const std::uint8_t* pixel) noexcept {
    return 0.2126F * pixel[0] + 0.7152F * pixel[1] + 0.0722F * pixel[2];
}

float smoothstep(float low, float high, float value) noexcept {
    if (high <= low) return value >= high ? 1.0F : 0.0F;
    const float t = clamp01((value - low) / (high - low));
    return t * t * (3.0F - 2.0F * t);
}

const std::uint8_t* pixelAt(
    const std::uint8_t* base,
    int stride,
    int width,
    int height,
    int x,
    int y) noexcept {
    const int clampedX = std::clamp(x, 0, width - 1);
    const int clampedY = std::clamp(y, 0, height - 1);
    return base +
        static_cast<std::size_t>(clampedY) * static_cast<std::size_t>(stride) +
        static_cast<std::size_t>(clampedX) * 4U;
}

bool copyStage(
    const std::uint8_t* input,
    std::uint8_t* output,
    std::size_t byteCount,
    const kepler_native::CancellationLease& cancellation) {
    if (cancellation.cancelled()) return false;
    std::memcpy(output, input, byteCount);
    return !cancellation.cancelled();
}

bool reduceChromaNoise(
    const std::uint8_t* input,
    std::uint8_t* output,
    int width,
    int height,
    int stride,
    float luminanceStrength,
    float chromaStrength,
    const kepler_native::CancellationLease& cancellation) {
    if (luminanceStrength <= 0.001F && chromaStrength <= 0.001F) {
        return copyStage(
            input,
            output,
            static_cast<std::size_t>(stride) * static_cast<std::size_t>(height),
            cancellation);
    }
    for (int y = 0; y < height; ++y) {
        if ((y & 15) == 0 && cancellation.cancelled()) return false;
        const auto* inputRow =
            input + static_cast<std::size_t>(y) * static_cast<std::size_t>(stride);
        auto* outputRow =
            output + static_cast<std::size_t>(y) * static_cast<std::size_t>(stride);
        for (int x = 0; x < width; ++x) {
            const auto* center = inputRow + static_cast<std::size_t>(x) * 4U;
            float meanR = 0.0F;
            float meanB = 0.0F;
            float meanY = 0.0F;
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    const auto* sample = pixelAt(input, stride, width, height, x + dx, y + dy);
                    meanR += sample[0];
                    meanB += sample[2];
                    meanY += luma(sample);
                }
            }
            meanR /= 9.0F;
            meanB /= 9.0F;
            meanY /= 9.0F;
            const float centerY = luma(center);
            const float edgeGuard =
                1.0F - smoothstep(5.0F, 30.0F, std::fabs(centerY - meanY));
            const float lumaMix = clamp01(luminanceStrength) * edgeGuard * 0.42F;
            const float chromaMix = clamp01(chromaStrength) * edgeGuard * 0.72F;
            const float denoisedY = centerY + (meanY - centerY) * lumaMix;
            const float centerCr = center[0] - centerY;
            const float centerCb = center[2] - centerY;
            const float meanCr = meanR - meanY;
            const float meanCb = meanB - meanY;
            const float correctedCr = centerCr + (meanCr - centerCr) * chromaMix;
            const float correctedCb = centerCb + (meanCb - centerCb) * chromaMix;
            const float correctedG =
                (denoisedY - 0.2126F * (denoisedY + correctedCr) -
                    0.0722F * (denoisedY + correctedCb)) /
                0.7152F;
            auto* out = outputRow + static_cast<std::size_t>(x) * 4U;
            out[0] = toByte(denoisedY + correctedCr);
            out[1] = toByte(correctedG);
            out[2] = toByte(denoisedY + correctedCb);
            out[3] = center[3];
        }
        if (stride > width * 4) {
            std::memcpy(
                outputRow + static_cast<std::size_t>(width) * 4U,
                inputRow + static_cast<std::size_t>(width) * 4U,
                static_cast<std::size_t>(stride - width * 4));
        }
    }
    return !cancellation.cancelled();
}

bool enhanceDetail(
    const std::uint8_t* input,
    std::uint8_t* output,
    int width,
    int height,
    int stride,
    const CorrectionParams& params,
    const kepler_native::CancellationLease& cancellation) {
    if (params.detail <= 0.001F) {
        return copyStage(
            input,
            output,
            static_cast<std::size_t>(stride) * static_cast<std::size_t>(height),
            cancellation);
    }
    for (int y = 0; y < height; ++y) {
        if ((y & 15) == 0 && cancellation.cancelled()) return false;
        const auto* inputRow =
            input + static_cast<std::size_t>(y) * static_cast<std::size_t>(stride);
        auto* outputRow =
            output + static_cast<std::size_t>(y) * static_cast<std::size_t>(stride);
        for (int x = 0; x < width; ++x) {
            const auto* center = inputRow + static_cast<std::size_t>(x) * 4U;
            float localY = 0.0F;
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    localY += luma(pixelAt(input, stride, width, height, x + dx, y + dy));
                }
            }
            localY /= 9.0F;
            const float centerY = luma(center);
            const float detail = centerY - localY;
            const float noiseFloor = 1.5F + 5.0F * clamp01(params.luminanceNoise);
            const float noiseGuard = smoothstep(noiseFloor, noiseFloor + 8.0F, std::fabs(detail));
            const float highlightGuard =
                1.0F - smoothstep(
                    205.0F - 35.0F * clamp01(params.highlightProtection),
                    252.0F,
                    centerY);
            const float shadowGuard =
                smoothstep(
                    4.0F,
                    28.0F + 35.0F * clamp01(params.shadowProtection),
                    centerY);
            const float haloLimit = std::clamp(detail, -10.0F, 10.0F);
            const float delta =
                haloLimit * clamp01(params.detail) * noiseGuard * highlightGuard *
                shadowGuard * 0.9F;
            auto* out = outputRow + static_cast<std::size_t>(x) * 4U;
            out[0] = toByte(center[0] + delta);
            out[1] = toByte(center[1] + delta);
            out[2] = toByte(center[2] + delta);
            out[3] = center[3];
        }
        if (stride > width * 4) {
            std::memcpy(
                outputRow + static_cast<std::size_t>(width) * 4U,
                inputRow + static_cast<std::size_t>(width) * 4U,
                static_cast<std::size_t>(stride - width * 4));
        }
    }
    return !cancellation.cancelled();
}

bool correctOptics(
    const std::uint8_t* input,
    std::uint8_t* output,
    int width,
    int height,
    int stride,
    float chromaticAberration,
    float vignette,
    const kepler_native::CancellationLease& cancellation) {
    const float ca = clamp01(chromaticAberration);
    const float vignetteStrength = clamp01(vignette);
    const float centerX = (width - 1) * 0.5F;
    const float centerY = (height - 1) * 0.5F;
    const float inverseRadius =
        1.0F / std::max(1.0F, std::sqrt(centerX * centerX + centerY * centerY));
    for (int y = 0; y < height; ++y) {
        if ((y & 15) == 0 && cancellation.cancelled()) return false;
        const auto* inputRow =
            input + static_cast<std::size_t>(y) * static_cast<std::size_t>(stride);
        auto* outputRow =
            output + static_cast<std::size_t>(y) * static_cast<std::size_t>(stride);
        for (int x = 0; x < width; ++x) {
            const auto* center = inputRow + static_cast<std::size_t>(x) * 4U;
            const float dx = x - centerX;
            const float dy = y - centerY;
            const float radius = std::sqrt(dx * dx + dy * dy) * inverseRadius;
            const int shiftX = (dx > 0.0F) - (dx < 0.0F);
            const int shiftY = (dy > 0.0F) - (dy < 0.0F);
            const auto* inward =
                pixelAt(input, stride, width, height, x - shiftX, y - shiftY);
            const float caMix = ca * smoothstep(0.25F, 1.0F, radius) * 0.55F;
            const float gain =
                1.0F + vignetteStrength * smoothstep(0.42F, 1.0F, radius) * 0.24F;
            auto* out = outputRow + static_cast<std::size_t>(x) * 4U;
            out[0] = toByte((center[0] + (inward[0] - center[0]) * caMix) * gain);
            out[1] = toByte(center[1] * gain);
            out[2] = toByte((center[2] + (inward[2] - center[2]) * caMix) * gain);
            out[3] = center[3];
        }
        if (stride > width * 4) {
            std::memcpy(
                outputRow + static_cast<std::size_t>(width) * 4U,
                inputRow + static_cast<std::size_t>(width) * 4U,
                static_cast<std::size_t>(stride - width * 4));
        }
    }
    return !cancellation.cancelled();
}

bool cleanSmallSpots(
    const std::uint8_t* input,
    std::uint8_t* output,
    int width,
    int height,
    int stride,
    float strength,
    const kepler_native::CancellationLease& cancellation) {
    const float bounded = clamp01(strength);
    if (bounded <= 0.001F) {
        return copyStage(
            input,
            output,
            static_cast<std::size_t>(stride) * static_cast<std::size_t>(height),
            cancellation);
    }
    for (int y = 0; y < height; ++y) {
        if ((y & 15) == 0 && cancellation.cancelled()) return false;
        const auto* inputRow =
            input + static_cast<std::size_t>(y) * static_cast<std::size_t>(stride);
        auto* outputRow =
            output + static_cast<std::size_t>(y) * static_cast<std::size_t>(stride);
        for (int x = 0; x < width; ++x) {
            const auto* center = inputRow + static_cast<std::size_t>(x) * 4U;
            float mean[3] = {0.0F, 0.0F, 0.0F};
            float minimumNeighborLuma = 255.0F;
            float maximumNeighborLuma = 0.0F;
            for (int dy = -1; dy <= 1; ++dy) {
                for (int dx = -1; dx <= 1; ++dx) {
                    if (dx == 0 && dy == 0) continue;
                    const auto* sample = pixelAt(input, stride, width, height, x + dx, y + dy);
                    mean[0] += sample[0];
                    mean[1] += sample[1];
                    mean[2] += sample[2];
                    const float sampleLuma = luma(sample);
                    minimumNeighborLuma = std::min(minimumNeighborLuma, sampleLuma);
                    maximumNeighborLuma = std::max(maximumNeighborLuma, sampleLuma);
                }
            }
            for (float& channel : mean) channel /= 8.0F;
            const float centerY = luma(center);
            const float meanY = 0.2126F * mean[0] + 0.7152F * mean[1] + 0.0722F * mean[2];
            const float lumaOutlier = std::fabs(centerY - meanY);
            const float chromaOutlier =
                std::max(
                    std::fabs((center[0] - center[1]) - (mean[0] - mean[1])),
                    std::fabs((center[2] - center[1]) - (mean[2] - mean[1])));
            const float neighborRange = maximumNeighborLuma - minimumNeighborLuma;
            const float neighborhoodCoherence =
                1.0F - smoothstep(10.0F, 34.0F, neighborRange);
            const float isolated =
                std::max(
                    smoothstep(20.0F, 56.0F, lumaOutlier),
                    smoothstep(24.0F, 72.0F, chromaOutlier));
            const float legitimateHighlightGuard =
                (centerY > 210.0F && centerY > meanY && chromaOutlier < 18.0F)
                    ? 0.04F
                    : 1.0F;
            const float mix =
                isolated * bounded * neighborhoodCoherence *
                legitimateHighlightGuard * 0.62F;
            auto* out = outputRow + static_cast<std::size_t>(x) * 4U;
            out[0] = toByte(center[0] + (mean[0] - center[0]) * mix);
            out[1] = toByte(center[1] + (mean[1] - center[1]) * mix);
            out[2] = toByte(center[2] + (mean[2] - center[2]) * mix);
            out[3] = center[3];
        }
        if (stride > width * 4) {
            std::memcpy(
                outputRow + static_cast<std::size_t>(width) * 4U,
                inputRow + static_cast<std::size_t>(width) * 4U,
                static_cast<std::size_t>(stride - width * 4));
        }
    }
    return !cancellation.cancelled();
}

int runCorrectionPipeline(
    const std::uint8_t* source,
    std::uint8_t* destination,
    int width,
    int height,
    int stride,
    const CorrectionParams& params,
    const kepler_native::CancellationLease& cancellation) {
    if (cancellation.cancelled()) return kepler_native::kCancelledBeforeStart;
    std::size_t bitmapBytes = 0;
    std::size_t scratchBytes = 0;
    if (!kepler_native::checkedMultiply(
            static_cast<std::size_t>(stride),
            static_cast<std::size_t>(height),
            bitmapBytes) ||
        !kepler_native::checkedMultiply(bitmapBytes, 2U, scratchBytes) ||
        scratchBytes > kMaximumScratchBytes) {
        return -12;
    }
    std::vector<std::uint8_t> first(bitmapBytes);
    std::vector<std::uint8_t> second(bitmapBytes);
    if (!copyStage(source, first.data(), bitmapBytes, cancellation)) {
        return kepler_native::kCancelledMidPass;
    }
    const std::uint8_t* input = first.data();
    std::uint8_t* output = second.data();
    const auto advance = [&]() {
        const auto* completed = output;
        output = const_cast<std::uint8_t*>(input);
        input = completed;
    };
    if (!reduceChromaNoise(
            input,
            output,
            width,
            height,
            stride,
            params.luminanceNoise,
            params.chromaNoise,
            cancellation)) {
        return kepler_native::kCancelledMidPass;
    }
    advance();
    if (!enhanceDetail(input, output, width, height, stride, params, cancellation)) {
        return kepler_native::kCancelledMidPass;
    }
    advance();
    if (!correctOptics(
            input,
            output,
            width,
            height,
            stride,
            params.chromaticAberration,
            params.vignette,
            cancellation)) {
        return kepler_native::kCancelledMidPass;
    }
    advance();
    if (!cleanSmallSpots(
            input,
            output,
            width,
            height,
            stride,
            params.spotCleanup,
            cancellation)) {
        return kepler_native::kCancelledMidPass;
    }
    advance();
    if (cancellation.cancelled()) return kepler_native::kCancelledBeforeCommit;
    std::memcpy(destination, input, bitmapBytes);
    return 0;
}

}  // namespace

#ifdef KEPLER_HOST_TEST
bool kepler_host::applyCorrectionsV2(
    const std::uint8_t* source,
    int width,
    int height,
    int sourceStride,
    std::uint8_t* destination,
    int destinationStride,
    const CorrectionV2Params& params) {
    if (source == nullptr || destination == nullptr || source == destination ||
        width <= 0 || height <= 0 || sourceStride != destinationStride ||
        sourceStride < width * 4) {
        return false;
    }
    const CorrectionParams nativeParams{
        params.detail,
        params.luminanceNoise,
        params.chromaNoise,
        params.highlightProtection,
        params.shadowProtection,
        params.chromaticAberration,
        params.vignette,
        params.spotCleanup,
    };
    kepler_native::CancellationLease cancellation(0);
    return runCorrectionPipeline(
               source,
               destination,
               width,
               height,
               sourceStride,
               nativeParams,
               cancellation) == 0;
}
#endif

extern "C" JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeApplyCorrectionsV2Native(
    JNIEnv* env,
    jobject,
    jobject source,
    jobject destination,
    jfloat detail,
    jfloat luminanceNoise,
    jfloat chromaNoise,
    jfloat highlightProtection,
    jfloat shadowProtection,
    jfloat chromaticAberration,
    jfloat vignette,
    jfloat spotCleanup,
    jlong operationToken) {
    return kepler_native::runNativeGuarded(
        "KeplerCorrectionsV2",
        "nativeApplyCorrectionsV2Native",
        [&]() -> jint {
            if (kepler_native::isSameBitmap(env, source, destination)) return -3;
            AndroidBitmapInfo sourceInfo{};
            AndroidBitmapInfo destinationInfo{};
            if (!kepler_native::getBitmapInfo(env, source, sourceInfo) ||
                !kepler_native::getBitmapInfo(env, destination, destinationInfo) ||
                !kepler_native::validateRgbaBitmapLayout(sourceInfo) ||
                !kepler_native::validateRgbaBitmapLayout(destinationInfo) ||
                !kepler_native::dimensionsMatch(sourceInfo, destinationInfo) ||
                sourceInfo.stride != destinationInfo.stride) {
                return -2;
            }
            std::size_t bitmapBytes = 0;
            std::size_t scratchBytes = 0;
            if (!kepler_native::checkedBitmapByteCount(sourceInfo, bitmapBytes) ||
                !kepler_native::checkedMultiply(bitmapBytes, 2U, scratchBytes) ||
                scratchBytes > kMaximumScratchBytes) {
                return -12;
            }
            kepler_native::CancellationLease cancellation(operationToken);
            if (cancellation.cancelled()) return kepler_native::kCancelledBeforeStart;

            kepler_native::LockedBitmap sourceLock(env, source);
            kepler_native::LockedBitmap destinationLock(env, destination);
            if (kepler_native::lockAll(sourceLock, destinationLock) >= 0) return -4;
            const auto* sourceBytes = static_cast<const std::uint8_t*>(sourceLock.pixels);
            auto* destinationBytes = static_cast<std::uint8_t*>(destinationLock.pixels);
            if (sourceBytes == destinationBytes) return -3;

            const CorrectionParams params{
                clamp01(detail),
                clamp01(luminanceNoise),
                clamp01(chromaNoise),
                clamp01(highlightProtection),
                clamp01(shadowProtection),
                clamp01(chromaticAberration),
                clamp01(vignette),
                clamp01(spotCleanup),
            };

            const int width = static_cast<int>(sourceInfo.width);
            const int height = static_cast<int>(sourceInfo.height);
            const int stride = static_cast<int>(sourceInfo.stride);
            return runCorrectionPipeline(
                sourceBytes,
                destinationBytes,
                width,
                height,
                stride,
                params,
                cancellation);
        });
}
