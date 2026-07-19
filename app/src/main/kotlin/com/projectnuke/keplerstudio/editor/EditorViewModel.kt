package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.heifwriter.HeifWriter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import java.util.ArrayDeque
import java.util.Collections
import java.io.File
import java.io.FileOutputStream
import java.util.IdentityHashMap
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val _uiState = MutableStateFlow(
        EditorUiState(nativeVersion = runCatching { NativePhotoCore.nativeVersion() }.getOrElse { "native load failed: ${it.message}" })
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _brushPreviewEpoch = MutableStateFlow(0L)
    val brushPreviewEpoch: StateFlow<Long> = _brushPreviewEpoch.asStateFlow()

    private var nativeSession: Long = 0L
    private var renderJob: Job? = null
    private var exportJob: Job? = null
    private var exportToken: Long = 0L
    internal var selectionLivePreviewJob: Job? = null
    internal var cropJob: Job? = null
    private var draftSaveJob: Job? = null
    private val draftSaveMutex = Mutex()
    private val savedExportHistoryMutex = Mutex()
    @Volatile private var savedExportHistoryRevision: Long = 0L
    /** Invalidates every queued draft save/restore when the document changes. */
    private var draftOperationEpoch: Long = 0L
    @Volatile private var draftPointerBaseline: String? = currentDraftGenerationId(app.applicationContext)
    private var managedEditJob: Job? = null
    private var managedEditToken: Long = 0L
    @Volatile private var shuttingDown: Boolean = false
    private var cropOperationToken: Long = 0L
    internal var selectionParamTransaction: SelectionParamTransaction? = null
    private var selectionGestureCounter: Long = 0L
    private var selectionPreviewCounter: Long = 0L
    private var transactionFinishJob: Job? = null
    private var brushingSnapshot: EditorHistorySnapshot? = null
    private var brushLayerId: String? = null
    private var brushBaseToken: String? = null
    private var brushRevision: Int = 0
    private var brushChanged: Boolean = false
    private var brushEpochCounter: Long = 0L
    private var paramUndoWindowJob: Job? = null
    private var paramUndoWindowOpen: Boolean = false
    private var pendingParamUndoSnapshot: EditorHistorySnapshot? = null
    private var paramUndoSnapshotCommitted: Boolean = false
    private var lastSuccessfullyRenderedParams: EditParams = EditParams()
    private var activeParamRenderRevision: Int? = null
    private var restoreDraftToken: Long = 0L
    private val undoHistory = ArrayDeque<EditorHistorySnapshot>()
    private val redoHistory = ArrayDeque<EditorHistorySnapshot>()

    fun updateUiState(transform: (EditorUiState) -> EditorUiState) {
        updateUiStateAndRecycleReplaced(transform)
    }

    /** Atomically update UI state; recycles displaced Bitmaps retained only by the previous state. */
    internal fun updateUiStateAndRecycleReplaced(transform: (EditorUiState) -> EditorUiState) {
        var previousState: EditorUiState? = null
        var nextState: EditorUiState? = null
        _uiState.update { current ->
            previousState = current
            transform(current).also { nextState = it }
        }
        val prev = previousState ?: return
        val next = nextState ?: return
        releaseOrphanedBitmaps(prev, next)
    }

    private fun releaseOrphanedBitmaps(previous: EditorUiState, next: EditorUiState) {
        val stillRetained = identityBitmapSet()
        next.previewBitmap?.let(stillRetained::add)
        next.originalPreviewBitmap?.let(stillRetained::add)
        next.selectionLayers.forEach { stillRetained.add(it.bitmap) }
        val toRelease = ArrayList<Bitmap>(8)
        previous.previewBitmap?.takeIf { it !in stillRetained && !it.isRecycled }?.let(toRelease::add)
        previous.originalPreviewBitmap?.takeIf { it !in stillRetained && !it.isRecycled }?.let(toRelease::add)
        previous.selectionLayers.forEach { layer ->
            if (layer.bitmap !in stillRetained && !layer.bitmap.isRecycled) toRelease.add(layer.bitmap)
        }
        toRelease.forEach { it.recycle() }
    }

    /** Starts a superseding bitmap/model edit. Callers must gate adoption with [isManagedEditCurrent]. */
    internal fun launchManagedEdit(block: suspend (Long) -> Unit): Job {
        managedEditJob?.cancel()
        val token = ++managedEditToken
        return viewModelScope.launch {
            try {
                block(token)
            } finally {
                if (managedEditToken == token) managedEditJob = null
            }
        }.also { managedEditJob = it }
    }

    internal fun isManagedEditCurrent(token: Long, revision: Int): Boolean =
        !shuttingDown && managedEditToken == token && _uiState.value.revision == revision

    internal fun isManagedEditTokenCurrent(token: Long): Boolean =
        !shuttingDown && managedEditToken == token

    internal fun isShuttingDown(): Boolean = shuttingDown

    internal fun canEnterEditorAction(allowMaskSupersession: Boolean = false): Boolean {
        if (shuttingDown) return false
        val state = _uiState.value
        return !state.isBusy || allowMaskSupersession && isBusyOwnedByMaskSupersedable()
    }

    private suspend fun invalidateRemovedHistoryThumbnails(context: Context, result: SavedExportHistoryResult) {
        withContext(Dispatchers.IO) {
            savedExportHistoryMutex.withLock {
                val retained = loadSavedExportsFromPrefs(context).map { it.uriString }.toSet()
                result.removedUris.filterNot(retained::contains).forEach { ThumbnailBitmapCache.invalidate("export:$it") }
            }
        }
    }

    private suspend fun isCurrentExport(
        token: Long,
        sourcePath: String,
        baseToken: String,
        revision: Int
    ): Boolean {
        val job = currentCoroutineContext()[Job] ?: return false
        if (!job.isActive || exportJob !== job || exportToken != token || shuttingDown) return false
        val state = _uiState.value
        return state.sourcePath == sourcePath &&
            state.baseContentToken == baseToken &&
            state.revision == revision
    }

    private fun isCurrentExportIdentity(
        token: Long,
        sourcePath: String,
        baseToken: String,
        revision: Int,
        job: Job
    ): Boolean {
        if (!job.isActive || exportJob !== job || exportToken != token || shuttingDown) return false
        val state = _uiState.value
        return state.sourcePath == sourcePath &&
            state.baseContentToken == baseToken &&
            state.revision == revision
    }

    internal fun beginCropOperation(): Long = ++cropOperationToken

    internal fun invalidateCropOperation() {
        cropOperationToken += 1L
    }

    internal fun isCropOperationCurrent(token: Long): Boolean = cropOperationToken == token

    internal fun isCropResultCurrent(token: Long, revision: Int): Boolean =
        !shuttingDown && isCropOperationCurrent(token) && _uiState.value.revision == revision

    internal fun beginSelectionPreview(transaction: SelectionParamTransaction): Long {
        val token = ++selectionPreviewCounter
        transaction.latestPreviewToken = token
        transaction.finalPreviewToken = null
        transaction.finalPreviewRevision = null
        transaction.finalPreviewBaseToken = null
        transaction.finalPreviewLayerId = null
        transaction.previewRevision = null
        transaction.previewBaseToken = null
        transaction.previewLayerId = null
        transaction.succeeded = false
        transaction.previewJob?.cancel()
        return token
    }

    internal fun isSelectionPreviewCurrent(
        transaction: SelectionParamTransaction,
        token: Long,
        revision: Int,
        baseToken: String,
        activeId: String?
    ): Boolean {
        if (shuttingDown) return false
        if (selectionParamTransaction !== transaction) return false
        if (transaction.latestPreviewToken != token) return false
        val state = _uiState.value
        return state.revision == revision &&
            state.baseContentToken == baseToken &&
            state.activeSelectionLayerId == activeId
    }

    internal fun beginSelectionParamGesture(): Boolean {
        if (selectionParamTransaction != null) return true
        val snapshot = captureCurrentHistorySnapshot() ?: return false
        val state = _uiState.value
        selectionParamTransaction = SelectionParamTransaction(
            gestureId = ++selectionGestureCounter,
            snapshot = snapshot,
            startRevision = state.revision,
            baseContentToken = state.baseContentToken,
            activeSelectionLayerId = state.activeSelectionLayerId
        )
        return true
    }

    internal fun startSelectionParamGesture(): Boolean {
        if (shuttingDown) return false
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return false
        prepareForMaskInteraction()
        if (uiState.value.isBusy) return false
        return beginSelectionParamGesture()
    }

    internal fun markSelectionPreviewSucceeded(
        transaction: SelectionParamTransaction,
        token: Long,
        revision: Int,
        baseToken: String,
        activeId: String?
    ) {
        if (selectionParamTransaction !== transaction) return
        if (transaction.latestPreviewToken != token) return
        transaction.finalPreviewToken = token
        transaction.finalPreviewRevision = revision
        transaction.finalPreviewBaseToken = baseToken
        transaction.finalPreviewLayerId = activeId
        transaction.previewRevision = revision
        transaction.previewBaseToken = baseToken
        transaction.previewLayerId = activeId
        transaction.succeeded = true
    }

    internal fun currentSelectionParamTransaction(): SelectionParamTransaction? =
        selectionParamTransaction

    internal fun bindSelectionPreviewJob(transaction: SelectionParamTransaction, job: Job, revision: Int, baseToken: String, activeId: String?) {
        transaction.previewJob?.cancel()
        transaction.previewJob = job
        transaction.previewRevision = revision
        transaction.previewBaseToken = baseToken
        transaction.previewLayerId = activeId
        selectionLivePreviewJob = job
    }

    internal fun finishSelectionParamGesture() {
        val transaction = selectionParamTransaction ?: return
        if (transactionFinishJob?.isActive == true && transaction.finished != true) return
        val job = viewModelScope.launch {
            var settled = false
            try {
                transaction.previewJob?.join()
                settleSelectionParamTransaction(transaction)
                settled = true
                transaction.finished = true
            } finally {
                // A late finally may only rollback while this job still owns the active slot.
                if (!settled && selectionParamTransaction === transaction && transaction.finishJobRef === coroutineContext[Job]) {
                    restoreSelectionParamTransaction(transaction)
                }
            }
        }
        transactionFinishJob = job
        transaction.finishJobRef = job
    }

    /**
     * Settle the active transaction: commit on success + current + token match, otherwise restore.
     * No-op when [transaction] is no longer the active one. Clears the active slot itself.
     */
    private fun settleSelectionParamTransaction(transaction: SelectionParamTransaction) {
        if (selectionParamTransaction !== transaction) return
        if (transaction.settled) return
        transaction.settled = true
        val state = _uiState.value
        val finalToken = transaction.finalPreviewToken
        val finalRevision = transaction.finalPreviewRevision
        val finalBaseToken = transaction.finalPreviewBaseToken
        val finalLayerId = transaction.finalPreviewLayerId
        val previewValid = transaction.succeeded &&
            finalToken != null &&
            transaction.latestPreviewToken == finalToken &&
            finalRevision != null &&
            finalBaseToken != null &&
            finalLayerId != null &&
            transaction.previewJob?.isActive != true
        val stillCurrent = !shuttingDown &&
            state.baseContentToken == transaction.baseContentToken &&
            state.activeSelectionLayerId == transaction.activeSelectionLayerId
        if (previewValid && stillCurrent &&
            state.revision == finalRevision &&
            state.baseContentToken == finalBaseToken &&
            state.activeSelectionLayerId == finalLayerId) {
            if (!transaction.committed) {
                transaction.committed = true
                commitUndoSnapshot(transaction.snapshot, clearRedo = true)
            }
            forceDraftSaveAsync()
            clearSelectionParamTransaction(transaction)
        } else if (stillCurrent && !transaction.hasOptimisticLiveParams(state)) {
            recycleHistorySnapshot(transaction.snapshot)
            clearSelectionParamTransaction(transaction)
        } else {
            restoreSelectionParamTransaction(transaction)
        }
    }

    private fun restoreSelectionParamTransaction(transaction: SelectionParamTransaction) {
        if (selectionParamTransaction !== transaction) return
        if (transaction.committed) {
            clearSelectionParamTransaction(transaction)
            return
        }
        if (shuttingDown) {
            recycleHistorySnapshot(transaction.snapshot)
            clearSelectionParamTransaction(transaction)
            return
        }
        restoreSnapshotWithoutHistory(transaction.snapshot)
        clearSelectionParamTransaction(transaction)
    }

    private fun clearSelectionParamTransaction(transaction: SelectionParamTransaction) {
        if (selectionParamTransaction !== transaction) return
        selectionParamTransaction = null
        transaction.previewJob = null
        if (transactionFinishJob === transaction.finishJobRef) transactionFinishJob = null
        transaction.finishJobRef = null
    }

    private fun settleSelectionParamTransactionForSupersession() {
        selectionParamTransaction?.let { settleSelectionParamTransaction(it) }
        selectionLivePreviewJob?.cancel()
        selectionPreviewCounter += 1L
        clearSelectionParamTransaction(selectionParamTransaction ?: return)
    }

    internal fun isBusyOwnedByMaskSupersedable(): Boolean {
        val state = _uiState.value
        if (activeParamRenderRevision != null && activeParamRenderRevision == state.revision && renderJob?.isActive == true) return true
        val transaction = selectionParamTransaction
        if (transaction != null && transaction.previewJob?.isActive == true) {
            if (transaction.previewRevision != null &&
                transaction.previewRevision == state.revision &&
                transaction.previewBaseToken == state.baseContentToken &&
                transaction.previewLayerId == state.activeSelectionLayerId) {
                return true
            }
        }
        return false
    }

    internal fun negateBrushStrokeDuringShutdownIfPresent() {
        val snapshot = brushingSnapshot
        if (snapshot == null) return
        brushingSnapshot = null
        brushLayerId = null
        brushBaseToken = null
        brushChanged = false
        recycleHistorySnapshot(snapshot)
    }

    internal fun invalidateSelectionPreview() {
        prepareForMaskInteraction()
    }

    internal fun beginBrushStroke(): Boolean {
        if (shuttingDown) return false
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return false
        prepareForMaskInteraction()
        if (uiState.value.isBusy) return false
        if (brushingSnapshot != null) return true
        val state = _uiState.value
        val layerId = state.activeSelectionLayerId ?: return false
        val layer = state.selectionLayers.firstOrNull { it.id == layerId } ?: return false
        if (state.params != lastSuccessfullyRenderedParams || activeParamRenderRevision != null) return false
        val snapshot = captureCurrentHistorySnapshot() ?: return false
        val ownedMask = runCatching { layer.bitmap.copyOrThrow(Bitmap.Config.ARGB_8888, true) }.getOrElse {
            recycleHistorySnapshot(snapshot)
            return false
        }
        brushingSnapshot = snapshot
        brushLayerId = layerId
        brushBaseToken = state.baseContentToken
        brushRevision = state.revision
        brushChanged = false
        brushEpochCounter = 0L
        _brushPreviewEpoch.value = 0L
        updateUiState { current ->
            current.copy(selectionLayers = current.selectionLayers.map { item ->
                if (item.id == layerId) item.copy(bitmap = ownedMask) else item
            })
        }
        return true
    }

    internal fun markBrushChanged(changed: Boolean) {
        brushChanged = brushChanged || changed
    }

    internal fun isBrushStrokeCurrent(layerId: String?): Boolean {
        if (brushingSnapshot == null) return false
        val state = _uiState.value
        return layerId == brushLayerId &&
            state.activeSelectionLayerId == brushLayerId &&
            state.baseContentToken == brushBaseToken &&
            state.revision == brushRevision
    }

    internal fun hasActiveBrushStroke(): Boolean = brushingSnapshot != null

    internal fun nextBrushPreviewEpoch(): Long {
        val epoch = ++brushEpochCounter
        _brushPreviewEpoch.value = epoch
        return epoch
    }

    internal fun finishBrushStroke() {
        val snapshot = brushingSnapshot ?: return
        if (shuttingDown) {
            brushingSnapshot = null
            brushLayerId = null
            brushBaseToken = null
            brushChanged = false
            recycleHistorySnapshot(snapshot)
            return
        }
        if (!isBrushStrokeCurrent(brushLayerId)) {
            cancelBrushStroke()
            return
        }
        brushingSnapshot = null
        brushLayerId = null
        brushBaseToken = null
        val changed = brushChanged
        brushChanged = false
        if (changed) {
            updateUiState { it.copy(revision = it.revision + 1, isBusy = false) }
            commitUndoSnapshot(snapshot, clearRedo = true)
            forceDraftSaveAsync()
        } else {
            restoreSnapshotWithoutHistory(snapshot)
        }
    }

    internal fun cancelBrushStroke() {
        val snapshot = brushingSnapshot ?: return
        brushingSnapshot = null
        brushLayerId = null
        brushBaseToken = null
        brushChanged = false
        if (shuttingDown) {
            recycleHistorySnapshot(snapshot)
            return
        }
        restoreSnapshotWithoutHistory(snapshot)
    }

    private fun restoreSnapshotWithoutHistory(snapshot: EditorHistorySnapshot) {
        updateUiState { current ->
            current.copy(
                params = snapshot.params,
                noiseEngine = snapshot.noiseEngine,
                detailEngine = snapshot.detailEngine,
                toneEngine = snapshot.toneEngine,
                hazeEngine = snapshot.hazeEngine,
                baseBitmapDirty = snapshot.baseBitmapDirty,
                baseContentToken = snapshot.baseContentToken,
                previewBitmap = snapshot.previewBitmap,
                originalPreviewBitmap = snapshot.originalPreviewBitmap,
                presetLook = snapshot.presetLook,
                cropState = snapshot.cropState,
                selectionLayers = snapshot.selectionLayers,
                activeSelectionLayerId = snapshot.activeSelectionLayerId,
                selectionPaintSettings = snapshot.selectionPaintSettings,
                showSelectionOverlay = snapshot.showSelectionOverlay,
                activeQuickEffects = snapshot.activeQuickEffects,
                flareGuardRuntimeStatus = snapshot.flareGuardRuntimeStatus,
                isBusy = false,
                revision = current.revision + 1
            )
        }
    }

    private fun invalidateManagedEdits() {
        managedEditToken += 1L
        managedEditJob?.cancel()
        managedEditJob = null
    }

    private fun invalidateExport() {
        exportToken += 1L
        exportJob?.cancel()
    }

    private fun invalidateDraftOperations() {
        draftOperationEpoch += 1L
        restoreDraftToken += 1L
        draftSaveJob?.cancel()
    }

    private fun beginDraftSaveOperation(): Pair<Long, Job?> {
        val previous = draftSaveJob
        draftOperationEpoch += 1L
        previous?.cancel()
        return draftOperationEpoch to previous
    }

    private fun isDraftPayloadCurrent(payload: DraftSavePayload): Boolean {
        return isDraftPayloadDocumentCurrent(payload) && payload.expectedPointerGenerationId == draftPointerBaseline
    }

    private fun isDraftPayloadDocumentCurrent(payload: DraftSavePayload): Boolean {
        val current = _uiState.value
        return payload.epoch == draftOperationEpoch &&
            payload.baseContentToken == current.baseContentToken &&
            payload.capturedRevision == current.revision &&
            payload.previousVisibleGenerationId == current.draftGenerationId &&
            sameCanonicalPath(payload.sourcePath, current.sourcePath) &&
            sameOptionalCanonicalPath(payload.previousVisibleDraftPath, current.draftSourcePath)
    }

    private fun isDraftResultCurrent(result: DraftSaveResult): Boolean {
        return result.epoch == draftOperationEpoch && draftResultMatchesState(result, _uiState.value)
    }

    private fun draftResultMatchesState(result: DraftSaveResult, current: EditorUiState): Boolean {
        return result.expectedPointerGenerationId == draftPointerBaseline &&
            draftResultMatchesDocumentState(result, current)
    }

    private fun draftResultMatchesDocumentState(result: DraftSaveResult, current: EditorUiState): Boolean {
        return result.epoch == draftOperationEpoch &&
            result.baseContentToken == current.baseContentToken &&
            result.capturedRevision == current.revision &&
            result.previousVisibleGenerationId == current.draftGenerationId &&
            sameCanonicalPath(result.originalSourcePath, current.sourcePath) &&
            sameOptionalCanonicalPath(result.previousDraftPath, current.draftSourcePath)
    }

    private fun isBitmapRetainedByCurrentState(bitmap: Bitmap): Boolean {
        val state = _uiState.value
        if (state.previewBitmap === bitmap) return true
        if (state.originalPreviewBitmap === bitmap) return true
        return state.selectionLayers.any { it.bitmap === bitmap }
    }

    fun recordUserEditForUndo(clearRedo: Boolean = true) {
        pushUndoSnapshot(clearRedo = clearRedo)
    }

    internal fun captureCurrentHistorySnapshot(): EditorHistorySnapshot? =
        runCatching { uiState.value.toHistorySnapshot() }.getOrNull()

    internal fun commitUndoSnapshot(snapshot: EditorHistorySnapshot, clearRedo: Boolean) {
        undoHistory.addLast(snapshot)
        trimUndoHistory()
        if (clearRedo) {
            redoHistory.forEach(::recycleHistorySnapshot)
            redoHistory.clear()
        }
        updateHistoryFlags()
    }

    internal fun recycleHistorySnapshot(snapshot: EditorHistorySnapshot) {
        snapshot.recycleBitmaps()
    }

    fun persistDraftSnapshot() {
        forceDraftSaveAsync()
    }

    suspend fun persistDraftSnapshotNow(): Boolean {
        val (epoch, previous) = beginDraftSaveOperation()
        previous?.join()
        val currentJob = currentCoroutineContext()[Job]
        if (currentJob != null) draftSaveJob = currentJob
        return try {
            persistDraftSnapshotInternal(epoch)
        } finally {
            if (draftSaveJob === currentJob) draftSaveJob = null
        }
    }

    internal fun scheduleDraftAutosave(delayMs: Long = 2000L) {
        val (epoch, _) = beginDraftSaveOperation()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(delayMs)
                persistDraftSnapshotInternal(epoch)
            } finally {
                if (draftSaveJob === currentCoroutineContext()[Job]) draftSaveJob = null
            }
        }
        draftSaveJob = job
        job.start()
    }

    private fun forceDraftSaveAsync() {
        val (epoch, _) = beginDraftSaveOperation()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                persistDraftSnapshotInternal(epoch)
            } finally {
                if (draftSaveJob === currentCoroutineContext()[Job]) draftSaveJob = null
            }
        }
        draftSaveJob = job
        job.start()
    }

    private suspend fun persistDraftSnapshotInternal(draftEpoch: Long): Boolean {
        val context = getApplication<Application>()
        val expectedPointer = withContext(Dispatchers.IO) {
            draftSaveMutex.withLock {
                if (draftEpoch != draftOperationEpoch || shuttingDown) return@withLock null
                val diskPointer = currentDraftGenerationId(context)
                if (diskPointer != draftPointerBaseline) return@withLock null
                DraftPointerSnapshot(diskPointer)
            }
        } ?: return false
        val draftState = _uiState.value
        val payload = try {
            createDraftSavePayload(context, draftState, draftEpoch, expectedPointer.generationId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            logDraftSaveFailure(t)
            updateUiStateAndRecycleReplaced {
                if (draftEpoch == draftOperationEpoch && it.revision == draftState.revision &&
                    it.baseContentToken == draftState.baseContentToken && sameCanonicalPath(it.sourcePath, draftState.sourcePath)
                ) it.copy(message = "\uc784\uc2dc \uc800\uc7a5\uc5d0 \uc2e4\ud328\ud588\uc2b5\ub2c8\ub2e4. \ud3b8\uc9d1\uc740 \uacc4\uc18d\ud560 \uc218 \uc788\uc2b5\ub2c8\ub2e4.") else it
            }
            return false
        }
        val owningJob = currentCoroutineContext()[Job]
        var committed: DraftSaveResult? = null
        var settled = false
        try {
            withContext(Dispatchers.IO) {
                draftSaveMutex.withLock {
                    committed = saveDraftSnapshot(context, payload) {
                        owningJob?.isActive != false && !shuttingDown && isDraftPayloadCurrent(payload)
                    }
                    committed?.let { saved ->
                        settled = withContext(NonCancellable) {
                            settleCommittedDraft(context, saved, payload, owningJob)
                        }
                    }
                }
            }
        } catch (_: CancellationException) {
            // A published pointer must be settled even when dispatcher return delivers cancellation.
        } catch (t: Throwable) {
            logDraftSaveFailure(t)
        }
        if (committed == null) {
            payload.recycleOwnedBitmaps()
            updateUiStateAndRecycleReplaced {
                if (owningJob?.isActive != false && isDraftPayloadDocumentCurrent(payload)) {
                    it.copy(message = "\uc784\uc2dc \uc800\uc7a5\uc5d0 \uc2e4\ud328\ud588\uc2b5\ub2c8\ub2e4. \ud3b8\uc9d1\uc740 \uacc4\uc18d\ud560 \uc218 \uc788\uc2b5\ub2c8\ub2e4.")
                } else it
            }
            return false
        }
        payload.recycleOwnedBitmaps()
        return settled
    }

private suspend fun settleCommittedDraft(
        context: Context,
        saved: DraftSaveResult,
        payload: DraftSavePayload,
        owningJob: Job?
    ): Boolean {
        val current = owningJob?.isActive != false && draftSaveJob === owningJob && isDraftResultCurrent(saved)
        if (!current) {
            withContext(Dispatchers.IO) { rollbackCommittedDraft(context, saved) }
            return false
        }
        val previousBaseline = draftPointerBaseline
        draftPointerBaseline = saved.generationId

        var adopted = false
        while (!adopted) {
            val expected = _uiState.value
            if (!draftResultMatchesDocumentState(saved, expected)) {
                draftPointerBaseline = previousBaseline
                withContext(Dispatchers.IO) { rollbackCommittedDraft(context, saved) }
                return false
            }
            val next = expected.copy(
                draftSavedAtMillis = saved.savedAtMillis,
                draftSourcePath = saved.sourcePath,
                draftBaseContentToken = saved.baseContentToken,
                draftGenerationId = saved.generationId,
                draftGenerationSourcePath = saved.sourcePath,
                draftGenerationThumbnailPath = saved.thumbnailPath
            )
            adopted = _uiState.compareAndSet(expected, next)
        }

        saved.expectedPointerGenerationId?.let { ThumbnailBitmapCache.invalidate("draft:$it") }
        withContext(Dispatchers.IO) {
            runCatching { persistLegacyDraftCompatibility(context, payload, saved) }.onFailure(::logDraftSaveFailure)
            runCatching { deleteAllDraftGenerationsExcept(context, saved.generationDirectory) }.onFailure(::logDraftSaveFailure)
        }
        return true
    }

    internal fun markParamsSuccessfullyRendered(params: EditParams) {
        lastSuccessfullyRenderedParams = params
    }

    fun appContext(): Context = getApplication<Application>().applicationContext

    fun appApplication(): Application = getApplication()

    init {
        val startupRestoreToken = ++restoreDraftToken
        val startupRevision = _uiState.value.revision
        viewModelScope.launch {
            val context = getApplication<Application>()
            val engines = loadEngineSelection(context)
            updateUiStateAndRecycleReplaced {
                it.copy(
                    noiseEngine = engines.noiseEngine,
                    detailEngine = engines.detailEngine,
                    toneEngine = engines.toneEngine,
                    hazeEngine = engines.hazeEngine
                )
            }
            restoreDraftIfAvailable(context, startupRestoreToken, startupRevision)
            val historyResult = withContext(Dispatchers.IO) {
                savedExportHistoryMutex.withLock {
                    val currentRetention = loadExportHistoryRetention(context)
                    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    val previous = if (prefs.getBoolean(KEY_SAVED_EXPORTS_INITIALIZED, false) || prefs.contains(KEY_SAVED_EXPORTS)) {
                        loadSavedExportsFromPrefs(context)
                    } else emptyList()
                    val next = loadOrRebuildSavedExportHistory(context, currentRetention)
                    SavedExportHistoryResult(
                        ++savedExportHistoryRevision,
                        next,
                        previous.map { it.uriString }.toSet() - next.map { it.uriString }.toSet(),
                        retention = currentRetention
                    )
                }
            }
            invalidateRemovedHistoryThumbnails(context, historyResult)
            updateUiStateAndRecycleReplaced {
                if (historyResult.revision != savedExportHistoryRevision) it else it.copy(
                    savedExports = historyResult.items,
                    exportHistoryRetention = historyResult.retention ?: it.exportHistoryRetention
                )
            }
        }
    }

    fun openImage(uri: Uri) {
        if (!canEnterEditorAction()) return
        abortPendingParameterEdit()
        invalidateSelectionPreview()
        invalidateCropOperation()
        invalidateManagedEdits()
        invalidateDraftOperations()
        renderJob?.cancel()
        invalidateExport()
        invalidateSelectionPreview()
        cropJob?.cancel()
        closeParamUndoWindow()
        val openToken = restoreDraftToken + 1L
        restoreDraftToken = openToken
        val invalidateRevision = _uiState.value.revision + 1
        val openingMessage = "\uC774\uBBF8\uC9C0\uB97C \uC5EC\uB294 \uC911\uC785\uB2C8\uB2E4"
        updateUiStateAndRecycleReplaced { it.copy(isBusy = true, revision = invalidateRevision, message = openingMessage) }

        viewModelScope.launch {
            var preview: Bitmap? = null
            var createdSession = 0L
            var sourceFile: File? = null
            try {
                val context = getApplication<Application>()
                withContext(Dispatchers.IO) {
                    val copiedSource = copyUriToCache(context, uri)
                    sourceFile = copiedSource
                    val decoded = decodeSampledMutableBitmapWithExif(copiedSource.absolutePath, maxSide = 2048)
                    preview = decoded
                }
                if (shuttingDown || openToken != restoreDraftToken) {
                    preview?.recycle()
                    preview = null
                    sourceFile?.delete()
                    return@launch
                }
                val decodedPreview = preview!!
                val openedSource = checkNotNull(sourceFile)
                Log.i(FLARE_GUARD_AI_TAG, "Opened image with EXIF orientation: ${openedSource.name} preview=${decodedPreview.width}x${decodedPreview.height}")

                createdSession = NativePhotoCore.nativeCreateSession(openedSource.absolutePath)
                if (shuttingDown || openToken != restoreDraftToken || _uiState.value.revision != invalidateRevision) {
                    preview?.recycle()
                    preview = null
                    sourceFile?.delete()
                    releaseNativeSessionHandle(createdSession)
                    createdSession = 0L
                    return@launch
                }
                val previousSession = nativeSession
                val previousState = _uiState.value
                val nextState = previousState.copy(
                    isBusy = false,
                    sourcePath = openedSource.absolutePath,
                    baseBitmapDirty = false,
                    baseContentToken = newBaseContentToken(),
                    draftSavedAtMillis = null,
                    draftSourcePath = null,
                    draftBaseContentToken = null,
                    draftGenerationId = null,
                    draftGenerationSourcePath = null,
                    draftGenerationThumbnailPath = null,
                    originalPreviewBitmap = decodedPreview,
                    previewBitmap = decodedPreview,
                    cropState = CropState(),
                    selectionLayers = emptyList(),
                    activeSelectionLayerId = null,
                    selectionPaintSettings = SelectionPaintSettings(),
                    showSelectionOverlay = true,
                    viewport = ViewportState(),
                    activeQuickEffects = emptyList(),
                    params = EditParams(),
                    presetLook = null,
                    canUndo = false,
                    canRedo = false,
                    flareGuardRuntimeStatus = null,
                    recoveryDebugInfo = null,
                    showRecoveryDebugCard = false,
                    revision = invalidateRevision + 1,
                    message = "\uC6D0\uBCF8 \uCE90\uC2DC\uAC00 \uC644\uB8CC\uB418\uC5C8\uC2B5\uB2C8\uB2E4: ${decodedPreview.width}x${decodedPreview.height} preview"
                )
                nativeSession = createdSession
                try {
                    _uiState.value = nextState
                } catch (t: Throwable) {
                    nativeSession = previousSession
                    throw t
                }
                createdSession = 0L
                lastSuccessfullyRenderedParams = EditParams()
                clearEditHistory()
                preview = null
                runCatching { releaseOrphanedBitmaps(previousState, _uiState.value) }
                releaseNativeSessionHandle(previousSession)
                deleteOwnedWorkingSource(context, previousState.sourcePath)
                forceDraftSaveAsync()
            } catch (ce: CancellationException) {
                preview?.recycle()
                releaseNativeSessionHandle(createdSession)
                sourceFile?.delete()
                throw ce
            } catch (t: Throwable) {
                preview?.recycle()
                releaseNativeSessionHandle(createdSession)
                sourceFile?.delete()
                if (!shuttingDown && openToken == restoreDraftToken && _uiState.value.revision == invalidateRevision) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "\uC774\uBBF8\uC9C0\uB97C \uC5F4\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4: ${t.message}") }
                }
            }
        }
    }

    fun updateParams(transform: (EditParams) -> EditParams) {
        if (shuttingDown) return
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return
        prepareForGlobalParamEdit()

        val windowWasOpen = paramUndoWindowOpen
        if (!paramUndoWindowOpen) {
            val hasActiveRender = activeParamRenderRevision != null && renderJob?.isActive == true
            val hasLiveParams = _uiState.value.params != lastSuccessfullyRenderedParams
            val hasPendingSnapshot = pendingParamUndoSnapshot != null
            if (hasActiveRender || hasLiveParams || hasPendingSnapshot) {
                renderJob?.cancel()
                activeParamRenderRevision = null
                if (pendingParamUndoSnapshot != null && !paramUndoSnapshotCommitted) {
                    pendingParamUndoSnapshot?.recycleBitmaps()
                    pendingParamUndoSnapshot = null
                }
                updateUiState { it.copy(params = lastSuccessfullyRenderedParams, revision = it.revision + 1, isBusy = false) }
            }
        }

        val current = _uiState.value
        val basePreview = current.originalPreviewBitmap ?: current.previewBitmap ?: return
        val nextParams = transform(current.params)
        if (nextParams == current.params) return
        val ownedBase = runCatching { basePreview.copyOrThrow() }.getOrElse {
            updateUiStateAndRecycleReplaced { it.copy(message = "미리보기 입력 이미지를 준비하지 못했습니다.") }
            return
        }

        if (!windowWasOpen) {
            val snapshot = runCatching { _uiState.value.toHistorySnapshot() }.getOrNull()
            if (snapshot == null) {
                ownedBase.recycle()
                updateUiState { it.copy(message = "편집을 준비하지 못했습니다.") }
                return
            }
            pendingParamUndoSnapshot = snapshot
            paramUndoSnapshotCommitted = false
            paramUndoWindowOpen = true
        }
        paramUndoWindowJob?.cancel()
        paramUndoWindowJob = viewModelScope.launch {
            delay(900L)
            paramUndoWindowOpen = false
        }
        updateUiState { it.copy(params = nextParams) }
        val nextRevision = current.revision + 1
        updateUiStateAndRecycleReplaced { it.copy(revision = nextRevision, isBusy = true, message = "미리보기를 렌더링하는 중입니다") }
        renderJob?.cancel()
        activeParamRenderRevision = nextRevision
        renderJob = launchManagedEdit { operationToken ->
            var rendered: Bitmap? = null
            try {
                rendered = withContext(Dispatchers.Default) {
                    renderEditedPreview(ownedBase, nextParams, current.engineSelection(), nextRevision, current.presetLook, current.activeQuickEffects)
                }
                if (isManagedEditCurrent(operationToken, nextRevision)) {
                    val adopted = rendered!!
                    commitPendingParamUndoSnapshot()
                    lastSuccessfullyRenderedParams = nextParams
                    if (activeParamRenderRevision == nextRevision) activeParamRenderRevision = null
                    updateUiStateAndRecycleReplaced {
                        it.copy(params = nextParams, previewBitmap = adopted, isBusy = false, message = "미리보기 렌더링이 완료되었습니다")
                    }
                    rendered = null
                    scheduleDraftAutosave()
                } else {
                    if (activeParamRenderRevision == nextRevision) activeParamRenderRevision = null
                    rendered?.recycle()
                    rendered = null
                }
            } catch (ce: CancellationException) {
                rendered?.recycle()
                if (activeParamRenderRevision == nextRevision) activeParamRenderRevision = null
                throw ce
            } catch (t: Throwable) {
                rendered?.recycle()
                if (activeParamRenderRevision == nextRevision) activeParamRenderRevision = null
                if (isManagedEditCurrent(operationToken, nextRevision)) {
                    if (paramUndoSnapshotCommitted) {
                        closeParamUndoWindow()
                        updateUiState { it.copy(params = lastSuccessfullyRenderedParams, revision = nextRevision + 1) }
                        updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "미리보기 렌더링에 실패했습니다: ${t.message}") }
                    } else {
                        val snapshot = takePendingParamUndoSnapshotForRollback()
                        snapshot?.let { restoreSnapshotWithoutHistory(it) }
                        updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "미리보기 렌더링에 실패했습니다: ${t.message}") }
                    }
                }
            } finally {
                ownedBase.recycle()
            }
        }
    }

    fun applyAutoEnhance() {
        if (shuttingDown) return
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return
        val current = prepareForExternalEdit()
        val basePreview = current.originalPreviewBitmap ?: current.previewBitmap
        if (basePreview == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "자동 보정을 적용할 이미지가 없습니다") }
            return
        }

        val sourcePath = current.sourcePath
        val baseContentToken = current.baseContentToken
        val engines = current.engineSelection()
        val presetLook = current.presetLook
        val quickEffects = current.activeQuickEffects
        val startRevision = current.revision

        var undoSnapshot: EditorHistorySnapshot? = captureCurrentHistorySnapshot() ?: run {
            updateUiStateAndRecycleReplaced { it.copy(message = "자동 보정 준비에 실패했습니다.") }
            return
        }
        val ownedBase = runCatching { basePreview.copyOrThrow() }.getOrElse {
            recycleHistorySnapshot(checkNotNull(undoSnapshot))
            undoSnapshot = null
            updateUiStateAndRecycleReplaced { it.copy(message = "자동 보정 준비에 실패했습니다.") }
            return
        }

        val nextRevision = startRevision + 1
        renderJob?.cancel()
        updateUiStateAndRecycleReplaced { it.copy(isBusy = true, revision = nextRevision, message = "자동 보정값을 분석하는 중입니다") }

        launchManagedEdit { operationToken ->
            var rendered: Bitmap? = null
            try {
                val nextParams = withContext(Dispatchers.Default) { computeAutoEnhanceParams(ownedBase) }
                withContext(Dispatchers.Default) {
                    rendered = renderEditedPreview(ownedBase, nextParams, engines, nextRevision, presetLook, quickEffects)
                }
                if (isManagedEditCurrent(operationToken, nextRevision) &&
                    uiState.value.sourcePath == sourcePath &&
                    uiState.value.baseContentToken == baseContentToken &&
                    !isShuttingDown()) {
                    val adopted = rendered!!
                    lastSuccessfullyRenderedParams = nextParams
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            params = nextParams,
                            previewBitmap = adopted,
                            isBusy = false,
                            message = "자동 보정이 적용되었습니다"
                        )
                    }
                    rendered = null
                    commitUndoSnapshot(checkNotNull(undoSnapshot), clearRedo = true)
                    undoSnapshot = null
                    scheduleDraftAutosave()
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (isManagedEditCurrent(operationToken, nextRevision) &&
                    uiState.value.sourcePath == sourcePath &&
                    uiState.value.baseContentToken == baseContentToken) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "자동 보정에 실패했습니다: ${t.message}") }
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } finally {
                rendered?.takeIf { !it.isRecycled }?.recycle()
                ownedBase.takeIf { !it.isRecycled }?.recycle()
                undoSnapshot?.let(::recycleHistorySnapshot)
            }
        }
    }

    fun setNoiseEngine(engine: NoiseEngine) {
        applyEngineChange(noiseEngine = engine, message = "노이즈 감소 엔진이 ${engine.label}으로 설정되었습니다")
    }

    fun setDetailEngine(engine: DetailEngine) {
        applyEngineChange(detailEngine = engine, message = "디테일 엔진이 ${engine.label}으로 설정되었습니다")
    }

    fun setToneEngine(engine: ToneEngine) {
        applyEngineChange(toneEngine = engine, message = "톤 엔진이 ${engine.label}으로 설정되었습니다")
    }

    fun setHazeEngine(engine: DehazeEngine) {
        applyEngineChange(hazeEngine = engine, message = "디헤이즈 엔진이 ${engine.label}으로 설정되었습니다")
    }

    private fun applyEngineChange(
        noiseEngine: NoiseEngine? = null,
        detailEngine: DetailEngine? = null,
        toneEngine: ToneEngine? = null,
        hazeEngine: DehazeEngine? = null,
        message: String
    ) {
        if (isShuttingDown()) return
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return
        val current = prepareForExternalEdit()
        val nextEngines = EngineSelection(
            noiseEngine = noiseEngine ?: current.noiseEngine,
            detailEngine = detailEngine ?: current.detailEngine,
            toneEngine = toneEngine ?: current.toneEngine,
            hazeEngine = hazeEngine ?: current.hazeEngine
        ).coerceImplemented()
        if (nextEngines == current.engineSelection()) return
        val context = getApplication<Application>()

        val basePreview = current.originalPreviewBitmap ?: current.previewBitmap
        if (basePreview == null) {
            saveEngineSelection(context, nextEngines)
            updateUiStateAndRecycleReplaced {
                it.copy(
                    noiseEngine = nextEngines.noiseEngine,
                    detailEngine = nextEngines.detailEngine,
                    toneEngine = nextEngines.toneEngine,
                    hazeEngine = nextEngines.hazeEngine,
                    message = message
                )
            }
            return
        }

        var undoSnapshot: EditorHistorySnapshot? = captureCurrentHistorySnapshot()
        if (undoSnapshot == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "처리 엔진 변경 준비에 실패했습니다.") }
            return
        }
        var ownedBase: Bitmap? = runCatching { basePreview.copyOrThrow() }.getOrElse {
            recycleHistorySnapshot(checkNotNull(undoSnapshot))
            undoSnapshot = null
            updateUiStateAndRecycleReplaced { it.copy(message = "처리 엔진 변경 준비에 실패했습니다.") }
            return
        }

        val sourcePath = current.sourcePath
        val baseContentToken = current.baseContentToken
        val params = current.params
        val presetLook = current.presetLook
        val quickEffects = current.activeQuickEffects
        val startRevision = current.revision
        val nextRevision = startRevision + 1
        updateUiStateAndRecycleReplaced {
                it.copy(
                    revision = nextRevision,
                isBusy = true,
                message = "$message. 미리보기를 다시 렌더링하는 중입니다"
            )
        }
        renderJob?.cancel()
        renderJob = launchManagedEdit { operationToken ->
            var rendered: Bitmap? = null
            try {
                withContext(Dispatchers.Default) {
                    val result = renderEditedPreview(checkNotNull(ownedBase), params, nextEngines, nextRevision, presetLook, quickEffects)
                    rendered = result
                }
                val identityUnchanged = uiState.value.sourcePath == sourcePath &&
                    uiState.value.baseContentToken == baseContentToken
                if (isManagedEditCurrent(operationToken, nextRevision) && identityUnchanged) {
                    val adopted = rendered!!
                    saveEngineSelection(context, nextEngines)
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            noiseEngine = nextEngines.noiseEngine,
                            detailEngine = nextEngines.detailEngine,
                            toneEngine = nextEngines.toneEngine,
                            hazeEngine = nextEngines.hazeEngine,
                            previewBitmap = adopted,
                            isBusy = false,
                            message = message
                        )
                    }
                    rendered = null
                    commitUndoSnapshot(checkNotNull(undoSnapshot), clearRedo = true)
                    undoSnapshot = null
                    scheduleDraftAutosave()
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val failureIdentityUnchanged = uiState.value.sourcePath == sourcePath &&
                    uiState.value.baseContentToken == baseContentToken
                if (isManagedEditCurrent(operationToken, nextRevision) && failureIdentityUnchanged) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "미리보기 렌더링에 실패했습니다: ${t.message}") }
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } finally {
                rendered?.takeIf { !it.isRecycled }?.recycle()
                ownedBase?.takeIf { !it.isRecycled }?.recycle()
                undoSnapshot?.let(::recycleHistorySnapshot)
            }
        }
    }

    fun resetAdjustments() {
        if (isShuttingDown()) return
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return
        val current = prepareForExternalEdit()
        val sourcePath = current.sourcePath
        if (sourcePath == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "초기화할 이미지가 없습니다.") }
            return
        }
        val baseContentToken = current.baseContentToken
        val startRevision = current.revision
        var undoSnapshot: EditorHistorySnapshot? = captureCurrentHistorySnapshot()
        if (undoSnapshot == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "초기화 준비에 실패했습니다.") }
            return
        }
        val nextRevision = startRevision + 1
        renderJob?.cancel()
        invalidateExport()
        var decoded: Bitmap? = null
        updateUiStateAndRecycleReplaced { it.copy(isBusy = true, revision = nextRevision, message = "초기화하는 중입니다") }
        renderJob = launchManagedEdit { operationToken ->
            try {
                withContext(Dispatchers.IO) {
                    val result = decodeSampledMutableBitmapWithExif(sourcePath, maxSide = 2048)
                    decoded = result
                }
                val identityUnchanged = uiState.value.sourcePath == sourcePath &&
                    uiState.value.baseContentToken == baseContentToken
                if (isManagedEditCurrent(operationToken, nextRevision) && identityUnchanged) {
                    val adopted = checkNotNull(decoded)
                    lastSuccessfullyRenderedParams = EditParams()
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            originalPreviewBitmap = adopted,
                            previewBitmap = adopted,
                            baseBitmapDirty = false,
                            baseContentToken = newBaseContentToken(),
                            params = EditParams(),
                            presetLook = null,
                            activeQuickEffects = emptyList(),
                            cropState = CropState(),
                            selectionLayers = emptyList(),
                            activeSelectionLayerId = null,
                            selectionPaintSettings = SelectionPaintSettings(),
                            showSelectionOverlay = true,
                            flareGuardRuntimeStatus = null,
                            isBusy = false,
                            message = "초기화가 완료되었습니다"
                        )
                    }
                    decoded = null
                    commitUndoSnapshot(checkNotNull(undoSnapshot), clearRedo = true)
                    undoSnapshot = null
                    forceDraftSaveAsync()
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val failureIdentityUnchanged = uiState.value.sourcePath == sourcePath &&
                    uiState.value.baseContentToken == baseContentToken
                if (isManagedEditCurrent(operationToken, nextRevision) && failureIdentityUnchanged) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "초기화에 실패했습니다: ${t.message}") }
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } finally {
                decoded?.takeIf { !it.isRecycled }?.recycle()
                undoSnapshot?.let(::recycleHistorySnapshot)
            }
        }
    }

fun applyPresetLook(params: EditParams, look: PresetColorLook?, message: String): PresetApplyResult {
        if (isShuttingDown()) return PresetApplyResult.Rejected
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return PresetApplyResult.Rejected
        val current = prepareForExternalEdit()
        val basePreview = current.originalPreviewBitmap ?: current.previewBitmap
        if (basePreview == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "적용할 이미지가 없습니다.") }
            return PresetApplyResult.Rejected
        }

        if (params == current.params && look == current.presetLook) {
            return PresetApplyResult.AlreadyApplied
        }

        var undoSnapshot: EditorHistorySnapshot? = captureCurrentHistorySnapshot()
        if (undoSnapshot == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "편집 기록을 저장하지 못했습니다.") }
            return PresetApplyResult.Rejected
        }
        var ownedBase: Bitmap? = runCatching { basePreview.copyOrThrow() }.getOrElse {
            recycleHistorySnapshot(checkNotNull(undoSnapshot))
            undoSnapshot = null
            updateUiStateAndRecycleReplaced { it.copy(message = "프리셋 적용 준비에 실패했습니다.") }
            return PresetApplyResult.Rejected
        }
        val sourcePath = current.sourcePath
        val baseContentToken = current.baseContentToken
        val engines = current.engineSelection()
        val quickEffects = current.activeQuickEffects
        val startRevision = current.revision
        val nextRevision = startRevision + 1
        renderJob?.cancel()
        updateUiStateAndRecycleReplaced { it.copy(isBusy = true, revision = nextRevision, message = message) }

        renderJob = launchManagedEdit { operationToken ->
            var rendered: Bitmap? = null
            try {
                withContext(Dispatchers.Default) {
                    val result = renderEditedPreview(checkNotNull(ownedBase), params, engines, nextRevision, look, quickEffects)
                    rendered = result
                }
                val identityUnchanged = uiState.value.sourcePath == sourcePath &&
                    uiState.value.baseContentToken == baseContentToken
                if (isManagedEditCurrent(operationToken, nextRevision) && identityUnchanged) {
                    val adopted = rendered!!
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            params = params,
                            presetLook = look,
                            previewBitmap = adopted,
                            isBusy = false,
                            message = message
                        )
                    }
                    lastSuccessfullyRenderedParams = params
                    rendered = null
                    commitUndoSnapshot(checkNotNull(undoSnapshot), clearRedo = true)
                    undoSnapshot = null
                    scheduleDraftAutosave()
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val failureIdentityUnchanged = uiState.value.sourcePath == sourcePath &&
                    uiState.value.baseContentToken == baseContentToken
                if (isManagedEditCurrent(operationToken, nextRevision) && failureIdentityUnchanged) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "프로필 적용에 실패했습니다.") }
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } finally {
                rendered?.takeIf { !it.isRecycled }?.recycle()
                ownedBase?.takeIf { !it.isRecycled }?.recycle()
                undoSnapshot?.let(::recycleHistorySnapshot)
            }
        }
        return PresetApplyResult.Accepted
    }

    fun setExportFormat(format: ExportFormat) {
        updateUiStateAndRecycleReplaced {
            it.copy(exportFormat = format, message = "파일 형식이 ${format.label}로 설정되었습니다")
        }
        scheduleDraftAutosave()
    }

    fun setExportResolution(resolution: ExportResolution) {
        updateUiStateAndRecycleReplaced {
            it.copy(exportResolution = resolution, message = "해상도가 ${resolution.label}로 설정되었습니다")
        }
        scheduleDraftAutosave()
    }

    fun setExportHistoryRetention(retention: ExportHistoryRetention) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                savedExportHistoryMutex.withLock {
                    saveExportHistoryRetention(context, retention)
                    val current = loadSavedExportsFromPrefs(context)
                    val pruned = pruneSavedExportsIfNeeded(context, current, retention)
                    SavedExportHistoryResult(
                        ++savedExportHistoryRevision,
                        pruned,
                        (current.map { it.uriString }.toSet() - pruned.map { it.uriString }.toSet())
                    )
                }
            }
            invalidateRemovedHistoryThumbnails(context, result)
            updateUiStateAndRecycleReplaced {
                if (result.revision != savedExportHistoryRevision) it else it.copy(
                    exportHistoryRetention = retention,
                    savedExports = result.items,
                    message = if (it.isBusy) it.message else "내보낸 사진 기록 자동 정리가 ${retention.label}으로 설정되었습니다"
                )
            }
        }
    }

    fun exportPreview() {
        if (shuttingDown) return
        val state = _uiState.value
        val sourcePath = state.sourcePath
        val exportBusyMessage = "${state.exportFormat.label} 형식, ${state.exportResolution.label} 목표 해상도로 내보내는 중입니다."
        if (sourcePath == null) {
            val missingMsg = "\uB0B4\uBCF4\uB0BC \uC6D0\uBCF8 \uC774\uBBF8\uC9C0\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4"
            if (state.message != missingMsg) {
                updateUiStateAndRecycleReplaced { it.copy(message = missingMsg) }
            }
            return
        }
        if (state.isBusy || brushingSnapshot != null) return

        val exportFormat = state.exportFormat
        val exportResolution = state.exportResolution
        val exportParams = state.params
        val exportEngines = state.engineSelection()
        val exportLook = state.presetLook
        val exportQuickEffects = state.activeQuickEffects.toList()
        val exportRevision = state.revision
        val exportBaseToken = state.baseContentToken
        val exportDirty = state.baseBitmapDirty
        val exportRetention = state.exportHistoryRetention

        var ownedDirtyBase: Bitmap? = null
        if (exportDirty) {
            val liveBase = state.originalPreviewBitmap ?: state.previewBitmap
            ownedDirtyBase = runCatching { liveBase?.copyOrThrow(Bitmap.Config.ARGB_8888, true) }.getOrElse {
                updateUiStateAndRecycleReplaced { it.copy(message = "\uB0B4\uBCF4\uB0B4\uAE30 \uC900\uBE44\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4") }
                return
            }
            if (ownedDirtyBase == null) {
                updateUiStateAndRecycleReplaced { it.copy(message = "\uB0B4\uBCF4\uB0B4\uAE30 \uC900\uBE44\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4") }
                return
            }
        }

        invalidateExport()
        val token = exportToken
        val fileName = "KeplerStudio_${exportTimestamp()}.${exportFormat.extension}"

        updateUiStateAndRecycleReplaced { it.copy(isBusy = true, message = exportBusyMessage) }

        val launchedJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val exportCoroutine = currentCoroutineContext()[Job] ?: return@launch
            var ownedExportResult: Bitmap? = null
            var pendingUri: Uri? = null
            var published = false
            var historySaved = false
            var historyError: Throwable? = null
            var persistedHistory: List<SavedExport> = emptyList()
            var persistedHistoryRevision = Long.MIN_VALUE
            try {
                val context = getApplication<Application>()
                withContext(Dispatchers.Default) {
                    if (isCurrentExport(token, sourcePath, exportBaseToken, exportRevision)) {
                        val rendered = if (exportDirty) {
                            renderEditedExportFromBitmap(
                                baseBitmap = checkNotNull(ownedDirtyBase),
                                params = exportParams,
                                resolution = exportResolution,
                                engines = exportEngines,
                                revision = exportRevision + 1,
                                look = exportLook,
                                quickEffects = exportQuickEffects
                            )
                        } else {
                            renderEditedExport(
                                sourcePath = sourcePath,
                                params = exportParams,
                                resolution = exportResolution,
                                engines = exportEngines,
                                revision = exportRevision + 1,
                                look = exportLook,
                                quickEffects = exportQuickEffects
                            )
                        }
                        ownedExportResult = rendered
                        rendered
                    } else {
                        null
                    }
                }
                val exportResult = ownedExportResult ?: run {
                    return@launch
                }
                val exportedResolutionLabel = "${exportResult.width}x${exportResult.height}"

                pendingUri = withContext(Dispatchers.IO) {
                    if (!isCurrentExport(token, sourcePath, exportBaseToken, exportRevision)) {
                        return@withContext null
                    }
                    insertExportPendingRow(context, exportResult, fileName, exportFormat).also { pendingUri = it }
                }
                if (pendingUri == null) {
                    return@launch
                }

                if (!isCurrentExport(token, sourcePath, exportBaseToken, exportRevision)) {
                    // Before commit, a stale export must remain invisible and be removed.
                    withContext(NonCancellable + Dispatchers.IO) { deletePendingExportRow(context, pendingUri!!) }
                    return@launch
                }

                val savedItem = SavedExport(
                    displayName = fileName,
                    uriString = pendingUri!!.toString(),
                    formatLabel = exportFormat.label,
                    resolutionLabel = exportedResolutionLabel,
                    timestampMillis = System.currentTimeMillis()
                )
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!isCurrentExportIdentity(token, sourcePath, exportBaseToken, exportRevision, exportCoroutine)) {
                        deletePendingExportRow(context, pendingUri!!)
                        return@withContext
                    }
                    publishExportRow(context, pendingUri!!)
                    published = true
                    try {
                        val historyResult = savedExportHistoryMutex.withLock {
                            val previous = loadSavedExportsFromPrefs(context)
                            val next = rememberSavedExport(context, savedItem, loadExportHistoryRetention(context))
                            SavedExportHistoryResult(
                                ++savedExportHistoryRevision,
                                next,
                                previous.map { it.uriString }.toSet() - next.map { it.uriString }.toSet()
                            )
                        }
                        persistedHistory = historyResult.items
                        persistedHistoryRevision = historyResult.revision
                        invalidateRemovedHistoryThumbnails(context, historyResult)
                        historySaved = true
                    } catch (t: Throwable) {
                        historyError = t
                    }
                    if (!shuttingDown && historySaved) {
                        updateUiStateAndRecycleReplaced { current ->
                            val merged = if (persistedHistoryRevision == savedExportHistoryRevision) persistedHistory else current.savedExports
                            if (isCurrentExportIdentity(token, sourcePath, exportBaseToken, exportRevision, exportCoroutine)) {
                                current.copy(
                                    isBusy = false,
                                    savedExports = merged,
                                    message = "이미지가 Gallery > Pictures/KeplerStudio에 저장되었고, 앱 내 내보낸 사진 기록에도 추가되었습니다."
                                )
                            } else {
                                current.copy(savedExports = merged)
                            }
                        }
                    } else if (!shuttingDown && published &&
                        isCurrentExportIdentity(token, sourcePath, exportBaseToken, exportRevision, exportCoroutine)
                    ) {
                        val errorMsg = historyError?.message ?: "unknown"
                        updateUiStateAndRecycleReplaced { current ->
                            current.copy(
                                isBusy = false,
                                message = "갤러리 파일은 저장되었지만 앱 내 내보낸 사진 기록 저장에 실패했습니다: $errorMsg"
                            )
                        }
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                if (!published) {
                    pendingUri?.let { uri ->
                        withContext(NonCancellable + Dispatchers.IO) { deletePendingExportRow(getApplication<Application>(), uri) }
                    }
                }
                throw ce
            } catch (t: Throwable) {
                if (!published) pendingUri?.let { uri ->
                    withContext(NonCancellable + Dispatchers.IO) { deletePendingExportRow(getApplication<Application>(), uri) }
                }
                if (!published && isCurrentExport(token, sourcePath, exportBaseToken, exportRevision)) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "\uB0B4\uBCF4\uB0B4\uAE30\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4: ${t.message}") }
                }
            } finally {
                val owned = identityBitmapSet()
                ownedExportResult?.let(owned::add)
                ownedDirtyBase?.let(owned::add)
                owned.forEach { if (!it.isRecycled) it.recycle() }
                if (exportJob === currentCoroutineContext()[Job]) exportJob = null
            }
        }
        exportJob = launchedJob
        launchedJob.start()
    }

fun clearDraft() {
        val context = getApplication<Application>()
        invalidateDraftOperations()
        val clearEpoch = draftOperationEpoch
        viewModelScope.launch {
            try {
                draftSaveJob?.cancelAndJoin()
                val cleared = withContext(Dispatchers.IO) {
                    draftSaveMutex.withLock {
                        if (clearEpoch != draftOperationEpoch) return@withLock false
                        val expectedPointer = currentDraftGenerationId(context)
                        val expectedBaseline = draftPointerBaseline
                        if (expectedPointer != expectedBaseline) return@withLock false

                        // Capture one stable visible state snapshot
                        val visibleBefore = _uiState.value
                        val liveSourcePath = visibleBefore.sourcePath

                        // Snapshot previous prefs for rollback
                        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        val prevPrefs = snapshotDraftPreferences(prefs)

                        // Clear pointer and baseline
                        if (!clearCurrentDraftGenerationPointer(context)) return@withLock false
                        draftPointerBaseline = null

                        // Clear legacy prefs
                        val committed = prefs.edit()
                            .remove(KEY_DRAFT_SOURCE)
                            .remove(KEY_DRAFT_EXPOSURE)
                            .remove(KEY_DRAFT_CONTRAST)
                            .remove(KEY_DRAFT_SHADOWS)
                            .remove(KEY_DRAFT_HIGHLIGHTS)
                            .remove(KEY_DRAFT_WHITES)
                            .remove(KEY_DRAFT_BLACKS)
                            .remove(KEY_DRAFT_TEMPERATURE)
                            .remove(KEY_DRAFT_TINT)
                            .remove(KEY_DRAFT_SATURATION)
                            .remove(KEY_DRAFT_VIBRANCE)
                            .remove(KEY_DRAFT_CLARITY)
                            .remove(KEY_DRAFT_DEHAZE)
                            .remove(KEY_DRAFT_SHARPNESS)
                            .remove(KEY_DRAFT_NOISE_REDUCTION)
                            .remove(KEY_DRAFT_LUMINANCE_NOISE_REDUCTION)
                            .remove(KEY_DRAFT_COLOR_NOISE_REDUCTION)
                            .remove(KEY_DRAFT_NOISE_DETAIL_PROTECTION)
                            .remove(KEY_DRAFT_FORMAT)
                            .remove(KEY_DRAFT_RESOLUTION)
                            .remove(KEY_DRAFT_LOOK)
                            .remove(KEY_DRAFT_QUICK_EFFECTS)
                            .remove(KEY_DRAFT_BASE_TOKEN)
                            .remove(KEY_DRAFT_BASE_VERSION_LEGACY)
                            .remove(KEY_DRAFT_GENERATION_ID)
                            .remove(KEY_DRAFT_SAVED_AT)
                            .commit()
                        if (!committed) {
                            val prefsRestored = restoreDraftPreferencesOrThrow(prefs, prevPrefs, IllegalStateException("failed to clear draft prefs"))
                            if (!prefsRestored) {
                                logDraftSaveFailure(IllegalStateException("clearDraft pref rollback failed"))
                            }
                            val pointerRestored = if (expectedPointer != null) {
                                publishDraftGeneration(context, expectedPointer)
                            } else {
                                true
                            }
                            if (!pointerRestored) {
                                logDraftSaveFailure(IllegalStateException("clearDraft pointer rollback failed"))
                            }
                            val currentPointer = currentDraftGenerationId(context)
                            draftPointerBaseline = currentPointer
                            return@withLock false
                        }

                        // Clear visible Draft metadata with explicit CAS - complete identity
                        var adopted = false
                        var state = _uiState.value
                        while (!adopted) {
                            // Already-cleared succeeds only when ALL fields are empty
                            if (state.draftSavedAtMillis == null &&
                                state.draftSourcePath == null &&
                                state.draftBaseContentToken == null &&
                                state.draftGenerationId == null &&
                                state.draftGenerationSourcePath == null &&
                                state.draftGenerationThumbnailPath == null &&
                                state.recoveryDebugInfo == null &&
                                state.showRecoveryDebugCard == false) {
                                adopted = true
                            } else if (state.draftSavedAtMillis == visibleBefore.draftSavedAtMillis &&
                                state.draftSourcePath == visibleBefore.draftSourcePath &&
                                state.draftBaseContentToken == visibleBefore.draftBaseContentToken &&
                                state.draftGenerationId == visibleBefore.draftGenerationId &&
                                state.draftGenerationSourcePath == visibleBefore.draftGenerationSourcePath &&
                                state.draftGenerationThumbnailPath == visibleBefore.draftGenerationThumbnailPath &&
                                state.recoveryDebugInfo == visibleBefore.recoveryDebugInfo &&
                                state.showRecoveryDebugCard == visibleBefore.showRecoveryDebugCard) {
                                val updated = state.copy(
                                    draftSavedAtMillis = null,
                                    draftSourcePath = null,
                                    draftBaseContentToken = null,
                                    draftGenerationId = null,
                                    draftGenerationSourcePath = null,
                                    draftGenerationThumbnailPath = null,
                                    recoveryDebugInfo = null,
                                    showRecoveryDebugCard = false
                                )
                                adopted = _uiState.compareAndSet(state, updated)
                            } else {
                                // State changed - rollback
                                val prefsRestored = restoreDraftPreferencesOrThrow(prefs, prevPrefs, IllegalStateException("clear superseded"))
                                if (!prefsRestored) {
                                    logDraftSaveFailure(IllegalStateException("clearDraft supersession pref rollback failed"))
                                }
                                val pointerRestored = if (expectedPointer != null) {
                                    publishDraftGeneration(context, expectedPointer)
                                } else {
                                    true
                                }
                                if (!pointerRestored) {
                                    logDraftSaveFailure(IllegalStateException("clearDraft supersession pointer rollback failed"))
                                }
                                val currentPointer = currentDraftGenerationId(context)
                                draftPointerBaseline = currentPointer
                                return@withLock false
                            }
                            state = _uiState.value
                        }

                        // Capture legacy Draft source from prefs for thumbnail invalidation
                        val legacyDraftSourcePath = prevPrefs[KEY_DRAFT_SOURCE] as? String

                        // Durable clear succeeded — cleanup is best-effort from here
                        val liveSourceCanonical = liveSourcePath?.let { runCatching { File(it).canonicalFile }.getOrNull() }
                        val legacyDraftSourceCanonical = legacyDraftSourcePath?.let { runCatching { File(it).canonicalFile }.getOrNull() }
                        runCatching {
                            persistentDraftDirectory(context).listFiles()?.forEach { file ->
                                val canonical = runCatching { file.canonicalFile }.getOrNull()
                                val isLiveSource = canonical != null && liveSourceCanonical != null && canonical == liveSourceCanonical
                                if (isLiveSource) return@forEach
                                if (file.name.endsWith(".tmp")) {
                                    val deleted = file.delete()
                                    if (!deleted) logDraftSaveFailure(IllegalStateException("failed to delete temp file: ${file.absolutePath}"))
                                    return@forEach
                                }
                                val matchesLegacySource = canonical != null && legacyDraftSourceCanonical != null && canonical == legacyDraftSourceCanonical
                                val isOwnedDraft = matchesLegacySource && isOwnedDraftSource(context, file)
                                if (matchesLegacySource && isOwnedDraft) {
                                    val deleted = file.delete()
                                    if (!deleted) logDraftSaveFailure(IllegalStateException("failed to delete legacy draft source: ${file.absolutePath}"))
                                }
                            }
                        }.onFailure { logDraftSaveFailure(it) }
                        runCatching {
                            expectedPointer?.let { deleteDraftGenerationById(context, it) }
                        }.onFailure { logDraftSaveFailure(it) }
                        runCatching {
                            val thumbFile = persistentDraftThumbnailFile(context)
                            if (thumbFile.isFile) {
                                val deleted = thumbFile.delete()
                                if (!deleted) logDraftSaveFailure(IllegalStateException("failed to delete draft thumbnail: ${thumbFile.absolutePath}"))
                            }
                        }.onFailure { logDraftSaveFailure(it) }
                        runCatching {
                            expectedPointer?.let { ThumbnailBitmapCache.invalidate("draft:$it") }
                            legacyDraftSourcePath?.let { ThumbnailBitmapCache.invalidate("draft:legacy:$it") }
                        }.onFailure { logDraftSaveFailure(it) }

                        true
                    }
                }
                if (!cleared) {
                    updateUiStateAndRecycleReplaced {
                        if (clearEpoch == draftOperationEpoch && !it.isBusy) it.copy(message = "임시 저장 삭제에 실패했습니다. 기존 임시 저장을 유지합니다.") else it
                    }
                    return@launch
                }
                updateUiStateAndRecycleReplaced {
                    if (clearEpoch != draftOperationEpoch) it else it.copy(message = "자동복구용 임시저장 기록을 삭제했습니다. 현재 편집 화면은 유지됩니다")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logDraftSaveFailure(t)
                updateUiStateAndRecycleReplaced {
                    if (clearEpoch == draftOperationEpoch && !it.isBusy) it.copy(message = "임시 저장 삭제에 실패했습니다. 기존 임시 저장을 유지합니다.") else it
                }
            }
        }
    }

    fun dismissRecoveryDebugCard() {
        updateUiStateAndRecycleReplaced { it.copy(showRecoveryDebugCard = false) }
    }

    fun cleanupOldTemporarySources() {
        val context = getApplication<Application>()
        val activeSourcePath = _uiState.value.sourcePath
        viewModelScope.launch {
            val removedCount = withContext(Dispatchers.IO) {
                cleanupTemporarySourceFiles(context, activeSourcePath = activeSourcePath)
            }
            updateUiStateAndRecycleReplaced {
                it.copy(message = "7일이 지난 임시 원본 캐시를 정리했습니다. 삭제된 파일: ${removedCount}개")
            }
        }
    }

    fun clearSavedExports() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { savedExportHistoryMutex.withLock {
                val current = loadSavedExportsFromPrefs(context)
                clearSavedExportsPrefs(context)
                SavedExportHistoryResult(++savedExportHistoryRevision, emptyList(), current.map { it.uriString }.toSet())
            } }
            invalidateRemovedHistoryThumbnails(context, result)
            updateUiStateAndRecycleReplaced {
                if (result.revision != savedExportHistoryRevision) it else it.copy(savedExports = result.items, message = if (it.isBusy) it.message else "내보낸 사진 기록을 모두 비웠습니다. 갤러리 파일은 삭제되지 않습니다")
            }
        }
    }

    fun removeSavedExport(uriString: String) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { savedExportHistoryMutex.withLock {
                val current = loadSavedExportsFromPrefs(context)
                val next = current.filterNot { it.uriString == uriString }
                saveSavedExportsToPrefs(context, next)
                SavedExportHistoryResult(
                    ++savedExportHistoryRevision,
                    next,
                    if (next.size != current.size) setOf(uriString) else emptySet()
                )
            } }
            invalidateRemovedHistoryThumbnails(context, result)
            updateUiStateAndRecycleReplaced {
                if (result.revision != savedExportHistoryRevision) it else it.copy(savedExports = result.items, message = if (it.isBusy) it.message else "선택한 내보낸 사진 기록을 삭제했습니다. 갤러리 파일은 삭제되지 않습니다")
            }
        }
    }

    fun updateViewport(viewport: ViewportState) {
        updateUiStateAndRecycleReplaced { it.copy(viewport = viewport) }
        // TODO v0.2: viewport가 scale 임계값 이상이면 ROI 타일 렌더 Job 발행.
    }

    fun undoEdit() {
        if (!canEnterEditorAction()) return
        invalidateSelectionPreview()
        abortPendingParameterEdit()
        invalidateCropOperation()
        if (undoHistory.isEmpty()) {
            updateUiStateAndRecycleReplaced { it.copy(message = "되돌릴 편집 기록이 없습니다.") }
            return
        }
        renderJob?.cancel()
        invalidateExport()
        val redoSnapshot = try {
            _uiState.value.toHistorySnapshot()
        } catch (t: Throwable) {
            Log.w(FLARE_GUARD_AI_TAG, "Failed to create redo snapshot", t)
            updateUiStateAndRecycleReplaced {
                it.copy(message = "되돌리기 기록을 저장하지 못했습니다. 편집은 계속할 수 있습니다.")
            }
            return
        }
        redoHistory.addLast(redoSnapshot)
        val snapshot = undoHistory.removeLast()
        val message = buildHistoryAppliedMessage(_uiState.value, snapshot, "이전 편집 상태를 적용했습니다")
        applyHistorySnapshot(snapshot, message)
        scheduleDraftAutosave()
        updateHistoryFlags()
        Log.i(FLARE_GUARD_AI_TAG, "Undo editor snapshot: undo=${undoHistory.size} redo=${redoHistory.size}")
    }

    fun redoEdit() {
        if (!canEnterEditorAction()) return
        invalidateSelectionPreview()
        abortPendingParameterEdit()
        invalidateCropOperation()
        if (redoHistory.isEmpty()) {
            updateUiStateAndRecycleReplaced { it.copy(message = "다시 실행할 편집 기록이 없습니다.") }
            return
        }
        renderJob?.cancel()
        invalidateExport()
        val undoSnapshot = try {
            _uiState.value.toHistorySnapshot()
        } catch (t: Throwable) {
            Log.w(FLARE_GUARD_AI_TAG, "Failed to create undo snapshot for redo", t)
            updateUiStateAndRecycleReplaced {
                it.copy(message = "되돌리기 기록을 저장하지 못했습니다. 편집은 계속할 수 있습니다.")
            }
            return
        }
        undoHistory.addLast(undoSnapshot)
        val snapshot = redoHistory.removeLast()
        val message = buildHistoryAppliedMessage(_uiState.value, snapshot, "다음 편집 상태를 적용했습니다")
        applyHistorySnapshot(snapshot, message)
        scheduleDraftAutosave()
        updateHistoryFlags()
        Log.i(FLARE_GUARD_AI_TAG, "Redo editor snapshot: undo=${undoHistory.size} redo=${redoHistory.size}")
    }

    fun rotatePreview90() {
        if (shuttingDown) return
        val current = _uiState.value
        if (current.isBusy) return
        abortPendingParameterEdit()
        invalidateSelectionPreview()
        invalidateManagedEdits()
        renderJob?.cancel()
        val preview = current.previewBitmap
        if (preview == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "회전할 이미지가 없습니다.") }
            return
        }
        val undoSnapshot = captureCurrentHistorySnapshot() ?: return
        var rotatedPreview: Bitmap? = null
        var rotatedOriginal: Bitmap? = null
        val rotatedMasks = ArrayList<Bitmap>(current.selectionLayers.size)
        try {
            rotatedPreview = rotateBitmap90(preview)
            rotatedOriginal = when {
                current.originalPreviewBitmap == null -> null
                current.originalPreviewBitmap === preview -> rotatedPreview
                else -> rotateBitmap90(current.originalPreviewBitmap)
            }
            current.selectionLayers.forEach { rotatedMasks += rotateBitmap90(it.bitmap) }
            val crop = current.cropState
            val nextCrop = crop.copy(
                cropLeft = 1f - crop.cropBottom,
                cropTop = crop.cropLeft,
                cropRight = 1f - crop.cropTop,
                cropBottom = crop.cropRight,
                aspectRatio = crop.aspectRatio.rotatedForQuarterTurn(),
                rotationDegrees = crop.rotationDegrees
            ).normalized()
            val adoptedPreview = checkNotNull(rotatedPreview)
            val adoptedOriginal = rotatedOriginal
            val adoptedMasks = rotatedMasks.toList()
            updateUiStateAndRecycleReplaced { state ->
                state.copy(
                    previewBitmap = adoptedPreview,
                    originalPreviewBitmap = adoptedOriginal,
                    selectionLayers = state.selectionLayers.mapIndexed { index, layer -> layer.copy(bitmap = adoptedMasks[index]) },
                    cropState = nextCrop,
                    baseBitmapDirty = true,
                    baseContentToken = newBaseContentToken(),
                    revision = state.revision + 1,
                    isBusy = false,
                    message = "미리보기를 90도 회전했습니다."
                )
            }
            rotatedPreview = null
            if (rotatedOriginal !== adoptedPreview) rotatedOriginal = null
            rotatedMasks.clear()
            commitUndoSnapshot(undoSnapshot, clearRedo = true)
            forceDraftSaveAsync()
            Log.i(FLARE_GUARD_AI_TAG, "Rotated preview manually: ${preview.width}x${preview.height} -> ${adoptedPreview.width}x${adoptedPreview.height}")
        } catch (t: Throwable) {
            val cleanup = java.util.Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
            listOf(rotatedPreview, rotatedOriginal).forEach { it?.let(cleanup::add) }
            rotatedMasks.forEach(cleanup::add)
            cleanup.forEach { if (!it.isRecycled) it.recycle() }
            recycleHistorySnapshot(undoSnapshot)
            updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "미리보기 회전에 실패했습니다.") }
        }
    }

    fun applySpotCleanup() {
        applyNativeSpecialEffects(
            title = "기본 정리",
            failureMessage = "기본 정리 적용에 실패했습니다.",
            effect = ActiveQuickEffect(QuickEffectKind.SpotCleanup)
        )
    }

    fun applyChromaticAberrationReduction() {
        applyNativeSpecialEffects(
            title = "색수차 완화",
            failureMessage = "색수차 완화 적용에 실패했습니다.",
            effect = ActiveQuickEffect(QuickEffectKind.ChromaticAberrationReduction)
        )
    }

    fun applyVignetteCorrection() {
        applyNativeSpecialEffects(
            title = "주변부 어두움 완화",
            failureMessage = "주변부 어두움 완화 적용에 실패했습니다.",
            effect = ActiveQuickEffect(QuickEffectKind.VignetteCorrection)
        )
    }

    fun applyOpticsCorrection() {
        applyNativeSpecialEffects(
            title = "통합 광학 보정",
            failureMessage = "통합 광학 보정 적용에 실패했습니다.",
            effect = ActiveQuickEffect(QuickEffectKind.OpticsCorrection)
        )
    }

    fun applySoftBlur(strength: Float = 0.32f) {
        applyNativeSpecialEffects(
            title = "부드러운 흐림",
            failureMessage = "부드러운 흐림 적용에 실패했습니다.",
            effect = ActiveQuickEffect(
                kind = QuickEffectKind.SoftBlur,
                strength = strength.toQuickEffectStrength()
            )
        )
    }

    private fun applyNativeSpecialEffects(
        title: String,
        failureMessage: String,
        effect: ActiveQuickEffect
    ) {
        if (isShuttingDown()) return
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return
        val current = prepareForExternalEdit()
        val baseOriginal = current.originalPreviewBitmap ?: current.previewBitmap
        if (baseOriginal == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "적용할 이미지가 없습니다.") }
            return
        }

        val currentQuickEffects = current.activeQuickEffects
        val nextActiveQuickEffects = currentQuickEffects.toggle(effect)
        if (nextActiveQuickEffects == currentQuickEffects) return
        var undoSnapshot: EditorHistorySnapshot? = captureCurrentHistorySnapshot()
        if (undoSnapshot == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = failureMessage) }
            return
        }
        var ownedBase: Bitmap? = try { baseOriginal.copyOrThrow() } catch (t: Throwable) {
            recycleHistorySnapshot(checkNotNull(undoSnapshot))
            undoSnapshot = null
            updateUiStateAndRecycleReplaced { it.copy(message = failureMessage) }
            return
        }
        val sourcePath = current.sourcePath
        val baseContentToken = current.baseContentToken
        val params = current.params
        val engines = current.engineSelection()
        val presetLook = current.presetLook
        val startRevision = current.revision
        val requestedQuickEffects = nextActiveQuickEffects
        val nextRevision = startRevision + 1
        renderJob?.cancel()
        updateUiStateAndRecycleReplaced {
            it.copy(
                isBusy = true,
                revision = nextRevision,
                message = "$title 적용 중입니다."
            )
        }

        renderJob = launchManagedEdit { operationToken ->
            var renderedPreview: Bitmap? = null
            try {
                withContext(Dispatchers.Default) {
                    val result = renderEditedPreview(
                        checkNotNull(ownedBase),
                        params,
                        engines,
                        nextRevision,
                        presetLook,
                        requestedQuickEffects
                    )
                    renderedPreview = result
                }
                val identityUnchanged = uiState.value.sourcePath == sourcePath &&
                    uiState.value.baseContentToken == baseContentToken
                if (isManagedEditCurrent(operationToken, nextRevision) && identityUnchanged) {
                    val adoptedPreview = renderedPreview!!
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            previewBitmap = adoptedPreview,
                            activeQuickEffects = requestedQuickEffects,
                            isBusy = false,
                            message = if (requestedQuickEffects.any { active -> active.matches(effect) }) {
                                "$title 적용했습니다. 다시 누르면 해제할 수 있습니다."
                            } else {
                                "$title 적용을 해제했습니다."
                            }
                        )
                    }
                    renderedPreview = null
                    commitUndoSnapshot(checkNotNull(undoSnapshot), clearRedo = true)
                    undoSnapshot = null
                    forceDraftSaveAsync()
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(FLARE_GUARD_AI_TAG, "$title native special effect failed", t)
                val failureIdentityUnchanged = uiState.value.sourcePath == sourcePath &&
                    uiState.value.baseContentToken == baseContentToken
                if (isManagedEditCurrent(operationToken, nextRevision) && failureIdentityUnchanged) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = failureMessage) }
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } finally {
                renderedPreview?.takeIf { !it.isRecycled }?.recycle()
                ownedBase?.takeIf { !it.isRecycled }?.recycle()
                undoSnapshot?.let(::recycleHistorySnapshot)
            }
        }
    }
    fun applyFlareGuardAiOrRulePreview(context: Context, mode: FlareGuardMode) {
        if (shuttingDown) return
        val stateBeforePrepare = _uiState.value
        if (stateBeforePrepare.isBusy && !isBusyOwnedByMaskSupersedable()) {
            return
        }
        val current = prepareForExternalEdit()
        val baseOriginal = current.originalPreviewBitmap ?: current.previewBitmap
        if (baseOriginal == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "번짐 완화를 적용할 이미지가 없습니다.") }
            return
        }

        val label = when (mode) {
            FlareGuardMode.NightLight -> "번짐 영역 감지"
            FlareGuardMode.DaySun -> "태양 번짐 영역 감지"
        }
        val nextRevision = current.revision + 1

        val undoSnapshot: EditorHistorySnapshot? = captureCurrentHistorySnapshot() ?: run {
            updateUiStateAndRecycleReplaced { it.copy(message = "번짐 완화 준비에 실패했습니다.") }
            return
        }
        val ownedBase: Bitmap? = runCatching { baseOriginal.copyOrThrow() }.getOrElse {
            recycleHistorySnapshot(checkNotNull(undoSnapshot))
            updateUiStateAndRecycleReplaced { it.copy(message = "번짐 완화 준비에 실패했습니다.") }
            return
        }

        val sourcePath = current.sourcePath
        val baseContentToken = current.baseContentToken
        val params = current.params
        val engines = current.engineSelection()
        val presetLook = current.presetLook
        val quickEffects = current.activeQuickEffects
        val appContext = context.applicationContext

        updateUiStateAndRecycleReplaced {
            it.copy(
                isBusy = true,
                revision = nextRevision,
                message = "$label 처리 중입니다.",
                flareGuardRuntimeStatus = "플레어 마스크 모델 상태를 확인하는 중입니다."
            )
        }
        Log.i(FLARE_GUARD_AI_TAG, "Starting FlareGuard preview: mode=$mode source=${baseOriginal.width}x${baseOriginal.height} revision=$nextRevision")

        renderJob = launchManagedEdit { operationToken ->
            var flareGuardResult: FlareGuardApplyResult? = null
            var flareGuardBitmap: Bitmap? = null
            var renderedPreview: Bitmap? = null
            var undoSnapshotOwned: EditorHistorySnapshot? = undoSnapshot
            var ownedBaseOwned: Bitmap? = ownedBase

            try {
                val result = withContext(Dispatchers.Default) {
                    val r = applyFlareGuardModelOrRuleResultV0(appContext, checkNotNull(ownedBaseOwned), mode, allowRuleFallback = true)
                    flareGuardResult = r
                    flareGuardBitmap = r.bitmap
                    r
                }

                renderedPreview = withContext(Dispatchers.Default) {
                    val p = renderEditedPreview(
                        checkNotNull(flareGuardBitmap),
                        params,
                        engines,
                        nextRevision,
                        presetLook,
                        quickEffects
                    )
                    renderedPreview = p
                    p
                }

                val adoptionIdentityUnchanged = !shuttingDown &&
                    _uiState.value.sourcePath == sourcePath &&
                    _uiState.value.baseContentToken == baseContentToken &&
                    _uiState.value.revision == nextRevision &&
                    managedEditToken == operationToken

                if (isManagedEditCurrent(operationToken, nextRevision) && adoptionIdentityUnchanged) {
                    val adoptedOriginal = flareGuardBitmap!!
                    val adoptedPreview = renderedPreview!!

                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            originalPreviewBitmap = adoptedOriginal,
                            previewBitmap = adoptedPreview,
                            baseBitmapDirty = true,
                            baseContentToken = newBaseContentToken(),
                            isBusy = false,
                            message = flareGuardResult!!.status.uiText,
                            flareGuardRuntimeStatus = flareGuardResult!!.status.uiText
                        )
                    }
                    flareGuardBitmap = null
                    renderedPreview = null
                    commitUndoSnapshot(checkNotNull(undoSnapshotOwned), clearRedo = true)
                    undoSnapshotOwned = null
                    forceDraftSaveAsync()
                    Log.i(FLARE_GUARD_AI_TAG, "Finished FlareGuard preview: mode=$mode status=${flareGuardResult!!.status} output=${flareGuardResult!!.bitmap.width}x${flareGuardResult!!.bitmap.height}")
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(FLARE_GUARD_AI_TAG, "FlareGuard preview failed", t)
                val failureIdentityUnchanged = !shuttingDown &&
                    _uiState.value.sourcePath == sourcePath &&
                    _uiState.value.baseContentToken == baseContentToken &&
                    _uiState.value.revision == nextRevision &&
                    managedEditToken == operationToken

                if (isManagedEditCurrent(operationToken, nextRevision) && failureIdentityUnchanged) {
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            isBusy = false,
                            message = "번짐 영역 감지에 실패했습니다.",
                            flareGuardRuntimeStatus = "번짐 영역 감지에 실패했습니다."
                        )
                    }
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } finally {
                flareGuardBitmap?.takeIf { !it.isRecycled }?.recycle()
                renderedPreview?.takeIf { !it.isRecycled }?.recycle()
                ownedBaseOwned?.takeIf { !it.isRecycled }?.recycle()
                undoSnapshotOwned?.let(::recycleHistorySnapshot)
            }
        }
    }

    private suspend fun restoreCurrentDraftGeneration(
        context: Context,
        restoreToken: Long,
        restoreStartRevision: Int
    ): GenerationRestoreOutcome {
        val pointer = withContext(Dispatchers.IO) { currentDraftGenerationId(context) }
            ?: return GenerationRestoreOutcome.Absent
        val validated = withContext(Dispatchers.IO) { validateCurrentDraftGeneration(context) }
            ?: return if (withContext(Dispatchers.IO) { currentDraftGenerationId(context) } != pointer) {
                GenerationRestoreOutcome.Stale
            } else {
                GenerationRestoreOutcome.Invalid(pointer)
            }
        if (validated.directory.root.name != pointer) return GenerationRestoreOutcome.Stale
        val manifest = validated.manifest
        val engines = runCatching {
            EngineSelection(
                NoiseEngine.valueOf(manifest.noiseEngine),
                DetailEngine.valueOf(manifest.detailEngine),
                ToneEngine.valueOf(manifest.toneEngine),
                DehazeEngine.valueOf(manifest.hazeEngine)
            )
        }.getOrNull() ?: return GenerationRestoreOutcome.Invalid(pointer)
        val exportFormat = runCatching { ExportFormat.valueOf(manifest.exportFormat) }.getOrNull()
            ?: return GenerationRestoreOutcome.Invalid(pointer)
        val exportResolution = runCatching { ExportResolution.valueOf(manifest.exportResolution) }.getOrNull()
            ?: return GenerationRestoreOutcome.Invalid(pointer)

        updateUiStateAndRecycleReplaced {
            if (restoreToken == restoreDraftToken && it.revision == restoreStartRevision) {
                it.copy(isBusy = true, message = "임시저장된 편집을 불러오는 중입니다")
            } else it
        }
        var ownedBase: Bitmap? = null
        var ownedRendered: Bitmap? = null
        val ownedMasks = ArrayList<Bitmap>(validated.maskFiles.size)
        var createdSession = 0L
        var ownedWorkingSource: File? = null
        var restoreSettlementLocked = false
        var restorePreviousBaseline: String? = null
        var restoreBaselineChanged = false
        var restoreStateAdopted = false
        try {
            withContext(Dispatchers.IO) {
                val workingSource = copyGenerationSourceToWorkingFile(context, validated.sourceFile)
                ownedWorkingSource = workingSource
                val decodedBase = decodeSampledMutableBitmapWithExif(workingSource.absolutePath, maxSide = 2048)
                ownedBase = decodedBase
                validated.maskFiles.forEach { file ->
                    val mask = BitmapFactory.decodeFile(
                        file.absolutePath,
                        BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            inMutable = true
                        }
                    ) ?: error("draft mask decode failed")
                    try {
                        ownedMasks += mask
                    } catch (t: Throwable) {
                        if (!mask.isRecycled) mask.recycle()
                        throw t
                    }
                }
            }
            val base = checkNotNull(ownedBase)
            if (ownedMasks.any { it.width != base.width || it.height != base.height }) error("draft mask geometry mismatch")
            val nextRevision = restoreStartRevision + 1
            val layers = manifest.selectionLayers.mapIndexed { index, entry ->
                SelectionLayer(
                    id = entry.id,
                    name = entry.name,
                    kind = SelectionLayerKind.valueOf(entry.kind),
                    bitmap = ownedMasks[index],
                    enabled = entry.enabled,
                    inverted = entry.inverted,
                    opacity = entry.opacity,
                    localParams = entry.localParams
                )
            }
            withContext(Dispatchers.Default) {
                val restoreState = _uiState.value.copy(
                    params = manifest.params,
                    presetLook = manifest.presetLook,
                    activeQuickEffects = manifest.activeQuickEffects,
                    selectionLayers = layers,
                    noiseEngine = engines.noiseEngine,
                    detailEngine = engines.detailEngine,
                    toneEngine = engines.toneEngine,
                    hazeEngine = engines.hazeEngine
                )
                val result = if (layers.any { it.enabled }) {
                    renderBitmapWithSelectionLayers(base, restoreState, nextRevision)
                } else {
                    renderEditedPreview(
                        base,
                        manifest.params,
                        engines,
                        nextRevision,
                        manifest.presetLook,
                        manifest.activeQuickEffects
                    )
                }
                ownedRendered = result
            }
            withContext(Dispatchers.IO) {
                val session = NativePhotoCore.nativeCreateSession(checkNotNull(ownedWorkingSource).absolutePath)
                createdSession = session
            }
            if (createdSession == 0L) error("draft native session creation failed")
            draftSaveMutex.lock()
            restoreSettlementLocked = true
            val pointerStillCurrent = withContext(Dispatchers.IO) { currentDraftGenerationId(context) == pointer }
            if (shuttingDown || restoreToken != restoreDraftToken || _uiState.value.revision != restoreStartRevision || !pointerStillCurrent || draftPointerBaseline != pointer) {
                if (!shuttingDown && restoreToken == restoreDraftToken && _uiState.value.revision == restoreStartRevision) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false) }
                }
                return GenerationRestoreOutcome.Stale
            }
            val activeLayerId = manifest.activeSelectionLayerId?.takeIf { id -> layers.any { it.id == id } }
            val previousState = _uiState.value
            val nextState = previousState.copy(
                isBusy = false,
                sourcePath = checkNotNull(ownedWorkingSource).absolutePath,
                baseBitmapDirty = manifest.baseBitmapDirty,
                baseContentToken = manifest.baseContentToken,
                originalPreviewBitmap = base,
                previewBitmap = checkNotNull(ownedRendered),
                params = manifest.params,
                presetLook = manifest.presetLook,
                cropState = manifest.cropState,
                exportFormat = exportFormat,
                exportResolution = exportResolution,
                noiseEngine = engines.noiseEngine,
                detailEngine = engines.detailEngine,
                toneEngine = engines.toneEngine,
                hazeEngine = engines.hazeEngine,
                draftSavedAtMillis = manifest.savedAtMillis,
                draftSourcePath = validated.sourceFile.absolutePath,
                draftBaseContentToken = manifest.baseContentToken,
                draftGenerationId = pointer,
                draftGenerationSourcePath = validated.sourceFile.absolutePath,
                draftGenerationThumbnailPath = validated.thumbnailFile.absolutePath,
                selectionLayers = layers,
                activeSelectionLayerId = activeLayerId,
                selectionPaintSettings = manifest.selectionPaintSettings,
                showSelectionOverlay = manifest.showSelectionOverlay,
                activeQuickEffects = manifest.activeQuickEffects,
                viewport = ViewportState(),
                flareGuardRuntimeStatus = null,
                revision = nextRevision,
                message = "임시저장된 편집을 불러왔습니다"
            )
            val previousSession = nativeSession
            restorePreviousBaseline = draftPointerBaseline
            draftPointerBaseline = pointer
            restoreBaselineChanged = true
            nativeSession = createdSession
            try {
                _uiState.value = nextState
            } catch (t: Throwable) {
                nativeSession = previousSession
                draftPointerBaseline = restorePreviousBaseline
                throw t
            }
            val adoptedState = _uiState.value
            val fullyAdopted = nativeSession == createdSession &&
                adoptedState.sourcePath == nextState.sourcePath &&
                adoptedState.originalPreviewBitmap === base &&
                adoptedState.previewBitmap === ownedRendered &&
                adoptedState.selectionLayers.size == layers.size &&
                adoptedState.selectionLayers.zip(layers).all { (live, expected) ->
                    live.id == expected.id && live.bitmap === expected.bitmap
                } &&
                adoptedState.baseContentToken == manifest.baseContentToken &&
                adoptedState.revision == nextRevision
            if (!fullyAdopted) {
                if (nativeSession == createdSession) nativeSession = previousSession
                draftPointerBaseline = restorePreviousBaseline
                error("draft generation adoption was not confirmed")
            }
restoreStateAdopted = true
            createdSession = 0L
            ownedBase = null
            ownedRendered = null
            ownedMasks.clear()
            ownedWorkingSource = null
            lastSuccessfullyRenderedParams = manifest.params
            runCatching { clearEditHistory() }.onFailure { logDraftSaveFailure(it) }
            runCatching { releaseOrphanedBitmaps(previousState, nextState) }.onFailure { logDraftSaveFailure(it) }
            runCatching { releaseNativeSessionHandle(previousSession) }.onFailure { logDraftSaveFailure(it) }
            runCatching { deleteOwnedWorkingSource(context, previousState.sourcePath) }.onFailure { logDraftSaveFailure(it) }
            return GenerationRestoreOutcome.Restored
        } catch (ce: CancellationException) {
            throw ce
} catch (t: Throwable) {
            logDraftSaveFailure(t)
            if (restoreStateAdopted) {
                return GenerationRestoreOutcome.Restored
            }
            if (withContext(Dispatchers.IO) { currentDraftGenerationId(context) } != pointer) {
                if (!shuttingDown && restoreToken == restoreDraftToken && _uiState.value.revision == restoreStartRevision) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false) }
                }
                return GenerationRestoreOutcome.Stale
            }
            if (!shuttingDown && restoreToken == restoreDraftToken && _uiState.value.revision == restoreStartRevision) {
                updateUiStateAndRecycleReplaced {
                    it.copy(isBusy = false, message = "새 임시저장 복구에 실패해 이전 복구 정보를 확인합니다.")
                }
            }
            return GenerationRestoreOutcome.Invalid(pointer)
        } finally {
            if (!restoreStateAdopted && restoreBaselineChanged && draftPointerBaseline == pointer) {
                draftPointerBaseline = restorePreviousBaseline
            }
            if (restoreSettlementLocked) draftSaveMutex.unlock()
            val cleanup = identityBitmapSet()
            ownedBase?.let(cleanup::add)
            ownedRendered?.let(cleanup::add)
            ownedMasks.forEach(cleanup::add)
            cleanup.forEach { if (!it.isRecycled) it.recycle() }
            ownedWorkingSource?.delete()
            releaseNativeSessionHandle(createdSession)
        }
    }

    private suspend fun restoreDraftIfAvailable(context: Context, restoreToken: Long, restoreStartRevision: Int) {
        if (shuttingDown || restoreToken != restoreDraftToken || restoreStartRevision != _uiState.value.revision) return
        val generationRestore = restoreCurrentDraftGeneration(context, restoreToken, restoreStartRevision)
        if (generationRestore == GenerationRestoreOutcome.Restored) return
        if (generationRestore == GenerationRestoreOutcome.Stale) return
        if (generationRestore is GenerationRestoreOutcome.Invalid) {
            val cleared = withContext(Dispatchers.IO) {
                draftSaveMutex.withLock {
                    if (restoreToken != restoreDraftToken || restoreStartRevision != _uiState.value.revision) return@withLock false
                    val actualPointer = currentDraftGenerationId(context)
                    if (actualPointer != generationRestore.generationId) {
                        draftPointerBaseline = when {
                            actualPointer == null -> null
                            validateCurrentDraftGeneration(context) != null -> actualPointer
                            else -> actualPointer
                        }
                        return@withLock false
                    }
                    if (!clearCurrentDraftGenerationPointer(context)) return@withLock false
                    draftPointerBaseline = null
                    true
                }
            }
            if (!cleared) {
                updateUiStateAndRecycleReplaced {
                    if (restoreToken == restoreDraftToken && it.revision == restoreStartRevision) {
                        it.copy(isBusy = false, message = "손상된 임시저장 포인터를 정리하지 못했습니다.")
                    } else it
                }
                return
            }
            withContext(Dispatchers.IO) {
                deleteDraftGenerationById(context, generationRestore.generationId)
            }
        }
        val restoreSnapshot = try {
            withContext(Dispatchers.IO) {
                draftSaveMutex.withLock {
                    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    val storedSourcePath = prefs.getString(KEY_DRAFT_SOURCE, null) ?: return@withLock null
                    val draftSavedAt = prefs.getLong(KEY_DRAFT_SAVED_AT, 0L).takeIf { it > 0L }
                    cleanupDraftTemporaryFiles(context)
                    DraftRestoreSnapshot(
                        preferences = DraftPreferencesSnapshot(prefs.all.toMap()),
                        savedAtMillis = draftSavedAt,
                        recovery = resolveDraftRecovery(context, storedSourcePath)
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            logDraftSaveFailure(t)
            if (restoreToken == restoreDraftToken) {
                updateUiStateAndRecycleReplaced {
                    it.copy(
                        isBusy = false,
                        message = "\uC784\uC2DC\uC800\uC7A5 \uBCF5\uAD6C \uC815\uBCF4\uB97C \uD655\uC778\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4. \uD3B8\uC9D1\uC740 \uACC4\uC18D\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4."
                    )
                }
            }
            return
        }
        if (restoreSnapshot == null) return
        val draftPrefs = restoreSnapshot.preferences
        val draftSavedAt = restoreSnapshot.savedAtMillis
        val recovery = restoreSnapshot.recovery
        if (restoreToken != restoreDraftToken) return
        updateUiStateAndRecycleReplaced {
            it.copy(
                draftSavedAtMillis = draftSavedAt,
                draftSourcePath = recovery.debugInfo.draftSourcePath,
                recoveryDebugInfo = recovery.debugInfo,
                showRecoveryDebugCard = true
            )
        }
        val sourceFile = recovery.sourceFile
        if (sourceFile == null) {
            val missingDraftMessage = "임시 저장 원본을 찾을 수 없습니다. 기존 임시 저장 파일이 삭제되어 복구할 수 없습니다."
            updateUiStateAndRecycleReplaced { it.copy(message = missingDraftMessage) }
            return
        }
        val sourcePath = sourceFile.absolutePath

        val legacyNoiseReduction = draftPrefs.getFloat(KEY_DRAFT_NOISE_REDUCTION, 0f)
        val params = EditParams(
            exposure = draftPrefs.getFloat(KEY_DRAFT_EXPOSURE, 0f),
            contrast = draftPrefs.getFloat(KEY_DRAFT_CONTRAST, 0f),
            shadows = draftPrefs.getFloat(KEY_DRAFT_SHADOWS, 0f),
            highlights = draftPrefs.getFloat(KEY_DRAFT_HIGHLIGHTS, 0f),
            whites = draftPrefs.getFloat(KEY_DRAFT_WHITES, 0f),
            blacks = draftPrefs.getFloat(KEY_DRAFT_BLACKS, 0f),
            temperature = draftPrefs.getFloat(KEY_DRAFT_TEMPERATURE, 0f),
            tint = draftPrefs.getFloat(KEY_DRAFT_TINT, 0f),
            saturation = draftPrefs.getFloat(KEY_DRAFT_SATURATION, 0f),
            vibrance = draftPrefs.getFloat(KEY_DRAFT_VIBRANCE, 0f),
            clarity = draftPrefs.getFloat(KEY_DRAFT_CLARITY, 0f),
            dehaze = draftPrefs.getFloat(KEY_DRAFT_DEHAZE, 0f),
            sharpness = draftPrefs.getFloat(KEY_DRAFT_SHARPNESS, 0f),
            noiseReduction = legacyNoiseReduction,
            luminanceNoiseReduction = draftPrefs.getFloat(KEY_DRAFT_LUMINANCE_NOISE_REDUCTION, legacyNoiseReduction),
            colorNoiseReduction = draftPrefs.getFloat(KEY_DRAFT_COLOR_NOISE_REDUCTION, legacyNoiseReduction),
            noiseDetailProtection = draftPrefs.getFloat(KEY_DRAFT_NOISE_DETAIL_PROTECTION, 0.50f)
        )
        val exportFormat = enumValueOrDefault(draftPrefs.getString(KEY_DRAFT_FORMAT, null), ExportFormat.Jpeg)
        val exportResolution = enumValueOrDefault(draftPrefs.getString(KEY_DRAFT_RESOLUTION, null), ExportResolution.Full)
        val presetLook = runCatching {
            presetColorLookFromJson(draftPrefs.getString(KEY_DRAFT_LOOK, null)?.let(::JSONObject))
        }.getOrNull()
        val engines = _uiState.value.engineSelection()
        updateUiStateAndRecycleReplaced {
            it.copy(
                draftSavedAtMillis = draftSavedAt,
                draftSourcePath = sourcePath
            )
        }

        updateUiStateAndRecycleReplaced { it.copy(isBusy = true, message = "\uC784\uC2DC\uC800\uC7A5\uB41C \uD3B8\uC9D1\uC744 \uBD88\uB7EC\uC624\uB294 \uC911\uC785\uB2C8\uB2E4") }
        var preview: Bitmap? = null
        var rendered: Bitmap? = null
        var createdSession = 0L
        var expectedRestoreRevision: Int? = null
        fun recycleOwnedRestoreBitmaps() {
            val owned = identityBitmapSet()
            preview?.let(owned::add)
            rendered?.let(owned::add)
            owned.forEach { if (!it.isRecycled) it.recycle() }
            preview = null
            rendered = null
        }
        try {
            withContext(Dispatchers.IO) {
                val decoded = decodeSampledMutableBitmapWithExif(sourcePath, maxSide = 2048)
                preview = decoded
            }
            val nextRevision = _uiState.value.revision + 1
            expectedRestoreRevision = nextRevision
            val activeQuickEffects = draftPrefs.getString(KEY_DRAFT_QUICK_EFFECTS, null).parseQuickEffects()
            withContext(Dispatchers.Default) {
                val result = renderEditedPreview(preview!!, params, engines, nextRevision, presetLook, activeQuickEffects)
                rendered = result
            }
            if (shuttingDown || restoreToken != restoreDraftToken || _uiState.value.revision != restoreStartRevision) {
                recycleOwnedRestoreBitmaps()
                return
            }
            createdSession = NativePhotoCore.nativeCreateSession(sourcePath)
            if (shuttingDown || restoreToken != restoreDraftToken || _uiState.value.revision != restoreStartRevision) {
                recycleOwnedRestoreBitmaps()
                releaseNativeSessionHandle(createdSession)
                createdSession = 0L
                return
            }
            val previousSession = nativeSession
            val adoptedPreview = preview!!
            val adoptedRendered = rendered!!
            val previousState = _uiState.value
            val nextState = previousState.copy(
                    isBusy = false,
                    sourcePath = sourcePath,
                    baseBitmapDirty = false,
                    baseContentToken = draftPrefs.getString(KEY_DRAFT_BASE_TOKEN, null) ?: newBaseContentToken(),
                    originalPreviewBitmap = adoptedPreview,
                    previewBitmap = adoptedRendered,
                    cropState = CropState(),
                    selectionLayers = emptyList(),
                    activeSelectionLayerId = null,
                    selectionPaintSettings = SelectionPaintSettings(),
                    showSelectionOverlay = true,
                    viewport = ViewportState(),
                    activeQuickEffects = activeQuickEffects,
                    params = params,
                    presetLook = presetLook,
                    exportFormat = exportFormat,
                    exportResolution = exportResolution,
                    draftSavedAtMillis = draftSavedAt,
                    draftSourcePath = sourcePath,
                    draftBaseContentToken = draftPrefs.getString(KEY_DRAFT_BASE_TOKEN, null),
                    draftGenerationId = null,
                    draftGenerationSourcePath = null,
                    draftGenerationThumbnailPath = null,
                    flareGuardRuntimeStatus = null,
                    revision = nextRevision,
                    message = "\uC784\uC2DC\uC800\uC7A5\uB41C \uD3B8\uC9D1\uC744 \uBD88\uB7EC\uC654\uC2B5\uB2C8\uB2E4"
            )
            nativeSession = createdSession
            try {
                _uiState.value = nextState
            } catch (t: Throwable) {
                nativeSession = previousSession
                throw t
            }
            createdSession = 0L
            preview = null
            rendered = null
            lastSuccessfullyRenderedParams = params
            clearEditHistory()
            runCatching { releaseOrphanedBitmaps(previousState, nextState) }
            releaseNativeSessionHandle(previousSession)
            deleteOwnedWorkingSource(context, previousState.sourcePath)
            forceDraftSaveAsync()
        } catch (ce: CancellationException) {
            recycleOwnedRestoreBitmaps()
            releaseNativeSessionHandle(createdSession)
            throw ce
        } catch (t: Throwable) {
            recycleOwnedRestoreBitmaps()
            releaseNativeSessionHandle(createdSession)
            val currentRevision = _uiState.value.revision
            val isRestoreStillCurrent = !shuttingDown && restoreToken == restoreDraftToken &&
                (currentRevision == restoreStartRevision || currentRevision == expectedRestoreRevision)
            if (isRestoreStillCurrent) {
                updateUiStateAndRecycleReplaced { it.copy(isBusy = false, message = "\uC784\uC2DC\uC800\uC7A5\uC744 \uBD88\uB7EC\uC624\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4: ${t.message}") }
            }
        }
    }

    private fun commitPendingParamUndoSnapshot() {
        val snapshot = pendingParamUndoSnapshot ?: return
        if (!paramUndoSnapshotCommitted) {
            undoHistory.addLast(snapshot)
            trimUndoHistory()
            redoHistory.forEach(::recycleHistorySnapshot)
            redoHistory.clear()
            updateHistoryFlags()
            paramUndoSnapshotCommitted = true
        }
        pendingParamUndoSnapshot = null
    }

    private fun discardPendingParamUndoSnapshot() {
        if (!paramUndoSnapshotCommitted) pendingParamUndoSnapshot?.recycleBitmaps()
        pendingParamUndoSnapshot = null
        closeParamUndoWindow()
    }

    private fun takePendingParamUndoSnapshotForRollback(): EditorHistorySnapshot? {
        val snapshot = pendingParamUndoSnapshot
        pendingParamUndoSnapshot = null
        closeParamUndoWindow()
        return snapshot
    }

    internal fun abortPendingParameterEdit() {
        val unresolved = activeParamRenderRevision != null ||
            _uiState.value.params != lastSuccessfullyRenderedParams ||
            pendingParamUndoSnapshot != null || paramUndoWindowOpen
        if (!unresolved) return
        renderJob?.cancel()
        activeParamRenderRevision = null
        updateUiState { it.copy(params = lastSuccessfullyRenderedParams, revision = it.revision + 1, isBusy = false) }
        discardPendingParamUndoSnapshot()
    }

    private fun prepareForMaskInteraction(): EditorUiState {
        abortPendingParameterEdit()
        if (brushingSnapshot != null) {
            cancelBrushStroke()
        }
        settleSelectionParamTransactionForSupersession()
        return uiState.value
    }

    private fun prepareForGlobalParamEdit(): EditorUiState {
        if (brushingSnapshot != null) {
            cancelBrushStroke()
        }
        settleSelectionParamTransactionForSupersession()
        return uiState.value
    }

    internal fun prepareForExternalEdit(): EditorUiState {
        prepareForMaskInteraction()
        return uiState.value
    }

    private fun closeParamUndoWindow() {
        paramUndoWindowJob?.cancel()
        paramUndoWindowJob = null
        paramUndoWindowOpen = false
    }

    private fun pushUndoSnapshot(clearRedo: Boolean) {
        if (activeParamRenderRevision != null || _uiState.value.params != lastSuccessfullyRenderedParams) {
            abortPendingParameterEdit()
        }
        closeParamUndoWindow()
        val state = _uiState.value
        if (state.previewBitmap == null && state.originalPreviewBitmap == null) return
        val snapshot = try {
            state.toHistorySnapshot()
        } catch (t: Throwable) {
            Log.w(FLARE_GUARD_AI_TAG, "Failed to create undo snapshot", t)
            updateUiStateAndRecycleReplaced {
                it.copy(message = "되돌리기 기록을 저장하지 못했습니다. 편집은 계속할 수 있습니다.")
            }
            return
        }
        undoHistory.addLast(snapshot)
        trimUndoHistory()
        if (clearRedo) {
            redoHistory.forEach(::recycleHistorySnapshot)
            redoHistory.clear()
        }
        updateHistoryFlags()
        Log.i(FLARE_GUARD_AI_TAG, "Pushed undo snapshot: undo=${undoHistory.size} redo=${redoHistory.size}")
    }

    private fun applyHistorySnapshot(snapshot: EditorHistorySnapshot, message: String) {
        invalidateSelectionPreview()
        lastSuccessfullyRenderedParams = snapshot.params
        updateUiStateAndRecycleReplaced {
            it.copy(
                params = snapshot.params,
                noiseEngine = snapshot.noiseEngine,
                detailEngine = snapshot.detailEngine,
                toneEngine = snapshot.toneEngine,
                hazeEngine = snapshot.hazeEngine,
                baseBitmapDirty = snapshot.baseBitmapDirty,
                baseContentToken = snapshot.baseContentToken,
                previewBitmap = snapshot.previewBitmap,
                originalPreviewBitmap = snapshot.originalPreviewBitmap,
                presetLook = snapshot.presetLook,
                cropState = snapshot.cropState,
                selectionLayers = snapshot.selectionLayers,
                activeSelectionLayerId = snapshot.activeSelectionLayerId,
                selectionPaintSettings = snapshot.selectionPaintSettings,
                showSelectionOverlay = snapshot.showSelectionOverlay,
                activeQuickEffects = snapshot.activeQuickEffects,
                flareGuardRuntimeStatus = snapshot.flareGuardRuntimeStatus,
                isBusy = false,
                revision = it.revision + 1,
                message = message
            )
        }
        saveEngineSelection(getApplication<Application>(), EngineSelection(snapshot.noiseEngine, snapshot.detailEngine, snapshot.toneEngine, snapshot.hazeEngine))
    }

    private fun updateHistoryFlags() {
        updateUiStateAndRecycleReplaced { it.copy(canUndo = undoHistory.isNotEmpty(), canRedo = redoHistory.isNotEmpty()) }
    }

    private fun trimUndoHistory() {
        while (undoHistory.size > EDITOR_HISTORY_MAX) {
            if (undoHistory.isEmpty()) break
            val evicted = undoHistory.removeFirst()
            recycleHistorySnapshot(evicted)
            Log.d(FLARE_GUARD_AI_TAG, "Evicted history snapshot")
        }
    }

    private fun clearEditHistory() {
        discardPendingParamUndoSnapshot()
        undoHistory.forEach(::recycleHistorySnapshot)
        redoHistory.forEach(::recycleHistorySnapshot)
        undoHistory.clear()
        redoHistory.clear()
        updateHistoryFlags()
    }

    private fun releaseNativeSessionHandle(session: Long) {
        if (session != 0L) {
            runCatching { NativePhotoCore.nativeReleaseSession(session) }
        }
    }

    private fun releaseNativeSession() {
        if (nativeSession != 0L) {
            releaseNativeSessionHandle(nativeSession)
            nativeSession = 0L
        }
    }

    override fun onCleared() {
        shuttingDown = true
        managedEditToken += 1L
        invalidateManagedEdits()
        invalidateDraftOperations()
        renderJob?.cancel()
        invalidateExport()
        invalidateSelectionPreview()
        abortPendingParameterEdit()
        paramUndoWindowJob?.cancel()
        cropJob?.cancel()
        transactionFinishJob?.cancel()
        ThumbnailBitmapCache.clear()
        releaseNativeSession()
        clearEditHistory()
        super.onCleared()
    }
}

internal data class EditorHistorySnapshot(
    val params: EditParams,
    val noiseEngine: NoiseEngine,
    val detailEngine: DetailEngine,
    val toneEngine: ToneEngine,
    val hazeEngine: DehazeEngine,
    val baseBitmapDirty: Boolean,
    val baseContentToken: String,
    val previewBitmap: Bitmap?,
    val originalPreviewBitmap: Bitmap?,
    val presetLook: PresetColorLook?,
    val cropState: CropState,
    val selectionLayers: List<SelectionLayer>,
    val activeSelectionLayerId: String?,
    val selectionPaintSettings: SelectionPaintSettings,
    val showSelectionOverlay: Boolean,
    val activeQuickEffects: List<ActiveQuickEffect>,
    val flareGuardRuntimeStatus: String?,
    var resourcesReleased: Boolean = false
)

/**
 * One instance per gesture. Old preview/finish jobs must re-confirm they still own this
 * transaction (by identity) and its finish job before mutating state.
 */
internal class SelectionParamTransaction(
    val gestureId: Long,
    val snapshot: EditorHistorySnapshot,
    val startRevision: Int,
    val baseContentToken: String,
    val activeSelectionLayerId: String?
) {
    var latestPreviewToken: Long = 0L
    var finalPreviewToken: Long? = null
    var finalPreviewRevision: Int? = null
    var finalPreviewBaseToken: String? = null
    var finalPreviewLayerId: String? = null
    var previewJob: Job? = null
    var succeeded: Boolean = false
    var committed: Boolean = false
    var settled: Boolean = false
    @Volatile var finished: Boolean = false
    @Volatile var finishJobRef: Job? = null
    var previewRevision: Int? = null
    var previewBaseToken: String? = null
    var previewLayerId: String? = null

    fun hasOptimisticLiveParams(state: EditorUiState): Boolean {
        if (activeSelectionLayerId == null) return false
        val liveLayer = state.selectionLayers.firstOrNull { it.id == activeSelectionLayerId } ?: return true
        val snapshotLayer = snapshot.selectionLayers.firstOrNull { it.id == activeSelectionLayerId } ?: return true
        return liveLayer.localParams != snapshotLayer.localParams
    }
}

private fun EditorUiState.toHistorySnapshot(): EditorHistorySnapshot {
    var previewCopy: Bitmap? = null
    var originalCopy: Bitmap? = null
    val selectionCopies = ArrayList<SelectionLayer>(selectionLayers.size)
    try {
        previewCopy = previewBitmap?.copyOrThrow(Bitmap.Config.ARGB_8888, true)
        originalCopy = if (originalPreviewBitmap == null) {
            null
        } else if (originalPreviewBitmap === previewBitmap) {
            previewCopy
        } else {
            originalPreviewBitmap.copyOrThrow(Bitmap.Config.ARGB_8888, true)
        }
        selectionLayers.forEach { layer ->
            selectionCopies.add(layer.copy(bitmap = layer.bitmap.copyOrThrow(Bitmap.Config.ARGB_8888, true)))
        }
        return EditorHistorySnapshot(
            params = params,
            noiseEngine = noiseEngine,
            detailEngine = detailEngine,
            toneEngine = toneEngine,
            hazeEngine = hazeEngine,
            baseBitmapDirty = baseBitmapDirty,
            baseContentToken = baseContentToken,
            previewBitmap = previewCopy,
            originalPreviewBitmap = originalCopy,
            presetLook = presetLook,
            cropState = cropState,
            selectionLayers = selectionCopies,
            activeSelectionLayerId = activeSelectionLayerId,
            selectionPaintSettings = selectionPaintSettings,
            showSelectionOverlay = showSelectionOverlay,
            activeQuickEffects = activeQuickEffects,
            flareGuardRuntimeStatus = flareGuardRuntimeStatus
        )
    } catch (t: Throwable) {
        previewCopy?.takeIf { it !== originalCopy && !it.isRecycled }?.recycle()
        originalCopy?.takeIf { !it.isRecycled }?.recycle()
        selectionCopies.forEach { it.bitmap.takeIf { bitmap -> !bitmap.isRecycled }?.recycle() }
        throw t
    }
}

private fun buildHistoryAppliedMessage(
    current: EditorUiState,
    target: EditorHistorySnapshot,
    prefix: String
): String {
    val changedParams = historyParamSummaries(current.params, target.params)
    if (changedParams.isNotEmpty()) {
        return "$prefix: ${changedParams.take(3).joinToString(", ")}"
    }
    val changedImageState = current.presetLook != target.presetLook ||
        current.noiseEngine != target.noiseEngine ||
        current.detailEngine != target.detailEngine ||
        current.toneEngine != target.toneEngine ||
        current.hazeEngine != target.hazeEngine ||
        current.cropState != target.cropState ||
        current.selectionLayers != target.selectionLayers ||
        current.activeSelectionLayerId != target.activeSelectionLayerId ||
        current.selectionPaintSettings != target.selectionPaintSettings ||
        current.showSelectionOverlay != target.showSelectionOverlay ||
        current.activeQuickEffects != target.activeQuickEffects ||
        current.previewBitmap !== target.previewBitmap ||
        current.originalPreviewBitmap !== target.originalPreviewBitmap
    return if (changedImageState) {
        "$prefix: 이미지 상태 변경"
    } else {
        prefix
    }
}

private fun historyParamSummaries(current: EditParams, target: EditParams): List<String> = listOfNotNull(
    historyExposureSummary(current.exposure, target.exposure),
    historySliderSummary("대비", current.contrast, target.contrast),
    historySliderSummary("하이라이트", current.highlights, target.highlights),
    historySliderSummary("그림자", current.shadows, target.shadows),
    historySliderSummary("화이트", current.whites, target.whites),
    historySliderSummary("블랙", current.blacks, target.blacks),
    historySliderSummary("색온도", current.temperature, target.temperature),
    historySliderSummary("색조", current.tint, target.tint),
    historySliderSummary("채도", current.saturation, target.saturation),
    historySliderSummary("생동감", current.vibrance, target.vibrance),
    historySliderSummary("명료도", current.clarity, target.clarity),
    historySliderSummary("디헤이즈", current.dehaze, target.dehaze),
    historySliderSummary("선명도", current.sharpness, target.sharpness),
    historyAbsoluteSliderSummary("노이즈 감소", current.luminanceNoiseReduction, target.luminanceNoiseReduction),
    historyAbsoluteSliderSummary("색상 노이즈 감소", current.colorNoiseReduction, target.colorNoiseReduction),
    historyAbsoluteSliderSummary("디테일 보호", current.noiseDetailProtection, target.noiseDetailProtection)
)

private fun historyExposureSummary(current: Float, target: Float): String? {
    if (!historyValueChanged(current, target)) return null
    val value = historySignedValue(target)
    return "노출 $value"
}

private fun historySliderSummary(label: String, current: Float, target: Float): String? {
    if (!historyValueChanged(current, target)) return null
    return "$label ${historySignedValue(target)}"
}

private fun historyAbsoluteSliderSummary(label: String, current: Float, target: Float): String? {
    if (!historyValueChanged(current, target)) return null
    return "$label ${String.format(Locale.US, "%.2f", target)}"
}

private fun historyValueChanged(current: Float, target: Float): Boolean =
    kotlin.math.abs(current - target) >= 0.0005f

private fun historyIsZero(value: Float): Boolean = kotlin.math.abs(value) < 0.0005f

private fun historySignedValue(value: Float): String =
    if (historyIsZero(value)) "0.00" else String.format(Locale.US, "%+.2f", value)

private fun identityBitmapSet(): MutableSet<Bitmap> =
    Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())

internal fun Bitmap.copyOrThrow(config: Bitmap.Config = Bitmap.Config.ARGB_8888, mutable: Boolean = true): Bitmap =
    copy(config, mutable) ?: throw IllegalStateException("bitmap copy failed")

private fun EditorHistorySnapshot.recycleBitmaps() {
    if (resourcesReleased) {
        Log.w(FLARE_GUARD_AI_TAG, "History bitmap release underflow: snapshot already released")
        return
    }
    resourcesReleased = true
    val bitmaps = identityBitmapSet()
    previewBitmap?.let(bitmaps::add)
    originalPreviewBitmap?.let(bitmaps::add)
    selectionLayers.forEach { bitmaps.add(it.bitmap) }
    bitmaps.forEach { if (!it.isRecycled) it.recycle() }
}

internal data class EngineSelection(
    val noiseEngine: NoiseEngine,
    val detailEngine: DetailEngine,
    val toneEngine: ToneEngine,
    val hazeEngine: DehazeEngine
)

internal fun EditorUiState.engineSelection(): EngineSelection = EngineSelection(
    noiseEngine = noiseEngine,
    detailEngine = detailEngine,
    toneEngine = toneEngine,
    hazeEngine = hazeEngine
)

private data class LumaStats(
    val p01: Float,
    val p05: Float,
    val p50: Float,
    val p95: Float,
    val p99: Float,
    val mean: Float,
    val chromaMean: Float
)

private fun computeAutoEnhanceParams(bitmap: Bitmap): EditParams {
    val stats = analyzeBitmap(bitmap)
    val safeMedian = stats.p50.coerceAtLeast(0.015f)
    val exposure = (ln(0.46f / safeMedian) / ln(2f)).toFloat().coerceIn(-0.70f, 0.70f)

    val range = (stats.p95 - stats.p05).coerceAtLeast(0.01f)
    val contrast = ((0.58f - range) * 0.70f).coerceIn(-0.22f, 0.38f)
    val shadows = ((0.24f - stats.p05) * 0.85f).coerceIn(-0.18f, 0.42f)
    val highlights = ((stats.p95 - 0.78f) * 0.95f).coerceIn(-0.18f, 0.42f)
    val whites = ((0.97f - stats.p99) * 0.65f).coerceIn(-0.20f, 0.30f)
    val blacks = ((0.025f - stats.p01) * 1.05f).coerceIn(-0.32f, 0.22f)
    val vibrance = ((0.30f - stats.chromaMean) * 0.72f).coerceIn(0.00f, 0.28f)
    val saturation = if (stats.chromaMean < 0.10f) 0.04f else 0.00f
    val clarity = if (range < 0.48f) 0.12f else 0.07f
    val dehaze = if (stats.p05 > 0.10f && stats.p95 < 0.86f) 0.10f else 0.02f
    val noiseReduction = if (stats.mean < 0.34f) 0.20f else 0.08f

    return EditParams(
        exposure = exposure,
        contrast = contrast,
        shadows = shadows,
        highlights = highlights,
        whites = whites,
        blacks = blacks,
        temperature = 0f,
        tint = 0f,
        saturation = saturation,
        vibrance = vibrance,
        clarity = clarity,
        dehaze = dehaze,
        sharpness = 0.16f,
        noiseReduction = noiseReduction
    )
}

private fun analyzeBitmap(bitmap: Bitmap): LumaStats {
    val histogram = IntArray(256)
    var count = 0
    var lumaSum = 0f
    var chromaSum = 0f
    val step = max(1, max(bitmap.width, bitmap.height) / 512)
    val row = IntArray(bitmap.width)

    var y = 0
    while (y < bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        var x = 0
        while (x < bitmap.width) {
            val pixel = row[x]
            val r = ((pixel shr 16) and 0xff) / 255f
            val g = ((pixel shr 8) and 0xff) / 255f
            val b = (pixel and 0xff) / 255f
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
            val maxC = max(r, max(g, b))
            val minC = kotlin.math.min(r, kotlin.math.min(g, b))
            histogram[(luma * 255f).roundToInt().coerceIn(0, 255)] += 1
            lumaSum += luma
            chromaSum += (maxC - minC).coerceIn(0f, 1f)
            count += 1
            x += step
        }
        y += step
    }

    if (count <= 0) {
        return LumaStats(0f, 0f, 0.5f, 1f, 1f, 0.5f, 0.1f)
    }

    return LumaStats(
        p01 = percentileFromHistogram(histogram, count, 0.01f),
        p05 = percentileFromHistogram(histogram, count, 0.05f),
        p50 = percentileFromHistogram(histogram, count, 0.50f),
        p95 = percentileFromHistogram(histogram, count, 0.95f),
        p99 = percentileFromHistogram(histogram, count, 0.99f),
        mean = lumaSum / count,
        chromaMean = chromaSum / count
    )
}

private fun percentileFromHistogram(histogram: IntArray, count: Int, percentile: Float): Float {
    val target = (count * percentile).roundToInt().coerceIn(1, count)
    var accum = 0
    for (i in histogram.indices) {
        accum += histogram[i]
        if (accum >= target) return i / 255f
    }
    return 1f
}

private fun copyUriToCache(context: Context, uri: Uri): File {
    val outFile = File(context.cacheDir, "source_${System.currentTimeMillis()}.img")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "input stream is null" }
        FileOutputStream(outFile).use { output -> input.copyTo(output) }
    }
    return outFile
}

private fun migrateDraftSourceIfNeeded(context: Context, storedSourcePath: String): File? {
    val storedSource = File(storedSourcePath)
    if (!storedSource.isFile) return null
    if (isOwnedDraftSource(context, storedSource)) return storedSource
    val draftSource = persistDraftSourceFile(context, storedSource.absolutePath) ?: return null
    if (draftSource.absolutePath != storedSource.absolutePath) {
        val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val previousPointer = preferences.getString(KEY_DRAFT_SOURCE, null)
        try {
            check(preferences.edit().putString(KEY_DRAFT_SOURCE, draftSource.absolutePath).commit())
        } catch (failure: Throwable) {
            val restored = try {
                preferences.edit().remove(KEY_DRAFT_SOURCE).apply {
                    if (previousPointer != null) putString(KEY_DRAFT_SOURCE, previousPointer)
                }.commit()
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
                false
            }
            if (restored) draftSource.delete()
            Log.w(FLARE_GUARD_AI_TAG, "Draft source migration failed", failure)
            return storedSource
        }
        saveDraftThumbnailFile(context, draftSource)
    }
    return draftSource
}

private data class DraftRecoveryResolution(
    val sourceFile: File?,
    val missingLegacyCacheDraft: Boolean,
    val debugInfo: RecoveryDebugInfo
)

private fun resolveDraftRecovery(context: Context, storedSourcePath: String): DraftRecoveryResolution {
    val storedSource = File(storedSourcePath)
    val persistentSource = persistentDraftSourceFile(context)
    val storedExists = storedSource.isFile
    val persistentExists = persistentSource.isFile
    val storedInCache = storedSource.absolutePath.startsWith(context.cacheDir.absolutePath)
    val sourceFile = when {
        storedExists && isOwnedDraftSource(context, storedSource) -> storedSource
        storedExists -> migrateDraftSourceIfNeeded(context, storedSource.absolutePath)
        storedInCache && persistentExists -> {
            runCatching {
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_DRAFT_SOURCE, persistentSource.absolutePath)
                    .commit()
            }
            persistentSource
        }
        storedSource.absolutePath == persistentSource.absolutePath && persistentExists -> persistentSource
        else -> null
    }
    return DraftRecoveryResolution(
        sourceFile = sourceFile,
        missingLegacyCacheDraft = storedInCache && !storedExists,
        debugInfo = RecoveryDebugInfo(
            draftSourcePath = sourceFile?.absolutePath ?: storedSource.absolutePath,
            draftSourceExists = sourceFile?.isFile == true || storedExists,
            filesDirDraftPath = persistentSource.absolutePath,
            filesDirDraftExists = persistentExists
        )
    )
}

private fun persistDraftSourceFile(context: Context, sourcePath: String): File? {
    val source = File(sourcePath).takeIf { it.isFile } ?: return null
    val draftDirectory = persistentDraftDirectory(context)
    if (isOwnedDraftSource(context, source)) return source
    val generation = File(draftDirectory, "source_${UUID.randomUUID()}.img")
    copyFileAtomically(source, generation)
    return generation
}

internal fun newBaseContentToken(): String = UUID.randomUUID().toString()

private fun persistDraftSourceFileIfNeeded(context: Context, sourcePath: String): DraftSourceResult? {
    val source = File(sourcePath).takeIf { it.isFile } ?: return null
    if (isOwnedDraftSource(context, source)) return DraftSourceResult(source, changed = false)
    return persistDraftSourceFile(context, source.absolutePath)?.let { DraftSourceResult(it, changed = true) }
}

private fun persistDraftBitmapFile(context: Context, bitmap: Bitmap): File? {
    val draftSource = File(persistentDraftDirectory(context), "source_${UUID.randomUUID()}.img")
    val temp = File(draftSource.parentFile, "${draftSource.name}.tmp")
    try {
        FileOutputStream(temp).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "failed to encode draft bitmap" }
            output.fd.sync()
        }
        check(temp.renameTo(draftSource)) { "failed to persist draft bitmap" }
        return draftSource
    } catch (t: Throwable) {
        temp.delete()
        draftSource.delete()
        throw t
    }
}

private fun copyFileAtomically(source: File, destination: File) {
    val temp = File(destination.parentFile, "${destination.name}.${UUID.randomUUID()}.tmp")
    try {
        source.inputStream().use { input ->
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        check(temp.renameTo(destination)) { "failed to persist draft source" }
    } catch (t: Throwable) {
        temp.delete()
        destination.delete()
        throw t
    }
}

private fun isOwnedDraftSource(context: Context, file: File): Boolean {
    val directory = persistentDraftDirectory(context).canonicalFile
    val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return false
    return candidate.parentFile == directory && candidate.name.startsWith("source_") && candidate.extension == "img" && candidate.isFile
}

private fun isReusableCommittedDraftSource(context: Context, state: EditorUiState): Boolean {
    val path = state.draftSourcePath ?: return false
    val source = File(path)
    if (!source.isFile) return false
    if (!isSupportedDraftSource(context, source)) return false
    val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    return sameCanonicalPath(preferences.getString(KEY_DRAFT_SOURCE, null), path) &&
        preferences.getString(KEY_DRAFT_BASE_TOKEN, null) == state.baseContentToken
}

private fun isSupportedDraftSource(context: Context, source: File): Boolean =
    source.isFile && (isOwnedDraftSource(context, source) || source.name == DRAFT_SOURCE_FILE_NAME)

private fun sameCanonicalPath(first: String?, second: String?): Boolean =
    first != null && second != null && runCatching {
        File(first).canonicalFile == File(second).canonicalFile
    }.getOrDefault(false)

private fun sameOptionalCanonicalPath(first: String?, second: String?): Boolean =
    if (first == null || second == null) first == second else sameCanonicalPath(first, second)

private fun deleteObsoleteDraftSources(context: Context, keep: File, preservePath: String?) {
    val directory = persistentDraftDirectory(context)
    val preserve = preservePath?.let { runCatching { File(it).canonicalFile }.getOrNull() }
    directory.listFiles()?.forEach { file ->
        val owned = file.name.startsWith("source_") && file.extension == "img"
        if (owned && file.canonicalFile != keep.canonicalFile && file.canonicalFile != preserve) file.delete()
        if (file.name.endsWith(".tmp")) file.delete()
    }
}

private fun cleanupDraftTemporaryFiles(context: Context) {
    persistentDraftDirectory(context).listFiles()?.forEach { file ->
        if (file.name.endsWith(".tmp")) file.delete()
    }
}

private fun saveDraftThumbnailFile(context: Context, source: File) {
    runCatching {
        val thumbnail = decodeSampledMutableBitmapWithExif(source.absolutePath, maxSide = 512)
        try {
            FileOutputStream(persistentDraftThumbnailFile(context)).use { output ->
                thumbnail.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
        } finally {
            thumbnail.recycle()
        }
    }
}

private fun logDraftSaveFailure(t: Throwable) {
    Log.w(FLARE_GUARD_AI_TAG, "Draft autosave failed", t)
}

private fun persistentDraftSourceFile(context: Context): File =
    File(persistentDraftDirectory(context), DRAFT_SOURCE_FILE_NAME)

private fun persistentDraftThumbnailFile(context: Context): File =
    File(persistentDraftDirectory(context), DRAFT_THUMBNAIL_FILE_NAME)

private fun persistentDraftDirectory(context: Context): File =
    File(context.filesDir, "drafts/current").apply { mkdirs() }

private fun decodeSampledMutableBitmap(path: String, maxSide: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "지원하지 않는 이미지이거나 디코딩에 실패했습니다" }

    var sample = 1
    val longest = max(bounds.outWidth, bounds.outHeight)
    while (longest / sample > maxSide) sample *= 2

    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = true
    }
    val decoded = requireNotNull(BitmapFactory.decodeFile(path, options)) { "미리보기 디코딩에 실패했습니다" }
    if (decoded.config == Bitmap.Config.ARGB_8888 && decoded.isMutable) return decoded
    val mutable = decoded.copyOrThrow(Bitmap.Config.ARGB_8888, true)
    decoded.recycle()
    return mutable
}

private fun decodeSampledMutableBitmapWithExif(path: String, maxSide: Int): Bitmap {
    return applyExifOrientation(path, decodeSampledMutableBitmap(path, maxSide))
}

private fun applyExifOrientation(path: String, bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        ExifInterface(path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_NORMAL -> return bitmap
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.postScale(-1f, 1f)
        }
        ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.postScale(-1f, 1f)
        }
        else -> return bitmap
    }

    val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (transformed !== bitmap) bitmap.recycle()
    val mutable = transformed.copyOrThrow(Bitmap.Config.ARGB_8888, true)
    if (mutable !== transformed) transformed.recycle()
    Log.i(FLARE_GUARD_AI_TAG, "Applied EXIF orientation=$orientation -> ${mutable.width}x${mutable.height}")
    return mutable
}

private fun rotateBitmap90(bitmap: Bitmap): Bitmap {
    val matrix = Matrix().apply { postRotate(90f) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun CropAspectRatio.rotatedForQuarterTurn(): CropAspectRatio = when (this) {
    CropAspectRatio.FourThree -> CropAspectRatio.ThreeFour
    CropAspectRatio.ThreeFour -> CropAspectRatio.FourThree
    CropAspectRatio.SixteenNine -> CropAspectRatio.NineSixteen
    CropAspectRatio.NineSixteen -> CropAspectRatio.SixteenNine
    else -> this
}

internal fun renderEditedPreview(
    basePreview: Bitmap,
    params: EditParams,
    engines: EngineSelection,
    revision: Int,
    look: PresetColorLook? = null,
    quickEffects: List<ActiveQuickEffect> = emptyList()
): Bitmap {
    val copy = basePreview.copyOrThrow(Bitmap.Config.ARGB_8888, true)
    return try {
        renderBitmapInNative(copy, params, engines, revision, look)
        applyActiveQuickEffectsToBitmap(copy, quickEffects, revision)
        applySelectedToneEngine(copy, engines.toneEngine)
        copy
    } catch (t: Throwable) {
        copy.recycle()
        throw t
    }
}

private data class NativeSpecialEffectOp(
    val effect: Int,
    val strength: Float
)

internal fun applyActiveQuickEffectsToBitmap(
    bitmap: Bitmap,
    quickEffects: List<ActiveQuickEffect>,
    revision: Int
) {
    quickEffects.forEach { effect ->
        effect.toNativeOperations().forEach { operation ->
            val result = NativePhotoCore.nativeApplySpecialEffectInPlace(
                bitmap = bitmap,
                effect = operation.effect,
                strength = operation.strength.coerceIn(0f, 1f),
                revision = revision
            )
            if (result < 0) {
                throw IllegalStateException("native special effect failed: effect=${operation.effect} code=$result")
            }
        }
    }
}

private fun renderEditedExport(
    sourcePath: String,
    params: EditParams,
    resolution: ExportResolution,
    engines: EngineSelection,
    revision: Int,
    look: PresetColorLook? = null,
    quickEffects: List<ActiveQuickEffect> = emptyList()
): Bitmap {
    // TODO v0.2: replace whole-bitmap export with ROI/tile rendering to reduce peak memory use.
    var decoded: Bitmap? = null
    var scaled: Bitmap? = null
    try {
        decoded = decodeSampledMutableBitmapWithExif(sourcePath, maxSide = EXPORT_MAX_SIDE)
        val working = decoded!!
        renderBitmapInNative(working, params, engines, revision, look)
        applyActiveQuickEffectsToBitmap(working, quickEffects, revision)
        applySelectedToneEngine(working, engines.toneEngine)
        scaled = scaleBitmapForExport(working, resolution)
        if (scaled !== working) {
            working.recycle()
        }
        val result = scaled
        decoded = null
        scaled = null
        return result
    } catch (t: Throwable) {
        if (scaled != null && scaled !== decoded && !scaled.isRecycled) scaled.recycle()
        if (decoded != null && !decoded.isRecycled) decoded.recycle()
        throw t
    }
}

private fun renderEditedExportFromBitmap(
    baseBitmap: Bitmap,
    params: EditParams,
    resolution: ExportResolution,
    engines: EngineSelection,
    revision: Int,
    look: PresetColorLook? = null,
    quickEffects: List<ActiveQuickEffect> = emptyList()
): Bitmap {
    var decoded: Bitmap? = null
    var scaled: Bitmap? = null
    try {
        decoded = baseBitmap.copyOrThrow(Bitmap.Config.ARGB_8888, true)
        val working = decoded!!
        renderBitmapInNative(working, params, engines, revision, look)
        applyActiveQuickEffectsToBitmap(working, quickEffects, revision)
        applySelectedToneEngine(working, engines.toneEngine)
        scaled = scaleBitmapForExport(working, resolution)
        if (scaled !== working) {
            working.recycle()
        }
        val result = scaled
        decoded = null
        scaled = null
        return result
    } catch (t: Throwable) {
        if (scaled != null && scaled !== decoded && !scaled.isRecycled) scaled.recycle()
        if (decoded != null && !decoded.isRecycled) decoded.recycle()
        throw t
    }
}

private fun renderBitmapInNative(
    bitmap: Bitmap,
    params: EditParams,
    engines: EngineSelection,
    revision: Int,
    look: PresetColorLook? = null
): Int {
    val result = NativePhotoCore.nativeRenderPreviewInPlace(
        bitmap,
        params.exposure,
        params.contrast,
        params.shadows,
        params.highlights,
        params.whites,
        params.blacks,
        params.temperature,
        params.tint,
        params.saturation,
        params.vibrance,
        params.clarity,
        params.dehaze,
        params.sharpness,
        params.noiseReduction,
        params.luminanceNoiseReduction,
        params.colorNoiseReduction,
        params.noiseDetailProtection,
        engines.noiseEngine.nativeId,
        engines.detailEngine.nativeId,
        engines.toneEngine.nativeId,
        engines.hazeEngine.nativeId,
        revision,
        look
    )
    if (result < 0) {
        throw IllegalStateException("native render failed: code=$result")
    }
    return result
}

private fun applySelectedToneEngine(bitmap: Bitmap, toneEngine: ToneEngine) {
    if (toneEngine == ToneEngine.Clahe) {
        applyClaheToneInPlace(bitmap, strength = 0.34f)
    }
}

private fun applyClaheToneInPlace(bitmap: Bitmap, strength: Float) {
    val width = bitmap.width
    val height = bitmap.height
    val safeStrength = strength.coerceIn(0f, 1f)
    if (safeStrength <= 0.001f || width < 16 || height < 16) return

    val tilesX = kotlin.math.min(12, max(2, (width + 255) / 256))
    val tilesY = kotlin.math.min(12, max(2, (height + 255) / 256))
    val tileW = (width + tilesX - 1) / tilesX
    val tileH = (height + tilesY - 1) / tilesY
    val luts = Array(tilesX * tilesY) { IntArray(256) }

    for (ty in 0 until tilesY) {
        val y0 = ty * tileH
        val y1 = kotlin.math.min(height, y0 + tileH)
        for (tx in 0 until tilesX) {
            val x0 = tx * tileW
            val x1 = kotlin.math.min(width, x0 + tileW)
            val tileWidth = x1 - x0
            val pixelCount = max(1, tileWidth * (y1 - y0))
            val histogram = IntArray(256)
            val row = IntArray(tileWidth)

            for (y in y0 until y1) {
                bitmap.getPixels(row, 0, tileWidth, x0, y, tileWidth, 1)
                for (pixel in row) histogram[lumaBin(pixel)] += 1
            }

            val limit = max(2, ((pixelCount / 256f) * 2.25f).roundToInt())
            var overflow = 0
            for (i in histogram.indices) {
                if (histogram[i] > limit) {
                    overflow += histogram[i] - limit
                    histogram[i] = limit
                }
            }
            val bonus = overflow / 256
            val remainder = overflow % 256
            for (i in histogram.indices) histogram[i] += bonus + if (i < remainder) 1 else 0

            var cdf = 0
            var cdfMin = 0
            var found = false
            for (i in histogram.indices) {
                cdf += histogram[i]
                if (!found && cdf > 0) {
                    cdfMin = cdf
                    found = true
                }
            }

            cdf = 0
            val denom = max(1, pixelCount - cdfMin).toFloat()
            val lut = luts[ty * tilesX + tx]
            for (i in histogram.indices) {
                cdf += histogram[i]
                val equalized = ((cdf - cdfMin) / denom).coerceIn(0f, 1f)
                lut[i] = (equalized * 255f).roundToInt().coerceIn(0, 255)
            }
        }
    }

    val rowPixels = IntArray(width)
    for (y in 0 until height) {
        bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1)
        val gy = y.toFloat() / max(1, tileH) - 0.5f
        val gyFloor = floor(gy)
        val ty0 = gyFloor.toInt()
        val ty1 = ty0 + 1
        val fy = (gy - gyFloor).coerceIn(0f, 1f)

        for (x in 0 until width) {
            val pixel = rowPixels[x]
            val luma = lumaFloat(pixel)
            val bin = (luma * 255f).roundToInt().coerceIn(0, 255)
            val gx = x.toFloat() / max(1, tileW) - 0.5f
            val gxFloor = floor(gx)
            val tx0 = gxFloor.toInt()
            val tx1 = tx0 + 1
            val fx = (gx - gxFloor).coerceIn(0f, 1f)

            val m00 = lutValue(luts, tilesX, tilesY, tx0, ty0, bin)
            val m10 = lutValue(luts, tilesX, tilesY, tx1, ty0, bin)
            val m01 = lutValue(luts, tilesX, tilesY, tx0, ty1, bin)
            val m11 = lutValue(luts, tilesX, tilesY, tx1, ty1, bin)
            val mappedTop = lerpFloat(m00, m10, fx)
            val mappedBottom = lerpFloat(m01, m11, fx)
            val mapped = lerpFloat(mappedTop, mappedBottom, fy)

            val highlightGuard = 1f - 0.65f * smoothstepFloat(0.86f, 1f, luma)
            val shadowGuard = 0.45f + 0.55f * smoothstepFloat(0.025f, 0.18f, luma)
            val localStrength = safeStrength * highlightGuard * shadowGuard
            val newLuma = lerpFloat(luma, mapped, localStrength)
            val scale = newLuma / max(0.015f, luma)
            rowPixels[x] = scalePixelLuma(pixel, scale)
        }
        bitmap.setPixels(rowPixels, 0, width, 0, y, width, 1)
    }
}

private fun lumaBin(pixel: Int): Int = (lumaFloat(pixel) * 255f).roundToInt().coerceIn(0, 255)

private fun lumaFloat(pixel: Int): Float {
    val r = ((pixel shr 16) and 0xff) / 255f
    val g = ((pixel shr 8) and 0xff) / 255f
    val b = (pixel and 0xff) / 255f
    return (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
}

private fun lutValue(luts: Array<IntArray>, tilesX: Int, tilesY: Int, tx: Int, ty: Int, bin: Int): Float {
    val safeTx = tx.coerceIn(0, tilesX - 1)
    val safeTy = ty.coerceIn(0, tilesY - 1)
    val safeBin = bin.coerceIn(0, 255)
    return luts[safeTy * tilesX + safeTx][safeBin] / 255f
}

private fun scalePixelLuma(pixel: Int, scale: Float): Int {
    val alpha = pixel and -0x1000000
    val r = ((((pixel shr 16) and 0xff) / 255f) * scale * 255f).roundToInt().coerceIn(0, 255)
    val g = ((((pixel shr 8) and 0xff) / 255f) * scale * 255f).roundToInt().coerceIn(0, 255)
    val b = (((pixel and 0xff) / 255f) * scale * 255f).roundToInt().coerceIn(0, 255)
    return alpha or (r shl 16) or (g shl 8) or b
}

private fun smoothstepFloat(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpFloat(a: Float, b: Float, t: Float): Float = a + (b - a) * t

private fun scaleBitmapForExport(bitmap: Bitmap, resolution: ExportResolution): Bitmap {
    if (resolution.scalePercent >= 100) return bitmap
    val scale = resolution.scalePercent / 100f
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

private fun insertExportPendingRow(
    context: Context,
    bitmap: Bitmap,
    fileName: String,
    format: ExportFormat
): Uri {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/KeplerStudio")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ?: error("저장 위치를 만들 수 없습니다")
    try {
        if (format == ExportFormat.Heif) {
            writeHeifToUri(context, uri, bitmap)
        } else {
            writeCompressedBitmapToUri(context, uri, bitmap, format)
        }
        return uri
    } catch (t: Throwable) {
        runCatching { resolver.delete(uri, null, null) }
        throw t
    }
}

private fun publishExportRow(context: Context, uri: Uri) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.IS_PENDING, 0)
    }
    val resolver = context.contentResolver
    val updated = resolver.update(uri, values, null, null)
    require(updated > 0) { "failed to publish media store row" }
}

private fun deletePendingExportRow(context: Context, uri: Uri) {
    runCatching { context.contentResolver.delete(uri, null, null) }
}

private fun writeCompressedBitmapToUri(
    context: Context,
    uri: Uri,
    bitmap: Bitmap,
    format: ExportFormat
) {
    val compressFormat = when (format) {
        ExportFormat.Jpeg -> Bitmap.CompressFormat.JPEG
        ExportFormat.Png -> Bitmap.CompressFormat.PNG
        ExportFormat.Webp -> Bitmap.CompressFormat.WEBP
        ExportFormat.Heif -> error("HEIF는 별도 인코더를 사용합니다")
    }
    val quality = if (format == ExportFormat.Png) 100 else 95
    context.contentResolver.openOutputStream(uri)?.use { output ->
        check(bitmap.compress(compressFormat, quality, output)) { "이미지 압축에 실패했습니다" }
    } ?: error("저장 스트림을 열 수 없습니다")
}

private fun writeHeifToUri(context: Context, uri: Uri, bitmap: Bitmap) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        error("HEIF 저장은 Android 9 이상에서 지원됩니다")
    }

    val descriptor = context.contentResolver.openFileDescriptor(uri, "w")
        ?: error("HEIF 저장 스트림을 열 수 없습니다")

    descriptor.use { pfd ->
        val writer = HeifWriter.Builder(
            pfd.fileDescriptor,
            bitmap.width,
            bitmap.height,
            HeifWriter.INPUT_MODE_BITMAP
        )
            .setMaxImages(1)
            .setQuality(95)
            .build()

        try {
            writer.start()
            writer.addBitmap(bitmap)
            writer.stop(0)
        } finally {
            writer.close()
        }
    }
}

private data class DraftSaveResult(
    val generationId: String,
    val generationDirectory: File,
    val sourcePath: String,
    val thumbnailPath: String,
    val savedAtMillis: Long,
    val baseContentToken: String,
    val capturedRevision: Int,
    val epoch: Long = Long.MIN_VALUE,
    val expectedPointerGenerationId: String? = null,
    val previousVisibleGenerationId: String? = null,
    val previousGenerationDirectory: File? = null,
    val pointerPublished: Boolean,
    val compatibilitySourceFile: File? = null,
    val compatibilitySourceChanged: Boolean = false,
    val previousDraftPath: String? = null,
    val originalSourcePath: String? = null
)

private class DraftPreferencesSnapshot(private val values: Map<String, *>) {
    fun getString(key: String, default: String?): String? = values[key] as? String ?: default
    fun getFloat(key: String, default: Float): Float = (values[key] as? Number)?.toFloat() ?: default
}

private data class DraftRestoreSnapshot(
    val preferences: DraftPreferencesSnapshot,
    val savedAtMillis: Long?,
    val recovery: DraftRecoveryResolution
)

private data class SavedExportHistoryResult(
    val revision: Long,
    val items: List<SavedExport>,
    val removedUris: Set<String> = emptySet(),
    val retention: ExportHistoryRetention? = null
)

private data class DraftPointerSnapshot(val generationId: String?)

private sealed class GenerationRestoreOutcome {
    data object Restored : GenerationRestoreOutcome()
    data object Absent : GenerationRestoreOutcome()
    data object Stale : GenerationRestoreOutcome()
    data class Invalid(val generationId: String) : GenerationRestoreOutcome()
}

public sealed class PresetApplyResult {
    data object Accepted : PresetApplyResult()
    data object AlreadyApplied : PresetApplyResult()
    data object Rejected : PresetApplyResult()
}

private data class DraftSavePayload(
    val epoch: Long,
    val sourcePath: String?,
    val previousVisibleDraftPath: String?,
    val baseContentToken: String,
    val baseBitmapDirty: Boolean,
    val dirtyBitmapCopy: Bitmap?,
    val editedPreviewCopy: Bitmap?,
    val capturedRevision: Int,
    val expectedPointerGenerationId: String?,
    val previousVisibleGenerationId: String?,
    val params: EditParams,
    val exportFormat: ExportFormat,
    val exportResolution: ExportResolution,
    val presetLook: PresetColorLook?,
    val activeQuickEffects: List<ActiveQuickEffect>,
    val cropState: CropState = CropState(),
    val selectionLayers: List<SelectionLayer> = emptyList(),
    val activeSelectionLayerId: String? = null,
    val selectionPaintSettings: SelectionPaintSettings = SelectionPaintSettings(),
    val showSelectionOverlay: Boolean = true,
    val noiseEngine: NoiseEngine = NoiseEngine.FastEdgeAware,
    val detailEngine: DetailEngine = DetailEngine.MaskedUnsharp,
    val toneEngine: ToneEngine = ToneEngine.HistogramAuto,
    val hazeEngine: DehazeEngine = DehazeEngine.FastContrast,
val originalSourcePath: String? = null
)

private data class DraftSourceResult(
    val file: File,
    val changed: Boolean
)

private enum class QuickEffectGroup {
    Remove,
    Optics,
    Blur
}

private fun createDraftSavePayload(
    context: Context,
    state: EditorUiState,
    epoch: Long,
    expectedPointerGenerationId: String?
): DraftSavePayload {
    val reusableSource = isReusableCommittedDraftSource(context, state)
    val owned = identityBitmapSet()
    val copiedBySource = IdentityHashMap<Bitmap, Bitmap>()
    fun copyOwned(bitmap: Bitmap?): Bitmap? {
        if (bitmap == null) return null
        return copiedBySource.getOrPut(bitmap) {
            bitmap.copyOrThrow(Bitmap.Config.ARGB_8888, true).also(owned::add)
        }
    }
    try {
        val dirtyBitmapCopy = when {
            reusableSource -> null
            !state.baseBitmapDirty && state.sourcePath != null -> null
            else -> copyOwned(state.originalPreviewBitmap ?: state.previewBitmap)
        }
        if (state.baseBitmapDirty && dirtyBitmapCopy == null) error("draft save bitmap is missing")
        val editedPreviewCopy = copyOwned(state.previewBitmap) ?: error("draft preview is missing")
        val copiedLayers = state.selectionLayers.map { layer ->
            val copy = copyOwned(layer.bitmap) ?: error("draft mask is missing")
            layer.copy(bitmap = copy)
        }
        return DraftSavePayload(
        epoch = epoch,
        sourcePath = state.sourcePath,
        previousVisibleDraftPath = state.draftSourcePath,
        baseContentToken = state.baseContentToken,
        baseBitmapDirty = state.baseBitmapDirty,
        dirtyBitmapCopy = dirtyBitmapCopy,
        editedPreviewCopy = editedPreviewCopy,
        capturedRevision = state.revision,
        expectedPointerGenerationId = expectedPointerGenerationId,
        previousVisibleGenerationId = state.draftGenerationId,
        params = state.params,
        exportFormat = state.exportFormat,
        exportResolution = state.exportResolution,
        presetLook = state.presetLook,
        activeQuickEffects = state.activeQuickEffects,
        cropState = state.cropState,
        selectionLayers = copiedLayers,
        activeSelectionLayerId = state.activeSelectionLayerId,
        selectionPaintSettings = state.selectionPaintSettings,
        showSelectionOverlay = state.showSelectionOverlay,
        noiseEngine = state.noiseEngine,
        detailEngine = state.detailEngine,
        toneEngine = state.toneEngine,
        hazeEngine = state.hazeEngine,
        originalSourcePath = state.sourcePath
        )
    } catch (t: Throwable) {
        owned.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
        throw t
    }
}

private fun copyGenerationSourceToWorkingFile(context: Context, source: File): File {
    val directory = File(context.filesDir, "editor_sources").apply { mkdirs() }.canonicalFile
    val destination = File(directory, "restored_${UUID.randomUUID()}.img").canonicalFile
    check(destination.parentFile == directory)
    try {
        source.inputStream().use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        check(destination.length() > 0L) { "draft working source copy failed" }
        return destination
    } catch (t: Throwable) {
        destination.delete()
        throw t
    }
}

private fun deleteOwnedWorkingSource(context: Context, sourcePath: String?) {
    if (sourcePath == null) return
    val directory = runCatching { File(context.filesDir, "editor_sources").canonicalFile }.getOrNull() ?: return
    val source = runCatching { File(sourcePath).canonicalFile }.getOrNull() ?: return
    if (source.parentFile == directory && source.name.startsWith("restored_") && source.extension == "img") {
        source.delete()
    }
}

private fun DraftSavePayload.recycleOwnedBitmaps() {
    val owned = identityBitmapSet()
    dirtyBitmapCopy?.let(owned::add)
    editedPreviewCopy?.let(owned::add)
    selectionLayers.forEach { owned.add(it.bitmap) }
    owned.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
}

private fun draftSourceIdentity(path: String?): String? {
    val file = path?.let(::File) ?: return null
    return runCatching {
        val canonical = file.canonicalFile
        "${canonical.path.hashCode().toUInt().toString(16)}:${canonical.length()}:${canonical.lastModified()}"
    }.getOrNull()
}

private fun saveDraftSnapshot(
    context: Context,
    payload: DraftSavePayload,
    isCurrent: () -> Boolean
): DraftSaveResult? {
    val draftSource = when {
        payload.previousVisibleDraftPath?.let(::File)?.isFile == true &&
            sameCanonicalPath(
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_DRAFT_SOURCE, null),
                payload.previousVisibleDraftPath
            ) && context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_DRAFT_BASE_TOKEN, null) == payload.baseContentToken &&
            isSupportedDraftSource(context, File(payload.previousVisibleDraftPath)) ->
            DraftSourceResult(File(payload.previousVisibleDraftPath), changed = false)
        !payload.baseBitmapDirty && payload.sourcePath != null ->
            persistDraftSourceFileIfNeeded(context, payload.sourcePath)
        payload.dirtyBitmapCopy != null -> persistDraftBitmapFile(context, payload.dirtyBitmapCopy)?.let { DraftSourceResult(it, changed = true) }
        else -> null
    } ?: return null
    if (!isCurrent()) {
        if (draftSource.changed) draftSource.file.delete()
        return null
    }
    val savedAt = System.currentTimeMillis()
    val generationResult = persistDraftGenerationInternal(
            context = context,
            payload = payload,
            draftSourceFile = draftSource.file,
            savedAt = savedAt,
            dirtyBitmapCopy = payload.dirtyBitmapCopy,
            isCurrent = isCurrent
        )
    if (generationResult == null) {
        if (draftSource.changed && isOwnedDraftSource(context, draftSource.file)) draftSource.file.delete()
        return null
    }
    return generationResult.copy(
        compatibilitySourceFile = draftSource.file,
        compatibilitySourceChanged = draftSource.changed
    )
}

private fun persistLegacyDraftCompatibility(
    context: Context,
    payload: DraftSavePayload,
    saved: DraftSaveResult
) {
    val draftSource = saved.compatibilitySourceFile ?: return
    val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val commitSucceeded = try {
        preferences.edit()
        .putString(KEY_DRAFT_SOURCE, draftSource.absolutePath)
        .putFloat(KEY_DRAFT_EXPOSURE, payload.params.exposure)
        .putFloat(KEY_DRAFT_CONTRAST, payload.params.contrast)
        .putFloat(KEY_DRAFT_SHADOWS, payload.params.shadows)
        .putFloat(KEY_DRAFT_HIGHLIGHTS, payload.params.highlights)
        .putFloat(KEY_DRAFT_WHITES, payload.params.whites)
        .putFloat(KEY_DRAFT_BLACKS, payload.params.blacks)
        .putFloat(KEY_DRAFT_TEMPERATURE, payload.params.temperature)
        .putFloat(KEY_DRAFT_TINT, payload.params.tint)
        .putFloat(KEY_DRAFT_SATURATION, payload.params.saturation)
        .putFloat(KEY_DRAFT_VIBRANCE, payload.params.vibrance)
        .putFloat(KEY_DRAFT_CLARITY, payload.params.clarity)
        .putFloat(KEY_DRAFT_DEHAZE, payload.params.dehaze)
        .putFloat(KEY_DRAFT_SHARPNESS, payload.params.sharpness)
        .putFloat(KEY_DRAFT_NOISE_REDUCTION, payload.params.noiseReduction)
        .putFloat(KEY_DRAFT_LUMINANCE_NOISE_REDUCTION, payload.params.luminanceNoiseReduction)
        .putFloat(KEY_DRAFT_COLOR_NOISE_REDUCTION, payload.params.colorNoiseReduction)
        .putFloat(KEY_DRAFT_NOISE_DETAIL_PROTECTION, payload.params.noiseDetailProtection)
        .putString(KEY_DRAFT_FORMAT, payload.exportFormat.name)
        .putString(KEY_DRAFT_RESOLUTION, payload.exportResolution.name)
        .putString(KEY_DRAFT_LOOK, presetColorLookToJson(payload.presetLook)?.toString())
        .putString(KEY_DRAFT_QUICK_EFFECTS, payload.activeQuickEffects.toDraftString())
        .putString(KEY_DRAFT_BASE_TOKEN, payload.baseContentToken)
        .putLong(KEY_DRAFT_SAVED_AT, saved.savedAtMillis)
        .commit()
    } catch (t: Throwable) {
        logDraftSaveFailure(t)
        false
    }
    if (!commitSucceeded) {
        logDraftSaveFailure(IllegalStateException("failed to commit legacy draft preferences"))
    }
    if (commitSucceeded && saved.compatibilitySourceChanged) runCatching { saveDraftThumbnailFile(context, draftSource) }
    if (commitSucceeded && isOwnedDraftSource(context, draftSource)) {
        deleteObsoleteDraftSources(context, draftSource, payload.sourcePath)
    }
}

private fun persistDraftGenerationInternal(
    context: Context,
    payload: DraftSavePayload,
    draftSourceFile: File,
    savedAt: Long,
    dirtyBitmapCopy: Bitmap?,
    isCurrent: () -> Boolean
): DraftSaveResult? {
    val genId = UUID.randomUUID().toString()
    var genDir = newDraftGenerationDirectory(context)
    var pendingResult: DraftSaveResult? = null
    var pointerCommitted = false
    try {
        if (!isCurrent()) return null
        val maskEntries = ArrayList<Pair<SelectionLayer, DraftSelectionLayerEntry>>(payload.selectionLayers.size)
        payload.selectionLayers.forEachIndexed { index, layer ->
            val fileName = "mask_${index}.png"
            val entry = DraftSelectionLayerEntry(
                id = layer.id,
                name = layer.name,
                kind = layer.kind.name,
                enabled = layer.enabled,
                inverted = layer.inverted,
                opacity = layer.opacity,
                localParams = layer.localParams,
                maskFileName = fileName,
                maskWidth = layer.bitmap.width,
                maskHeight = layer.bitmap.height,
                sourceIdentity = payload.baseContentToken
            )
            maskEntries += layer to entry
        }
        val sourceIdentity = payload.baseContentToken
        val sourceBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(draftSourceFile.absolutePath, sourceBounds)
        val thumbnailDimensions = payload.editedPreviewCopy?.let { draftThumbnailDimensions(it.width, it.height) } ?: (0 to 0)
        val manifest = DraftGenerationManifest(
            formatVersion = DRAFT_FORMAT_VERSION,
            generationId = genId,
            savedAtMillis = savedAt,
            draftOperationEpoch = payload.epoch,
            editorRevision = payload.capturedRevision,
            originalSourceIdentity = draftSourceIdentity(payload.originalSourcePath),
            sourceIdentity = sourceIdentity,
            baseContentToken = payload.baseContentToken,
            baseBitmapDirty = payload.baseBitmapDirty,
            sourceFileName = "source.img",
            sourceWidth = sourceBounds.outWidth,
            sourceHeight = sourceBounds.outHeight,
            thumbnailFileName = "thumbnail.jpg",
            thumbnailWidth = thumbnailDimensions.first,
            thumbnailHeight = thumbnailDimensions.second,
            params = payload.params,
            noiseEngine = payload.noiseEngine.name,
            detailEngine = payload.detailEngine.name,
            toneEngine = payload.toneEngine.name,
            hazeEngine = payload.hazeEngine.name,
            presetLook = payload.presetLook,
            activeQuickEffects = payload.activeQuickEffects,
            exportFormat = payload.exportFormat.name,
            exportResolution = payload.exportResolution.name,
            cropState = payload.cropState,
            selectionLayers = maskEntries.map { it.second },
            activeSelectionLayerId = payload.activeSelectionLayerId,
            selectionPaintSettings = payload.selectionPaintSettings,
            showSelectionOverlay = payload.showSelectionOverlay
        )
        if (!writeDraftGeneration(
                context = context,
                genDir = genDir,
                manifest = manifest,
                baseBitmapDirty = payload.baseBitmapDirty,
                reusableSourceFile = draftSourceFile.takeIf { !payload.baseBitmapDirty },
                dirtyBitmapCopy = dirtyBitmapCopy ?: payload.dirtyBitmapCopy,
                editedPreviewCopy = checkNotNull(payload.editedPreviewCopy),
                maskEntries = maskEntries,
                isCurrent = isCurrent
            )) {
            deleteDraftDirectory(context, genDir)
            return null
        }
        val completedDir = finalizeDraftGeneration(context, genDir, genId)
        if (completedDir == null) {
            deleteDraftDirectory(context, genDir)
            return null
        }
        genDir = completedDir
        val validated = validateDraftGeneration(genDir, genId)
        if (validated == null) {
            deleteDraftDirectory(context, genDir)
            return null
        }
        if (!isCurrent()) {
            deleteDraftDirectory(context, genDir)
            return null
        }
        if (currentDraftGenerationId(context) != payload.expectedPointerGenerationId) {
            deleteDraftDirectory(context, genDir)
            return null
        }
        val previousDirectory = payload.expectedPointerGenerationId?.let { findDraftGenerationDirectory(context, it)?.root }
        pendingResult = DraftSaveResult(
            generationId = genDir.root.name,
            generationDirectory = genDir.root,
            sourcePath = validated.sourceFile.absolutePath,
            thumbnailPath = validated.thumbnailFile.absolutePath,
            savedAtMillis = savedAt,
            baseContentToken = payload.baseContentToken,
            capturedRevision = payload.capturedRevision,
            epoch = payload.epoch,
            expectedPointerGenerationId = payload.expectedPointerGenerationId,
            previousVisibleGenerationId = payload.previousVisibleGenerationId,
            previousGenerationDirectory = previousDirectory,
            pointerPublished = false,
            previousDraftPath = payload.previousVisibleDraftPath,
            originalSourcePath = payload.sourcePath
        )
        if (!publishDraftGeneration(context, genDir.root.name)) {
            deleteDraftDirectory(context, genDir)
            return null
        }
        pointerCommitted = true
        return checkNotNull(pendingResult).copy(pointerPublished = true)
    } catch (t: Throwable) {
        if (pointerCommitted && pendingResult != null) {
            rollbackCommittedDraft(context, checkNotNull(pendingResult).copy(pointerPublished = true))
        } else {
            deleteDraftDirectory(context, genDir)
        }
        Log.w(FLARE_GUARD_AI_TAG, "Draft generation save failed", t)
        return null
    }
}

private fun rollbackCommittedDraft(context: Context, saved: DraftSaveResult) {
    if (!saved.pointerPublished) {
        deleteDraftDirectory(context, DraftGenerationDirectory(saved.generationDirectory))
        return
    }
    val pointer = currentDraftGenerationId(context)
    if (pointer == saved.generationId) {
        val previousIsComplete = runCatching {
            saved.expectedPointerGenerationId != null &&
                saved.previousGenerationDirectory?.let { directory ->
                    findDraftGenerationDirectory(context, saved.expectedPointerGenerationId)?.root?.canonicalFile == directory.canonicalFile
                } == true
        }.getOrDefault(false)
        val restoredPrevious = previousIsComplete &&
            publishDraftGeneration(context, checkNotNull(saved.expectedPointerGenerationId))
        val rolledBack = restoredPrevious || clearCurrentDraftGenerationPointer(context)
        if (!rolledBack || currentDraftGenerationId(context) == saved.generationId) return
    }
    if (currentDraftGenerationId(context) != saved.generationId) {
        deleteDraftDirectory(context, DraftGenerationDirectory(saved.generationDirectory))
        saved.compatibilitySourceFile?.takeIf {
            saved.compatibilitySourceChanged && isOwnedDraftSource(context, it)
        }?.delete()
    }
}

private fun snapshotDraftPreferences(preferences: android.content.SharedPreferences): Map<String, Any?> =
    preferences.all.filterKeys { key -> key.startsWith("draft_") }

private fun restoreDraftPreferences(
    preferences: android.content.SharedPreferences,
    snapshot: Map<String, Any?>
): Boolean {
    val editor = preferences.edit()
    preferences.all.keys.filter { it.startsWith("draft_") }.forEach { editor.remove(it) }
    snapshot.forEach { (key, value) ->
        when (value) {
            null -> Unit
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
return editor.commit()
}

private fun restoreDraftPreferencesOrThrow(
    preferences: android.content.SharedPreferences,
    snapshot: Map<String, Any?>,
    original: Throwable
): Boolean {
    try {
        check(restoreDraftPreferences(preferences, snapshot)) { "failed to restore draft preferences" }
        return true
    } catch (rollbackFailure: Throwable) {
        original.addSuppressed(rollbackFailure)
        return false
    }
}

private fun List<ActiveQuickEffect>.toggle(effect: ActiveQuickEffect): List<ActiveQuickEffect> {
    val group = effect.kind.group()
    val current = firstOrNull { it.kind.group() == group }
    return if (current != null && current.matches(effect)) {
        filterNot { it.kind.group() == group }
    } else {
        filterNot { it.kind.group() == group } + effect
    }
}

private fun ActiveQuickEffect.matches(other: ActiveQuickEffect): Boolean =
    kind == other.kind && strength == other.strength

private fun QuickEffectKind.group(): QuickEffectGroup = when (this) {
    QuickEffectKind.SpotCleanup -> QuickEffectGroup.Remove
    QuickEffectKind.ChromaticAberrationReduction,
    QuickEffectKind.VignetteCorrection,
    QuickEffectKind.OpticsCorrection -> QuickEffectGroup.Optics
    QuickEffectKind.SoftBlur -> QuickEffectGroup.Blur
}

private fun ActiveQuickEffect.toNativeOperations(): List<NativeSpecialEffectOp> = when (kind) {
    QuickEffectKind.SpotCleanup -> listOf(NativeSpecialEffectOp(effect = 0, strength = 0.58f))
    QuickEffectKind.ChromaticAberrationReduction -> listOf(NativeSpecialEffectOp(effect = 1, strength = 0.62f))
    QuickEffectKind.VignetteCorrection -> listOf(NativeSpecialEffectOp(effect = 2, strength = 0.45f))
    QuickEffectKind.OpticsCorrection -> listOf(
        NativeSpecialEffectOp(effect = 1, strength = 0.62f),
        NativeSpecialEffectOp(effect = 2, strength = 0.45f)
    )
    QuickEffectKind.SoftBlur -> listOf(
        NativeSpecialEffectOp(effect = 3, strength = when (strength) {
            QuickEffectStrength.Weak -> 0.22f
            QuickEffectStrength.Medium -> 0.38f
            QuickEffectStrength.Strong -> 0.58f
        })
    )
}

private fun Float.toQuickEffectStrength(): QuickEffectStrength = when {
    this < 0.30f -> QuickEffectStrength.Weak
    this < 0.48f -> QuickEffectStrength.Medium
    else -> QuickEffectStrength.Strong
}

private fun List<ActiveQuickEffect>.toDraftString(): String =
    joinToString("|") { "${it.kind.name}:${it.strength.name}" }

private fun String?.parseQuickEffects(): List<ActiveQuickEffect> =
    this
        ?.split('|')
        ?.mapNotNull { token ->
            val parts = token.split(':')
            if (parts.size != 2) return@mapNotNull null
            val kind = runCatching { enumValueOf<QuickEffectKind>(parts[0]) }.getOrNull() ?: return@mapNotNull null
            val strength = runCatching { enumValueOf<QuickEffectStrength>(parts[1]) }.getOrDefault(QuickEffectStrength.Medium)
            ActiveQuickEffect(kind = kind, strength = strength)
        }
        .orEmpty()

private fun cleanupTemporarySourceFiles(context: Context, activeSourcePath: String?): Int {
    val now = System.currentTimeMillis()
    val maxAgeMs = 7L * 24L * 60L * 60L * 1000L
    val activePath = activeSourcePath?.let { File(it).absolutePath }
    val files = context.cacheDir.listFiles { file ->
        file.isFile && file.name.startsWith("source_") && file.name.endsWith(".img")
    }.orEmpty()

    var removed = 0
    files.forEach { file ->
        val expired = now - file.lastModified() > maxAgeMs
        val isActive = activePath != null && file.absolutePath == activePath
        if (expired && !isActive && file.delete()) removed += 1
    }
    return removed
}

private fun rememberSavedExport(
    context: Context,
    item: SavedExport,
    retention: ExportHistoryRetention
): List<SavedExport> {
    val next = (listOf(item) + loadSavedExportsFromPrefs(context).filter { it.uriString != item.uriString }).take(60)
    return pruneSavedExportsIfNeeded(context, next, retention)
}

private fun pruneSavedExportsIfNeeded(
    context: Context,
    items: List<SavedExport>,
    retention: ExportHistoryRetention
): List<SavedExport> {
    val days = retention.days
    val pruned = if (days == null) {
        items
    } else {
        val cutoff = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
        items.filter { it.timestampMillis >= cutoff }
    }
    saveSavedExportsToPrefs(context, pruned)
    return pruned
}

private fun saveSavedExportsToPrefs(context: Context, items: List<SavedExport>) {
    check(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .putString(KEY_SAVED_EXPORTS, items.joinToString("\n") { encodeSavedExport(it) })
        .putBoolean(KEY_SAVED_EXPORTS_INITIALIZED, true)
        .commit()) { "failed to persist saved export history" }
}

private fun clearSavedExportsPrefs(context: Context) {
    check(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .putString(KEY_SAVED_EXPORTS, "")
        .putBoolean(KEY_SAVED_EXPORTS_INITIALIZED, true)
        .commit()) { "failed to clear saved export history" }
}

private fun loadOrRebuildSavedExportHistory(context: Context, retention: ExportHistoryRetention): List<SavedExport> {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val initialized = prefs.getBoolean(KEY_SAVED_EXPORTS_INITIALIZED, false) || prefs.contains(KEY_SAVED_EXPORTS)
    val seed = if (initialized) loadSavedExportsFromPrefs(context) else rebuildSavedExportsFromMediaStore(context)
    return pruneSavedExportsIfNeeded(context, seed, retention)
}

private fun loadSavedExportsFromPrefs(context: Context): List<SavedExport> {
    val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_SAVED_EXPORTS, null)
        ?: return emptyList()
    return raw.lines().mapNotNull { decodeSavedExport(it) }
}

private fun rebuildSavedExportsFromMediaStore(context: Context): List<SavedExport> {
    val projection = buildList {
        add(MediaStore.Images.Media._ID)
        add(MediaStore.Images.Media.DISPLAY_NAME)
        add(MediaStore.Images.Media.MIME_TYPE)
        add(MediaStore.Images.Media.WIDTH)
        add(MediaStore.Images.Media.HEIGHT)
        add(MediaStore.Images.Media.DATE_ADDED)
        add(MediaStore.Images.Media.RELATIVE_PATH)
    }.toTypedArray()
    val items = mutableListOf<SavedExport>()
    context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        null,
        null,
        "${MediaStore.Images.Media.DATE_ADDED} DESC"
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
        val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
        val mimeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
        val widthIndex = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
        val heightIndex = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
        val dateAddedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
        val relativePathIndex = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
        if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0 || dateAddedIndex < 0) return@use
        while (cursor.moveToNext() && items.size < 60) {
            val displayName = cursor.getString(nameIndex).orEmpty()
            val inKeplerStudio = relativePathIndex >= 0 &&
                cursor.getString(relativePathIndex).orEmpty().startsWith("${Environment.DIRECTORY_PICTURES}/KeplerStudio")
            if (!inKeplerStudio) continue
            val id = cursor.getLong(idIndex)
            val safeDisplayName = displayName.ifBlank { "KeplerStudio_$id" }
            val mimeType = cursor.getString(mimeIndex).orEmpty()
            val width = if (widthIndex >= 0) cursor.getInt(widthIndex) else 0
            val height = if (heightIndex >= 0) cursor.getInt(heightIndex) else 0
            val dateAddedSeconds = cursor.getLong(dateAddedIndex)
            items += SavedExport(
                displayName = safeDisplayName,
                uriString = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()).toString(),
                formatLabel = mimeTypeToExportLabel(mimeType, safeDisplayName),
                resolutionLabel = if (width > 0 && height > 0) "${width}x${height}" else "원본",
                timestampMillis = if (dateAddedSeconds > 0L) dateAddedSeconds * 1000L else System.currentTimeMillis()
            )
        }
    }
    if (items.isNotEmpty()) saveSavedExportsToPrefs(context, items)
    return items
}

private fun mimeTypeToExportLabel(mimeType: String, displayName: String): String =
    when {
        mimeType.equals("image/jpeg", ignoreCase = true) -> "JPEG"
        mimeType.equals("image/png", ignoreCase = true) -> "PNG"
        mimeType.equals("image/webp", ignoreCase = true) -> "WebP"
        mimeType.equals("image/heic", ignoreCase = true) || mimeType.equals("image/heif", ignoreCase = true) -> "HEIF"
        displayName.endsWith(".jpg", ignoreCase = true) || displayName.endsWith(".jpeg", ignoreCase = true) -> "JPEG"
        displayName.endsWith(".png", ignoreCase = true) -> "PNG"
        displayName.endsWith(".webp", ignoreCase = true) -> "WebP"
        displayName.endsWith(".heic", ignoreCase = true) || displayName.endsWith(".heif", ignoreCase = true) -> "HEIF"
        else -> "사진"
    }

private fun saveExportHistoryRetention(context: Context, retention: ExportHistoryRetention) {
    check(context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .putString(KEY_EXPORT_HISTORY_RETENTION, retention.name)
        .commit()) { "failed to persist export history retention" }
}

private fun loadExportHistoryRetention(context: Context): ExportHistoryRetention =
    enumValueOrDefault(
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getString(KEY_EXPORT_HISTORY_RETENTION, null),
        ExportHistoryRetention.Never
    )

private fun saveEngineSelection(context: Context, engines: EngineSelection) {
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit()
        .putString(KEY_NOISE_ENGINE, engines.noiseEngine.name)
        .putString(KEY_DETAIL_ENGINE, engines.detailEngine.name)
        .putString(KEY_TONE_ENGINE, engines.toneEngine.name)
        .putString(KEY_HAZE_ENGINE, engines.hazeEngine.name)
        .apply()
}

private fun loadEngineSelection(context: Context): EngineSelection {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    return EngineSelection(
        noiseEngine = enumValueOrDefault(prefs.getString(KEY_NOISE_ENGINE, null), NoiseEngine.FastEdgeAware),
        detailEngine = enumValueOrDefault(prefs.getString(KEY_DETAIL_ENGINE, null), DetailEngine.MaskedUnsharp),
        toneEngine = enumValueOrDefault(prefs.getString(KEY_TONE_ENGINE, null), ToneEngine.HistogramAuto),
        hazeEngine = enumValueOrDefault(prefs.getString(KEY_HAZE_ENGINE, null), DehazeEngine.FastContrast)
    ).coerceImplemented()
}

private fun EngineSelection.coerceImplemented(): EngineSelection = copy(
    noiseEngine = noiseEngine.takeIf { it in IMPLEMENTED_NOISE_ENGINES } ?: NoiseEngine.FastEdgeAware,
    detailEngine = detailEngine.takeIf { it in IMPLEMENTED_DETAIL_ENGINES } ?: DetailEngine.MaskedUnsharp,
    toneEngine = toneEngine.takeIf { it in IMPLEMENTED_TONE_ENGINES } ?: ToneEngine.HistogramAuto,
    hazeEngine = hazeEngine.takeIf { it in IMPLEMENTED_DEHAZE_ENGINES } ?: DehazeEngine.FastContrast
)

private fun encodeSavedExport(item: SavedExport): String = listOf(
    item.displayName,
    item.uriString,
    item.formatLabel,
    item.resolutionLabel,
    item.timestampMillis.toString()
).joinToString("|") { it.replace("|", " ").replace("\n", " ") }

private fun decodeSavedExport(raw: String): SavedExport? {
    val parts = raw.split("|")
    if (parts.size != 5) return null
    return SavedExport(
        displayName = parts[0],
        uriString = parts[1],
        formatLabel = parts[2],
        resolutionLabel = parts[3],
        timestampMillis = parts[4].toLongOrNull() ?: return null
    )
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
    runCatching { enumValueOf<T>(name ?: return default) }.getOrDefault(default)

private fun exportTimestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

internal const val FLARE_GUARD_AI_TAG = "KeplerFlareAI"
private const val EDITOR_HISTORY_MAX = 5
private const val EXPORT_MAX_SIDE = 8192
private const val DRAFT_SOURCE_FILE_NAME = "source.img"
private const val DRAFT_THUMBNAIL_FILE_NAME = "thumbnail.jpg"
private const val PREF_NAME = "kepler_studio_editor"
private const val KEY_SAVED_EXPORTS = "saved_exports"
private const val KEY_SAVED_EXPORTS_INITIALIZED = "saved_exports_initialized"
private const val KEY_EXPORT_HISTORY_RETENTION = "export_history_retention"
private const val KEY_NOISE_ENGINE = "noise_engine"
private const val KEY_DETAIL_ENGINE = "detail_engine"
private const val KEY_TONE_ENGINE = "tone_engine"
private const val KEY_HAZE_ENGINE = "haze_engine"
private const val KEY_DRAFT_SOURCE = "draft_source"
private const val KEY_DRAFT_EXPOSURE = "draft_exposure"
private const val KEY_DRAFT_CONTRAST = "draft_contrast"
private const val KEY_DRAFT_SHADOWS = "draft_shadows"
private const val KEY_DRAFT_HIGHLIGHTS = "draft_highlights"
private const val KEY_DRAFT_WHITES = "draft_whites"
private const val KEY_DRAFT_BLACKS = "draft_blacks"
private const val KEY_DRAFT_TEMPERATURE = "draft_temperature"
private const val KEY_DRAFT_TINT = "draft_tint"
private const val KEY_DRAFT_SATURATION = "draft_saturation"
private const val KEY_DRAFT_VIBRANCE = "draft_vibrance"
private const val KEY_DRAFT_CLARITY = "draft_clarity"
private const val KEY_DRAFT_DEHAZE = "draft_dehaze"
private const val KEY_DRAFT_SHARPNESS = "draft_sharpness"
private const val KEY_DRAFT_NOISE_REDUCTION = "draft_noise_reduction"
private const val KEY_DRAFT_LUMINANCE_NOISE_REDUCTION = "draft_luminance_noise_reduction"
private const val KEY_DRAFT_COLOR_NOISE_REDUCTION = "draft_color_noise_reduction"
private const val KEY_DRAFT_NOISE_DETAIL_PROTECTION = "draft_noise_detail_protection"
private const val KEY_DRAFT_FORMAT = "draft_format"
private const val KEY_DRAFT_RESOLUTION = "draft_resolution"
private const val KEY_DRAFT_LOOK = "draft_look"
private const val KEY_DRAFT_QUICK_EFFECTS = "draft_quick_effects"
private const val KEY_DRAFT_SAVED_AT = "draft_saved_at"
private const val KEY_DRAFT_BASE_TOKEN = "draft_base_token"
private const val KEY_DRAFT_BASE_VERSION_LEGACY = "draft_base_version"
internal const val KEY_DRAFT_GENERATION_ID = "draft_generation_id"
internal const val DRAFT_MANIFEST_FILE_NAME = "manifest.json"
internal const val DRAFT_GENERATION_DIR_PREFIX = "gen_"
internal const val DRAFT_GENERATION_STAGING_PREFIX = ".staging_"
internal const val PREF_NAME_DRAFT = "kepler_studio_editor"
internal const val DRAFT_FORMAT_VERSION = 2
internal val IMPLEMENTED_NOISE_ENGINES = listOf(
    NoiseEngine.FastEdgeAware,
    NoiseEngine.GuidedFilter,
    NoiseEngine.NonLocalMeansLite
)
internal val IMPLEMENTED_DETAIL_ENGINES = listOf(DetailEngine.MaskedUnsharp)
internal val IMPLEMENTED_TONE_ENGINES = listOf(ToneEngine.HistogramAuto, ToneEngine.Clahe)
internal val IMPLEMENTED_DEHAZE_ENGINES = listOf(DehazeEngine.FastContrast)
