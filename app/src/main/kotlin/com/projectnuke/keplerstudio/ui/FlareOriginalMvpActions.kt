package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import com.projectnuke.keplerstudio.editor.BitmapAllocationRejectedException
import com.projectnuke.keplerstudio.editor.AlgorithmContracts
import com.projectnuke.keplerstudio.editor.BakedFeatureProvenance
import com.projectnuke.keplerstudio.editor.BakedFeatureType
import com.projectnuke.keplerstudio.editor.EditorRenderer
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.FlareGuardMode
import com.projectnuke.keplerstudio.editor.HistorySnapshotStorage
import com.projectnuke.keplerstudio.editor.PendingHistorySnapshot
import com.projectnuke.keplerstudio.editor.MemoryRetryAction
import com.projectnuke.keplerstudio.editor.PreparedResourceHandoff
import com.projectnuke.keplerstudio.editor.RenderFailedException
import com.projectnuke.keplerstudio.editor.RenderOperation
import com.projectnuke.keplerstudio.editor.RenderParticipation
import com.projectnuke.keplerstudio.editor.RenderResult
import com.projectnuke.keplerstudio.editor.FeatureExecutionOutcome
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.newBaseContentToken
import com.projectnuke.keplerstudio.editor.successOrThrow
import com.projectnuke.keplerstudio.editor.withFailedRender
import com.projectnuke.keplerstudio.editor.withSuccessfulRender
import com.projectnuke.keplerstudio.editor.withBakedFeatureProvenance
import com.projectnuke.keplerstudio.editor.acquireEditorSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun EditorViewModel.applyFlareOriginalMvp() {
    applyFlareRuleFallbackInternal(FlareGuardMode.NightLight, "번짐 완화", 0.28f)
}

fun EditorViewModel.applySunFlareOriginalMvp() {
    applyFlareRuleFallbackInternal(FlareGuardMode.DaySun, "태양 번짐 완화", 0.24f)
}

private fun EditorViewModel.applyFlareRuleFallbackInternal(
    mode: FlareGuardMode,
    title: String,
    strength: Float,
) {
    if (isShuttingDown()) return
    if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return

    prepareForExternalEdit()
    val startSnapshot = acquireEditorSnapshot("ruleFlare") ?: return
    val current = startSnapshot.state
    val baseOriginal = current.originalPreviewBitmap ?: current.previewBitmap
    if (baseOriginal == null) {
        startSnapshot.close()
        updateUiState { it.copy(message = "$title 적용할 이미지가 없습니다.") }
        return
    }

    var pendingHistory: PendingHistorySnapshot? =
        prepareHistorySnapshot("ruleFlare", startSnapshot)

    val sourcePath = current.sourcePath
    val baseToken = current.baseContentToken
    val params = current.params
    val nextRevision = current.revision + 1

    updateUiState {
        it.copy(
            isBusy = true,
            revision = nextRevision,
            message = "$title 처리 중입니다.",
            flareGuardRuntimeStatus = "규칙 기반 보정으로 처리 중입니다.",
        )
    }

    launchManagedEditWithPreparedResources(
        { operationToken ->
            var pendingHistoryOwned: PendingHistorySnapshot? = pendingHistory
            pendingHistory = null
            var undoSnapshotOwned =
                pendingHistoryOwned?.await()
            pendingHistoryOwned = null
            var ownedBaseOwned: Bitmap? = null
            var adoptedFlare = ownedBaseOwned
            var ownedPreview: Bitmap? = null
            var previewSuccess: RenderResult.Success? = null
            try {
                ownedBaseOwned =
                    withContext(Dispatchers.Default) {
                        baseOriginal.copyOrThrow(Bitmap.Config.ARGB_8888, true)
                    }
                withContext(Dispatchers.Default) {
                    val result =
                        NativePhotoCore.nativeApplyFlareGuardInPlace(
                            checkNotNull(ownedBaseOwned),
                            mode.ordinal,
                            strength.coerceIn(0f, 1f),
                            nextRevision,
                        )
                    if (result < 0) {
                        error("nativeApplyFlareGuardInPlace failed: $result")
                    }
                }
                previewSuccess =
                    withContext(Dispatchers.Default) {
                        EditorRenderer.render(
                            createRenderRequest(
                                state = current,
                                operation = RenderOperation.FlareGuard,
                                basePreview = checkNotNull(ownedBaseOwned),
                                revision = nextRevision,
                                params = params,
                            )
                        ).successOrThrow().let { success ->
                            success.copy(participation = RenderParticipation(rule = true))
                        }
                    }
                ownedPreview = checkNotNull(previewSuccess).output
                adoptedFlare = checkNotNull(ownedBaseOwned)
                val adoptedPreview = checkNotNull(ownedPreview)
                if (
                    isManagedEditCurrent(operationToken, nextRevision) &&
                        uiState.value.sourcePath == sourcePath &&
                        uiState.value.baseContentToken == baseToken &&
                        !isShuttingDown()
                ) {
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            originalPreviewBitmap = adoptedFlare,
                            previewBitmap = adoptedPreview,
                            baseBitmapDirty = true,
                            baseContentToken = newBaseContentToken(),
                            isBusy = false,
                            correctionEngineState =
                                it.correctionEngineState.withSuccessfulRender(
                                    current.correctionEngineState.documentEngine,
                                    checkNotNull(previewSuccess),
                                ),
                            message = "규칙 기반 보정으로 번짐을 완화했습니다.",
                            flareGuardRuntimeStatus = "규칙 기반 보정으로 번짐을 완화했습니다.",
                        ).withBakedFeatureProvenance(
                            provenance =
                                BakedFeatureProvenance(
                                    feature = BakedFeatureType.FlareGuard,
                                    operationId = operationToken.toString(),
                                    sequence =
                                        (it.baseProvenance.operations.lastOrNull()?.sequence ?: 0L) +
                                            1L,
                                    requestedRoute = "V1Rule",
                                    actualRoute = "V1Rule",
                                    participation = RenderParticipation(rule = true),
                                    capabilityPhase = null,
                                    outcome = FeatureExecutionOutcome.Applied,
                                    stageContract = AlgorithmContracts.FLARE_V1,
                                    timestampMillis = System.currentTimeMillis(),
                                ),
                            nativeRenderContract = checkNotNull(previewSuccess).algorithmVersion,
                        )
                    }
                    settleAdoptedEditHistory(undoSnapshotOwned)
                    undoSnapshotOwned = null
                    ownedBaseOwned = null
                    ownedPreview = null
                    scheduleDraftAutosave()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (
                    isManagedEditCurrent(operationToken, nextRevision) &&
                        uiState.value.sourcePath == sourcePath &&
                        uiState.value.baseContentToken == baseToken &&
                        !isShuttingDown()
                ) {
                    updateUiState {
                        it.copy(
                            isBusy = false,
                            correctionEngineState =
                                (t as? RenderFailedException)?.failure?.let { failure ->
                                    it.correctionEngineState.withFailedRender(
                                        current.correctionEngineState.documentEngine,
                                        failure,
                                    )
                                } ?: it.correctionEngineState,
                            message = "번짐 완화에 실패했습니다.",
                            flareGuardRuntimeStatus = "번짐 완화에 실패했습니다.",
                        )
                    }
                }
                if (
                    t is BitmapAllocationRejectedException &&
                        isManagedEditTokenCurrent(operationToken)
                ) {
                    requestAllocationRecovery(
                        if (mode == FlareGuardMode.NightLight) MemoryRetryAction.FlareNight
                        else MemoryRetryAction.FlareSun,
                        t.requiredBytes,
                    )
                }
            } finally {
                ownedBaseOwned?.let { if (!it.isRecycled) it.recycle() }
                ownedPreview?.let { if (!it.isRecycled) it.recycle() }
                undoSnapshotOwned?.let(::recycleHistorySnapshot)
                pendingHistoryOwned?.close()
                startSnapshot.close()
            }
        },
        handoff =
            PreparedResourceHandoff.create(
                "ruleFlare",
                {
                    startSnapshot.close()
                    pendingHistory?.close()
                    pendingHistory = null
                },
                {
                    val live = uiState.value
                    if (live.revision == nextRevision && live.sourcePath == sourcePath &&
                        live.baseContentToken == baseToken) {
                        updateUiState { it.copy(isBusy = false) }
                    }
                },
            ),
    )
}
