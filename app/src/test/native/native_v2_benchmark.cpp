#include "native_corrections_v2.h"

#include <chrono>
#include <cstdint>
#include <iostream>
#include <stdexcept>
#include <vector>

namespace {

void runCase(int width, int height) {
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
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - start);
    std::cout << width << 'x' << height
              << " elapsedMs=" << elapsed.count()
              << " plannedScratchBytes=" << bytes * 2U
              << " sourceDestinationBytes=" << bytes * 2U
              << '\n';
}

}  // namespace

int main() {
    runCase(640, 360);
    runCase(1920, 1080);
    runCase(4000, 3000);
    return 0;
}
