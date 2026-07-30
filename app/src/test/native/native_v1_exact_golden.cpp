#include "native_v1_host_test.h"
#include "native_processing_algorithms.h"
#include "native_cancellation.h"

#include <array>
#include <cstdint>
#include <iomanip>
#include <iostream>
#include <vector>

namespace {

constexpr int kWidth = 5;
constexpr int kHeight = 3;
constexpr std::array<std::uint32_t, kWidth * kHeight> kFixture = {
    0xff000000U, 0xffffffffU, 0xfffff000U, 0xff001020U, 0x0080ff40U,
    0xff102030U, 0xff405060U, 0xff708090U, 0xffa0b0c0U, 0xffd0e0f0U,
    0xffff00ffU, 0xff00ffffU, 0xffffff00U, 0xff7f7f7fU, 0xff010203U,
};

std::vector<std::uint8_t> fixtureRgba() {
    std::vector<std::uint8_t> result(kFixture.size() * 4U);
    for (std::size_t index = 0; index < kFixture.size(); ++index) {
        const auto value = kFixture[index];
        result[index * 4U] = static_cast<std::uint8_t>((value >> 16U) & 0xffU);
        result[index * 4U + 1U] = static_cast<std::uint8_t>((value >> 8U) & 0xffU);
        result[index * 4U + 2U] = static_cast<std::uint8_t>(value & 0xffU);
        result[index * 4U + 3U] = static_cast<std::uint8_t>((value >> 24U) & 0xffU);
    }
    return result;
}

std::int64_t exactHash(
    const std::uint8_t* rgba,
    int width,
    int height,
    int stride
) {
    std::uint64_t hash = 0xcbf29ce484222325ULL;
    for (int y = 0; y < height; ++y) {
        const auto* row =
            rgba + static_cast<std::size_t>(y) * static_cast<std::size_t>(stride);
        for (int x = 0; x < width; ++x) {
            const auto* pixel = row + static_cast<std::size_t>(x) * 4U;
            const std::uint8_t argbBytes[4] = {pixel[2], pixel[1], pixel[0], pixel[3]};
            for (const auto value : argbBytes) {
                hash ^= value;
                hash *= 0x100000001b3ULL;
            }
        }
    }
    return static_cast<std::int64_t>(hash);
}

bool expectHash(
    const char* label,
    const std::vector<std::uint8_t>& rgba,
    int width,
    int height,
    std::int64_t expected
) {
    const auto actual = exactHash(rgba.data(), width, height, width * 4);
    if (actual == expected) return true;
    std::cerr << label << ": expected=" << expected << " actual=" << actual << '\n';
    return false;
}

}  // namespace

int main() {
    auto mainRender = fixtureRgba();
    const kepler_host::MainRenderParams params{
        0.10f,
        1.02f,
        0.03f,
        0.05f,
        0.01f,
        -0.01f,
        0.05f,
        0.02f,
        1.03f,
        0.04f,
        0.03f,
        0.02f,
        0.03f,
        0.02f,
        0.01f,
        0.50f,
        0,
        0,
        0,
        0,
    };
    if (!kepler_host::renderMain(mainRender.data(), kWidth, kHeight, kWidth * 4, params)) {
        return 1;
    }
    if (!expectHash("main", mainRender, kWidth, kHeight, 8252045260985128563LL)) return 11;

    auto flareMaskSource = fixtureRgba();
    std::vector<std::uint8_t> flareMask(flareMaskSource.size());
    if (!kepler_host::createFlareMask(
            flareMaskSource.data(),
            kWidth,
            kHeight,
            kWidth * 4,
            flareMask.data(),
            kWidth * 4,
            0.92f,
            2,
            1)) {
        return 2;
    }
    if (!expectHash("flareMask", flareMask, kWidth, kHeight, 649638774562468513LL)) return 12;

    auto night = fixtureRgba();
    if (!kepler_host::applyFlareCorrection(night.data(), kWidth, kHeight, kWidth * 4, 0, 0.28f)) {
        return 3;
    }
    if (!expectHash("night", night, kWidth, kHeight, -98438237269600328LL)) return 13;

    auto day = fixtureRgba();
    if (!kepler_host::applyFlareCorrection(day.data(), kWidth, kHeight, kWidth * 4, 1, 0.24f)) {
        return 4;
    }
    if (!expectHash("day", day, kWidth, kHeight, -7628775503570520508LL)) return 14;

    constexpr std::int64_t kSpecialHashes[] = {
        289374068137732291LL,
        -5217437593436956710LL,
        7403062178545612789LL,
        -7823268298983010661LL,
    };
    for (int effect = 0; effect <= 3; ++effect) {
        auto special = fixtureRgba();
        if (!kepler_host::applySpecialEffect(
                special.data(), kWidth, kHeight, kWidth * 4, effect, 0.5f)) {
            return 5 + effect;
        }
        const std::string label = "effect" + std::to_string(effect);
        if (!expectHash(label.c_str(), special, kWidth, kHeight, kSpecialHashes[effect])) {
            return 15 + effect;
        }
    }

    auto cropSource = fixtureRgba();
    std::vector<std::uint8_t> crop(cropSource.size());
    if (!kepler_host::renderCrop(
            cropSource.data(),
            kWidth,
            kHeight,
            kWidth * 4,
            crop.data(),
            kWidth,
            kHeight,
            kWidth * 4,
            0.0f,
            0.0f,
            1.0f,
            1.0f,
            90.0f,
            true)) {
        return 9;
    }
    if (!expectHash("crop90Flip", crop, kWidth, kHeight, 2178894102342394339LL)) return 19;

    std::vector<std::uint8_t> target{
        1, 2, 3, 255,
        16, 32, 48, 255,
        64, 80, 96, 255,
    };
    const std::vector<std::uint8_t> local{
        17, 34, 51, 128,
        160, 176, 192, 255,
        68, 85, 102, 0,
    };
    const std::vector<std::uint8_t> mask{255, 255, 255, 255};
    if (!kepler_host::blendSelection(
            target.data(),
            local.data(),
            3,
            1,
            12,
            12,
            mask.data(),
            1,
            1,
            4,
            false,
            0.5f)) {
        return 10;
    }
    if (!expectHash("selectionHalf", target, 3, 1, 6317636425074921512LL)) return 20;

    const auto identityHash = exactHash(
        fixtureRgba().data(),
        kWidth,
        kHeight,
        kWidth * 4);
    for (int effect = 0; effect <= 3; ++effect) {
        auto identity = fixtureRgba();
        if (!kepler_host::applySpecialEffect(
                identity.data(), kWidth, kHeight, kWidth * 4, effect, 0.0f) ||
            exactHash(identity.data(), kWidth, kHeight, kWidth * 4) != identityHash) {
            return 21 + effect;
        }
    }
    for (int mode = 0; mode <= 1; ++mode) {
        auto identity = fixtureRgba();
        if (!kepler_host::applyFlareCorrection(
                identity.data(), kWidth, kHeight, kWidth * 4, mode, 0.0f) ||
            exactHash(identity.data(), kWidth, kHeight, kWidth * 4) != identityHash) {
            return 25 + mode;
        }
    }

    // Alternative processing engines are opt-in and must preserve alpha and zero-strength identity.
    kepler_native::CancellationLease noCancellation(0);
    auto nlmIdentity = fixtureRgba();
    const auto nlmIdentityHash = exactHash(nlmIdentity.data(), kWidth, kHeight, kWidth * 4);
    if (!kepler_processing::applyNonLocalMeansLite(
            nlmIdentity.data(), kWidth, kHeight, kWidth * 4, 0.0f, 0.0f, 0.5f, noCancellation) ||
        exactHash(nlmIdentity.data(), kWidth, kHeight, kWidth * 4) != nlmIdentityHash) {
        return 27;
    }

    std::vector<std::uint8_t> noisy(7U * 7U * 4U, 128U);
    for (std::size_t index = 3; index < noisy.size(); index += 4) noisy[index] = 211U;
    noisy[(3U * 7U + 3U) * 4U] = 250U;
    noisy[(3U * 7U + 3U) * 4U + 1U] = 20U;
    noisy[(3U * 7U + 3U) * 4U + 2U] = 20U;
    const auto noisyBefore = noisy;
    if (!kepler_processing::applyNonLocalMeansLite(
            noisy.data(), 7, 7, 28, 1.0f, 1.0f, 0.2f, noCancellation) ||
        noisy == noisyBefore || noisy[(3U * 7U + 3U) * 4U + 3U] != 211U) {
        return 28;
    }

    auto filmic = fixtureRgba();
    const auto filmicBefore = filmic;
    if (!kepler_processing::applyFilmicTone(
            filmic.data(), kWidth, kHeight, kWidth * 4, noCancellation) ||
        filmic == filmicBefore) {
        return 29;
    }
    for (std::size_t index = 3; index < filmic.size(); index += 4) {
        if (filmic[index] != filmicBefore[index]) return 30;
    }

    std::vector<std::uint8_t> hazy(9U * 5U * 4U, 255U);
    for (int y = 0; y < 5; ++y) {
        for (int x = 0; x < 9; ++x) {
            auto* pixel = hazy.data() + (static_cast<std::size_t>(y) * 9U + x) * 4U;
            const auto value = static_cast<std::uint8_t>(145 + x * 9);
            pixel[0] = value;
            pixel[1] = static_cast<std::uint8_t>(value + 3);
            pixel[2] = static_cast<std::uint8_t>(value + 6);
            pixel[3] = 173U;
        }
    }
    const auto hazyBefore = hazy;
    if (!kepler_processing::applyDarkChannelDehaze(
            hazy.data(), 9, 5, 36, 0.8f, true, noCancellation) ||
        hazy == hazyBefore) {
        return 31;
    }
    for (std::size_t index = 3; index < hazy.size(); index += 4) {
        if (hazy[index] != 173U) return 32;
    }

    std::cout << "native V1 host exact goldens and alternative engines passed\n";
    return 0;
}
