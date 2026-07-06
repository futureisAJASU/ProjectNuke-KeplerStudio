package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.SelectionLayerKind
import com.projectnuke.keplerstudio.editor.SelectionPaintMode
import com.projectnuke.keplerstudio.editor.SelectionPaintSettings
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

fun EditorViewModel.addSubjectSelectionFromEdgeModel() {
    val state = uiState.value
    val base = state.originalPreviewBitmap ?: state.previewBitmap
    if (base == null) {
        updateUiState { it.copy(message = "마스크를 만들 이미지가 없습니다.") }
        return
    }
    if (RemasterModelSession.activeModel?.id != "edge_masker" || !RemasterModelSession.isModelLoaded) {
        updateUiState { it.copy(message = "Edge Masker 모델을 먼저 로드해 주세요.") }
        return
    }

    recordUserEditForUndo(clearRedo = true)
    updateUiState { it.copy(isBusy = true, message = "피사체 마스크를 생성하는 중입니다.") }
    viewModelScope.launch {
        try {
            val layer = withContext(Dispatchers.Default) {
                val mask = RemasterModelSession.createForegroundMask(base)
                    ?: error("마스크를 생성하지 못했습니다.")
                SelectionLayer(
                    id = newSelectionId(),
                    name = "피사체 마스크",
                    kind = SelectionLayerKind.Subject,
                    bitmap = mask.copy(Bitmap.Config.ARGB_8888, true)
                )
            }
            updateUiState { current ->
                current.copy(
                    isBusy = false,
                    selectionLayers = current.selectionLayers + layer,
                    activeSelectionLayerId = layer.id,
                    message = "피사체 마스크를 추가했습니다."
                )
            }
            persistDraftSnapshot()
        } catch (t: Throwable) {
            updateUiState { it.copy(isBusy = false, message = "피사체 마스크 생성에 실패했습니다: ${t.message}") }
        }
    }
}

fun EditorViewModel.createBrushSelection() {
    val state = uiState.value
    val base = state.originalPreviewBitmap ?: state.previewBitmap
    if (base == null) {
        updateUiState { it.copy(message = "브러시 마스크를 만들 이미지가 없습니다.") }
        return
    }
    recordUserEditForUndo(clearRedo = true)
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
    updateUiState { it.copy(activeSelectionLayerId = id, message = "마스크를 선택했습니다.") }
}

fun EditorViewModel.deleteActiveSelectionLayer() {
    val activeId = uiState.value.activeSelectionLayerId ?: return
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
    val activeId = uiState.value.activeSelectionLayerId ?: run {
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
    val activeId = uiState.value.activeSelectionLayerId ?: run {
        updateUiState { it.copy(message = "먼저 마스크를 선택해 주세요.") }
        return
    }
    recordUserEditForUndo(clearRedo = true)
    updateUiState { current ->
        current.copy(
            selectionLayers = current.selectionLayers.map { layer ->
                if (layer.id == activeId) {
                    layer.bitmap.eraseColor(0xFF000000.toInt())
                    layer
                } else {
                    layer
                }
            },
            message = "마스크를 비웠습니다."
        )
    }
    persistDraftSnapshot()
}

fun EditorViewModel.updateSelectionPaintSettings(transform: (SelectionPaintSettings) -> SelectionPaintSettings) {
    updateUiState { it.copy(selectionPaintSettings = transform(it.selectionPaintSettings)) }
}

fun EditorViewModel.paintActiveSelectionAt(maskX: Float, maskY: Float) {
    val activeId = uiState.value.activeSelectionLayerId ?: run {
        updateUiState { it.copy(message = "먼저 마스크를 선택해 주세요.") }
        return
    }
    updateUiState { current ->
        var changed = false
        val nextLayers = current.selectionLayers.map { layer ->
            if (layer.id == activeId) {
                applyPaintStroke(layer.bitmap, maskX, maskY, current.selectionPaintSettings)
                changed = true
            }
            layer
        }
        current.copy(
            selectionLayers = nextLayers,
            revision = current.revision + if (changed) 1 else 0,
            message = if (changed) "마스크를 수정했습니다." else current.message
        )
    }
}

fun EditorViewModel.updateActiveSelectionParams(transform: (EditParams) -> EditParams) {
    val activeId = uiState.value.activeSelectionLayerId ?: run {
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
    val state = uiState.value
    val base = state.originalPreviewBitmap ?: state.previewBitmap
    val layer = state.selectionLayers.firstOrNull { it.id == state.activeSelectionLayerId }
    if (base == null || layer == null) {
        updateUiState { it.copy(message = "적용할 마스크 또는 이미지가 없습니다.") }
        return
    }
    recordUserEditForUndo(clearRedo = true)
    val nextRevision = state.revision + 1
    updateUiState { it.copy(isBusy = true, revision = nextRevision, message = "마스크 보정을 적용하는 중입니다.") }
    viewModelScope.launch {
        try {
            val rendered = withContext(Dispatchers.Default) {
                renderSelectionLocalEdit(base, state, layer, nextRevision)
            }
            if (uiState.value.revision == nextRevision) {
                updateUiState {
                    it.copy(previewBitmap = rendered, isBusy = false, message = "선택한 마스크 보정을 적용했습니다.")
                }
                persistDraftSnapshot()
            } else {
                rendered.recycle()
            }
        } catch (t: Throwable) {
            updateUiState { it.copy(isBusy = false, message = "마스크 보정 적용에 실패했습니다: ${t.message}") }
        }
    }
}

private fun applyPaintStroke(bitmap: Bitmap, cx: Float, cy: Float, settings: SelectionPaintSettings) {
    val radius = settings.sizePx.coerceAtLeast(1f) * 0.5f
    val left = (cx - radius).toInt().coerceIn(0, bitmap.width - 1)
    val top = (cy - radius).toInt().coerceIn(0, bitmap.height - 1)
    val right = (cx + radius).toInt().coerceIn(0, bitmap.width - 1)
    val bottom = (cy + radius).toInt().coerceIn(0, bitmap.height - 1)
    val width = right - left + 1
    if (width <= 0 || bottom < top) return

    val feather = settings.feather.coerceIn(0f, 0.98f)
    val hardRadius = radius * (1f - feather)
    val row = IntArray(width)
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
            row[i] = -0x1000000 or (next shl 16) or (next shl 8) or next
        }
        bitmap.setPixels(row, 0, width, left, y, width, 1)
    }
}

private fun renderSelectionLocalEdit(base: Bitmap, state: EditorUiState, layer: SelectionLayer, revision: Int): Bitmap {
    val global = renderWithParams(base, state.params, state, revision)
    val local = renderWithParams(base, mergeParams(state.params, layer.localParams), state, revision)
    return blendWithSelectionMask(local, global, layer)
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

private fun blendWithSelectionMask(local: Bitmap, global: Bitmap, layer: SelectionLayer): Bitmap {
    val width = global.width
    val height = global.height
    val mask = if (layer.bitmap.width == width && layer.bitmap.height == height) {
        layer.bitmap
    } else {
        Bitmap.createScaledBitmap(layer.bitmap, width, height, true)
    }
    val localRow = IntArray(width)
    val globalRow = IntArray(width)
    val maskRow = IntArray(width)
    for (y in 0 until height) {
        local.getPixels(localRow, 0, width, 0, y, width, 1)
        global.getPixels(globalRow, 0, width, 0, y, width, 1)
        mask.getPixels(maskRow, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            val raw = ((maskRow[x] ushr 16) and 0xff) / 255f
            val a = (if (layer.inverted) 1f - raw else raw) * layer.opacity.coerceIn(0f, 1f)
            globalRow[x] = blendArgb(localRow[x], globalRow[x], a)
        }
        global.setPixels(globalRow, 0, width, 0, y, width, 1)
    }
    if (mask !== layer.bitmap) mask.recycle()
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

private fun newSelectionId(): String = "sel_" + UUID.randomUUID().toString().take(8)
