package com.projectnuke.keplerstudio.ui

import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NativeSpecialEffect(val id: Int, val label: String) {
    SmallSpotCleanup(0, "작은 결함 완화"),
    ChromaFringeReduce(1, "색수차 완화"),
    VignetteCorrection(2, "비네팅 보정"),
    SoftBlur(3, "소프트 블러")
}

fun EditorViewModel.applyNativeSpecialEffect(effect: NativeSpecialEffect, strength: Float = 0.45f) {
    val state = uiState.value
    val base = state.previewBitmap ?: state.originalPreviewBitmap
    if (base == null) {
        editorFlowNativeFx().update { it.copy(message = "적용할 이미지가 없습니다") }
        return
    }
    val nextRevision = state.revision + 1
    editorFlowNativeFx().update {
        it.copy(
            isBusy = true,
            revision = nextRevision,
            message = "${effect.label}을 적용하는 중입니다"
        )
    }
    viewModelScope.launch {
        try {
            val rendered = withContext(Dispatchers.Default) {
                val copy = base.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                NativePhotoCore.nativeApplySpecialEffectInPlace(
                    bitmap = copy,
                    effect = effect.id,
                    strength = strength.coerceIn(0f, 1f),
                    revision = nextRevision
                )
                copy
            }
            if (uiState.value.revision == nextRevision) {
                editorFlowNativeFx().update {
                    it.copy(
                        previewBitmap = rendered,
                        isBusy = false,
                        message = "${effect.label}을 적용했습니다"
                    )
                }
            } else {
                rendered.recycle()
            }
        } catch (t: Throwable) {
            editorFlowNativeFx().update {
                it.copy(isBusy = false, message = "${effect.label} 적용에 실패했습니다: ${t.message}")
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun EditorViewModel.editorFlowNativeFx(): MutableStateFlow<EditorUiState> {
    val field = EditorViewModel::class.java.getDeclaredField("_uiState")
    field.isAccessible = true
    return field.get(this) as MutableStateFlow<EditorUiState>
}
