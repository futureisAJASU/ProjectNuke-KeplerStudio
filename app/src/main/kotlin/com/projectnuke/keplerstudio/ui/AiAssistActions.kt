package com.projectnuke.keplerstudio.ui

import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.analyzeAutoRouterV0
import com.projectnuke.keplerstudio.editor.applyDaySunFlareGuardV0
import com.projectnuke.keplerstudio.editor.applyFlareGuardV0
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.runAutoRouterV0Analysis() {
    val bitmap = uiState.value.originalPreviewBitmap ?: uiState.value.previewBitmap
    if (bitmap == null) {
        editorFlowAi().update { it.copy(message = "분석할 이미지가 없습니다") }
        return
    }
    val scores = analyzeAutoRouterV0(bitmap)
    val labels = scores.topLabels().joinToString(", ").ifBlank { "normal" }
    editorFlowAi().update {
        it.copy(message = "Auto Router v0 추천: $labels")
    }
}

fun EditorViewModel.applyFlareGuardV0Preview() {
    val state = uiState.value
    val base = state.previewBitmap ?: state.originalPreviewBitmap
    if (base == null) {
        editorFlowAi().update { it.copy(message = "플레어 완화를 적용할 이미지가 없습니다") }
        return
    }
    val nextRevision = state.revision + 1
    editorFlowAi().update {
        it.copy(
            isBusy = true,
            revision = nextRevision,
            message = "플레어 완화를 적용하는 중입니다"
        )
    }
    viewModelScope.launch {
        try {
            val rendered = withContext(Dispatchers.Default) {
                applyFlareGuardV0(base, strength = 0.35f)
            }
            if (uiState.value.revision == nextRevision) {
                editorFlowAi().update {
                    it.copy(
                        previewBitmap = rendered,
                        isBusy = false,
                        message = "플레어 완화를 적용했습니다"
                    )
                }
            } else {
                rendered.recycle()
            }
        } catch (t: Throwable) {
            editorFlowAi().update {
                it.copy(isBusy = false, message = "플레어 완화에 실패했습니다: ${t.message}")
            }
        }
    }
}

fun EditorViewModel.applyDaySunFlareGuardV0Preview() {
    val state = uiState.value
    val base = state.previewBitmap ?: state.originalPreviewBitmap
    if (base == null) {
        editorFlowAi().update { it.copy(message = "태양 플레어 완화를 적용할 이미지가 없습니다") }
        return
    }
    val nextRevision = state.revision + 1
    editorFlowAi().update {
        it.copy(
            isBusy = true,
            revision = nextRevision,
            message = "태양 플레어 완화를 적용하는 중입니다"
        )
    }
    viewModelScope.launch {
        try {
            val rendered = withContext(Dispatchers.Default) {
                applyDaySunFlareGuardV0(base, strength = 0.32f)
            }
            if (uiState.value.revision == nextRevision) {
                editorFlowAi().update {
                    it.copy(
                        previewBitmap = rendered,
                        isBusy = false,
                        message = "태양 플레어 완화를 적용했습니다"
                    )
                }
            } else {
                rendered.recycle()
            }
        } catch (t: Throwable) {
            editorFlowAi().update {
                it.copy(isBusy = false, message = "태양 플레어 완화에 실패했습니다: ${t.message}")
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun EditorViewModel.editorFlowAi(): MutableStateFlow<EditorUiState> {
    val field = EditorViewModel::class.java.getDeclaredField("_uiState")
    field.isAccessible = true
    return field.get(this) as MutableStateFlow<EditorUiState>
}
