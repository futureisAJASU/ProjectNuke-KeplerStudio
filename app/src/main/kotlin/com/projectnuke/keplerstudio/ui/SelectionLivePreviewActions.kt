package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorUiState
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.renderBitmapWithSelectionLayers
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.BitmapAllocationRejectedException
import com.projectnuke.keplerstudio.editor.BitmapMemoryBudget
import com.projectnuke.keplerstudio.editor.beginMemoryTracking
import com.projectnuke.keplerstudio.editor.copyBitmapsOwned
import com.projectnuke.keplerstudio.editor.PreparedResourceHandoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun EditorViewModel.updateActiveSelectionParamsLive(transform: (EditParams) -> EditParams) {
    val transaction = currentSelectionParamTransaction() ?: return
    val current = uiState.value
    if (transaction.settled || transaction.committed) return
    if (current.baseContentToken != transaction.baseContentToken) return
    if (current.activeSelectionLayerId != transaction.activeSelectionLayerId) return
    if (hasActiveBrushStroke()) return
    val activeId = current.activeSelectionLayerId ?: return
    val base = current.originalPreviewBitmap ?: current.previewBitmap ?: return
    if (transaction.activeSelectionLayerId != activeId) return

    val nextLayers = current.selectionLayers.map { layer ->
        if (layer.id == activeId) {
            val nextParams = transform(layer.localParams)
            if (nextParams == layer.localParams) layer else layer.copy(localParams = nextParams)
        } else {
            layer
        }
    }
    if (nextLayers == current.selectionLayers) return
    val prepareTracker = beginMemoryTracking("selectionLivePreview:prepare", snapshotState = "copying")
    val ownedBase = runCatching { base.copyOrThrow() }.getOrElse { failure ->
        prepareTracker?.end()
        invalidateSelectionPreview()
        updateUiState { it.copy(message = if (failure is BitmapAllocationRejectedException) "메모리가 부족하여 선택 마스크 미리보기를 준비하지 못했습니다." else "선택 마스크 미리보기용 이미지를 준비하지 못했습니다.") }
        return
    }
    prepareTracker?.track(ownedBase, "selectionPreview:base")
    val ownedLayers = runCatching {
        nextLayers.copyBitmapsOwned()
    }.getOrElse { failure ->
        ownedBase.recycle()
        prepareTracker?.end()
        invalidateSelectionPreview()
        updateUiState { it.copy(message = if (failure is BitmapAllocationRejectedException) "메모리가 부족하여 선택 마스크 미리보기를 준비하지 못했습니다." else "선택 마스크 미리보기를 준비하지 못했습니다.") }
        return
    }
    ownedLayers.forEach { prepareTracker?.track(it.bitmap, "selectionPreview:layer:${it.id}") }

    val baseToken = current.baseContentToken
    val nextRevision = current.revision + 1
    val nextState = current.copy(
        selectionLayers = nextLayers,
        revision = nextRevision,
        isBusy = true,
        message = "선택 마스크 미리보기를 렌더링하는 중입니다."
    )
    updateUiState { nextState }

    val previewToken = beginSelectionPreview(transaction)
    val handoff =
        PreparedResourceHandoff.create(
            { if (!ownedBase.isRecycled) ownedBase.recycle() },
            {
                ownedLayers.forEach { layer ->
                    if (!layer.bitmap.isRecycled) layer.bitmap.recycle()
                }
            },
            { prepareTracker?.end() },
        )
    val previewJob = viewModelScope.launch {
        if (!handoff.claimForChild()) return@launch
        try {
        var preview: Bitmap? = null
        val previewTracker = beginMemoryTracking(
            "selectionLivePreview",
            snapshotState = "rendering",
            transientReserveBytes = BitmapMemoryBudget.operationReserveBytes()
        )
        previewTracker?.track(ownedBase, "selectionPreview:base")
        ownedLayers.forEach { previewTracker?.track(it.bitmap, "selectionPreview:layer:${it.id}") }
        prepareTracker?.end()
        try {
            delay(120L)
            preview = withContext(Dispatchers.Default) {
                renderLiveSelectionPreview(ownedBase, nextState.copy(selectionLayers = ownedLayers), nextRevision)
            }
            previewTracker?.track(checkNotNull(preview), "selectionPreview:result")
            if (isSelectionPreviewCurrent(transaction, previewToken, nextRevision, baseToken, activeId)) {
                val adopted = preview ?: error("missing selection live preview")
                updateUiState {
                    it.copy(
                        previewBitmap = adopted,
                        isBusy = false,
                        message = "선택 마스크 미리보기가 적용되었습니다."
                    )
                }
                preview = null
                markSelectionPreviewSucceeded(transaction, previewToken, nextRevision, baseToken, activeId)
            } else {
                preview?.recycle()
                preview = null
            }
        } catch (ce: CancellationException) {
            preview?.recycle()
            throw ce
        } catch (t: Throwable) {
            preview?.recycle()
            if (isSelectionPreviewCurrent(transaction, previewToken, nextRevision, baseToken, activeId)) {
                invalidateSelectionPreview()
                updateUiState {
                    it.copy(
                        isBusy = false,
                        message = "선택 마스크 미리보기를 적용하지 못했습니다: ${t.message}"
                    )
                }
            }
        } finally {
            previewTracker?.end()
        }
        } finally {
            handoff.settleChildOwned()
        }
    }
    previewJob.invokeOnCompletion { handoff.settleCallerOwned() }
    bindSelectionPreviewJob(transaction, previewJob, nextRevision, baseToken, activeId)
}

fun EditorViewModel.finishActiveSelectionParamsGesture() {
    finishSelectionParamGesture()
}

private fun renderLiveSelectionPreview(base: Bitmap, state: EditorUiState, revision: Int): Bitmap =
    renderBitmapWithSelectionLayers(base, state, revision)
