package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.NativePhotoCore

fun renderBitmapWithSelectionLayers(
    base: Bitmap,
    state: EditorUiState,
    revision: Int
): Bitmap {
    val enabledLayers = state.selectionLayers.filter { it.enabled }
    val global = renderSelectionBitmapWithParams(base, state.params, state, revision)
    if (enabledLayers.isEmpty()) return global

    for (layer in enabledLayers) {
        val local = renderSelectionBitmapWithParams(base, mergeSelectionParams(state.params, layer.localParams), state, revision)
        NativePhotoCore.nativeBlendSelectionLayerInPlace(
            target = global,
            local = local,
            mask = layer.bitmap,
            inverted = layer.inverted,
            opacity = layer.opacity.coerceIn(0f, 1f)
        )
        local.recycle()
    }
    return global
}

private fun mergeSelectionParams(base: EditParams, local: EditParams): EditParams = EditParams(
    exposure = (base.exposure + local.exposure).coerceIn(-1f, 1f),
    contrast = (base.contrast + local.contrast).coerceIn(-1f, 1f),
    shadows = (base.shadows + local.shadows).coerceIn(-1f, 1f),
    highlights = (base.highlights + local.highlights).coerceIn(-1f, 1f),
    whites = (base.whites + local.whites).coerceIn(-1f, 1f),
    blacks = (base.blacks + local.blacks).coerceIn(-1f, 1f),
    temperature = (base.temperature + local.temperature).coerceIn(-1f, 1f),
    tint = (base.tint + local.tint).coerceIn(-1f, 1f),
    saturation = (base.saturation + local.saturation).coerceIn(-1f, 1f),
    vibrance = (base.vibrance + local.vibrance).coerceIn(-1f, 1f),
    clarity = (base.clarity + local.clarity).coerceIn(-1f, 1f),
    dehaze = (base.dehaze + local.dehaze).coerceIn(-1f, 1f),
    sharpness = (base.sharpness + local.sharpness).coerceIn(0f, 1f),
    noiseReduction = (base.noiseReduction + local.noiseReduction).coerceIn(0f, 1f),
    luminanceNoiseReduction = (base.luminanceNoiseReduction + local.luminanceNoiseReduction).coerceIn(0f, 1f),
    colorNoiseReduction = (base.colorNoiseReduction + local.colorNoiseReduction).coerceIn(0f, 1f),
    noiseDetailProtection = (base.noiseDetailProtection + local.noiseDetailProtection - 0.50f).coerceIn(0f, 1f)
)

private fun renderSelectionBitmapWithParams(
    base: Bitmap,
    params: EditParams,
    state: EditorUiState,
    revision: Int
): Bitmap {
    val out = base.copy(Bitmap.Config.ARGB_8888, true)
    NativePhotoCore.nativeRenderPreviewInPlace(
        out,
        params.exposure,
        params.contrast,
        params.shadows,
        params.highlights,
        params.whites,
        params.blacks,
        params.temperature,
        params.tint,
        params.saturation,
        params.vibrance,
        params.clarity,
        params.dehaze,
        params.sharpness,
        params.noiseReduction,
        params.luminanceNoiseReduction,
        params.colorNoiseReduction,
        params.noiseDetailProtection,
        state.noiseEngine.nativeId,
        state.detailEngine.nativeId,
        state.toneEngine.nativeId,
        state.hazeEngine.nativeId,
        revision
    )
    return out
}
