package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorHistorySnapshot
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.engineSelection
import com.projectnuke.keplerstudio.editor.newBaseContentToken
import com.projectnuke.keplerstudio.editor.renderEditedPreview
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun EditorViewModel.applyMaskAwareRemaster() {
    if (isShuttingDown()) return
    if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return
    if (RemasterModelSession.activeModel?.id != "edge_masker" || !RemasterModelSession.isModelLoaded) {
        updateUiState { it.copy(message = "Edge Masker 모델 파일과 런타임이 준비된 뒤 사용할 수 있습니다.") }
        return
    }

    val current = prepareForExternalEdit()
    val basePreview = current.originalPreviewBitmap ?: current.previewBitmap
    if (basePreview == null) {
        updateUiState { it.copy(message = "모델 마스크 보조를 적용할 이미지가 없습니다.") }
        return
    }

    var undoSnapshot: EditorHistorySnapshot? = captureCurrentHistorySnapshot()
    if (undoSnapshot == null) {
        updateUiState { it.copy(message = "모델 마스크 보조 준비에 실패했습니다.") }
        return
    }
    var ownedBase: Bitmap? = null
    try {
        ownedBase = basePreview.copyOrThrow()
    } catch (t: Throwable) {
        ownedBase = null
        recycleHistorySnapshot(checkNotNull(undoSnapshot))
        undoSnapshot = null
        updateUiState { it.copy(message = "모델 마스크 보조 준비에 실패했습니다.") }
        return
    }

    val sourcePath = current.sourcePath
    val baseContentToken = current.baseContentToken
    val engines = current.engineSelection()
    val presetLook = current.presetLook
    val quickEffects = current.activeQuickEffects
    val startRevision = current.revision
    val nextRevision = startRevision + 1

    updateUiState {
        it.copy(
            isBusy = true,
            revision = nextRevision,
            message = "Edge Masker로 마스크를 분석하는 중입니다."
        )
    }

    launchManagedEdit { operationToken ->
        var modelMask: Bitmap? = null
        var remasteredOriginal: Bitmap? = null
        var renderedPreview: Bitmap? = null
        var undoSnapshotOwned: EditorHistorySnapshot? = undoSnapshot
        var ownedBaseOwned: Bitmap? = ownedBase
        undoSnapshot = null
        ownedBase = null
        try {
            remasteredOriginal = withContext(Dispatchers.Default) {
                val createdBase = checkNotNull(ownedBaseOwned)
                val mask = RemasterModelSession.createForegroundMask(createdBase) ?: error("Edge Masker 마스크를 생성하지 못했습니다.")
                modelMask = mask
                renderMaskAwareRemaster(
                    basePreview = createdBase,
                    mask = mask,
                    state = current,
                    revision = nextRevision
                )
            }

            renderedPreview = withContext(Dispatchers.Default) {
                renderEditedPreview(
                    basePreview = remasteredOriginal ?: error("missing mask-aware render"),
                    params = EditParams(),
                    engines = engines,
                    revision = nextRevision,
                    look = presetLook,
                    quickEffects = quickEffects
                )
            }

            val adoptionIdentityUnchanged = !isShuttingDown() &&
                uiState.value.sourcePath == sourcePath &&
                uiState.value.baseContentToken == baseContentToken &&
                uiState.value.revision == nextRevision &&
                isManagedEditTokenCurrent(operationToken)
            if (isManagedEditCurrent(operationToken, nextRevision) && adoptionIdentityUnchanged) {
                val adoptedOriginal = remasteredOriginal ?: error("missing mask-aware original")
                val adoptedPreview = renderedPreview ?: error("missing mask-aware preview")
                updateUiStateAndRecycleReplaced {
                    it.copy(
                        params = EditParams(),
                        originalPreviewBitmap = adoptedOriginal,
                        previewBitmap = adoptedPreview,
                        baseBitmapDirty = true,
                        baseContentToken = newBaseContentToken(),
                        isBusy = false,
                        message = "Edge Masker 기반 마스크 보정을 적용했습니다."
                    )
                }
                remasteredOriginal = null
                renderedPreview = null
                markParamsSuccessfullyRendered(EditParams())
                commitUndoSnapshot(checkNotNull(undoSnapshotOwned), clearRedo = true)
                undoSnapshotOwned = null
                persistDraftSnapshot()
            } else if (isManagedEditTokenCurrent(operationToken)) {
                updateUiState { it.copy(isBusy = false) }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            val failureIdentityUnchanged = !isShuttingDown() &&
                uiState.value.sourcePath == sourcePath &&
                uiState.value.baseContentToken == baseContentToken &&
                uiState.value.revision == nextRevision &&
                isManagedEditTokenCurrent(operationToken)
            if (isManagedEditCurrent(operationToken, nextRevision) && failureIdentityUnchanged) {
                updateUiState {
                    it.copy(
                        isBusy = false,
                        message = "Edge Masker 기반 마스크 보정 적용에 실패했습니다: ${t.message}"
                    )
                }
            } else if (isManagedEditTokenCurrent(operationToken)) {
                updateUiState { it.copy(isBusy = false) }
            }
        } finally {
            renderedPreview?.takeIf { !it.isRecycled }?.recycle()
            remasteredOriginal?.takeIf { !it.isRecycled }?.recycle()
            modelMask?.takeIf { !it.isRecycled }?.recycle()
            ownedBaseOwned?.takeIf { !it.isRecycled }?.recycle()
            undoSnapshotOwned?.let(::recycleHistorySnapshot)
        }
    }
}

private fun renderMaskAwareRemaster(
    basePreview: Bitmap,
    mask: Bitmap,
    state: EditorUiState,
    revision: Int
): Bitmap {
    val baseParams = computeMaskAwareBaseParams(basePreview)
    val foregroundParams = baseParams.copy(
        contrast = (baseParams.contrast * 0.60f).coerceIn(-1f, 1f),
        saturation = (baseParams.saturation * 0.70f).coerceIn(-1f, 1f),
        vibrance = (baseParams.vibrance * 0.65f).coerceIn(-1f, 1f),
        clarity = (baseParams.clarity * 0.45f).coerceIn(-1f, 1f),
        dehaze = (baseParams.dehaze * 0.35f).coerceIn(-1f, 1f),
        sharpness = (baseParams.sharpness * 0.55f).coerceIn(0f, 1f),
        noiseReduction = (baseParams.noiseReduction + 0.03f).coerceIn(0f, 1f)
    )
    val backgroundParams = baseParams.copy(
        contrast = (baseParams.contrast + 0.12f).coerceIn(-1f, 1f),
        saturation = (baseParams.saturation + 0.04f).coerceIn(-1f, 1f),
        vibrance = (baseParams.vibrance + 0.08f).coerceIn(-1f, 1f),
        clarity = (baseParams.clarity + 0.12f).coerceIn(-1f, 1f),
        dehaze = (baseParams.dehaze + 0.10f).coerceIn(-1f, 1f),
        sharpness = (baseParams.sharpness + 0.08f).coerceIn(0f, 1f)
    )

    var foreground: Bitmap? = null
    var background: Bitmap? = null
    var resultOwned: Bitmap? = null
    try {
        foreground = renderWithState(basePreview, foregroundParams, state, revision)
        background = renderWithState(basePreview, backgroundParams, state, revision)
        val blended = blendForegroundOverBackground(foreground, background, mask)
        resultOwned = blended
        if (foreground === blended) foreground = null
        if (background === blended) background = null
        return blended
    } catch (t: Throwable) {
        resultOwned?.takeIf { !it.isRecycled }?.recycle()
        resultOwned = null
        background?.takeIf { !it.isRecycled && it !== foreground }?.recycle()
        background = null
        foreground?.takeIf { !it.isRecycled }?.recycle()
        foreground = null
        throw t
    } finally {
        background?.takeIf { !it.isRecycled }?.recycle()
        foreground?.takeIf { !it.isRecycled }?.recycle()
    }
}

private fun renderWithState(
    basePreview: Bitmap,
    params: EditParams,
    state: EditorUiState,
    revision: Int
): Bitmap {
    var out: Bitmap? = null
    try {
        out = basePreview.copyOrThrow(Bitmap.Config.ARGB_8888, true)
        val result = NativePhotoCore.nativeRenderPreviewInPlace(
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
        if (result < 0) {
            out.recycle()
            out = null
            throw IllegalStateException("native mask-aware render failed: code=$result")
        }
        val transferred = out
        out = null
        return transferred
    } catch (t: Throwable) {
        out?.takeIf { !it.isRecycled }?.recycle()
        throw t
    }
}

private fun blendForegroundOverBackground(
    foreground: Bitmap,
    background: Bitmap,
    mask: Bitmap
): Bitmap {
    val width = foreground.width
    val height = foreground.height
    val scaledMask = if (mask.width == width && mask.height == height) {
        mask
    } else {
        Bitmap.createScaledBitmap(mask, width, height, true)
    }

    val output = background
    val fgRow = IntArray(width)
    val bgRow = IntArray(width)
    val prevMaskRow = IntArray(width)
    val currMaskRow = IntArray(width)
    val nextMaskRow = IntArray(width)

    try {
        for (y in 0 until height) {
            foreground.getPixels(fgRow, 0, width, 0, y, width, 1)
            output.getPixels(bgRow, 0, width, 0, y, width, 1)
            scaledMask.getPixels(currMaskRow, 0, width, 0, y, width, 1)
            scaledMask.getPixels(prevMaskRow, 0, width, 0, max(0, y - 1), width, 1)
            scaledMask.getPixels(nextMaskRow, 0, width, 0, min(height - 1, y + 1), width, 1)

            for (x in 0 until width) {
                val a = featheredMaskAlpha(prevMaskRow, currMaskRow, nextMaskRow, x, width)
                bgRow[x] = blendArgb(fgRow[x], bgRow[x], a)
            }
            output.setPixels(bgRow, 0, width, 0, y, width, 1)
        }
    } finally {
        if (scaledMask !== mask) scaledMask.recycle()
    }
    return output
}

private fun featheredMaskAlpha(prev: IntArray, curr: IntArray, next: IntArray, x: Int, width: Int): Float {
    val xm = max(0, x - 1)
    val xp = min(width - 1, x + 1)
    val sum = maskValue(prev[xm]) + maskValue(prev[x]) + maskValue(prev[xp]) +
        maskValue(curr[xm]) + maskValue(curr[x]) + maskValue(curr[xp]) +
        maskValue(next[xm]) + maskValue(next[x]) + maskValue(next[xp])
    return (sum / (9f * 255f)).coerceIn(0f, 1f)
}

private fun maskValue(pixel: Int): Int {
    val r = (pixel ushr 16) and 0xff
    val g = (pixel ushr 8) and 0xff
    val b = pixel and 0xff
    return max(r, max(g, b))
}

private fun blendArgb(foreground: Int, background: Int, alpha: Float): Int {
    val inv = 1f - alpha
    val a = 0xff
    val r = (((foreground ushr 16) and 0xff) * alpha + ((background ushr 16) and 0xff) * inv).roundToInt().coerceIn(0, 255)
    val g = (((foreground ushr 8) and 0xff) * alpha + ((background ushr 8) and 0xff) * inv).roundToInt().coerceIn(0, 255)
    val b = ((foreground and 0xff) * alpha + (background and 0xff) * inv).roundToInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun computeMaskAwareBaseParams(bitmap: Bitmap): EditParams {
    val stats = sampleStats(bitmap)
    val exposure = ((0.48f - stats.mean) * 1.20f).coerceIn(-0.55f, 0.55f)
    val contrast = ((0.56f - stats.range) * 0.55f).coerceIn(-0.18f, 0.34f)
    val shadows = ((0.28f - stats.dark) * 0.72f).coerceIn(-0.12f, 0.36f)
    val highlights = ((stats.bright - 0.78f) * 0.70f).coerceIn(-0.18f, 0.34f)
    val vibrance = ((0.32f - stats.chroma) * 0.62f).coerceIn(0f, 0.24f)
    val dehaze = if (stats.range < 0.48f) 0.08f else 0.02f
    val clarity = if (stats.range < 0.52f) 0.12f else 0.07f
    val noise = if (stats.mean < 0.36f) 0.20f else 0.08f
    return EditParams(
        exposure = exposure,
        contrast = contrast,
        shadows = shadows,
        highlights = highlights,
        whites = 0.06f,
        blacks = -0.05f,
        temperature = 0f,
        tint = 0f,
        saturation = 0.02f,
        vibrance = vibrance,
        clarity = clarity,
        dehaze = dehaze,
        sharpness = 0.14f,
        noiseReduction = noise
    )
}

private data class QuickStats(
    val mean: Float,
    val dark: Float,
    val bright: Float,
    val range: Float,
    val chroma: Float
)

private fun sampleStats(bitmap: Bitmap): QuickStats {
    val step = max(1, max(bitmap.width, bitmap.height) / 360)
    val row = IntArray(bitmap.width)
    var count = 0
    var sum = 0f
    var dark = 1f
    var bright = 0f
    var chromaSum = 0f
    var y = 0
    while (y < bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        var x = 0
        while (x < bitmap.width) {
            val pixel = row[x]
            val r = ((pixel ushr 16) and 0xff) / 255f
            val g = ((pixel ushr 8) and 0xff) / 255f
            val b = (pixel and 0xff) / 255f
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            dark = min(dark, luma)
            bright = max(bright, luma)
            sum += luma
            chromaSum += (maxC - minC).coerceIn(0f, 1f)
            count += 1
            x += step
        }
        y += step
    }
    if (count <= 0) return QuickStats(0.5f, 0.05f, 0.95f, 0.75f, 0.1f)
    return QuickStats(
        mean = sum / count,
        dark = dark,
        bright = bright,
        range = (bright - dark).coerceIn(0f, 1f),
        chroma = chromaSum / count
    )
}
