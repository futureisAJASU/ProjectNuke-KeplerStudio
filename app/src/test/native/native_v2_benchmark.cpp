#include "native_corrections_v2.h"

#include <chrono>
#include <cstdint>
#include <iostream>
#include <algorithm>
#include <stdexcept>
#include <vector>

namespace {

void runCase(int width, int height, int measuredRuns) {
    const std::size_t bytes =
        static_cast<std::size_t>(width) * static_cast<std::size_t>(height) * 4U;
    std::vector<std::uint8_t> source(bytes);
    std::vector<std::uint8_t> destination(bytes);
    for (std::size_t index = 0; index < bytes; index += 4U) {
        const auto pixel = static_cast<std::uint32_t>(index / 4U);
        source[index] = static_cast<std::uint8_t>((pixel * 17U) & 0xffU);
        source[index + 1U] = static_cast<std::uint8_t>((pixel * 29U) & 0xffU);
        source[index + 2U] = static_cast<std::uint8_t>((pixel * 43U) & 0xffU);
        source[index + 3U] = 255U;
    }
    const kepler_host::CorrectionV2Params params{
        0.55F,
        0.35F,
        0.48F,
        0.70F,
        0.65F,
        0.40F,
        0.32F,
        0.35F,
    };
    const auto execute = [&]() {
        const auto start = std::chrono::steady_clock::now();
        if (!kepler_host::applyCorrectionsV2(
            source.data(),
            width,
            height,
            width * 4,
            destination.data(),
            width * 4,
            params)) {
            throw std::runtime_error("V2 benchmark kernel rejected admitted dimensions");
        }
        return std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - start).count();
    };
    execute();
    execute();
    std::vector<double> samples;
    samples.reserve(static_cast<std::size_t>(measuredRuns));
    for (int run = 0; run < measuredRuns; ++run) samples.push_back(execute());
    std::sort(samples.begin(), samples.end());
    const double median = samples[samples.size() / 2U];
    const std::size_t p95Index =
        std::min(samples.size() - 1U, (samples.size() * 95U + 99U) / 100U - 1U);
    std::cout << width << 'x' << height
              << " runs=" << measuredRuns
              << " medianMs=" << median
              << " p95Ms=" << samples[p95Index]
              << " plannedScratchBytes=" << bytes * 2U
              << " sourceDestinationBytes=" << bytes * 2U
              << '\n';
}

}  // namespace

int main() {
    runCase(640, 360, 9);
    runCase(1920, 1080, 7);
    runCase(4000, 3000, 5);
    constexpr std::uint64_t maximumPlanPixels = 8192ULL * 8192ULL;
    std::cout << "8192x8192 planningOnlyScratchBytes="
              << maximumPlanPixels * 8ULL
              << " executionSkipped=safety"
              << '\n';
    return 0;
}
