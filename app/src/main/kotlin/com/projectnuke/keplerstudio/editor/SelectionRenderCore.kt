package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import kotlin.math.roundToInt

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
        blendSelectionLayerInto(local = local, target = global, layer = layer)
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
    noiseReduction = (base.noiseReduction + local.noiseReduction).coerceIn(0f, 1f)
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
        state.noiseEngine.nativeId,
        state.detailEngine.nativeId,
        state.toneEngine.nativeId,
        state.hazeEngine.nativeId,
        revision
    )
    return out
}

private fun blendSelectionLayerInto(local: Bitmap, target: Bitmap, layer: SelectionLayer) {
    val width = target.width
    val height = target.height
    val mask = if (layer.bitmap.width == width && layer.bitmap.height == height) {
        layer.bitmap
    } else {
        Bitmap.createScaledBitmap(layer.bitmap, width, height, true)
    }
    val localRow = IntArray(width)
    val targetRow = IntArray(width)
    val maskRow = IntArray(width)
    for (y in 0 until height) {
        local.getPixels(localRow, 0, width, 0, y, width, 1)
        target.getPixels(targetRow, 0, width, 0, y, width, 1)
        mask.getPixels(maskRow, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            val raw = ((maskRow[x] ushr 16) and 0xff) / 255f
            val alpha = (if (layer.inverted) 1f - raw else raw) * layer.opacity.coerceIn(0f, 1f)
            targetRow[x] = blendSelectionPixel(localRow[x], targetRow[x], alpha)
        }
        target.setPixels(targetRow, 0, width, 0, y, width, 1)
    }
    if (mask !== layer.bitmap) mask.recycle()
}

private fun blendSelectionPixel(foreground: Int, background: Int, alpha: Float): Int {
    val a = alpha.coerceIn(0f, 1f)
    val inv = 1f - a
    val r = (((foreground ushr 16) and 0xff) * a + ((background ushr 16) and 0xff) * inv).roundToInt().coerceIn(0, 255)
    val g = (((foreground ushr 8) and 0xff) * a + ((background ushr 8) and 0xff) * inv).roundToInt().coerceIn(0, 255)
    val b = ((foreground and 0xff) * a + (background and 0xff) * inv).roundToInt().coerceIn(0, 255)
    return -0x1000000 or (r shl 16) or (g shl 8) or b
}
