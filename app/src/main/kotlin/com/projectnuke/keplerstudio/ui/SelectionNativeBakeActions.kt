package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.engineSelection
import com.projectnuke.keplerstudio.editor.renderBitmapWithSelectionLayers
import com.projectnuke.keplerstudio.editor.renderEditedPreview
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.copyBitmapsOwned
import com.projectnuke.keplerstudio.editor.newBaseContentToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.applyActiveSelectionLocalEditNativeBaked() {
    if (isShuttingDown()) return
    if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return
    val current = prepareForExternalEdit()
    val baseOriginal = current.originalPreviewBitmap ?: current.previewBitmap
    if (baseOriginal == null) {
        updateUiState { it.copy(message = "적용할 이미지가 없습니다.") }
        return
    }
    val capturedSelectionLayers = current.selectionLayers
    val capturedActiveSelectionLayerId = current.activeSelectionLayerId
    val enabledLayers = capturedSelectionLayers.filter { it.enabled }
    if (enabledLayers.isEmpty()) {
        updateUiState { it.copy(message = "적용할 선택 마스크가 없습니다.") }
        return
    }
    val params = current.params
    val engines = current.engineSelection()
    val presetLook = current.presetLook
    val quickEffects = current.activeQuickEffects
    val sourcePath = current.sourcePath
    val baseContentToken = current.baseContentToken

    var undoSnapshot: com.projectnuke.keplerstudio.editor.EditorHistorySnapshot? = captureCurrentHistorySnapshot() ?: return
    var ownedBase: Bitmap? = runCatching { baseOriginal.copyOrThrow() }.getOrElse {
        recycleHistorySnapshot(checkNotNull(undoSnapshot))
        undoSnapshot = null
        updateUiState { it.copy(message = "선택 마스크 보정 준비에 실패했습니다.") }
        return
    }
    var ownedLayers: List<com.projectnuke.keplerstudio.editor.SelectionLayer> = runCatching {
        enabledLayers.copyBitmapsOwned()
    }.getOrElse {
        ownedBase?.takeIf { !it.isRecycled }?.recycle()
        ownedBase = null
        recycleHistorySnapshot(checkNotNull(undoSnapshot))
        undoSnapshot = null
        updateUiState { it.copy(message = "선택 마스크 보정 준비에 실패했습니다.") }
        return
    }
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
            withContext(Dispatchers.Default) {
                val localOnlyState = current.copy(
                    params = EditParams(),
                    activeQuickEffects = emptyList()
                )
                val result = renderBitmapWithSelectionLayers(checkNotNull(ownedBase), localOnlyState.copy(selectionLayers = ownedLayers), nextRevision)
                bakedOriginal = result
            }
            withContext(Dispatchers.Default) {
                val result = renderEditedPreview(
                    basePreview = checkNotNull(bakedOriginal),
                    params = params,
                    engines = engines,
                    revision = nextRevision,
                    look = presetLook,
                    quickEffects = quickEffects
                )
                renderedPreview = result
            }
            val adoptedOriginal = bakedOriginal ?: error("missing baked original")
            val adoptedPreview = renderedPreview ?: error("missing rendered preview")
            if (isManagedEditCurrent(operationToken, nextRevision) &&
                uiState.value.sourcePath == sourcePath &&
                uiState.value.baseContentToken == baseContentToken &&
                uiState.value.selectionLayers == capturedSelectionLayers &&
                uiState.value.activeSelectionLayerId == capturedActiveSelectionLayerId &&
                !isShuttingDown()) {
                updateUiStateAndRecycleReplaced {
                    it.copy(
                        originalPreviewBitmap = adoptedOriginal,
                        previewBitmap = adoptedPreview,
                        baseBitmapDirty = true,
                        baseContentToken = newBaseContentToken(),
                        selectionLayers = emptyList(),
                        activeSelectionLayerId = null,
                        isBusy = false,
                        message = "선택 마스크 보정을 원본에 적용했습니다. 저장 결과에도 반영됩니다."
                    )
                }
                bakedOriginal = null
                renderedPreview = null
                commitUndoSnapshot(checkNotNull(undoSnapshot), clearRedo = true)
                undoSnapshot = null
                persistDraftSnapshot()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            if (isManagedEditCurrent(operationToken, nextRevision) &&
                uiState.value.sourcePath == sourcePath &&
                uiState.value.baseContentToken == baseContentToken &&
                uiState.value.selectionLayers == capturedSelectionLayers &&
                uiState.value.activeSelectionLayerId == capturedActiveSelectionLayerId) updateUiState {
                it.copy(
                    isBusy = false,
                    message = "선택 마스크 보정 적용에 실패했습니다."
                )
            }
        } finally {
            ownedBase?.takeIf { !it.isRecycled }?.recycle()
            ownedLayers.forEach { it.bitmap.takeIf { bitmap -> !bitmap.isRecycled }?.recycle() }
            bakedOriginal?.takeIf { !it.isRecycled }?.recycle()
            renderedPreview?.takeIf { !it.isRecycled }?.recycle()
            undoSnapshot?.let(::recycleHistorySnapshot)
        }
    }
}
