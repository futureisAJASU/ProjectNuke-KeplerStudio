#pragma once

#include <cstdint>

#include "native_cancellation.h"

namespace kepler_processing {

bool applyNonLocalMeansLite(
    std::uint8_t* rgba,
    int width,
    int height,
    int stride,
    float luminanceStrength,
    float colorStrength,
    float detailProtection,
    const kepler_native::CancellationLease& cancellation);

bool applyMultiscaleLaplacianDetail(
    std::uint8_t* rgba,
    int width,
    int height,
    int stride,
    float strength,
    float noiseReduction,
    const kepler_native::CancellationLease& cancellation);

bool applyFilmicTone(
    std::uint8_t* rgba,
    int width,
    int height,
    int stride,
    const kepler_native::CancellationLease& cancellation);

bool applySigmoidTone(
    std::uint8_t* rgba,
    int width,
    int height,
    int stride,
    const kepler_native::CancellationLease& cancellation);

bool applyDarkChannelDehaze(
    std::uint8_t* rgba,
    int width,
    int height,
    int stride,
    float strength,
    bool multiscale,
    const kepler_native::CancellationLease& cancellation);

}  // namespace kepler_processing
