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
        updateUiState { it.copy(message = "遺꾩꽍???대?吏媛 ?놁뒿?덈떎") }
        return
    }
    val scores = analyzeAutoRouterV0(bitmap)
    val labels = scores.topLabels().joinToString(", ").ifBlank { "normal" }
    updateUiState { it.copy(message = "Auto Router v0 異붿쿇: $labels") }
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
