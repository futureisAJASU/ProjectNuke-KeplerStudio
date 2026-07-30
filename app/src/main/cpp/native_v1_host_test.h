#pragma once

#include <cstddef>
#include <cstdint>

namespace kepler_host {

struct MainRenderParams {
    float exposure;
    float contrast;
    float shadows;
    float highlights;
    float whites;
    float blacks;
    float temperature;
    float tint;
    float saturation;
    float vibrance;
    float clarity;
    float dehaze;
    float sharpness;
    float luminanceNoiseReduction;
    float colorNoiseReduction;
    float noiseDetailProtection;
    int noiseEngine;
    int detailEngine;
    int toneEngine;
    int hazeEngine;
};

bool renderMain(
    std::uint8_t* rgba,
    int width,
    int height,
    int stride,
    const MainRenderParams& params);

bool applySpecialEffect(
    std::uint8_t* rgba,
    int width,
    int height,
    int stride,
    int effect,
    float strength);

bool applyFlareCorrection(
    std::uint8_t* rgba,
    int width,
    int height,
    int stride,
    int mode,
    float strength);

bool createFlareMask(
    const std::uint8_t* source,
    int width,
    int height,
    int sourceStride,
    std::uint8_t* mask,
    int maskStride,
    float threshold,
    int radius,
    int passes);

bool renderCrop(
    const std::uint8_t* source,
    int sourceWidth,
    int sourceHeight,
    int sourceStride,
    std::uint8_t* destination,
    int destinationWidth,
    int destinationHeight,
    int destinationStride,
    float cropLeft,
    float cropTop,
    float cropRight,
    float cropBottom,
    float rotationDegrees,
    bool flipHorizontal);

bool blendSelection(
    std::uint8_t* target,
    const std::uint8_t* local,
    int width,
    int height,
    int targetStride,
    int localStride,
    const std::uint8_t* mask,
    int maskWidth,
    int maskHeight,
    int maskStride,
    bool inverted,
    float opacity);

}  // namespace kepler_host
