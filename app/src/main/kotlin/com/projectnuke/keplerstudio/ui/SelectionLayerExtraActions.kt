package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.SelectionLayerKind
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

fun EditorViewModel.toggleSelectionOverlay() {
    editorFlowExtra().update {
        it.copy(
            showSelectionOverlay = !it.showSelectionOverlay,
            message = if (it.showSelectionOverlay) "마스크 표시를 해제했습니다" else "마스크를 표시합니다"
        )
    }
}

fun EditorViewModel.duplicateActiveSelectionLayer() {
    editorFlowExtra().update { current ->
        val active = current.selectionLayers.firstOrNull { it.id == current.activeSelectionLayerId }
            ?: return@update current.copy(message = "복제할 마스크를 선택해 주세요")
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
}

fun EditorViewModel.createBackgroundSelectionFromActive() {
    editorFlowExtra().update { current ->
        val active = current.selectionLayers.firstOrNull { it.id == current.activeSelectionLayerId }
            ?: return@update current.copy(message = "배경으로 변환할 마스크를 선택해 주세요")
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
}

private fun newExtraSelectionId(): String = "sel_" + UUID.randomUUID().toString().take(8)

@Suppress("UNCHECKED_CAST")
private fun EditorViewModel.editorFlowExtra(): MutableStateFlow<EditorUiState> {
    val field = EditorViewModel::class.java.getDeclaredField("_uiState")
    field.isAccessible = true
    return field.get(this) as MutableStateFlow<EditorUiState>
}
