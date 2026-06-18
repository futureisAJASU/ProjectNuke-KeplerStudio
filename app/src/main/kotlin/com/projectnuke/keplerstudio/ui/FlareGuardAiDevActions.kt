package com.projectnuke.keplerstudio.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.FlareGuardMode
import com.projectnuke.keplerstudio.editor.applyFlareGuardModelOrRuleV0
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FLARE_GUARD_AI_TAG = "KeplerFlareAI"

/**
 * Temporary dev action for validating the optional Flare Guard TFLite path.
 *
 * This intentionally lives next to the Remaster dev UI so we can test model loading
 * without reshaping the main EditorViewModel state pipeline yet.
 */
fun EditorViewModel.applyFlareGuardAiPreview(
    context: Context,
    mode: FlareGuardMode
) {
    val stateFlow = mutableEditorStateFlowOrNull()
    if (stateFlow == null) {
        Log.e(FLARE_GUARD_AI_TAG, "Editor state reflection failed; cannot apply Flare Guard AI preview")
        return
    }

    val current = uiState.value
    val source = current.previewBitmap ?: current.originalPreviewBitmap
    if (source == null) {
        stateFlow.update { it.copy(message = "번짐 완화를 적용할 이미지가 없습니다") }
        return
    }

    val label = when (mode) {
        FlareGuardMode.NightLight -> "번짐 완화"
        FlareGuardMode.DaySun -> "태양 번짐 완화"
    }
    val nextRevision = current.revision + 1
    val appContext = context.applicationContext

    Log.i(
        FLARE_GUARD_AI_TAG,
        "Starting $label preview: mode=$mode source=${source.width}x${source.height} revision=$nextRevision"
    )
    stateFlow.update {
        it.copy(
            isBusy = true,
            revision = nextRevision,
            message = "$label AI 경로를 실행하는 중입니다"
        )
    }

    viewModelScope.launch {
        try {
            val rendered = withContext(Dispatchers.Default) {
                applyFlareGuardModelOrRuleV0(
                    context = appContext,
                    source = source,
                    mode = mode
                )
            }

            stateFlow.update { state ->
                if (state.revision == nextRevision) {
                    Log.i(
                        FLARE_GUARD_AI_TAG,
                        "Finished $label preview: output=${rendered.width}x${rendered.height} revision=$nextRevision"
                    )
                    state.copy(
                        previewBitmap = rendered,
                        isBusy = false,
                        message = "$label 적용이 완료되었습니다"
                    )
                } else {
                    Log.w(
                        FLARE_GUARD_AI_TAG,
                        "Discarded stale $label preview: expected=$nextRevision actual=${state.revision}"
                    )
                    rendered.recycle()
                    state
                }
            }
        } catch (t: Throwable) {
            Log.e(FLARE_GUARD_AI_TAG, "$label preview failed", t)
            stateFlow.update {
                it.copy(
                    isBusy = false,
                    message = "$label 적용에 실패했습니다: ${t.message}"
                )
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun EditorViewModel.mutableEditorStateFlowOrNull(): MutableStateFlow<EditorUiState>? {
    return runCatching {
        val field = EditorViewModel::class.java.getDeclaredField("_uiState")
        field.isAccessible = true
        field.get(this) as? MutableStateFlow<EditorUiState>
    }.getOrNull()
}
