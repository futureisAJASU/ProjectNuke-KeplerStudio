package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.engineSelection
import com.projectnuke.keplerstudio.editor.renderBitmapWithSelectionLayers
import com.projectnuke.keplerstudio.editor.renderEditedPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.applyActiveSelectionLocalEditNativeBaked() {
    val current = prepareForExternalEdit()
    val baseOriginal = current.originalPreviewBitmap ?: current.previewBitmap
    if (baseOriginal == null) {
        updateUiState { it.copy(message = "적용할 이미지가 없습니다.") }
        return
    }
    val enabledLayers = current.selectionLayers.filter { it.enabled }
    if (enabledLayers.isEmpty()) {
        updateUiState { it.copy(message = "적용할 선택 마스크가 없습니다.") }
        return
    }

    var undoSnapshot: com.projectnuke.keplerstudio.editor.EditorHistorySnapshot? = captureCurrentHistorySnapshot() ?: return
    val nextRevision = current.revision + 1
    updateUiState {
        it.copy(
            isBusy = true,
            revision = nextRevision,
            message = "선택 마스크 보정을 적용하는 중입니다."
        )
    }

    launchManagedEdit { operationToken ->
        var bakedOriginal: Bitmap? = null
        var renderedPreview: Bitmap? = null
        try {
            bakedOriginal = withContext(Dispatchers.Default) {
                // Keep the adopted original free of non-destructive quick effects.
                val localOnlyState = current.copy(
                    params = EditParams(),
                    activeQuickEffects = emptyList()
                )
                renderBitmapWithSelectionLayers(baseOriginal, localOnlyState, nextRevision)
            }
            renderedPreview = withContext(Dispatchers.Default) {
                renderEditedPreview(
                    basePreview = bakedOriginal ?: error("missing baked original"),
                    params = current.params,
                    engines = current.engineSelection(),
                    revision = nextRevision,
                    look = current.presetLook,
                    quickEffects = current.activeQuickEffects
                )
            }
            val adoptedOriginal = bakedOriginal ?: error("missing baked original")
            val adoptedPreview = renderedPreview ?: error("missing rendered preview")
            if (isManagedEditCurrent(operationToken, nextRevision)) {
                updateUiState {
                    it.copy(
                        originalPreviewBitmap = adoptedOriginal,
                        previewBitmap = adoptedPreview,
                        baseBitmapDirty = true,
                        baseContentToken = java.util.UUID.randomUUID().toString(),
                        selectionLayers = emptyList(),
                        activeSelectionLayerId = null,
                        isBusy = false,
                        message = "선택 마스크 보정을 원본에 적용했습니다. 저장 결과에도 반영됩니다."
                    )
                }
                commitUndoSnapshot(checkNotNull(undoSnapshot), clearRedo = true)
                undoSnapshot = null
                bakedOriginal = null
                renderedPreview = null
                persistDraftSnapshot()
            } else {
                bakedOriginal?.recycle()
                bakedOriginal = null
                renderedPreview?.recycle()
                renderedPreview = null
            }
        } catch (ce: CancellationException) {
            bakedOriginal?.recycle()
            renderedPreview?.recycle()
            throw ce
        } catch (_: Throwable) {
            bakedOriginal?.recycle()
            renderedPreview?.recycle()
            if (isManagedEditCurrent(operationToken, nextRevision)) updateUiState {
                it.copy(
                    isBusy = false,
                    message = "선택 마스크 보정 적용에 실패했습니다."
                )
            }
        } finally {
            undoSnapshot?.let(::recycleHistorySnapshot)
        }
    }
}
