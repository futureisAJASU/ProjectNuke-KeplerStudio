package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.editor.BitmapAllocationRejectedException
import com.projectnuke.keplerstudio.editor.BitmapMemoryBudget
import com.projectnuke.keplerstudio.editor.EditParams
import com.projectnuke.keplerstudio.editor.EditorRenderer
import com.projectnuke.keplerstudio.editor.EditorHistorySnapshot
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.FallbackPolicy
import com.projectnuke.keplerstudio.editor.HistorySnapshotStorage
import com.projectnuke.keplerstudio.editor.MemoryRetryAction
import com.projectnuke.keplerstudio.editor.PreparedResourceHandoff
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.RenderFailedException
import com.projectnuke.keplerstudio.editor.RenderOperation
import com.projectnuke.keplerstudio.editor.RenderResult
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

    var undoSnapshot: EditorHistorySnapshot? =
        captureCurrentHistorySnapshot(HistorySnapshotStorage.Exact)
    val prepareTracker =
        beginMemoryTracking(
            "selectionNativeBake:prepare",
            snapshotState = "copying",
            transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
        )
    var ownedBase: Bitmap? =
        runCatching { baseOriginal.copyOrThrow() }
            .getOrElse { failure ->
                prepareTracker?.end()
                undoSnapshot?.let(::recycleHistorySnapshot)
                updateUiState { it.copy(message = "선택 마스크 보정 준비에 실패했습니다.") }
                if (failure is BitmapAllocationRejectedException)
                    requestAllocationRecovery(
                        MemoryRetryAction.ApplySelectionNative,
                        failure.requiredBytes,
                    )
                return
            }
    prepareTracker?.track(checkNotNull(ownedBase), "selectionBake:base")
    var ownedLayers: List<SelectionLayer> =
        runCatching { enabledLayers.copyBitmapsOwned() }
            .getOrElse { failure ->
                ownedBase?.takeIf { !it.isRecycled }?.recycle()
                prepareTracker?.end()
                undoSnapshot?.let(::recycleHistorySnapshot)
                updateUiState { it.copy(message = "선택 마스크 보정 준비에 실패했습니다.") }
                if (failure is BitmapAllocationRejectedException)
                    requestAllocationRecovery(
                        MemoryRetryAction.ApplySelectionNative,
                        failure.requiredBytes,
                    )
                return
            }
    ownedLayers.forEach { prepareTracker?.track(it.bitmap, "selectionBake:layer:${it.id}") }
    val nextRevision = current.revision + 1
    updateUiState {
        it.copy(isBusy = true, revision = nextRevision, message = "선택 마스크 보정을 적용하는 중입니다.")
    }

    launchManagedEditWithPreparedResources(
        { operationToken ->
            var ownedBaseOwned = ownedBase
            ownedBase = null
            var ownedLayersOwned = ownedLayers
            ownedLayers = emptyList()
            var undoSnapshotOwned = undoSnapshot
            undoSnapshot = null
            var bakedOriginal: Bitmap? = null
            var renderedPreview: Bitmap? = null
            var bakeSuccess: RenderResult.Success? = null
            var previewSuccess: RenderResult.Success? = null
            val bakeTracker =
                beginMemoryTracking(
                    "selectionNativeBake",
                    snapshotState = "rendering",
                    transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
                )
            bakeTracker?.track(checkNotNull(ownedBaseOwned), "selectionBake:base")
            ownedLayersOwned.forEach {
                bakeTracker?.track(it.bitmap, "selectionBake:layer:${it.id}")
            }
            prepareTracker?.end()
            try {
                withContext(Dispatchers.Default) {
                    val localOnlyState =
                        current.copy(params = EditParams(), activeQuickEffects = emptyList())
                    bakeSuccess =
                        EditorRenderer.render(
                            createRenderRequest(
                                state = localOnlyState,
                                operation = RenderOperation.SelectionNativeBake,
                                basePreview = checkNotNull(ownedBaseOwned),
                                revision = nextRevision,
                                params = EditParams(),
                                quickEffects = emptyList(),
                                selectionLayers = ownedLayersOwned,
                                diagnostics = bakeTracker,
                            )
                        ).successOrThrow()
                    bakedOriginal = checkNotNull(bakeSuccess).output
                    bakeTracker?.track(checkNotNull(bakedOriginal), "selectionBake:original")
                }
                withContext(Dispatchers.Default) {
                    previewSuccess =
                        EditorRenderer.render(
                            createRenderRequest(
                                state = current,
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
                    renderedPreview = checkNotNull(previewSuccess).output
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
                                    current.correctionEngineState.documentEngine,
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
                                        current.correctionEngineState.documentEngine,
                                        renderFailure,
                                    )
                            )
                        }
                    }
                    updateUiState { it.copy(isBusy = false, message = "선택 마스크 보정 적용에 실패했습니다.") }
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } finally {
                ownedBaseOwned?.takeIf { !it.isRecycled }?.recycle()
                ownedLayersOwned.forEach {
                    it.bitmap.takeIf { bitmap -> !bitmap.isRecycled }?.recycle()
                }
                undoSnapshotOwned?.let(::recycleHistorySnapshot)
                bakedOriginal?.takeIf { !it.isRecycled }?.recycle()
                renderedPreview?.takeIf { !it.isRecycled }?.recycle()
                bakeTracker?.end()
            }
        },
        handoff =
            PreparedResourceHandoff.create(
                "nativeSelectionBake",
                {
                    ownedBase?.takeIf { !it.isRecycled }?.recycle()
                    ownedBase = null
                },
                {
                    ownedLayers.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
                    ownedLayers = emptyList()
                },
                {
                    undoSnapshot?.let(::recycleHistorySnapshot)
                    undoSnapshot = null
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
