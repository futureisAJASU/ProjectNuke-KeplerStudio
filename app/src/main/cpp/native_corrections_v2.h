#pragma once

#include <cstdint>

namespace kepler_host {

struct CorrectionV2Params {
    float detail;
    float luminanceNoise;
    float chromaNoise;
    float highlightProtection;
    float shadowProtection;
    float chromaticAberration;
    float vignette;
    float spotCleanup;
};

bool applyCorrectionsV2(
    const std::uint8_t* source,
    int width,
    int height,
    int sourceStride,
    std::uint8_t* destination,
    int destinationStride,
    const CorrectionV2Params& params);

}  // namespace kepler_host
