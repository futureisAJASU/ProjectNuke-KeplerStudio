package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.SelectionLayerKind
import java.util.UUID

fun EditorViewModel.toggleSelectionOverlay() {
    updateUiState {
        it.copy(
            showSelectionOverlay = !it.showSelectionOverlay,
            message = if (it.showSelectionOverlay) "마스크 오버레이를 숨겼습니다" else "마스크 오버레이를 표시합니다"
        )
    }
}

fun EditorViewModel.duplicateActiveSelectionLayer() {
    val state = prepareForExternalEdit()
    val active = state.selectionLayers.firstOrNull { it.id == state.activeSelectionLayerId } ?: run {
        updateUiState { it.copy(message = "복제할 마스크를 선택해 주세요") }
        return
    }
    recordUserEditForUndo(clearRedo = true)
    updateUiState { current ->
        val copy = active.copy(
            id = newExtraSelectionId(),
            name = "${active.name} 복사본",
            bitmap = active.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        )
        current.copy(
            selectionLayers = current.selectionLayers + copy,
            activeSelectionLayerId = copy.id,
            message = "마스크를 복제했습니다"
        )
    }
    persistDraftSnapshot()
}

fun EditorViewModel.createBackgroundSelectionFromActive() {
    val state = prepareForExternalEdit()
    val active = state.selectionLayers.firstOrNull { it.id == state.activeSelectionLayerId } ?: run {
        updateUiState { it.copy(message = "배경으로 변환할 마스크를 선택해 주세요") }
        return
    }
    recordUserEditForUndo(clearRedo = true)
    updateUiState { current ->
        val layer = SelectionLayer(
            id = newExtraSelectionId(),
            name = "배경 마스크",
            kind = SelectionLayerKind.Background,
            bitmap = active.bitmap.copy(Bitmap.Config.ARGB_8888, true),
            enabled = active.enabled,
            inverted = !active.inverted,
            opacity = active.opacity,
            localParams = active.localParams
        )
        current.copy(
            selectionLayers = current.selectionLayers + layer,
            activeSelectionLayerId = layer.id,
            message = "선택한 마스크를 기준으로 배경 마스크를 만들었습니다"
        )
    }
    persistDraftSnapshot()
}

private fun newExtraSelectionId(): String = "sel_" + UUID.randomUUID().toString().take(8)
