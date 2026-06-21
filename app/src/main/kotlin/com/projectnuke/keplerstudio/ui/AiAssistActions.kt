package com.projectnuke.keplerstudio.ui

import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.FlareGuardMode
import com.projectnuke.keplerstudio.editor.analyzeAutoRouterV0
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.runAutoRouterV0Analysis() {
    val bitmap = uiState.value.originalPreviewBitmap ?: uiState.value.previewBitmap
    if (bitmap == null) {
        updateUiState { it.copy(message = "자동 라우터 분석을 실행할 이미지가 없습니다.") }
        return
    }
    val scores = analyzeAutoRouterV0(bitmap)
    val labels = scores.topLabels().joinToString(", ").ifBlank { "normal" }
    updateUiState {
        it.copy(message = "자동 라우터는 현재 분석 전용입니다. 추천 분류: $labels. 추천만 표시하고 자동 적용하지 않았습니다.")
    }
}

fun EditorViewModel.applyFlareGuardV0Preview() {
    val context = appContext()
    viewModelScope.launch {
        withContext(Dispatchers.Main) {
            applyFlareGuardAiOrRulePreview(context, FlareGuardMode.NightLight)
        }
    }
}

fun EditorViewModel.applyDaySunFlareGuardV0Preview() {
    val context = appContext()
    viewModelScope.launch {
        withContext(Dispatchers.Main) {
            applyFlareGuardAiOrRulePreview(context, FlareGuardMode.DaySun)
        }
    }
}
