package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.FlareGuardMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.applyFlareOriginalMvp() {
    applyFlareRuleFallbackInternal(FlareGuardMode.NightLight, "번짐 완화", 0.28f)
}

fun EditorViewModel.applySunFlareOriginalMvp() {
    applyFlareRuleFallbackInternal(FlareGuardMode.DaySun, "태양 번짐 완화", 0.24f)
}

private fun EditorViewModel.applyFlareRuleFallbackInternal(mode: FlareGuardMode, title: String, strength: Float) {
    val current = uiState.value
    val baseOriginal = current.originalPreviewBitmap ?: current.previewBitmap
    if (baseOriginal == null) {
        updateUiState { it.copy(message = "$title 적용할 이미지가 없습니다.") }
        return
    }

    recordUserEditForUndo(clearRedo = true)
    val nextRevision = current.revision + 1
    updateUiState {
        it.copy(
            isBusy = true,
            revision = nextRevision,
            message = "$title 처리 중입니다.",
            flareGuardRuntimeStatus = "규칙 기반 보정으로 처리 중입니다."
        )
    }

    viewModelScope.launch {
        try {
            val nextOriginal = withContext(Dispatchers.Default) {
                val copy = baseOriginal.copy(Bitmap.Config.ARGB_8888, true)
                val result = NativePhotoCore.nativeApplyFlareGuardInPlace(
                    copy,
                    mode.ordinal,
                    strength.coerceIn(0f, 1f),
                    nextRevision
                )
                if (result < 0) {
                    copy.recycle()
                    error("nativeApplyFlareGuardInPlace failed: $result")
                }
                copy
            }
            val nextPreview = withContext(Dispatchers.Default) {
                renderPreviewFromState(nextOriginal, current, nextRevision)
            }
            if (uiState.value.revision == nextRevision) {
                updateUiState {
                    it.copy(
                        originalPreviewBitmap = nextOriginal,
                        previewBitmap = nextPreview,
                        isBusy = false,
                        message = "규칙 기반 보정으로 번짐을 완화했습니다.",
                        flareGuardRuntimeStatus = "규칙 기반 보정으로 번짐을 완화했습니다."
                    )
                }
                persistDraftSnapshot()
            } else {
                nextOriginal.recycle()
                nextPreview.recycle()
            }
        } catch (_: Throwable) {
            updateUiState {
                it.copy(
                    isBusy = false,
                    message = "번짐 완화에 실패했습니다.",
                    flareGuardRuntimeStatus = "번짐 완화에 실패했습니다."
                )
            }
        }
    }
}

private fun renderPreviewFromState(base: Bitmap, state: EditorUiState, revision: Int): Bitmap {
    val copy = base.copy(Bitmap.Config.ARGB_8888, true)
    val params = state.params
    NativePhotoCore.nativeRenderPreviewInPlace(
        copy,
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
        revision,
        state.presetLook
    )
    return copy
}
