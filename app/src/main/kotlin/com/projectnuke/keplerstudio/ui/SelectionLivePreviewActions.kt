package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.BitmapMemoryBudget
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorRenderer
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.MemoryTrackerScope
import com.projectnuke.keplerstudio.editor.RenderFailedException
import com.projectnuke.keplerstudio.editor.RenderOperation
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.SelectionParamTransaction
import com.projectnuke.keplerstudio.editor.BitmapLease
import com.projectnuke.keplerstudio.editor.acquireBitmapLease
import com.projectnuke.keplerstudio.editor.SelectionPreviewPreparationGateway
import com.projectnuke.keplerstudio.editor.beginMemoryTracking
import com.projectnuke.keplerstudio.editor.copyBitmapsOwned
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.successOrThrow
import com.projectnuke.keplerstudio.editor.withFailedRender
import com.projectnuke.keplerstudio.editor.withSuccessfulRender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lightweight selection-parameter live preview.
 *
 * Per pointer tick this performs only cheap validation and a preview-state dispatch on the
 * Main dispatcher (stamping only the [SelectionLayer.localParams] of the active layer). The
 * expensive full-resolution bitmap copies AND the render happen inside a single managed
 * coroutine that:
 *
 * 1. Debounces by 120 ms so the latest slider value wins.
 * 2. Re-reads the authoritative transaction identity after the debounce so a newer tick or
 *    document replacement supersedes an older one before any bitmap is touched.
 * 3. Prepares and renders on `Dispatchers.Default`. Each preparation is observable via
 *    [SelectionPreviewPreparationGateway] so tests can prove rapid events do not duplicate
 *    full-size copies.
 * 4. Releases every transaction-owned input in its `finally`, irrespective of adoption or
 *    stale-result discard.
 */
fun EditorViewModel.updateActiveSelectionParamsLive(transform: (EditParams) -> EditParams) {
    val transaction = currentSelectionParamTransaction() ?: return
    val current = uiState.value
    if (transaction.settled || transaction.committed) return
    if (current.baseContentToken != transaction.baseContentToken) return
    if (current.activeSelectionLayerId != transaction.activeSelectionLayerId) return
    if (hasActiveBrushStroke()) return
    val activeId = current.activeSelectionLayerId ?: return
    if (transaction.activeSelectionLayerId != activeId) return

    // --- Lightweight preview state update on Main (no Bitmap copy) ---
    // Only the in-state layer's EditParams reference is replaced; the bitmap object identity
    // is preserved so the visible preview keeps rendering the existing pixels while a new
    // prepared render is in flight.
    val nextLayers = current.selectionLayers.map { layer ->
        if (layer.id == activeId) {
            val nextParams = transform(layer.localParams)
            if (nextParams == layer.localParams) layer else layer.copy(localParams = nextParams)
        } else {
            layer
        }
    }
    if (nextLayers == current.selectionLayers) return

    // Apply the lightweight, params-only preview state on Main immediately. This is what the
    // user sees in the slider row and what the worker re-reads after debounce; no bitmap copy
    // occurs on this thread. The existing in-state Bitmap object identities are preserved so
    // the visible preview keeps rendering the existing pixels while a new prepared render is
    // in flight.
    val baseToken = current.baseContentToken
    val nextRevision = current.revision + 1
    updateUiState {
        it.copy(
            selectionLayers = nextLayers,
            revision = nextRevision,
            isBusy = true,
            message = "선택 마스크 미리보기를 렌더링하는 중입니다.",
        )
    }

    val previewToken = beginSelectionPreview(transaction)
    SelectionPreviewPreparationGateway.notePrepareIntention()

    val previewJob = viewModelScope.launch {
        delay(120L)
        ensureActive()
        val settledTransaction = currentSelectionParamTransaction() ?: return@launch
        if (settledTransaction !== transaction) return@launch
        if (settledTransaction.latestPreviewToken != previewToken) return@launch
        if (settledTransaction.settled || settledTransaction.committed) return@launch
        prepareAndRenderLivePreview(transaction, previewToken, activeId)
    }
    bindSelectionPreviewJob(
        transaction = transaction,
        job = previewJob,
        revision = nextRevision,
        baseToken = baseToken,
        activeId = activeId,
    )
}

/**
 * Worker-side preparation and render of the latest survived preview state. The base and
 * selection-mask bitmaps are copied here, on `Dispatchers.Default`, AFTER debounce, using the
 * authoritative state captured after the debounce so that a rapid gesture only ever causes one
 * full-resolution preparation.
 */
private suspend fun EditorViewModel.prepareAndRenderLivePreview(
    transaction: SelectionParamTransaction,
    previewToken: Long,
    activeId: String,
) {
    var lease: BitmapLease? = null
    var ownedBase: Bitmap? = null
    var ownedLayers: List<SelectionLayer>? = null
    var previewResult: Bitmap? = null
    var previewTracker: MemoryTrackerScope? = null
    var observedRevision: Int = 0
    try {
        val prepared = withContext(Dispatchers.Default) {
            ensureActive()
            lease = acquireBitmapLease("selectionLivePreview") ?: return@withContext null
            val stateForCopy = uiState.value
            if (stateForCopy.baseContentToken != transaction.baseContentToken) return@withContext null
            if (stateForCopy.activeSelectionLayerId != activeId) return@withContext null
            if (stateForCopy.selectionLayers.none { it.id == activeId }) return@withContext null
            val liveBase =
                stateForCopy.originalPreviewBitmap ?: stateForCopy.previewBitmap
                    ?: return@withContext null
            observedRevision = stateForCopy.revision
            SelectionPreviewPreparationGateway.noteCopy()
            previewTracker =
                beginMemoryTracking(
                    "selectionLivePreview",
                    snapshotState = "rendering",
                    transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
                )
            val ownedBaseLocal =
                runCatching { liveBase.copyOrThrow() }.getOrElse { failure ->
                    previewTracker?.end()
                    previewTracker = null
                    throw failure
                }
            previewTracker?.track(ownedBaseLocal, "selectionPreview:base")
            val ownedLayersLocal =
                try {
                    stateForCopy.selectionLayers.copyBitmapsOwned()
                } catch (failure: Throwable) {
                    ownedBaseLocal.recycle()
                    previewTracker?.end()
                    previewTracker = null
                    throw failure
                }
            ownedLayersLocal.forEach {
                previewTracker?.track(it.bitmap, "selectionPreview:layer:${it.id}")
            }
            Pair(ownedBaseLocal, ownedLayersLocal)
        } ?: run {
            recordPreviewPrepareFailure("선택 마스크 미리보기를 준비하지 못했습니다.", failure = null)
            return
        }
        ownedBase = prepared.first
        ownedLayers = prepared.second
        checkNotNull(ownedBase)
        val stateForRender = checkNotNull(uiState.value.takeIf {
            it.baseContentToken == transaction.baseContentToken &&
                it.activeSelectionLayerId == activeId &&
                it.revision == observedRevision
        })

        val success =
            withContext(Dispatchers.Default) {
                EditorRenderer.render(
                    createRenderRequest(
                        state = stateForRender,
                        operation = RenderOperation.SelectionLivePreview,
                        basePreview = checkNotNull(ownedBase),
                        revision = stateForRender.revision,
                        diagnostics = previewTracker,
                        selectionLayers = checkNotNull(ownedLayers),
                    )
                ).successOrThrow()
            }
        previewResult = success.output
        previewTracker?.track(previewResult, "selectionPreview:result")

        if (isSelectionPreviewCurrent(transaction, previewToken, stateForRender.revision, transaction.baseContentToken, activeId)) {
            val adopted = previewResult ?: error("missing selection live preview")
            updateUiState {
                it.copy(
                    previewBitmap = adopted,
                    isBusy = false,
                    correctionEngineState =
                        it.correctionEngineState.withSuccessfulRender(
                            stateForRender.correctionEngineState.documentEngine,
                            success.copy(output = adopted),
                        ),
                    message = "선택 마스크 미리보기가 적용되었습니다.",
                )
            }
            previewResult = null
            markSelectionPreviewSucceeded(transaction, previewToken, stateForRender.revision, transaction.baseContentToken, activeId)
        } else {
            previewResult?.recycle()
            previewResult = null
        }
    } catch (ce: CancellationException) {
        previewResult?.recycle()
        previewResult = null
        throw ce
    } catch (t: Throwable) {
        previewResult?.recycle()
        previewResult = null
        recordPreviewPrepareFailure("선택 마스크 미리보기를 적용하지 못했습니다: ${t.message}", t)
    } finally {
        ownedBase?.takeUnless { it.isRecycled }?.recycle()
        ownedLayers?.forEach { layer -> layer.bitmap.takeUnless { it.isRecycled }?.recycle() }
        previewResult?.takeUnless { it.isRecycled }?.recycle()
        settleSelectionPreviewBusyIfOwned(
            transaction = transaction,
            token = previewToken,
            revision = uiState.value.revision,
            baseToken = transaction.baseContentToken,
            activeId = activeId,
        )
        lease?.close()
        previewTracker?.end()
    }
}

/**
 * Reports a preparation or render failure on the Main dispatcher only when the transaction is
 * still the authoritative one. Allocation-rejected failures reuse the existing
 * [requestAllocationRecovery] path so the user can retry via the memory-recovery dialog.
 */
private fun EditorViewModel.recordPreviewPrepareFailure(message: String, failure: Throwable?) {
    val state = uiState.value
    invalidateSelectionPreview()
    updateUiStateAndRecycleReplaced {
        it.copy(
            isBusy = false,
            correctionEngineState =
                (failure as? RenderFailedException)?.failure?.let { renderFailure ->
                    it.correctionEngineState.withFailedRender(
                        state.correctionEngineState.documentEngine,
                        renderFailure,
                    )
                } ?: it.correctionEngineState,
            message = message,
        )
    }
    // Selection live preview is a transient mask-only preview on the preview bitmap; an
    // allocation failure leaves the prior document unchanged and stays inspectable in the
    // message. It does not trigger the persistent memory-recovery dialog because the user
    // can simply retune the slider or run a heavier operation via the documented recovery
    // path for those actions. We intentionally avoid a [MemoryRetryAction] entry here.
}

fun EditorViewModel.finishActiveSelectionParamsGesture() {
    finishSelectionParamGesture()
}

/**
 * Exposed for tests. The latest authored localParams of the active layer after the debounce
 * decide whether a preparation must run.
 */
internal fun EditorViewModel.selectionPreviewPrepareCount(): Long =
    SelectionPreviewPreparationGateway.prepareCount

internal fun EditorViewModel.selectionPreviewCopyCount(): Long =
    SelectionPreviewPreparationGateway.copyCount

internal fun EditorViewModel.resetSelectionPreviewInstrumentationForTest() {
    SelectionPreviewPreparationGateway.resetForTest()
}
