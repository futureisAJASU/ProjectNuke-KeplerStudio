package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.editor.BitmapAllocationRejectedException
import com.projectnuke.keplerstudio.editor.BitmapMemoryBudget
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorRenderer
import com.projectnuke.keplerstudio.editor.EditorHistorySnapshot
import com.projectnuke.keplerstudio.editor.PendingHistorySnapshot
import com.projectnuke.keplerstudio.editor.OwnedHandoff
import com.projectnuke.keplerstudio.editor.OwnedRenderSuccess
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.FallbackPolicy
import com.projectnuke.keplerstudio.editor.HistorySnapshotStorage
import com.projectnuke.keplerstudio.editor.MemoryRetryAction
import com.projectnuke.keplerstudio.editor.SelectionRetryTarget
import com.projectnuke.keplerstudio.editor.PreparedResourceHandoff
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.RenderFailedException
import com.projectnuke.keplerstudio.editor.RenderOperation
import com.projectnuke.keplerstudio.editor.RenderResult
import com.projectnuke.keplerstudio.editor.LeasedEditorSnapshot
import com.projectnuke.keplerstudio.editor.MaskReservationBatch
import com.projectnuke.keplerstudio.editor.acquireEditorSnapshot
import com.projectnuke.keplerstudio.editor.reserveSelectionMaskCopies
import com.projectnuke.keplerstudio.editor.beginMemoryTracking
import com.projectnuke.keplerstudio.editor.copyBitmapsOwned
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.engineSelection
import com.projectnuke.keplerstudio.editor.newBaseContentToken
import com.projectnuke.keplerstudio.editor.successOrThrow
import com.projectnuke.keplerstudio.editor.withFailedRender
import com.projectnuke.keplerstudio.editor.withSuccessfulRender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun EditorViewModel.applyActiveSelectionLocalEditNativeBaked() {
    if (isShuttingDown()) return
    val settlement = settleParameterTransactionBeforeExternalEdit()
    if (continueAfterOwnParameterSettlement(settlement) {
            applyActiveSelectionLocalEditNativeBaked()
        }
    ) return
    if (!canEnterEditorActionAfterSettlement(allowMaskSupersession = true)) return
    prepareForExternalEdit()
    val startSnapshot = acquireEditorSnapshot("selectionNativeBake") ?: return
    val current = startSnapshot.state
    val baseOriginal = current.originalPreviewBitmap ?: current.previewBitmap
    if (baseOriginal == null) {
        startSnapshot.close()
        updateUiState { it.copy(message = "적용할 이미지가 없습니다.") }
        return
    }
    val capturedSelectionLayers = current.selectionLayers
    val capturedActiveSelectionLayerId = current.activeSelectionLayerId
    val enabledLayers = capturedSelectionLayers.filter { it.enabled }
    if (enabledLayers.isEmpty()) {
        startSnapshot.close()
        updateUiState { it.copy(message = "적용할 선택 마스크가 없습니다.") }
        return
    }
    val params = current.params
    val engines = current.engineSelection()
    val presetLook = current.presetLook
    val quickEffects = current.activeQuickEffects
    val sourcePath = current.sourcePath
    val baseContentToken = current.baseContentToken

    var pendingHistory: PendingHistorySnapshot? =
        prepareHistorySnapshot("selectionNativeBake", startSnapshot)
    val prepareTracker =
        beginMemoryTracking(
            "selectionNativeBake:prepare",
            snapshotState = "copying",
            transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
        )
    val nextRevision = current.revision + 1
    updateUiState {
        it.copy(isBusy = true, revision = nextRevision, message = "선택 마스크 보정을 적용하는 중입니다.")
    }

    launchManagedEditWithPreparedResources(
        { operationToken ->
            applySelectionNativeBakeBackground(
                operationToken = operationToken,
                capturedCurrent = current,
                enabledLayers = enabledLayers,
                params = params,
                engines = engines,
                presetLook = presetLook,
                quickEffects = quickEffects,
                sourcePath = sourcePath,
                baseContentToken = baseContentToken,
                capturedSelectionLayers = capturedSelectionLayers,
                capturedActiveSelectionLayerId = capturedActiveSelectionLayerId,
                nextRevision = nextRevision,
                startSnapshot = startSnapshot,
                originalHistoryRef = { pendingHistory },
                consumeHistory = { pendingHistory = null },
                releaseHistory = { pendingHistory?.close(); pendingHistory = null },
                prepareTracker = prepareTracker,
            )
        },
        handoff =
            PreparedResourceHandoff.create(
                "nativeSelectionBake",
                {
                    startSnapshot.close()
                    pendingHistory?.close()
                    pendingHistory = null
                },
                { prepareTracker?.end() },
                {
                    val live = uiState.value
                    if (live.revision == nextRevision && live.sourcePath == sourcePath &&
                        live.baseContentToken == baseContentToken) {
                        updateUiState { it.copy(isBusy = false) }
                    }
                },
            ),
    )
}

/**
 * Worker-side preparation and bake of the captured selection-native-bake request. The
 * full-resolution base and selection-mask bitmaps are copied here on `Dispatchers.Default`,
 * after the captured identity is re-validated against the authoritative state, so the
 * Compose event handler does not block the Main dispatcher and a superseded request recycles
 * its owned inputs without adopting the document.
 */
private suspend fun EditorViewModel.applySelectionNativeBakeBackground(
    operationToken: Long,
    capturedCurrent: com.projectnuke.keplerstudio.editor.EditorUiState,
    enabledLayers: List<SelectionLayer>,
    params: com.projectnuke.keplerstudio.editor.EditParams,
    engines: com.projectnuke.keplerstudio.editor.EngineSelection,
    presetLook: com.projectnuke.keplerstudio.editor.PresetColorLook?,
    quickEffects: List<com.projectnuke.keplerstudio.editor.ActiveQuickEffect>,
    sourcePath: String?,
    baseContentToken: String,
    capturedSelectionLayers: List<SelectionLayer>,
    capturedActiveSelectionLayerId: String?,
    nextRevision: Int,
    startSnapshot: LeasedEditorSnapshot,
    originalHistoryRef: () -> PendingHistorySnapshot?,
    consumeHistory: () -> Unit,
    releaseHistory: () -> Unit,
    prepareTracker: com.projectnuke.keplerstudio.editor.MemoryTrackerScope?,
) {
    val leasedSnapshot = startSnapshot
    var ownedBase: Bitmap? = null
    var ownedLayers: List<SelectionLayer> = emptyList()
    var maskReservations: MaskReservationBatch? = null
    var bakedOriginal: Bitmap? = null
    var renderedPreview: Bitmap? = null
    var bakeSuccess: RenderResult.Success? = null
    var previewSuccess: RenderResult.Success? = null
    val bakeRenderSlot = OwnedHandoff<OwnedRenderSuccess>()
    val previewRenderSlot = OwnedHandoff<OwnedRenderSuccess>()
    var pendingHistoryOwned: PendingHistorySnapshot? = originalHistoryRef()
    consumeHistory()
    var undoSnapshotOwned: EditorHistorySnapshot? =
        pendingHistoryOwned?.await()
    pendingHistoryOwned = null
    val bakeTracker =
        beginMemoryTracking(
            "selectionNativeBake",
            snapshotState = "rendering",
            transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
        )

    fun releaseTransients() {
        ownedBase?.takeIf { !it.isRecycled }?.recycle()
        ownedBase = null
        ownedLayers.forEach { layer -> layer.bitmap.takeIf { !layer.bitmap.isRecycled }?.recycle() }
        ownedLayers = emptyList()
        maskReservations?.close()
    }

    try {
        val prepared = withContext(Dispatchers.Default) {
            if (!isManagedEditTokenCurrent(operationToken)) return@withContext null
            val workerState = leasedSnapshot.state
            if (workerState.sourcePath != sourcePath) return@withContext null
            if (workerState.baseContentToken != baseContentToken) return@withContext null
            if (workerState.selectionLayers != capturedSelectionLayers) return@withContext null
            if (workerState.activeSelectionLayerId != capturedActiveSelectionLayerId) return@withContext null
            val baseOriginal =
                workerState.originalPreviewBitmap ?: workerState.previewBitmap
                    ?: return@withContext null
            val enabledLayers = workerState.selectionLayers.filter { it.enabled }
            maskReservations =
                reserveSelectionMaskCopies("selectionBake:$operationToken", enabledLayers)
                    ?: throw BitmapAllocationRejectedException(
                        enabledLayers.sumOf { BitmapMemoryBudget.bytes(it.bitmap) }
                    )
            ownedBase =
                baseOriginal.copyOrThrow().also { bakeTracker?.track(it, "selectionBake:base") }
            ownedLayers =
                try {
                workerState.selectionLayers.filter { it.enabled }.copyBitmapsOwned()
                } catch (failure: Throwable) {
                    ownedBase?.takeIf { !it.isRecycled }?.recycle()
                    ownedBase = null
                    throw failure
                }
            ownedLayers.forEach {
                bakeTracker?.track(it.bitmap, "selectionBake:layer:${it.id}")
            }
            prepareTracker?.end()
            true
        }

        if (prepared == null) {
            prepareTracker?.end()
            return
        }

        withContext(Dispatchers.Default) {
            val localOnlyState =
                capturedCurrent.copy(params = EditParams(), activeQuickEffects = emptyList())
            bakeRenderSlot.publish(
                OwnedRenderSuccess(
                    EditorRenderer.render(
                        createRenderRequest(
                            state = localOnlyState,
                            operation = RenderOperation.SelectionNativeBake,
                            basePreview = checkNotNull(ownedBase),
                            revision = nextRevision,
                            params = EditParams(),
                            quickEffects = emptyList(),
                            selectionLayers = ownedLayers,
                            diagnostics = bakeTracker,
                        )
                    ).successOrThrow()
                )
            )
            val bakeOwner = checkNotNull(bakeRenderSlot.take())
            bakeSuccess = bakeOwner.result
            bakedOriginal = checkNotNull(bakeOwner.takeOutput())
            bakeTracker?.track(checkNotNull(bakedOriginal), "selectionBake:original")
        }
        withContext(Dispatchers.Default) {
            previewRenderSlot.publish(
                OwnedRenderSuccess(
                    EditorRenderer.render(
                        createRenderRequest(
                            state = capturedCurrent,
                            operation = RenderOperation.SelectionNativeBake,
                            basePreview = checkNotNull(bakedOriginal),
                            revision = nextRevision,
                            params = params,
                            engines = engines,
                            look = presetLook,
                            quickEffects = quickEffects,
                            selectionLayers = emptyList(),
                            exactRoute = checkNotNull(bakeSuccess).actualRoute,
                            fallbackPolicy = FallbackPolicy.NoFallback,
                            diagnostics = bakeTracker,
                        )
                    ).successOrThrow()
                )
            )
            val previewOwner = checkNotNull(previewRenderSlot.take())
            previewSuccess = previewOwner.result
            renderedPreview = checkNotNull(previewOwner.takeOutput())
            bakeTracker?.track(checkNotNull(renderedPreview), "selectionBake:preview")
        }
        val adoptedOriginal = bakedOriginal ?: error("missing baked original")
        val adoptedPreview = renderedPreview ?: error("missing rendered preview")
        if (
            isManagedEditCurrent(operationToken, nextRevision) &&
                uiState.value.sourcePath == sourcePath &&
                uiState.value.baseContentToken == baseContentToken &&
                uiState.value.selectionLayers == capturedSelectionLayers &&
                uiState.value.activeSelectionLayerId == capturedActiveSelectionLayerId &&
                !isShuttingDown()
        ) {
            updateUiStateAndRecycleReplaced {
                it.copy(
                    originalPreviewBitmap = adoptedOriginal,
                    previewBitmap = adoptedPreview,
                    baseBitmapDirty = true,
                    baseContentToken = newBaseContentToken(),
                    selectionLayers = emptyList(),
                    activeSelectionLayerId = null,
                    isBusy = false,
                    correctionEngineState =
                        it.correctionEngineState.withSuccessfulRender(
                            capturedCurrent.correctionEngineState.documentEngine,
                            checkNotNull(previewSuccess),
                        ),
                    message = "선택 마스크 보정을 원본에 적용했습니다. 저장 결과에도 반영됩니다.",
                )
            }
            bakedOriginal = null
            renderedPreview = null
            settleAdoptedEditHistory(undoSnapshotOwned)
            undoSnapshotOwned = null
            persistDraftSnapshot()
        } else if (isManagedEditTokenCurrent(operationToken)) {
            updateUiState { it.copy(isBusy = false) }
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (failure: Throwable) {
        if (
            isManagedEditCurrent(operationToken, nextRevision) &&
                uiState.value.sourcePath == sourcePath &&
                uiState.value.baseContentToken == baseContentToken &&
                uiState.value.selectionLayers == capturedSelectionLayers &&
                uiState.value.activeSelectionLayerId == capturedActiveSelectionLayerId
        ) {
            (failure as? RenderFailedException)?.failure?.let { renderFailure ->
                updateUiState {
                    it.copy(
                        correctionEngineState =
                            it.correctionEngineState.withFailedRender(
                                capturedCurrent.correctionEngineState.documentEngine,
                                renderFailure,
                            )
                    )
                }
            }
            updateUiState { it.copy(isBusy = false, message = "선택 마스크 보정 적용에 실패했습니다.") }
            if (failure is BitmapAllocationRejectedException) {
                requestAllocationRecovery(
                    MemoryRetryAction.ApplySelectionNative,
                    failure.requiredBytes,
                    selectionTarget =
                        capturedActiveSelectionLayerId?.let(SelectionRetryTarget::Layer)
                            ?: SelectionRetryTarget.Irrelevant,
                )
            }
        } else if (isManagedEditTokenCurrent(operationToken)) {
            updateUiState { it.copy(isBusy = false) }
        }
    } finally {
        bakeRenderSlot.close()
        previewRenderSlot.close()
        releaseTransients()
        undoSnapshotOwned?.let(::recycleHistorySnapshot)
        undoSnapshotOwned = null
        pendingHistoryOwned?.close()
        releaseHistory()
        bakedOriginal?.takeIf { !it.isRecycled }?.recycle()
        renderedPreview?.takeIf { !it.isRecycled }?.recycle()
        leasedSnapshot.close()
        bakeTracker?.end()
    }
}
