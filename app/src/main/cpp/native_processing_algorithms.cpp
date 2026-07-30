#include "native_processing_algorithms.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <limits>
#include <vector>

// Experimental, bounded mobile variants inspired by established classical methods:
// Buades et al. non-local means, multiscale Laplacian detail decomposition,
// Hable-style filmic tone mapping, and He et al. dark-channel dehazing.
// These implementations are original compact variants, not copied reference code, and
// intentionally trade exhaustive search/guided refinement for predictable on-device memory.
namespace kepler_processing {
namespace {

constexpr std::size_t kMaxScratchBytes = 256ull * 1024ull * 1024ull;

inline float clamp01(float value) {
    return std::clamp(value, 0.0f, 1.0f);
}

inline std::uint8_t toByte(float value) {
    return static_cast<std::uint8_t>(std::lround(clamp01(value) * 255.0f));
}

inline float luma(float r, float g, float b) {
    return 0.2126f * r + 0.7152f * g + 0.0722f * b;
}

bool checkedFrameBytes(int stride, int height, std::size_t& result) {
    if (stride <= 0 || height <= 0) return false;
    const auto strideSize = static_cast<std::size_t>(stride);
    const auto heightSize = static_cast<std::size_t>(height);
    if (strideSize > std::numeric_limits<std::size_t>::max() / heightSize) return false;
    result = strideSize * heightSize;
    return result <= kMaxScratchBytes;
}

bool checkedPixels(int width, int height, std::size_t& result) {
    if (width <= 0 || height <= 0) return false;
    const auto widthSize = static_cast<std::size_t>(width);
    const auto heightSize = static_cast<std::size_t>(height);
    if (widthSize > std::numeric_limits<std::size_t>::max() / heightSize) return false;
    result = widthSize * heightSize;
    return result <= kMaxScratchBytes;
}

inline const std::uint8_t* pixelAt(
    const std::vector<std::uint8_t>& source,
    int stride,
    int width,
    int height,
    int x,
    int y) {
    const int safeX = std::clamp(x, 0, width - 1);
    const int safeY = std::clamp(y, 0, height - 1);
    return source.data() + static_cast<std::size_t>(safeY) * static_cast<std::size_t>(stride) +
        static_cast<std::size_t>(safeX) * 4U;
}

inline float channel(const std::uint8_t* pixel, int index) {
    return pixel[index] / 255.0f;
}

float patchDistanceCross(
    const std::vector<std::uint8_t>& source,
    int stride,
    int width,
    int height,
    int x0,
    int y0,
    int x1,
    int y1) {
    static constexpr std::array<std::array<int, 2>, 5> kPatch = {{
        {{0, 0}}, {{-1, 0}}, {{1, 0}}, {{0, -1}}, {{0, 1}},
    }};
    float distance = 0.0f;
    for (const auto& offset : kPatch) {
        const auto* first = pixelAt(source, stride, width, height, x0 + offset[0], y0 + offset[1]);
        const auto* second = pixelAt(source, stride, width, height, x1 + offset[0], y1 + offset[1]);
        const float firstLuma = luma(channel(first, 0), channel(first, 1), channel(first, 2));
        const float secondLuma = luma(channel(second, 0), channel(second, 1), channel(second, 2));
        const float delta = firstLuma - secondLuma;
        distance += delta * delta;
    }
    return distance / static_cast<float>(kPatch.size());
}

float localGradient(
    const std::vector<std::uint8_t>& source,
    int stride,
    int width,
    int height,
    int x,
    int y) {
    const auto* left = pixelAt(source, stride, width, height, x - 1, y);
    const auto* right = pixelAt(source, stride, width, height, x + 1, y);
    const auto* top = pixelAt(source, stride, width, height, x, y - 1);
    const auto* bottom = pixelAt(source, stride, width, height, x, y + 1);
    return std::abs(luma(channel(right, 0), channel(right, 1), channel(right, 2)) -
                    luma(channel(left, 0), channel(left, 1), channel(left, 2))) +
        std::abs(luma(channel(bottom, 0), channel(bottom, 1), channel(bottom, 2)) -
                 luma(channel(top, 0), channel(top, 1), channel(top, 2)));
}

float boxLuma(
    const std::vector<std::uint8_t>& source,
    int stride,
    int width,
    int height,
    int x,
    int y,
    int radius) {
    float sum = 0.0f;
    int count = 0;
    for (int dy = -radius; dy <= radius; ++dy) {
        for (int dx = -radius; dx <= radius; ++dx) {
            const auto* pixel = pixelAt(source, stride, width, height, x + dx, y + dy);
            sum += luma(channel(pixel, 0), channel(pixel, 1), channel(pixel, 2));
            ++count;
        }
    }
    return sum / static_cast<float>(count);
}

float filmicCurve(float value) {
    constexpr float a = 0.15f;
    constexpr float b = 0.50f;
    constexpr float c = 0.10f;
    constexpr float d = 0.20f;
    constexpr float e = 0.02f;
    constexpr float f = 0.30f;
    const auto curve = [=](float x) {
        return ((x * (a * x + c * b) + d * e) / (x * (a * x + b) + d * f)) - e / f;
    };
    const float white = curve(1.35f);
    return clamp01(curve(value * 1.35f) / std::max(white, 1e-5f));
}

float sigmoidCurve(float value) {
    constexpr float slope = 6.0f;
    const auto logistic = [=](float x) { return 1.0f / (1.0f + std::exp(-slope * (x - 0.5f))); };
    const float low = logistic(0.0f);
    const float high = logistic(1.0f);
    return clamp01((logistic(value) - low) / std::max(high - low, 1e-5f));
}

bool applyLumaCurve(
    std::uint8_t* rgba,
    int width,
    int height,
    int stride,
    float (*curve)(float),
    float blend,
    const kepler_native::CancellationLease& cancellation) {
    if (!rgba || width <= 0 || height <= 0 || stride < width * 4) return false;
    for (int y = 0; y < height; ++y) {
        if ((y & 15) == 0 && cancellation.cancelled()) return false;
        auto* row = rgba + static_cast<std::size_t>(y) * static_cast<std::size_t>(stride);
        for (int x = 0; x < width; ++x) {
            auto* pixel = row + static_cast<std::size_t>(x) * 4U;
            const float r = channel(pixel, 0);
            const float g = channel(pixel, 1);
            const float b = channel(pixel, 2);
            const float sourceLuma = luma(r, g, b);
            const float mappedLuma = sourceLuma + (curve(sourceLuma) - sourceLuma) * blend;
            const float ratio = sourceLuma > 1e-4f ? mappedLuma / sourceLuma : 1.0f;
            pixel[0] = toByte(r * ratio);
            pixel[1] = toByte(g * ratio);
            pixel[2] = toByte(b * ratio);
        }
    }
    return true;
}

void horizontalMinFilter(
    const std::vector<std::uint8_t>& source,
    std::vector<std::uint8_t>& destination,
    int width,
    int height,
    int radius) {
    for (int y = 0; y < height; ++y) {
        std::deque<int> queue;
        const auto row = static_cast<std::size_t>(y) * static_cast<std::size_t>(width);
        for (int x = 0; x < width + radius; ++x) {
            const int incoming = std::min(width - 1, x);
            while (!queue.empty() && source[row + queue.back()] >= source[row + incoming]) {
                queue.pop_back();
            }
            queue.push_back(incoming);
            const int outgoing = x - radius * 2;
            while (!queue.empty() && queue.front() < outgoing) queue.pop_front();
            const int outputX = x - radius;
            if (outputX >= 0 && outputX < width) {
                destination[row + static_cast<std::size_t>(outputX)] = source[row + queue.front()];
            }
        }
    }
}

bool minFilter(
    const std::vector<std::uint8_t>& source,
    std::vector<std::uint8_t>& temporary,
    std::vector<std::uint8_t>& destination,
    int width,
    int height,
    int radius,
    const kepler_native::CancellationLease& cancellation) {
    horizontalMinFilter(source, temporary, width, height, radius);
    for (int x = 0; x < width; ++x) {
        if ((x & 31) == 0 && cancellation.cancelled()) return false;
        std::deque<int> queue;
        for (int y = 0; y < height + radius; ++y) {
            const int incoming = std::min(height - 1, y);
            const auto incomingIndex =
                static_cast<std::size_t>(incoming) * static_cast<std::size_t>(width) +
                static_cast<std::size_t>(x);
            while (!queue.empty()) {
                const auto backIndex =
                    static_cast<std::size_t>(queue.back()) * static_cast<std::size_t>(width) +
                    static_cast<std::size_t>(x);
                if (temporary[backIndex] < temporary[incomingIndex]) break;
                queue.pop_back();
            }
            queue.push_back(incoming);
            const int outgoing = y - radius * 2;
            while (!queue.empty() && queue.front() < outgoing) queue.pop_front();
            const int outputY = y - radius;
            if (outputY >= 0 && outputY < height) {
                const auto outputIndex =
                    static_cast<std::size_t>(outputY) * static_cast<std::size_t>(width) +
                    static_cast<std::size_t>(x);
                const auto minIndex =
                    static_cast<std::size_t>(queue.front()) * static_cast<std::size_t>(width) +
                    static_cast<std::size_t>(x);
                destination[outputIndex] = temporary[minIndex];
            }
        }
    }
    return true;
}

}  // namespace

bool applyNonLocalMeansLite(
    std::uint8_t* rgba,
    int width,
    int height,
    int stride,
    float luminanceStrength,
    float colorStrength,
    float detailProtection,
    const kepler_native::CancellationLease& cancellation) {
    const float lumaStrength = clamp01(luminanceStrength);
    const float chromaStrength = clamp01(colorStrength);
    if (std::max(lumaStrength, chromaStrength) <= 0.001f) return true;
    std::size_t frameBytes = 0;
    if (!rgba || stride < width * 4 || !checkedFrameBytes(stride, height, frameBytes)) return false;
    std::vector<std::uint8_t> source(rgba, rgba + frameBytes);
    static constexpr std::array<std::array<int, 2>, 13> kSearch = {{
        {{0, 0}}, {{-1, 0}}, {{1, 0}}, {{0, -1}}, {{0, 1}},
        {{-1, -1}}, {{1, -1}}, {{-1, 1}}, {{1, 1}},
        {{-2, 0}}, {{2, 0}}, {{0, -2}}, {{0, 2}},
    }};
    const float protection = clamp01(detailProtection);
    const float h = 0.028f + 0.095f * std::max(lumaStrength, chromaStrength);
    const float hSquared = std::max(h * h, 1e-6f);
    for (int y = 0; y < height; ++y) {
        if ((y & 7) == 0 && cancellation.cancelled()) return false;
        auto* outputRow = rgba + static_cast<std::size_t>(y) * static_cast<std::size_t>(stride);
        for (int x = 0; x < width; ++x) {
            const auto* center = pixelAt(source, stride, width, height, x, y);
            const float gradient = localGradient(source, stride, width, height, x, y);
            const float edgeGuard = 1.0f - protection * clamp01(gradient / 0.22f);
            float weights = 0.0f;
            std::array<float, 3> sums = {0.0f, 0.0f, 0.0f};
            for (const auto& candidateOffset : kSearch) {
                const int candidateX = x + candidateOffset[0];
                const int candidateY = y + candidateOffset[1];
                const float distance = patchDistanceCross(
                    source, stride, width, height, x, y, candidateX, candidateY);
                const float spatial =
                    static_cast<float>(candidateOffset[0] * candidateOffset[0] +
                                       candidateOffset[1] * candidateOffset[1]);
                const float weight = std::exp(-distance / hSquared - spatial * 0.08f);
                const auto* candidate =
                    pixelAt(source, stride, width, height, candidateX, candidateY);
                for (int channelIndex = 0; channelIndex < 3; ++channelIndex) {
                    sums[channelIndex] += channel(candidate, channelIndex) * weight;
                }
                weights += weight;
            }
            auto* output = outputRow + static_cast<std::size_t>(x) * 4U;
            const float filteredR = sums[0] / std::max(weights, 1e-6f);
            const float filteredG = sums[1] / std::max(weights, 1e-6f);
            const float filteredB = sums[2] / std::max(weights, 1e-6f);
            const float originalR = channel(center, 0);
            const float originalG = channel(center, 1);
            const float originalB = channel(center, 2);
            const float originalLuma = luma(originalR, originalG, originalB);
            const float filteredLuma = luma(filteredR, filteredG, filteredB);
            const float lumaMix = lumaStrength * edgeGuard;
            const float chromaMix = chromaStrength * (0.55f + 0.45f * edgeGuard);
            const float targetLuma = originalLuma + (filteredLuma - originalLuma) * lumaMix;
            const float originalCb = originalB - originalLuma;
            const float originalCr = originalR - originalLuma;
            const float filteredCb = filteredB - filteredLuma;
            const float filteredCr = filteredR - filteredLuma;
            const float targetCb = originalCb + (filteredCb - originalCb) * chromaMix;
            const float targetCr = originalCr + (filteredCr - originalCr) * chromaMix;
            const float targetB = targetLuma + targetCb;
            const float targetR = targetLuma + targetCr;
            const float targetG =
                (targetLuma - 0.2126f * targetR - 0.0722f * targetB) / 0.7152f;
            output[0] = toByte(targetR);
            output[1] = toByte(targetG);
            output[2] = toByte(targetB);
            output[3] = center[3];
        }
    }
    return true;
}

bool applyMultiscaleLaplacianDetail(
    std::uint8_t* rgba,
    int width,
    int height,
    int stride,
    float strength,
    float noiseReduction,
    const kepler_native::CancellationLease& cancellation) {
    const float amount = clamp01(strength);
    if (amount <= 0.001f) return true;
    std::size_t frameBytes = 0;
    if (!rgba || stride < width * 4 || !checkedFrameBytes(stride, height, frameBytes)) return false;
    std::vector<std::uint8_t> source(rgba, rgba + frameBytes);
    for (int y = 0; y < height; ++y) {
        if ((y & 7) == 0 && cancellation.cancelled()) return false;
        auto* outputRow = rgba + static_cast<std::size_t>(y) * static_cast<std::size_t>(stride);
        for (int x = 0; x < width; ++x) {
            const auto* center = pixelAt(source, stride, width, height, x, y);
            const float centerLuma = luma(channel(center, 0), channel(center, 1), channel(center, 2));
            const float fineBlur = boxLuma(source, stride, width, height, x, y, 1);
            const float coarseBlur = boxLuma(source, stride, width, height, x, y, 2);
            const float fine = centerLuma - fineBlur;
            const float coarse = fineBlur - coarseBlur;
            const float noiseFloor = 0.006f + 0.022f * clamp01(noiseReduction);
            const float detailMagnitude = std::abs(fine) + std::abs(coarse) * 0.6f;
            const float textureGate = clamp01((detailMagnitude - noiseFloor) / 0.08f);
            const float shadowGuard = clamp01((centerLuma - 0.035f) / 0.18f);
            const float highlightGuard = clamp01((0.985f - centerLuma) / 0.16f);
            const float delta = std::clamp(
                (fine * 0.95f + coarse * 0.55f) * amount * textureGate * shadowGuard * highlightGuard,
                -0.075f,
                0.075f);
            auto* output = outputRow + static_cast<std::size_t>(x) * 4U;
            for (int channelIndex = 0; channelIndex < 3; ++channelIndex) {
                output[channelIndex] = toByte(channel(center, channelIndex) + delta);
            }
            output[3] = center[3];
        }
    }
    return true;
}

bool applyFilmicTone(
    std::uint8_t* rgba,
    int width,
    int height,
    int stride,
    const kepler_native::CancellationLease& cancellation) {
    return applyLumaCurve(rgba, width, height, stride, filmicCurve, 0.78f, cancellation);
}

bool applySigmoidTone(
    std::uint8_t* rgba,
    int width,
    int height,
    int stride,
    const kepler_native::CancellationLease& cancellation) {
    return applyLumaCurve(rgba, width, height, stride, sigmoidCurve, 0.62f, cancellation);
}

bool applyDarkChannelDehaze(
    std::uint8_t* rgba,
    int width,
    int height,
    int stride,
    float strength,
    bool multiscale,
    const kepler_native::CancellationLease& cancellation) {
    const float amount = clamp01(strength);
    if (amount <= 0.001f) return true;
    std::size_t frameBytes = 0;
    std::size_t pixels = 0;
    if (!rgba || stride < width * 4 || !checkedFrameBytes(stride, height, frameBytes) ||
        !checkedPixels(width, height, pixels)) {
        return false;
    }
    const std::size_t scratchBytes = frameBytes + pixels * (multiscale ? 4U : 3U);
    if (scratchBytes > kMaxScratchBytes) return false;
    std::vector<std::uint8_t> source(rgba, rgba + frameBytes);
    std::vector<std::uint8_t> minimum(pixels);
    std::vector<std::uint8_t> temporary(pixels);
    std::vector<std::uint8_t> fine(pixels);
    std::vector<std::uint8_t> coarse(multiscale ? pixels : 0U);
    for (int y = 0; y < height; ++y) {
        if ((y & 31) == 0 && cancellation.cancelled()) return false;
        const auto* row = source.data() + static_cast<std::size_t>(y) * static_cast<std::size_t>(stride);
        for (int x = 0; x < width; ++x) {
            const auto* pixel = row + static_cast<std::size_t>(x) * 4U;
            minimum[static_cast<std::size_t>(y) * static_cast<std::size_t>(width) + x] =
                std::min(pixel[0], std::min(pixel[1], pixel[2]));
        }
    }
    if (!minFilter(minimum, temporary, fine, width, height, 3, cancellation)) return false;
    const std::vector<std::uint8_t>* atmosphericDark = &fine;
    if (multiscale) {
        if (!minFilter(minimum, temporary, coarse, width, height, 7, cancellation)) return false;
        atmosphericDark = &coarse;
    }

    std::array<std::size_t, 256> histogram{};
    for (const auto value : *atmosphericDark) histogram[value]++;
    const std::size_t target = std::max<std::size_t>(1U, pixels / 1000U);
    std::size_t accumulated = 0;
    int threshold = 255;
    for (; threshold > 0; --threshold) {
        accumulated += histogram[static_cast<std::size_t>(threshold)];
        if (accumulated >= target) break;
    }
    float bestLuma = -1.0f;
    std::array<float, 3> atmosphere = {1.0f, 1.0f, 1.0f};
    for (std::size_t index = 0; index < pixels; ++index) {
        if ((*atmosphericDark)[index] < threshold) continue;
        const int y = static_cast<int>(index / static_cast<std::size_t>(width));
        const int x = static_cast<int>(index % static_cast<std::size_t>(width));
        const auto* pixel = source.data() + static_cast<std::size_t>(y) * stride +
            static_cast<std::size_t>(x) * 4U;
        const float r = channel(pixel, 0);
        const float g = channel(pixel, 1);
        const float b = channel(pixel, 2);
        const float candidateLuma = luma(r, g, b);
        if (candidateLuma > bestLuma) {
            bestLuma = candidateLuma;
            atmosphere = {r, g, b};
        }
    }
    for (auto& value : atmosphere) value = std::max(value, 0.45f);
    const float atmosphereMinimum = std::min(atmosphere[0], std::min(atmosphere[1], atmosphere[2]));
    const float omega = (multiscale ? 0.70f : 0.76f) * amount;
    for (int y = 0; y < height; ++y) {
        if ((y & 7) == 0 && cancellation.cancelled()) return false;
        auto* outputRow = rgba + static_cast<std::size_t>(y) * static_cast<std::size_t>(stride);
        const auto* sourceRow = source.data() + static_cast<std::size_t>(y) * static_cast<std::size_t>(stride);
        for (int x = 0; x < width; ++x) {
            const auto index = static_cast<std::size_t>(y) * static_cast<std::size_t>(width) + x;
            const float fineDark = fine[index] / 255.0f;
            const float dark =
                multiscale ? (fineDark * 0.58f + (coarse[index] / 255.0f) * 0.42f) : fineDark;
            const float transmission =
                std::clamp(1.0f - omega * dark / std::max(atmosphereMinimum, 0.01f), 0.28f, 1.0f);
            const auto* input = sourceRow + static_cast<std::size_t>(x) * 4U;
            auto* output = outputRow + static_cast<std::size_t>(x) * 4U;
            const float r = channel(input, 0);
            const float g = channel(input, 1);
            const float b = channel(input, 2);
            const float sourceLuma = luma(r, g, b);
            const float saturation = std::max(r, std::max(g, b)) - std::min(r, std::min(g, b));
            const float skyGuard =
                sourceLuma > 0.72f && saturation < 0.12f ? 0.42f : 1.0f;
            for (int channelIndex = 0; channelIndex < 3; ++channelIndex) {
                const float sourceValue = channel(input, channelIndex);
                const float restored =
                    (sourceValue - atmosphere[channelIndex]) / transmission + atmosphere[channelIndex];
                const float bounded = sourceValue +
                    std::clamp(restored - sourceValue, -0.16f, 0.20f) * skyGuard;
                output[channelIndex] = toByte(bounded);
            }
            output[3] = input[3];
        }
    }
    return true;
}

}  // namespace kepler_processing
