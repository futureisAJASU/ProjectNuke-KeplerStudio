package com.projectnuke.keplerstudio.ui

import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import com.projectnuke.keplerstudio.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class NativeSpecialEffect(val id: Int, val label: String) {
    SmallSpotCleanup(0, "?묒? 寃고븿 ?꾪솕"),
    ChromaFringeReduce(1, "?됱닔李??꾪솕"),
    VignetteCorrection(2, "鍮꾨꽕??蹂댁젙"),
    SoftBlur(3, "?뚰봽??釉붾윭")
}

fun EditorViewModel.applyNativeSpecialEffect(effect: NativeSpecialEffect, strength: Float = 0.45f) {
    val state = uiState.value
    val base = state.previewBitmap ?: state.originalPreviewBitmap
    if (base == null) {
        updateUiState { it.copy(message = "?곸슜???대?吏媛 ?놁뒿?덈떎") }
        return
    }
    recordUserEditForUndo(clearRedo = true)
    val nextRevision = state.revision + 1
    updateUiState {
        it.copy(
            isBusy = true,
            revision = nextRevision,
            message = "${effect.label}???곸슜?섎뒗 以묒엯?덈떎"
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
                updateUiState {
                    it.copy(
                        previewBitmap = rendered,
                        isBusy = false,
                        message = "${effect.label}???곸슜?덉뒿?덈떎"
                    )
                }
                persistDraftSnapshot()
            } else {
                rendered.recycle()
            }
        } catch (t: Throwable) {
            updateUiState {
                it.copy(isBusy = false, message = "${effect.label} ?곸슜???ㅽ뙣?덉뒿?덈떎: ${t.message}")
            }
        }
    }
}
