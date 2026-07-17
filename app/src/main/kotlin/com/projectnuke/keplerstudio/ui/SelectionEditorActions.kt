package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorHistorySnapshot
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.newBaseContentToken
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.engineSelection
import com.projectnuke.keplerstudio.editor.renderEditedPreview
import com.projectnuke.keplerstudio.editor.SelectionLayerKind
import com.projectnuke.keplerstudio.editor.SelectionPaintMode
import com.projectnuke.keplerstudio.editor.SelectionPaintSettings
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

fun EditorViewModel.addSubjectSelectionFromEdgeModel() {
    invalidateSelectionPreview()
    val state = prepareForExternalEdit()
    val base = state.originalPreviewBitmap ?: state.previewBitmap
    val sourcePath = state.sourcePath
    val sourceRevision = state.revision
    val busyMessage = "\uD53C\uC0AC\uCCB4 \uB9C8\uC2A4\uD06C\uB97C \uC0DD\uC131\uD558\uB294 \uC911\uC785\uB2C8\uB2E4."
    if (base == null) {
        updateUiState { it.copy(message = "\uB9C8\uC2A4\uD06C\uB97C \uB9CC\uB4E4 \uC774\uBBF8\uC9C0\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4.") }
        return
    }
    if (RemasterModelSession.activeModel?.id != "edge_masker" || !RemasterModelSession.isModelLoaded) {
        updateUiState { it.copy(message = "Edge Masker \uBAA8\uB378\uC744 \uBA3C\uC800 \uB85C\uB4DC\uD574 \uC8FC\uC138\uC694.") }
        return
    }

    var undoSnapshot: EditorHistorySnapshot? = captureCurrentHistorySnapshot()
    if (undoSnapshot == null) {
        updateUiState { it.copy(message = "\uD3C9\uC9D1 \uAE30\uB85D\uC744 \uC800\uC7A5\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4. \uD3B8\uC9D1 \uD654\uBA74\uC744 \uC720\uC9C0\uD569\uB2C8\uB2E4.") }
        return
    }
    val ownedBase = try {
        base.copyOrThrow(mutable = false)
    } catch (t: Throwable) {
        recycleHistorySnapshot(undoSnapshot)
        updateUiState { it.copy(message = "마스크 입력 이미지를 준비하지 못했습니다.") }
        return
    }
    updateUiState { it.copy(isBusy = true, message = busyMessage) }
    launchManagedEdit { operationToken ->
        var pendingLayerBitmap: Bitmap? = null
        try {
            val layer = withContext(Dispatchers.Default) {
                val mask = RemasterModelSession.createForegroundMask(ownedBase)
                    ?: error("\uB9C8\uC2A4\uD06C\uB97C \uC0DD\uC131\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.")
                val ownedMask = try {
                    mask.copyOrThrow(Bitmap.Config.ARGB_8888, true)
                } finally {
                    mask.recycle()
                }
                if (!ownedMask.hasForegroundPixel()) {
                    ownedMask.recycle()
                    return@withContext null
                }
                pendingLayerBitmap = ownedMask
                SelectionLayer(
                    id = newSelectionId(),
                    name = "\uD53C\uC0AC\uCCB4 \uB9C8\uC2A4\uD06C",
                    kind = SelectionLayerKind.Subject,
                    bitmap = ownedMask
                )
            } ?: run {
                pendingLayerBitmap?.recycle()
                pendingLayerBitmap = null
                undoSnapshot?.let(::recycleHistorySnapshot)
                undoSnapshot = null
                val current = uiState.value
                if (isManagedEditCurrent(operationToken, sourceRevision) && current.sourcePath == sourcePath && current.revision == sourceRevision) {
                    updateUiState { it.copy(isBusy = false, message = "\uD53C\uC0AC\uCCB4\uB97C \uAC10\uC9C0\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.") }
                }
                return@launchManagedEdit
            }
            var applied = false
            updateUiState { current ->
                if (!isManagedEditCurrent(operationToken, sourceRevision) || current.sourcePath != sourcePath || current.revision != sourceRevision) {
                    current
                } else {
                    applied = true
                    current.copy(
                        isBusy = false,
                        selectionLayers = current.selectionLayers + layer,
                        activeSelectionLayerId = layer.id,
                        message = "\uD53C\uC0AC\uCCB4 \uB9C8\uC2A4\uD06C\uB97C \uCD94\uAC00\uD588\uC2B5\uB2C8\uB2E4."
                    )
                }
            }
            if (!applied) {
                pendingLayerBitmap?.recycle()
                pendingLayerBitmap = null
                undoSnapshot?.let(::recycleHistorySnapshot)
                undoSnapshot = null
                val current = uiState.value
                return@launchManagedEdit
            }
            commitUndoSnapshot(checkNotNull(undoSnapshot), clearRedo = true)
            undoSnapshot = null
            pendingLayerBitmap = null
            persistDraftSnapshot()
        } catch (ce: CancellationException) {
            pendingLayerBitmap?.recycle()
            undoSnapshot?.let(::recycleHistorySnapshot)
            undoSnapshot = null
            throw ce
        } catch (t: Throwable) {
            pendingLayerBitmap?.recycle()
            undoSnapshot?.let(::recycleHistorySnapshot)
            undoSnapshot = null
            val current = uiState.value
            if (isManagedEditCurrent(operationToken, sourceRevision) && current.sourcePath == sourcePath && current.revision == sourceRevision) {
                updateUiState { it.copy(isBusy = false, message = "\uD53C\uC0AC\uCCB4 \uB9C8\uC2A4\uD06C \uC0DD\uC131\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4: ${t.message}") }
            }
        } finally {
            ownedBase.recycle()
        }
    }
}

fun EditorViewModel.createBrushSelection() {
    invalidateSelectionPreview()
    val state = prepareForExternalEdit()
    val base = state.originalPreviewBitmap ?: state.previewBitmap
    if (base == null) {
        updateUiState { it.copy(message = "브러시 마스크를 만들 이미지가 없습니다.") }
        return
    }
    val layer = SelectionLayer(
        id = newSelectionId(),
        name = "브러시 마스크 ${state.selectionLayers.count { it.kind == SelectionLayerKind.Brush } + 1}",
        kind = SelectionLayerKind.Brush,
        bitmap = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
    )
    updateUiState {
        it.copy(
            selectionLayers = it.selectionLayers + layer,
            activeSelectionLayerId = layer.id,
            message = "브러시 마스크를 만들었습니다."
        )
    }
    persistDraftSnapshot()
}

fun EditorViewModel.selectSelectionLayer(id: String) {
    invalidateSelectionPreview()
    updateUiState { it.copy(activeSelectionLayerId = id, message = "마스크를 선택했습니다.") }
}

fun EditorViewModel.deleteActiveSelectionLayer() {
    invalidateSelectionPreview()
    val state = prepareForExternalEdit()
    val activeId = state.activeSelectionLayerId ?: return
    recordUserEditForUndo(clearRedo = true)
    updateUiState { current ->
        val nextLayers = current.selectionLayers.filterNot { it.id == activeId }
        current.copy(
            selectionLayers = nextLayers,
            activeSelectionLayerId = nextLayers.lastOrNull()?.id,
            message = "선택한 마스크를 삭제했습니다."
        )
    }
    persistDraftSnapshot()
}

fun EditorViewModel.invertActiveSelectionLayer() {
    invalidateSelectionPreview()
    val state = prepareForExternalEdit()
    val activeId = state.activeSelectionLayerId ?: run {
        updateUiState { it.copy(message = "먼저 마스크를 선택해 주세요.") }
        return
    }
    recordUserEditForUndo(clearRedo = true)
    updateUiState { current ->
        current.copy(
            selectionLayers = current.selectionLayers.map { layer ->
                if (layer.id == activeId) layer.copy(inverted = !layer.inverted) else layer
            },
            message = "마스크 반전을 전환했습니다."
        )
    }
    persistDraftSnapshot()
}

fun EditorViewModel.clearActiveSelectionLayer() {
    invalidateSelectionPreview()
    val state = prepareForExternalEdit()
    val activeId = state.activeSelectionLayerId ?: run {
        updateUiState { it.copy(message = "\uBA3C\uC800 \uB9C8\uC2A4\uD06C\uB97C \uC120\uD0DD\uD574 \uC8FC\uC138\uC694.") }
        return
    }
    recordUserEditForUndo(clearRedo = true)
    updateUiState { current ->
        var changed = false
        current.copy(
            selectionLayers = current.selectionLayers.map { layer ->
                if (layer.id == activeId) {
                    layer.bitmap.eraseColor(0xFF000000.toInt())
                    changed = true
                    layer
                } else {
                    layer
                }
            },
            revision = current.revision + if (changed) 1 else 0,
            message = "\uB9C8\uC2A4\uD06C\uB97C \uBE44\uC6E0\uC2B5\uB2C8\uB2E4."
        )
    }
    persistDraftSnapshot()
}

fun EditorViewModel.updateSelectionPaintSettings(transform: (SelectionPaintSettings) -> SelectionPaintSettings) {
    updateUiState { it.copy(selectionPaintSettings = transform(it.selectionPaintSettings)) }
}

fun EditorViewModel.paintActiveSelectionAt(maskX: Float, maskY: Float) {
    if (!hasActiveBrushStroke()) return
    val state = uiState.value
    val activeId = state.activeSelectionLayerId
    val layer = state.selectionLayers.firstOrNull { it.id == activeId }
    if (activeId == null || layer == null || !isBrushStrokeCurrent(activeId)) {
        cancelBrushStroke()
        return
    }
    val painted = applyPaintStroke(layer.bitmap, maskX, maskY, state.selectionPaintSettings)
    if (painted) {
        markBrushChanged(true)
        nextBrushPreviewEpoch()
    }
}

fun EditorViewModel.updateActiveSelectionParams(transform: (EditParams) -> EditParams) {
    invalidateSelectionPreview()
    val state = prepareForExternalEdit()
    val activeId = state.activeSelectionLayerId ?: run {
        updateUiState { it.copy(message = "먼저 마스크를 선택해 주세요.") }
        return
    }
    updateUiState { current ->
        current.copy(
            selectionLayers = current.selectionLayers.map { layer ->
                if (layer.id == activeId) layer.copy(localParams = transform(layer.localParams)) else layer
            },
            message = "마스크 보정값을 변경했습니다."
        )
    }
}

fun EditorViewModel.applyActiveSelectionLocalEdit() {
    if (isShuttingDown()) return
    if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return
    invalidateSelectionPreview()
    val state = prepareForExternalEdit()
    val base = state.originalPreviewBitmap ?: state.previewBitmap
    val layer = state.selectionLayers.firstOrNull { it.id == state.activeSelectionLayerId }
    if (base == null || layer == null) {
        updateUiState { it.copy(message = "적용할 마스크 또는 이미지가 없습니다.") }
        return
    }
    var undoSnapshot: EditorHistorySnapshot? = captureCurrentHistorySnapshot() ?: return
    val nextRevision = state.revision + 1
    updateUiState { it.copy(isBusy = true, revision = nextRevision, message = "마스크 보정을 적용하는 중입니다.") }
    launchManagedEdit { operationToken ->
        var renderedOriginal: Bitmap? = null
        var renderedPreview: Bitmap? = null
        try {
            renderedOriginal = withContext(Dispatchers.Default) {
                renderSelectionLocalEdit(base, state, layer, nextRevision)
            }
            renderedPreview = withContext(Dispatchers.Default) {
                renderEditedPreview(
                    basePreview = renderedOriginal ?: error("missing selection render"),
                    params = EditParams(),
                    engines = state.engineSelection(),
                    revision = nextRevision,
                    look = state.presetLook,
                    quickEffects = state.activeQuickEffects
                )
            }
            if (isManagedEditCurrent(operationToken, nextRevision)) {
                val adoptedOriginal = renderedOriginal ?: error("missing selection original")
                val adoptedPreview = renderedPreview ?: error("missing selection preview")
                updateUiStateAndRecycleReplaced {
                    it.copy(
                        // The selection composite is baked into the base bitmap; neutral params avoid export double-application.
                        params = EditParams(),
                        originalPreviewBitmap = adoptedOriginal,
                        previewBitmap = adoptedPreview,
                        baseBitmapDirty = true,
                        baseContentToken = newBaseContentToken(),
                        isBusy = false,
                        message = "선택한 마스크 보정을 적용했습니다."
                    )
                }
                commitUndoSnapshot(checkNotNull(undoSnapshot), clearRedo = true)
                undoSnapshot = null
                markParamsSuccessfullyRendered(EditParams())
                renderedOriginal = null
                renderedPreview = null
                persistDraftSnapshot()
            } else {
                renderedOriginal?.recycle()
                renderedOriginal = null
                renderedPreview?.recycle()
                renderedPreview = null
            }
        } catch (ce: CancellationException) {
            renderedOriginal?.recycle()
            renderedPreview?.recycle()
            throw ce
        } catch (t: Throwable) {
            renderedOriginal?.recycle()
            renderedPreview?.recycle()
            if (isManagedEditCurrent(operationToken, nextRevision)) {
                updateUiState { it.copy(isBusy = false, message = "마스크 보정 적용에 실패했습니다: ${t.message}") }
            }
        } finally {
            undoSnapshot?.let(::recycleHistorySnapshot)
        }
    }
}

private fun applyPaintStroke(bitmap: Bitmap, cx: Float, cy: Float, settings: SelectionPaintSettings): Boolean {
    val radius = settings.sizePx.coerceAtLeast(1f) * 0.5f
    val left = (cx - radius).toInt().coerceIn(0, bitmap.width - 1)
    val top = (cy - radius).toInt().coerceIn(0, bitmap.height - 1)
    val right = (cx + radius).toInt().coerceIn(0, bitmap.width - 1)
    val bottom = (cy + radius).toInt().coerceIn(0, bitmap.height - 1)
    val width = right - left + 1
    if (width <= 0 || bottom < top) return false

    val feather = settings.feather.coerceIn(0f, 0.98f)
    val hardRadius = radius * (1f - feather)
    val row = IntArray(width)
    var changed = false
    for (y in top..bottom) {
        bitmap.getPixels(row, 0, width, left, y, width, 1)
        for (i in 0 until width) {
            val x = left + i
            val dist = sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy))
            if (dist > radius) continue
            val fade = if (dist <= hardRadius) 1f else {
                val t = ((dist - hardRadius) / (radius - hardRadius).coerceAtLeast(1f)).coerceIn(0f, 1f)
                1f - t * t * (3f - 2f * t)
            }
            val old = (row[i] ushr 16) and 0xff
            val delta = (255f * settings.strength.coerceIn(0f, 1f) * fade).roundToInt()
            val next = when (settings.mode) {
                SelectionPaintMode.Add -> (old + delta).coerceIn(0, 255)
                SelectionPaintMode.Remove -> (old - delta).coerceIn(0, 255)
            }
            if (next != old) changed = true
            row[i] = -0x1000000 or (next shl 16) or (next shl 8) or next
        }
        bitmap.setPixels(row, 0, width, left, y, width, 1)
    }
    return changed
}

private fun renderSelectionLocalEdit(base: Bitmap, state: EditorUiState, layer: SelectionLayer, revision: Int): Bitmap {
    var global: Bitmap? = null
    var local: Bitmap? = null
    try {
        global = renderWithParams(base, state.params, state, revision)
        local = renderWithParams(base, mergeParams(state.params, layer.localParams), state, revision)
        return blendWithSelectionMask(local, global, layer)
    } catch (t: Throwable) {
        global?.recycle()
        local?.recycle()
        throw t
    }
}

private fun mergeParams(base: EditParams, local: EditParams): EditParams = EditParams(
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

private fun renderWithParams(base: Bitmap, params: EditParams, state: EditorUiState, revision: Int): Bitmap {
    val out = base.copyOrThrow(Bitmap.Config.ARGB_8888, true)
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
        throw IllegalStateException("native selection render failed: code=$result")
    }
    return out
}

private fun blendWithSelectionMask(local: Bitmap, global: Bitmap, layer: SelectionLayer): Bitmap {
    val width = global.width
    val height = global.height
    val scaledMask = if (layer.bitmap.width == width && layer.bitmap.height == height) {
        layer.bitmap
    } else {
        Bitmap.createScaledBitmap(layer.bitmap, width, height, true)
    }
    val localRow = IntArray(width)
    val globalRow = IntArray(width)
    val maskRow = IntArray(width)
    try {
        for (y in 0 until height) {
            local.getPixels(localRow, 0, width, 0, y, width, 1)
            global.getPixels(globalRow, 0, width, 0, y, width, 1)
            scaledMask.getPixels(maskRow, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val raw = ((maskRow[x] ushr 16) and 0xff) / 255f
                val a = (if (layer.inverted) 1f - raw else raw) * layer.opacity.coerceIn(0f, 1f)
                globalRow[x] = blendArgb(localRow[x], globalRow[x], a)
            }
            global.setPixels(globalRow, 0, width, 0, y, width, 1)
        }
    } finally {
        if (scaledMask !== layer.bitmap) scaledMask.recycle()
    }
    local.recycle()
    return global
}

private fun blendArgb(foreground: Int, background: Int, alpha: Float): Int {
    val inv = 1f - alpha.coerceIn(0f, 1f)
    val a = 0xff
    val r = (((foreground ushr 16) and 0xff) * alpha + ((background ushr 16) and 0xff) * inv).roundToInt().coerceIn(0, 255)
    val g = (((foreground ushr 8) and 0xff) * alpha + ((background ushr 8) and 0xff) * inv).roundToInt().coerceIn(0, 255)
    val b = ((foreground and 0xff) * alpha + (background and 0xff) * inv).roundToInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun Bitmap.hasForegroundPixel(): Boolean {
    val threshold = 8
    val row = IntArray(width)
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        for (pixel in row) {
            val red = (pixel ushr 16) and 0xff
            if (red > threshold) return true
        }
    }
    return false
}

private fun newSelectionId(): String = "sel_" + UUID.randomUUID().toString().take(8)
