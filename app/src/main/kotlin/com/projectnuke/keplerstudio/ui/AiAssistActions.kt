package com.projectnuke.keplerstudio.ui

import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.FlareGuardMode
import com.projectnuke.keplerstudio.editor.acquireEditorSnapshot
import com.projectnuke.keplerstudio.editor.analyzeAutoRouterV0
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.runAutoRouterV0Analysis() {
    if (!canEnterEditorAction()) return
    val snapshot = acquireEditorSnapshot("autoRouterAnalysis")
    if (snapshot == null) {
        updateUiState { it.copy(message = "\uC790\uB3D9 \uB77C\uC6B0\uD130 \uBD84\uC11D\uC5D0 \uC0AC\uC6A9\uD560 \uC774\uBBF8\uC9C0\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4.") }
        return
    }
    val bitmap = snapshot.originalPreviewBitmap ?: snapshot.previewBitmap
    if (bitmap == null) {
        snapshot.close()
        updateUiState { it.copy(message = "\uC790\uB3D9 \uB77C\uC6B0\uD130 \uBD84\uC11D\uC5D0 \uC0AC\uC6A9\uD560 \uC774\uBBF8\uC9C0\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4.") }
        return
    }
    viewModelScope.launch(Dispatchers.Default) {
        try {
            val labels = analyzeAutoRouterV0(bitmap).topLabels().joinToString(", ").ifBlank { "normal" }
            withContext(Dispatchers.Main) {
                val current = uiState.value
                if (current.sourcePath == snapshot.identity.sourcePath &&
                    current.baseContentToken == snapshot.identity.baseContentToken &&
                    current.revision == snapshot.identity.revision &&
                    currentDocumentGeneration() == snapshot.identity.generation) {
                    updateUiState {
                        it.copy(
                            message =
                                "\uC790\uB3D9 \uB77C\uC6B0\uD130\uB294 \uD604\uC7AC \uBD84\uC11D \uC804\uC6A9\uC785\uB2C8\uB2E4. \uCD94\uCC9C \uBD84\uB958: $labels. \uCD94\uCC9C\uB9CC \uD45C\uC2DC\uD558\uACE0 \uC790\uB3D9 \uC801\uC6A9\uD558\uC9C0 \uC54A\uC558\uC2B5\uB2C8\uB2E4.",
                        )
                    }
                }
            }
        } finally {
            snapshot.close()
        }
    }
}

fun EditorViewModel.applyFlareGuardV0Preview() {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    val context = appContext()
    viewModelScope.launch {
        withContext(Dispatchers.Main) {
            applyFlareGuardAiOrRulePreview(context, FlareGuardMode.NightLight)
        }
    }
}

fun EditorViewModel.applyDaySunFlareGuardV0Preview() {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    val context = appContext()
    viewModelScope.launch {
        withContext(Dispatchers.Main) {
            applyFlareGuardAiOrRulePreview(context, FlareGuardMode.DaySun)
        }
    }
}
