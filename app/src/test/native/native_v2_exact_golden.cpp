#include "native_corrections_v2.h"

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <vector>

namespace {

constexpr int kWidth = 9;
constexpr int kHeight = 7;

std::vector<std::uint8_t> fixture() {
    std::vector<std::uint8_t> result(kWidth * kHeight * 4U);
    for (int y = 0; y < kHeight; ++y) {
        for (int x = 0; x < kWidth; ++x) {
            const std::size_t offset =
                (static_cast<std::size_t>(y) * kWidth + x) * 4U;
            const int base = 22 + x * 17 + y * 9;
            result[offset] = static_cast<std::uint8_t>(std::min(255, base + (x == 7 ? 74 : 0)));
            result[offset + 1U] =
                static_cast<std::uint8_t>(std::min(255, base + 6 + (y == 1 ? 28 : 0)));
            result[offset + 2U] =
                static_cast<std::uint8_t>(std::min(255, base + 12 + (x == 1 ? 52 : 0)));
            result[offset + 3U] = static_cast<std::uint8_t>(x == 0 ? 96 : 255);
        }
    }
    return result;
}

std::int64_t exactHash(const std::vector<std::uint8_t>& rgba) {
    std::uint64_t hash = 0xcbf29ce484222325ULL;
    for (const auto value : rgba) {
        hash ^= value;
        hash *= 0x100000001b3ULL;
    }
    return static_cast<std::int64_t>(hash);
}

}  // namespace

int main() {
    const auto source = fixture();
    std::vector<std::uint8_t> destination(source.size(), 0x5aU);
    const kepler_host::CorrectionV2Params params{
        0.62F,
        0.38F,
        0.55F,
        0.72F,
        0.68F,
        0.46F,
        0.34F,
        0.41F,
    };
    if (!kepler_host::applyCorrectionsV2(
            source.data(),
            kWidth,
            kHeight,
            kWidth * 4,
            destination.data(),
            kWidth * 4,
            params)) {
        return 1;
    }
    constexpr std::int64_t kExpectedHash = -7938035531949190446LL;
    const auto actualHash = exactHash(destination);
    if (actualHash != kExpectedHash) {
        std::cerr << "correctionsV2: expected=" << kExpectedHash
                  << " actual=" << actualHash << '\n';
        return 2;
    }
    if (source != fixture()) {
        std::cerr << "source changed during transactional correction\n";
        return 3;
    }
    for (std::size_t index = 3; index < destination.size(); index += 4U) {
        if (destination[index] != source[index]) {
            std::cerr << "alpha changed at byte " << index << '\n';
            return 4;
        }
    }

    std::vector<std::uint8_t> identity(source.size(), 0U);
    const kepler_host::CorrectionV2Params zero{};
    if (!kepler_host::applyCorrectionsV2(
            source.data(),
            kWidth,
            kHeight,
            kWidth * 4,
            identity.data(),
            kWidth * 4,
            zero) ||
        identity != source) {
        std::cerr << "zero correction is not exact identity\n";
        return 5;
    }
    auto alias = source;
    if (kepler_host::applyCorrectionsV2(
            alias.data(),
            kWidth,
            kHeight,
            kWidth * 4,
            alias.data(),
            kWidth * 4,
            params)) {
        std::cerr << "alias was accepted\n";
        return 6;
    }

    constexpr int spotWidth = 5;
    constexpr int spotHeight = 5;
    std::vector<std::uint8_t> pointLight(spotWidth * spotHeight * 4U, 80U);
    for (std::size_t index = 3; index < pointLight.size(); index += 4U) {
        pointLight[index] = 255U;
    }
    const std::size_t center = (2U * spotWidth + 2U) * 4U;
    pointLight[center] = 240U;
    pointLight[center + 1U] = 240U;
    pointLight[center + 2U] = 240U;
    std::vector<std::uint8_t> protectedLight(pointLight.size());
    const kepler_host::CorrectionV2Params spotOnly{
        0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
    };
    if (!kepler_host::applyCorrectionsV2(
            pointLight.data(),
            spotWidth,
            spotHeight,
            spotWidth * 4,
            protectedLight.data(),
            spotWidth * 4,
            spotOnly) ||
        protectedLight[center] < 235U ||
        protectedLight[center + 1U] < 235U ||
        protectedLight[center + 2U] < 235U) {
        std::cerr << "legitimate neutral point light was removed\n";
        return 7;
    }

    auto coloredDefect = pointLight;
    coloredDefect[center] = 230U;
    coloredDefect[center + 1U] = 24U;
    coloredDefect[center + 2U] = 220U;
    std::vector<std::uint8_t> cleanedDefect(coloredDefect.size());
    if (!kepler_host::applyCorrectionsV2(
            coloredDefect.data(),
            spotWidth,
            spotHeight,
            spotWidth * 4,
            cleanedDefect.data(),
            spotWidth * 4,
            spotOnly) ||
        cleanedDefect[center] >= coloredDefect[center] ||
        cleanedDefect[center + 1U] <= coloredDefect[center + 1U] ||
        cleanedDefect[center + 2U] >= coloredDefect[center + 2U]) {
        std::cerr << "isolated colored defect was not reduced\n";
        return 8;
    }

    std::cout << "native V2 host exact goldens passed\n";
    return 0;
}
