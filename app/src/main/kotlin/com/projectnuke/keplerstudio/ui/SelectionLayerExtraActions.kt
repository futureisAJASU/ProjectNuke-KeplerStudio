package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.BitmapAllocationRejectedException
import com.projectnuke.keplerstudio.editor.MemoryRetryAction
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.SelectionLayerKind
import java.util.UUID

fun EditorViewModel.toggleSelectionOverlay() {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    updateUiState {
        it.copy(
            showSelectionOverlay = !it.showSelectionOverlay,
            message = if (it.showSelectionOverlay) "마스크 오버레이를 숨겼습니다" else "마스크 오버레이를 표시합니다"
        )
    }
}

fun EditorViewModel.duplicateActiveSelectionLayer() {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    invalidateSelectionPreview()
    val state = prepareForExternalEdit()
    val active = state.selectionLayers.firstOrNull { it.id == state.activeSelectionLayerId } ?: run {
        updateUiState { it.copy(message = "복제할 마스크를 선택해 주세요") }
        return
    }
    val copy = try {
        active.copy(
            id = newExtraSelectionId(),
            name = "${active.name} 복사본",
            bitmap = active.bitmap.copyOrThrow(Bitmap.Config.ARGB_8888, true)
        )
    } catch (t: Throwable) {
        updateUiState { it.copy(message = if (t is BitmapAllocationRejectedException) "메모리가 부족하여 마스크를 복제하지 못했습니다." else "마스크 복제에 실패했습니다.") }
        if (t is BitmapAllocationRejectedException) requestAllocationRecovery(MemoryRetryAction.DuplicateSelection, t.requiredBytes)
        return
    }
    val adopted = applySynchronousEditWithHistory { current ->
        current.copy(
            selectionLayers = current.selectionLayers + copy,
            activeSelectionLayerId = copy.id,
            message = "마스크를 복제했습니다"
        )
    }
    if (!adopted) {
        if (!copy.bitmap.isRecycled) copy.bitmap.recycle()
        return
    }
    markMemoryRetrySucceeded(MemoryRetryAction.DuplicateSelection)
    persistDraftSnapshot()
}

fun EditorViewModel.createBackgroundSelectionFromActive() {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    invalidateSelectionPreview()
    val state = prepareForExternalEdit()
    val active = state.selectionLayers.firstOrNull { it.id == state.activeSelectionLayerId } ?: run {
        updateUiState { it.copy(message = "배경으로 변환할 마스크를 선택해 주세요") }
        return
    }
    val layer = try {
        SelectionLayer(
            id = newExtraSelectionId(),
            name = "배경 마스크",
            kind = SelectionLayerKind.Background,
            bitmap = active.bitmap.copyOrThrow(Bitmap.Config.ARGB_8888, true),
            enabled = active.enabled,
            inverted = !active.inverted,
            opacity = active.opacity,
            localParams = active.localParams
        )
    } catch (t: Throwable) {
        updateUiState { it.copy(message = if (t is BitmapAllocationRejectedException) "메모리가 부족하여 배경 마스크를 만들지 못했습니다." else "배경 마스크 생성에 실패했습니다.") }
        if (t is BitmapAllocationRejectedException) requestAllocationRecovery(MemoryRetryAction.BackgroundSelection, t.requiredBytes)
        return
    }
    val adopted = applySynchronousEditWithHistory { current ->
        current.copy(
            selectionLayers = current.selectionLayers + layer,
            activeSelectionLayerId = layer.id,
            message = "선택한 마스크를 기준으로 배경 마스크를 만들었습니다"
        )
    }
    if (!adopted) {
        if (!layer.bitmap.isRecycled) layer.bitmap.recycle()
        return
    }
    markMemoryRetrySucceeded(MemoryRetryAction.BackgroundSelection)
    persistDraftSnapshot()
}

private fun newExtraSelectionId(): String = "sel_" + UUID.randomUUID().toString().take(8)
