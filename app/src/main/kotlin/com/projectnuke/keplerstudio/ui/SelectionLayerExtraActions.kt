package com.projectnuke.keplerstudio.ui

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.editor.LeasedEditorSnapshot
import com.projectnuke.keplerstudio.editor.EditorViewModel
import com.projectnuke.keplerstudio.editor.BitmapAllocationRejectedException
import com.projectnuke.keplerstudio.editor.BitmapMemoryBudget
import com.projectnuke.keplerstudio.editor.acquireEditorSnapshot
import com.projectnuke.keplerstudio.editor.PendingHistorySnapshot
import com.projectnuke.keplerstudio.editor.MemoryRetryAction
import com.projectnuke.keplerstudio.editor.PreparedResourceHandoff
import com.projectnuke.keplerstudio.editor.copyOrThrow
import com.projectnuke.keplerstudio.editor.SelectionLayer
import com.projectnuke.keplerstudio.editor.SelectionLayerKind
import com.projectnuke.keplerstudio.editor.MaskReservation
import com.projectnuke.keplerstudio.editor.reserveSelectionMaskCopy
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun EditorViewModel.toggleSelectionOverlay() {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    updateUiState {
        it.copy(
            showSelectionOverlay = !it.showSelectionOverlay,
            message = if (it.showSelectionOverlay) "마스크 오버레이를 숨겼습니다" else "마스크 오버레이를 표시합니다"
        )
    }
}

fun EditorViewModel.duplicateActiveSelectionLayer() {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    invalidateSelectionPreview()
    prepareForExternalEdit()
    val startSnapshot = acquireEditorSnapshot("duplicateSelection") ?: return
    val state = startSnapshot.state
    val active = state.selectionLayers.firstOrNull { it.id == state.activeSelectionLayerId } ?: run {
        startSnapshot.close()
        updateUiState { it.copy(message = "복제할 마스크를 선택해 주세요") }
        return
    }
    val sourcePath = state.sourcePath
    val baseContentToken = state.baseContentToken
    val activeId = active.id
    val activeName = active.name
    val activeKind = active.kind
    val activeBitmap = active.bitmap
    val sourceActiveConfig = activeBitmap.config ?: Bitmap.Config.ARGB_8888
    val nextRevision = state.revision + 1
    var pendingHistory: PendingHistorySnapshot? =
        prepareHistorySnapshot("duplicateSelection", startSnapshot)

    updateUiState { it.copy(isBusy = true, revision = nextRevision, message = "마스크를 복제하는 중입니다.") }

    launchManagedEditWithPreparedResources(
        { operationToken ->
            duplicateSelectionLayerBackground(
                operationToken = operationToken,
                nextRevision = nextRevision,
                sourcePath = sourcePath,
                baseContentToken = baseContentToken,
                activeId = activeId,
                activeName = activeName,
                activeKind = activeKind,
                sourceBitmap = activeBitmap,
                sourceConfig = sourceActiveConfig,
                startSnapshot = startSnapshot,
                originalHistoryRef = { pendingHistory },
                consumeHistory = { pendingHistory = null },
                releaseHistory = { pendingHistory?.close(); pendingHistory = null },
            )
        },
        handoff =
            PreparedResourceHandoff.create(
                "duplicateSelection",
                {
                    startSnapshot.close()
                    pendingHistory?.close()
                    pendingHistory = null
                },
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

private suspend fun EditorViewModel.duplicateSelectionLayerBackground(
    operationToken: Long,
    nextRevision: Int,
    sourcePath: String?,
    baseContentToken: String,
    activeId: String,
    activeName: String,
    activeKind: SelectionLayerKind,
    sourceBitmap: Bitmap,
    sourceConfig: Bitmap.Config,
    startSnapshot: LeasedEditorSnapshot,
    originalHistoryRef: () -> PendingHistorySnapshot?,
    consumeHistory: () -> Unit,
    releaseHistory: () -> Unit,
) {
    val leasedSnapshot = startSnapshot
    var ownedCopy: Bitmap? = null
    var outputReservation: MaskReservation? = null
    var pendingHistoryOwned: PendingHistorySnapshot? = originalHistoryRef()
    consumeHistory()
    var undoSnapshotOwned: com.projectnuke.keplerstudio.editor.EditorHistorySnapshot? =
        withContext(Dispatchers.Default) { pendingHistoryOwned?.await() }
    pendingHistoryOwned = null
    try {
        val prepared = withContext(Dispatchers.Default) {
            if (!isManagedEditTokenCurrent(operationToken)) return@withContext null
            val workerState = leasedSnapshot.state
            if (workerState.sourcePath != sourcePath) return@withContext null
            if (workerState.baseContentToken != baseContentToken) return@withContext null
            val workerActive = workerState.selectionLayers.firstOrNull { it.id == activeId }
                ?: return@withContext null
            outputReservation = reserveSelectionMaskCopy(
                "duplicate:$activeId",
                workerActive.bitmap,
                sourceConfig,
            ) ?: throw BitmapAllocationRejectedException(
                BitmapMemoryBudget.bytes(workerActive.bitmap.width, workerActive.bitmap.height, sourceConfig)
            )
            ownedCopy = workerActive.bitmap.copyOrThrow(sourceConfig, true)
            ownedCopy
        }
        if (prepared == null) return

        val newLayer = SelectionLayer(
            id = newExtraSelectionId(),
            name = "$activeName 복사본",
            kind = activeKind,
            bitmap = prepared,
        )

        val adoptable =
            isManagedEditCurrent(operationToken, nextRevision) &&
                uiState.value.sourcePath == sourcePath &&
                uiState.value.baseContentToken == baseContentToken

        if (adoptable) {
            var applied = false
            updateUiState { current ->
                if (
                    !isManagedEditCurrent(operationToken, nextRevision) ||
                        current.sourcePath != sourcePath ||
                        current.baseContentToken != baseContentToken
                ) {
                    current
                } else {
                    applied = true
                    current.copy(
                        selectionLayers = current.selectionLayers + newLayer,
                        activeSelectionLayerId = newLayer.id,
                        isBusy = false,
                        message = "마스크를 복제했습니다",
                    )
                }
            }
            if (applied) {
                ownedCopy = null
                settleAdoptedEditHistory(undoSnapshotOwned)
                undoSnapshotOwned = null
                markMemoryRetrySucceeded(MemoryRetryAction.DuplicateSelection)
                persistDraftSnapshot()
            } else {
                ownedCopy?.takeIf { !it.isRecycled }?.recycle()
                ownedCopy = null
            }
        } else if (isManagedEditTokenCurrent(operationToken)) {
            updateUiState { it.copy(isBusy = false) }
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        if (isManagedEditTokenCurrent(operationToken)) {
            val msg = if (t is BitmapAllocationRejectedException)
                "메모리가 부족하여 마스크를 복제하지 못했습니다."
            else "마스크 복제에 실패했습니다."
            updateUiState { it.copy(isBusy = false, message = msg) }
            if (t is BitmapAllocationRejectedException)
                requestAllocationRecovery(MemoryRetryAction.DuplicateSelection, t.requiredBytes)
        }
    } finally {
        leasedSnapshot.close()
        ownedCopy?.takeIf { !it.isRecycled }?.recycle()
        outputReservation?.close()
        undoSnapshotOwned?.let(::recycleHistorySnapshot)
        undoSnapshotOwned = null
        pendingHistoryOwned?.close()
        releaseHistory()
    }
}

fun EditorViewModel.createBackgroundSelectionFromActive() {
    if (!canEnterEditorAction(allowMaskSupersession = true)) return
    invalidateSelectionPreview()
    prepareForExternalEdit()
    val startSnapshot = acquireEditorSnapshot("createBackgroundSelection") ?: return
    val state = startSnapshot.state
    val active = state.selectionLayers.firstOrNull { it.id == state.activeSelectionLayerId } ?: run {
        startSnapshot.close()
        updateUiState { it.copy(message = "배경으로 변환할 마스크를 선택해 주세요") }
        return
    }
    val sourcePath = state.sourcePath
    val baseContentToken = state.baseContentToken
    val activeId = active.id
    val activeEnabled = active.enabled
    val activeInverted = active.inverted
    val activeOpacity = active.opacity
    val activeLocalParams = active.localParams
    val activeBitmap = active.bitmap
    val sourceActiveConfig = activeBitmap.config ?: Bitmap.Config.ARGB_8888
    val nextRevision = state.revision + 1
    var pendingHistory: PendingHistorySnapshot? =
        prepareHistorySnapshot("backgroundSelection", startSnapshot)

    updateUiState { it.copy(isBusy = true, revision = nextRevision, message = "배경 마스크를 만드는 중입니다.") }

    launchManagedEditWithPreparedResources(
        { operationToken ->
            createBackgroundSelectionBackground(
                operationToken = operationToken,
                nextRevision = nextRevision,
                sourcePath = sourcePath,
                baseContentToken = baseContentToken,
                activeId = activeId,
                activeEnabled = activeEnabled,
                activeInverted = activeInverted,
                activeOpacity = activeOpacity,
                activeLocalParams = activeLocalParams,
                sourceBitmap = activeBitmap,
                sourceConfig = sourceActiveConfig,
                startSnapshot = startSnapshot,
                originalHistoryRef = { pendingHistory },
                consumeHistory = { pendingHistory = null },
                releaseHistory = { pendingHistory?.close(); pendingHistory = null },
            )
        },
        handoff =
            PreparedResourceHandoff.create(
                "backgroundSelection",
                {
                    startSnapshot.close()
                    pendingHistory?.close()
                    pendingHistory = null
                },
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

private suspend fun EditorViewModel.createBackgroundSelectionBackground(
    operationToken: Long,
    nextRevision: Int,
    sourcePath: String?,
    baseContentToken: String,
    activeId: String,
    activeEnabled: Boolean,
    activeInverted: Boolean,
    activeOpacity: Float,
    activeLocalParams: com.projectnuke.keplerstudio.editor.EditParams,
    sourceBitmap: Bitmap,
    sourceConfig: Bitmap.Config,
    startSnapshot: LeasedEditorSnapshot,
    originalHistoryRef: () -> PendingHistorySnapshot?,
    consumeHistory: () -> Unit,
    releaseHistory: () -> Unit,
) {
    val leasedSnapshot = startSnapshot
    var ownedCopy: Bitmap? = null
    var outputReservation: MaskReservation? = null
    var pendingHistoryOwned: PendingHistorySnapshot? = originalHistoryRef()
    consumeHistory()
    var undoSnapshotOwned: com.projectnuke.keplerstudio.editor.EditorHistorySnapshot? =
        withContext(Dispatchers.Default) { pendingHistoryOwned?.await() }
    pendingHistoryOwned = null
    try {
        val prepared = withContext(Dispatchers.Default) {
            if (!isManagedEditTokenCurrent(operationToken)) return@withContext null
            val workerState = leasedSnapshot.state
            if (workerState.sourcePath != sourcePath) return@withContext null
            if (workerState.baseContentToken != baseContentToken) return@withContext null
            val workerActive = workerState.selectionLayers.firstOrNull { it.id == activeId }
                ?: return@withContext null
            val source = if (workerActive.bitmap === sourceBitmap && !sourceBitmap.isRecycled) sourceBitmap else workerActive.bitmap
            outputReservation = reserveSelectionMaskCopy(
                "background:$activeId",
                source,
                sourceConfig,
            ) ?: throw BitmapAllocationRejectedException(
                BitmapMemoryBudget.bytes(source.width, source.height, sourceConfig)
            )
            ownedCopy = source.copyOrThrow(sourceConfig, true)
            ownedCopy
        }
        if (prepared == null) return

        val newLayer = SelectionLayer(
            id = newExtraSelectionId(),
            name = "배경 마스크",
            kind = SelectionLayerKind.Background,
            bitmap = prepared,
            enabled = activeEnabled,
            inverted = !activeInverted,
            opacity = activeOpacity,
            localParams = activeLocalParams,
        )

        val adoptable =
            isManagedEditCurrent(operationToken, nextRevision) &&
                uiState.value.sourcePath == sourcePath &&
                uiState.value.baseContentToken == baseContentToken

        if (adoptable) {
            var applied = false
            updateUiState { current ->
                if (
                    !isManagedEditCurrent(operationToken, nextRevision) ||
                        current.sourcePath != sourcePath ||
                        current.baseContentToken != baseContentToken
                ) {
                    current
                } else {
                    applied = true
                    current.copy(
                        selectionLayers = current.selectionLayers + newLayer,
                        activeSelectionLayerId = newLayer.id,
                        isBusy = false,
                        message = "선택한 마스크를 기준으로 배경 마스크를 만들었습니다",
                    )
                }
            }
            if (applied) {
                ownedCopy = null
                settleAdoptedEditHistory(undoSnapshotOwned)
                undoSnapshotOwned = null
                markMemoryRetrySucceeded(MemoryRetryAction.BackgroundSelection)
                persistDraftSnapshot()
            } else {
                ownedCopy?.takeIf { !it.isRecycled }?.recycle()
                ownedCopy = null
            }
        } else if (isManagedEditTokenCurrent(operationToken)) {
            updateUiState { it.copy(isBusy = false) }
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        if (isManagedEditTokenCurrent(operationToken)) {
            val msg = if (t is BitmapAllocationRejectedException)
                "메모리가 부족하여 배경 마스크를 만들지 못했습니다."
            else "배경 마스크 생성에 실패했습니다."
            updateUiState { it.copy(isBusy = false, message = msg) }
            if (t is BitmapAllocationRejectedException)
                requestAllocationRecovery(MemoryRetryAction.BackgroundSelection, t.requiredBytes)
        }
    } finally {
        leasedSnapshot.close()
        ownedCopy?.takeIf { !it.isRecycled }?.recycle()
        outputReservation?.close()
        undoSnapshotOwned?.let(::recycleHistorySnapshot)
        undoSnapshotOwned = null
        pendingHistoryOwned?.close()
        releaseHistory()
    }
}

private fun newExtraSelectionId(): String = "sel_" + UUID.randomUUID().toString().take(8)
