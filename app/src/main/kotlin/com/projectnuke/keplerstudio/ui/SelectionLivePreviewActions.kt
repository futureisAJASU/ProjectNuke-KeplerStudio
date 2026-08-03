package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.editor.BitmapMemoryBudget
import com.projectnuke.keplerstudio.editor.BitmapAllocationRejectedException
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorRenderer
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.MemoryTrackerScope
import com.projectnuke.keplerstudio.editor.RenderFailedException
import com.projectnuke.keplerstudio.editor.RenderOperation
import com.projectnuke.keplerstudio.editor.SelectionParamTransaction
import com.projectnuke.keplerstudio.editor.SelectionPreviewFailureKind
import com.projectnuke.keplerstudio.editor.SelectionPreviewIdentity
import com.projectnuke.keplerstudio.editor.SelectionPreviewPreparationOutcome
import com.projectnuke.keplerstudio.editor.PreparedSelectionPreview
import com.projectnuke.keplerstudio.editor.PreparedSelectionPreviewSlot
import com.projectnuke.keplerstudio.editor.OwnedHandoff
import com.projectnuke.keplerstudio.editor.OwnedRenderSuccess
import com.projectnuke.keplerstudio.editor.reserveSelectionMaskCopies
import com.projectnuke.keplerstudio.editor.SelectionPreviewPreparationGateway
import com.projectnuke.keplerstudio.editor.beginMemoryTracking
import com.projectnuke.keplerstudio.editor.copyBitmapsOwned
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.successOrThrow
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
    val preparedSlot = PreparedSelectionPreviewSlot()
    val renderSlot = OwnedHandoff<OwnedRenderSuccess>()
    var preparedOwner: PreparedSelectionPreview? = null
    var renderOwner: OwnedRenderSuccess? = null
    var previewResult: Bitmap? = null
    var observedRevision: Int = 0
    try {
        val expectedRevision = transaction.previewRevision ?: transaction.startRevision
        observedRevision = expectedRevision
        val previewIdentity =
            SelectionPreviewIdentity(
                gestureId = transaction.gestureId,
                previewToken = previewToken,
                revision = expectedRevision,
                documentGeneration = transaction.documentGeneration,
                baseContentToken = transaction.baseContentToken,
                activeSelectionLayerId = activeId,
            )
        val prepared = withContext(Dispatchers.Default) {
            ensureActive()
            val acquiredSnapshot =
                acquireSelectionPreviewSnapshot(
                    transaction = transaction,
                    previewToken = previewToken,
                    expectedRevision = expectedRevision,
                    activeLayerId = activeId,
                ) ?: return@withContext SelectionPreviewPreparationOutcome.Rejected(
                    previewIdentity,
                    SelectionPreviewFailureKind.StaleOrSuperseded,
                    "selection preview superseded",
                )
            val stateForCopy = acquiredSnapshot.state
            if (stateForCopy.baseContentToken != transaction.baseContentToken ||
                stateForCopy.activeSelectionLayerId != activeId ||
                stateForCopy.selectionLayers.none { it.id == activeId }
            ) {
                acquiredSnapshot.close()
                return@withContext SelectionPreviewPreparationOutcome.Rejected(
                    previewIdentity,
                    SelectionPreviewFailureKind.StaleOrSuperseded,
                    "selection preview state is stale",
                )
            }
            val liveBase = stateForCopy.originalPreviewBitmap ?: stateForCopy.previewBitmap
            if (liveBase == null) {
                acquiredSnapshot.close()
                return@withContext SelectionPreviewPreparationOutcome.Rejected(
                    previewIdentity,
                    SelectionPreviewFailureKind.MissingSource,
                    "selection preview source is missing",
                )
            }
            val observed = stateForCopy.revision
            SelectionPreviewPreparationGateway.noteCopy()
            val owner = PreparedSelectionPreview(previewIdentity, acquiredSnapshot)
            try {
                owner.attachBase(liveBase.copyOrThrow())
                val tracker =
                    beginMemoryTracking(
                        "selectionLivePreview",
                        snapshotState = "rendering",
                        transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
                    )
                if (tracker != null) owner.attachTracker(tracker)
                tracker?.track(owner.requireBase(), "selectionPreview:base")
                val reservations =
                    reserveSelectionMaskCopies(
                        owner = "selectionPreview:${transaction.gestureId}:$previewToken",
                        layers = stateForCopy.selectionLayers,
                    ) ?: throw BitmapAllocationRejectedException(
                        stateForCopy.selectionLayers.sumOf { BitmapMemoryBudget.bytes(it.bitmap) }
                    )
                owner.attachReservations(reservations)
                owner.attachLayers(stateForCopy.selectionLayers.copyBitmapsOwned())
                owner.requireLayers().forEach {
                    tracker?.track(it.bitmap, "selectionPreview:layer:${it.id}")
                }
                preparedSlot.publish(owner)
                SelectionPreviewPreparationGateway.awaitPreparedOwnerHookForTest()
            } catch (cancelled: CancellationException) {
                owner.close()
                throw cancelled
            } catch (failure: Throwable) {
                owner.close()
                return@withContext SelectionPreviewPreparationOutcome.Rejected(
                    previewIdentity,
                    if (failure is BitmapAllocationRejectedException) {
                        SelectionPreviewFailureKind.AllocationFailure
                    } else {
                        SelectionPreviewFailureKind.InvariantFailure
                    },
                    "selection preview preparation failed",
                    failure,
                )
            }
            SelectionPreviewPreparationOutcome.Prepared(
                identity = previewIdentity,
                observedRevision = observed,
            )
        }
        if (prepared is SelectionPreviewPreparationOutcome.Rejected) {
            recordSelectionPreviewFailure(
                transaction,
                prepared.identity.previewToken,
                prepared.identity.revision,
                prepared.identity.baseContentToken,
                prepared.identity.activeSelectionLayerId ?: activeId,
                prepared.kind,
                prepared.message,
                prepared.failure,
            )
            return
        }
        val ready = prepared as SelectionPreviewPreparationOutcome.Prepared
        preparedOwner = preparedSlot.take()
        checkNotNull(preparedOwner)
        observedRevision = ready.observedRevision
        val stateForRender =
            checkNotNull(preparedOwner?.snapshot?.state?.takeIf {
                it.baseContentToken == transaction.baseContentToken &&
                    it.activeSelectionLayerId == activeId &&
                    it.revision == observedRevision
            })

        withContext(Dispatchers.Default) {
            val owned =
                OwnedRenderSuccess(
                    EditorRenderer.render(
                    createRenderRequest(
                        state = stateForRender,
                        operation = RenderOperation.SelectionLivePreview,
                        basePreview = checkNotNull(preparedOwner).requireBase(),
                        revision = stateForRender.revision,
                        diagnostics = preparedOwner?.tracker(),
                        selectionLayers = checkNotNull(preparedOwner).requireLayers(),
                        documentGeneration = checkNotNull(preparedOwner).snapshot.identity.generation,
                    )
                    ).successOrThrow()
                )
            if (!renderSlot.publish(owned)) return@withContext
            SelectionPreviewPreparationGateway.awaitRenderOutputHookForTest()
        }
        renderOwner = checkNotNull(renderSlot.take())
        val success = checkNotNull(renderOwner).result
        previewResult = checkNotNull(renderOwner).takeOutput()
        preparedOwner?.tracker()?.track(checkNotNull(previewResult), "selectionPreview:result")

        val producedPreview = previewResult ?: error("missing selection live preview")
        var adoptedByCurrentTransaction = false
        withContext(Dispatchers.Main) {
            if (isSelectionPreviewCurrent(transaction, previewToken, stateForRender.revision, transaction.baseContentToken, activeId)) {
                updateUiStateAndRecycleReplaced { current ->
                    val stillCurrent =
                        current.revision == stateForRender.revision &&
                            current.baseContentToken == transaction.baseContentToken &&
                            current.activeSelectionLayerId == activeId &&
                            currentDocumentGeneration() == transaction.documentGeneration
                    if (!stillCurrent) current
                    else {
                        adoptedByCurrentTransaction = true
                        transaction.succeeded = true
                        current.copy(
                            previewBitmap = producedPreview,
                            isBusy = false,
                            correctionEngineState =
                                current.correctionEngineState.withSuccessfulRender(
                                    stateForRender.correctionEngineState.documentEngine,
                                    success.copy(output = producedPreview),
                                ),
                        )
                    }
                }
                if (adoptedByCurrentTransaction) {
                    markSelectionPreviewSucceeded(transaction, previewToken, stateForRender.revision, transaction.baseContentToken, activeId)
                }
            }
        }
        if (adoptedByCurrentTransaction) previewResult = null
        if (adoptedByCurrentTransaction) return
        previewResult?.recycle()
        previewResult = null
    } catch (ce: CancellationException) {
        previewResult?.recycle()
        previewResult = null
        throw ce
    } catch (t: Throwable) {
        val failureKind =
            if (t is BitmapAllocationRejectedException) {
                SelectionPreviewFailureKind.AllocationFailure
            } else if (t is RenderFailedException) {
                SelectionPreviewFailureKind.RenderFailure
            } else {
                SelectionPreviewFailureKind.InvariantFailure
            }
        recordSelectionPreviewFailure(
            transaction,
            previewToken,
            observedRevision.takeIf { it != 0 } ?: (transaction.previewRevision ?: transaction.startRevision),
            transaction.baseContentToken,
            activeId,
            failureKind,
            when (failureKind) {
                SelectionPreviewFailureKind.AllocationFailure ->
                    "메모리가 부족하여 선택 마스크 미리보기를 준비하지 못했습니다."
                SelectionPreviewFailureKind.RenderFailure ->
                    "선택 마스크 미리보기 렌더링에 실패했습니다."
                else -> "선택 마스크 미리보기를 적용하지 못했습니다."
            },
            t,
        )
        previewResult?.recycle()
        previewResult = null
    } finally {
        previewResult?.takeUnless { it.isRecycled }?.recycle()
        settleSelectionPreviewBusyIfOwned(
            transaction = transaction,
            token = previewToken,
            revision = observedRevision,
            baseToken = transaction.baseContentToken,
            activeId = activeId,
        )
        preparedOwner?.close()
        preparedSlot.close()
        renderOwner?.close()
        renderSlot.close()
    }
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
