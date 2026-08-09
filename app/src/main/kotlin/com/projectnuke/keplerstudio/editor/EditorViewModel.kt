package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.content.ComponentCallbacks2
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
import androidx.compose.ui.unit.IntSize
import com.projectnuke.keplerstudio.BuildConfig
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import com.projectnuke.keplerstudio.bridge.nativeCreateSessionOrTest
import com.projectnuke.keplerstudio.bridge.NativeCorrectionV2Params
import com.projectnuke.keplerstudio.bridge.NativeScratchPlanner
import com.projectnuke.keplerstudio.ui.RemasterModelSession
import com.projectnuke.keplerstudio.ui.addSubjectSelectionFromEdgeModel
import com.projectnuke.keplerstudio.ui.applyActiveSelectionLocalEditNativeBaked
import com.projectnuke.keplerstudio.ui.applyFlareOriginalMvp
import com.projectnuke.keplerstudio.ui.applyMaskAwareRemaster
import com.projectnuke.keplerstudio.ui.applySunFlareOriginalMvp
import com.projectnuke.keplerstudio.ui.autoStraightenCrop
import com.projectnuke.keplerstudio.ui.createBackgroundSelectionFromActive
import com.projectnuke.keplerstudio.ui.duplicateActiveSelectionLayer
import com.projectnuke.keplerstudio.ui.normalizeBrushMaskStorage
import com.projectnuke.keplerstudio.ui.paintActiveSelectionAt
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class PendingHistorySnapshot(
    private val deferred: CompletableDeferred<EditorHistorySnapshot?>,
) : AutoCloseable {
    private enum class Terminal {
        Pending,
        CompletedUnclaimed,
        Transferred,
        Discarded,
        Failed,
        Cancelled,
    }

    private var terminal = Terminal.Pending
    private var producerJob: Job? = null
    private var completedResult: EditorHistorySnapshot? = null
    private var producerFailure: Throwable? = null

    internal fun attachProducer(job: Job) {
        val cancel = synchronized(this) {
            if (terminal == Terminal.Pending) {
                producerJob = job
                false
            } else {
                true
            }
        }
        if (cancel) job.cancel()
    }

    internal fun producerFinished() {
        synchronized(this) { producerJob = null }
    }

    internal fun complete(value: EditorHistorySnapshot?) {
        val recycleNow = synchronized(this) {
            if (terminal != Terminal.Pending) {
                value
            } else {
                completedResult = value
                terminal = Terminal.CompletedUnclaimed
                deferred.complete(value)
                null
            }
        }
        recycleNow?.recycleBitmaps()
    }

    internal fun fail(error: Throwable) {
        val publish = synchronized(this) {
            if (terminal != Terminal.Pending) false
            else {
                producerFailure = error
                terminal = Terminal.Failed
                true
            }
        }
        if (publish) deferred.completeExceptionally(error)
    }

    internal fun producerCancelled() {
        val publish = synchronized(this) {
            if (terminal != Terminal.Pending) false
            else {
                terminal = Terminal.Cancelled
                true
            }
        }
        if (publish) deferred.cancel()
    }

    suspend fun await(): EditorHistorySnapshot? {
        synchronized(this) {
            when (terminal) {
                Terminal.Pending,
                Terminal.CompletedUnclaimed -> Unit
                Terminal.Failed -> throw (producerFailure ?: IllegalStateException("history preparation failed"))
                else -> return null
            }
        }
        val value =
            try {
                deferred.await()
            } catch (cancelled: CancellationException) {
                close()
                throw cancelled
            }
        val transferred = synchronized(this) {
            if (terminal == Terminal.CompletedUnclaimed) {
                terminal = Terminal.Transferred
                completedResult = null
                true
            } else false
        }
        return if (transferred) value else null
    }

    override fun close() {
        val decision = synchronized(this) {
            when (terminal) {
                Terminal.Pending -> {
                    terminal = Terminal.Cancelled
                    producerJob to null
                }
                Terminal.CompletedUnclaimed -> {
                    terminal = Terminal.Discarded
                    null to completedResult.also { completedResult = null }
                }
                else -> null to null
            }
        }
        decision.first?.cancel()
        decision.second?.recycleBitmaps()
        if (decision.first != null) deferred.cancel()
    }
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val _uiState =
        MutableStateFlow(
            EditorUiState(
                nativeVersion =
                    runCatching { NativePhotoCore.nativeVersion() }
                        .getOrElse { "native load failed: ${it.message}" }
            )
        )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()
    private val _brushPreviewEpoch = MutableStateFlow(0L)
    val brushPreviewEpoch: StateFlow<Long> = _brushPreviewEpoch.asStateFlow()
    private var nativeSession: Long = 0L
    private var renderJob: Job? = null
    private val correctionEngineSettings = CorrectionEngineSettings(app.applicationContext)
    @Volatile private var correctionEngineEpoch: Long = 0L
    private var exportJob: Job? = null
    private var exportToken: Long = 0L
    private var comparisonJob: Job? = null
    private var comparisonEpoch: Long = 0L
    internal var selectionLivePreviewJob: Job? = null
    internal var cropJob: Job? = null
    private var draftSaveJob: Job? = null
    private val draftSaveMutex = Mutex()

    /** Pure read-only inspection for tests: current Draft save epoch. */
    internal fun draftEpochForTest(): Long = draftOperationEpoch

    /** Pure read-only inspection for tests: whether a Draft save job is queued/running. */
    internal fun hasActiveDraftSaveJobForTest(): Boolean = draftSaveJob?.isActive == true

    /** Terminal teardown assertion: no child of this ViewModel's owner scope may remain active. */
    internal fun hasActiveViewModelJobsForTest(): Boolean =
        viewModelScope.coroutineContext[Job]?.children?.any { it.isActive } == true

    /** Test-only capture of the most recent export coroutine exception for
     *  diagnostic production tests (never read by production logic). */
    @Volatile internal var lastExportFailureForTest: Throwable? = null

    /** Pure read-only inspection for tests: current export token (drives
     *  stale-export identity checks across ViewModel state changes). */
    internal fun exportTokenForTest(): Long = exportToken

    /** Pure read-only inspection for tests: whether an export coroutine is live. */
    internal fun exportJobActiveForTest(): Boolean = exportJob?.isActive == true

    /** Test-only trigger of the production invalidation that cancels the live
     *  export and bumps `exportToken`, equivalent to a document replacement. */
    internal fun invalidateExportForTest() = invalidateExport()

    /** Pure read-only inspection for tests: global saved-export history revision. */
    internal val savedExportHistoryRevisionForTest: Long
        get() = savedExportHistoryStore.revision

    /**
     * Completes when the startup init coroutine (engine prefs, Draft restore,
     * export-history rebuild) has fully finished. Tests await this before
     * ending a test or clearing the ViewModel so no init IO outlives the
     * test sandbox.
     */
    internal val startupInitCompletion = CompletableDeferred<Unit>()

    private val savedExportHistoryMutex = Mutex()
    /**
     * Single production owner of the saved-export history SharedPreferences and
     * the global history-revision arbitration. Replaces the scattered
     * top-level history mutation helpers so the merge algorithm is directly
     * testable. Gallery rows remain owned by the export pipeline's
     * publication commit point; this store only touches the app-history pref.
     */
    private val savedExportHistoryStore: SavedExportHistoryStore =
        SavedExportHistoryStore(getApplication<Application>())
    /** Invalidates every queued draft save/restore when the document changes. */
    private var draftOperationEpoch: Long = 0L
    @Volatile
    private var draftPointerBaseline: String? = currentDraftGenerationId(app.applicationContext)
    private val managedEdits by lazy(LazyThreadSafetyMode.NONE) {
        ManagedEditLaunchController(viewModelScope)
    }
    @Volatile private var shuttingDown: Boolean = false
    private var cropOperationToken: Long = 0L
    internal var selectionParamTransaction: SelectionParamTransaction? = null
    private var selectionGestureCounter: Long = 0L
    private var selectionPreviewCounter: Long = 0L
    private var asyncBusyCounter: Long = 0L
    private data class AsyncBusyOwner(
        val token: Long,
        val operationType: String,
        val documentGeneration: String,
        val sourcePath: String?,
        val baseContentToken: String,
        val startRevision: Int,
    )
    private var asyncBusyOwner: AsyncBusyOwner? = null
    private var transactionFinishJob: Job? = null
    private val selectionTransactionGate = Any()
    private var brushingSnapshot: EditorHistorySnapshot? = null
    private enum class BrushTransactionState { Idle, Preparing, Active, Finishing, Cancelling }
    @Volatile private var brushTransactionState = BrushTransactionState.Idle
    private var brushStartSnapshot: LeasedEditorSnapshot? = null
    private val pendingBrushPoints = ArrayDeque<Pair<Float, Float>>()
    private var brushSettlementJob: Job? = null
    private data class BrushTransactionIdentity(
        val strokeId: Long,
        val documentGeneration: String,
        val sourcePath: String?,
        val baseContentToken: String,
        val startRevision: Int,
        val activeLayerId: String,
        val workingMask: Bitmap? = null,
    )
    private var brushIdentity: BrushTransactionIdentity? = null
    private var brushWorkingMask: Bitmap? = null
    private var brushStrokeCounter: Long = 0L
    private var brushLayerId: String? = null
    private var brushBaseToken: String? = null
    private var brushRevision: Int = 0
    private var brushChanged: Boolean = false
    /**
     * Deferred history-snapshot capture for the active brush stroke. Off-Main because the
     * full Exact snapshot copies previewBitmap + originalPreviewBitmap + every layer bitmap.
     * The painter does not need the snapshot — it paints into a separately owned layer bitmap
     * installed synchronously in [beginBrushStroke]. The snapshot is only consumed on finish
     * or cancel, which settles asynchronously without blocking Main.
     */
    private var brushSnapshotJob: Job? = null
    /**
     * Named-owner handle for the brush stroke's owned working mask. Released on finish,
     * cancel, or supersession so the bitmap can be recycled once the stroke is over and no
     * worker is still reading it.
     */
    private var brushOwnedMaskHandle: MaskOwnerHandle? = null
    private var brushMaskReservation: MaskReservation? = null
    internal val brushRasterizer = com.projectnuke.keplerstudio.ui.BrushRasterizer()
    private var brushEpochCounter: Long = 0L
    internal var brushLastX: Float = Float.NaN
        private set
    internal var brushLastY: Float = Float.NaN
        private set
    internal fun setBrushLastPosition(x: Float, y: Float) {
        brushLastX = x
        brushLastY = y
    }
    private var paramUndoWindowJob: Job? = null
    private var lastSuccessfullyRenderedParams: EditParams = EditParams()
    private var activeParamRenderRevision: Int? = null
    private var parameterGesture: ParameterGestureTransaction? = null
    private var parameterGestureCounter: Long = 0L

    /** Pure read-only inspection for tests: a parameter transaction is open. */
    internal fun hasOpenParameterGesture(): Boolean = parameterGesture != null

    /** Pure read-only inspection for tests: the currently pending render revision. */
    internal fun pendingParamRenderRevision(): Int? = activeParamRenderRevision

    /** Pure read-only inspection for tests: latest adopted params in the open transaction. */
    internal fun adoptedParamsForTest(): EditParams? = parameterGesture?.adoptedParams

    /** Pure read-only inspection for tests: latest optimistic params in the open transaction. */
    internal fun latestParamsForTest(): EditParams? = parameterGesture?.latestParams

    /** Pure read-only inspection for tests: render progress phase of the open transaction. */
    internal fun paramRenderPhaseForTest(): ParamRenderPhase? = parameterGesture?.currentRenderPhase()
    internal fun paramRenderRevisionPhasesForTest(): Map<Int, ParamRenderRevisionPhase> =
        parameterGesture?.renderRevisionPhases().orEmpty()

    /** Pure read-only inspection for tests: committed undo-stack entry count. */
    internal fun undoEntryCountForTest(): Int = historyCoordinator.undoEntryCountForTest()

    /** Pure read-only inspection for tests: committed redo-stack entry count. */
    internal fun redoEntryCountForTest(): Int = historyCoordinator.redoEntryCountForTest()

    /** Test seam: invalidate the managed-edit token so that any in-flight
     *  parameter render whose [isManagedEditCurrent] check will fail, leaving
     *  the transaction with no adopted output. */
    internal fun invalidateManagedEditsForTest() = invalidateManagedEdits()

    /**
     * Terminal state of the parameter gesture itself: an open gesture is
     * [Active]; settlement makes it [Committed] (when at least one render was
     * adopted) or [RolledBack] (when nothing was adopted). [Closed] is
     * ownership release. Terminal state is decided ONLY by the adoption
     * record, never by per-render progress.
     */
    internal enum class ParamTransactionTerminalState {
        Active, Committed, RolledBack, Closed,
    }

    /**
     * Progress of the gesture's current render, independent of the terminal
     * state: [Idle] means no render in flight and nothing adopted yet,
     * [Rendering] means a render for the latest requested revision is in
     * flight, [Adopted] means the latest adopted revision is retained and no
     * render is in flight. Multiple sequential adoptions in one gesture move
     * the phase Rendering → Adopted → Rendering → Adopted without ever
     * touching the terminal state.
     */
    internal enum class ParamRenderPhase {
        Idle, Rendering, Adopted,
    }

    internal enum class ParamRenderRevisionPhase {
        Requested, Preparing, Rendering, Produced, Adopted, Canceled, Failed, Closed,
    }

    internal enum class SettlementReason {
        ExternalEdit,
        ManualDraftSave,
        Autosave,
        SaveAndLeave,
        Shutdown,
        DocumentReplacement,
        Export,
    }

    internal sealed class SettlementResult {
        data class Committed(val adoptedRevision: Int) : SettlementResult()
        data class RolledBack(val startRevision: Int) : SettlementResult()
        data object NoTransaction : SettlementResult()
    }

    internal class OwnedHistorySnapshot(val snapshot: EditorHistorySnapshot) : AutoCloseable {
        override fun close() { snapshot.recycleBitmaps() }
    }

    private class ParameterGestureTransaction(
        val id: Long,
        val start: LeasedEditorSnapshot,
        val lifecycleInstallation: ParameterLifecycleTestHook.Installation?,
        val historyPublishSeam: HistoryPublishTestSeam?,
    ) : AutoCloseable {
        @Volatile private var terminalState: ParamTransactionTerminalState = ParamTransactionTerminalState.Active
        private data class RenderRevisionOwner(
            val revision: Int,
            val params: EditParams,
            val sourceIdentity: DocumentIdentity,
            var operationToken: Long? = null,
            var phase: ParamRenderRevisionPhase = ParamRenderRevisionPhase.Requested,
            var job: Job? = null,
            var outputOwned: Boolean = false,
            var adoptionIdentity: String? = null,
            var terminalReason: String? = null,
        )
        private val renderRevisions = LinkedHashMap<Int, RenderRevisionOwner>()
        var historyHandle: PendingHistorySnapshot? = null
        var historyJob: Job? = null
        val historyHandoff = OwnedHandoff<OwnedHistorySnapshot>()
        var historyFailure: Throwable? = null
        @Volatile var historySnapshotPublished = false
        var latestRevision: Int = start.state.revision
        var latestParams: EditParams = start.state.params
        var renderJob: Job? = null
        var windowExpired: Boolean = false
        var historyCommitted: Boolean = false
        @Volatile var adoptedRevision: Int? = null
        @Volatile var adoptedParams: EditParams? = null
        @Volatile var inactivityGeneration: Long = 0L
        internal val stateLock = Any()
        private var closed = false

        internal fun currentTerminalState(): ParamTransactionTerminalState = terminalState

        internal fun currentRenderPhase(): ParamRenderPhase = synchronized(stateLock) {
            val latest = renderRevisions[latestRevision]
            when (latest?.phase) {
                ParamRenderRevisionPhase.Requested,
                ParamRenderRevisionPhase.Preparing,
                ParamRenderRevisionPhase.Rendering,
                ParamRenderRevisionPhase.Produced -> ParamRenderPhase.Rendering
                ParamRenderRevisionPhase.Adopted -> ParamRenderPhase.Adopted
                else -> if (adoptedRevision != null) ParamRenderPhase.Adopted else ParamRenderPhase.Idle
            }
        }

        internal fun renderRevisionPhases(): Map<Int, ParamRenderRevisionPhase> =
            synchronized(stateLock) { renderRevisions.mapValues { it.value.phase } }

        internal fun requestRender(revision: Int, params: EditParams) = synchronized(stateLock) {
            check(!closed && terminalState == ParamTransactionTerminalState.Active)
            renderRevisions.values
                .filter { it.revision != adoptedRevision && it.phase !in setOf(ParamRenderRevisionPhase.Canceled, ParamRenderRevisionPhase.Failed, ParamRenderRevisionPhase.Closed) }
                .forEach { it.phase = ParamRenderRevisionPhase.Canceled; it.terminalReason = "superseded"; it.job?.cancel() }
            renderRevisions[revision] = RenderRevisionOwner(revision, params, start.identity)
        }

        internal fun prepareRender(revision: Int, operationToken: Long, job: Job?) = synchronized(stateLock) {
            val owner = renderRevisions[revision] ?: return@synchronized false
            if (closed || owner.phase == ParamRenderRevisionPhase.Canceled) return@synchronized false
            owner.operationToken = operationToken
            owner.job = job
            owner.phase = ParamRenderRevisionPhase.Preparing
            true
        }

        /** A render job for the latest requested revision now owns the phase. */
        internal fun markRendering(revision: Int): Boolean {
            synchronized(stateLock) {
                val owner = renderRevisions[revision] ?: return false
                if (closed || terminalState != ParamTransactionTerminalState.Active || owner.phase == ParamRenderRevisionPhase.Canceled) return false
                owner.phase = ParamRenderRevisionPhase.Rendering
                return true
            }
        }

        internal fun markProduced(revision: Int): Boolean = synchronized(stateLock) {
            val owner = renderRevisions[revision] ?: return@synchronized false
            if (closed || owner.phase != ParamRenderRevisionPhase.Rendering) return@synchronized false
            owner.phase = ParamRenderRevisionPhase.Produced
            owner.outputOwned = true
            true
        }

        /** The current render adopted; its output is retained as the gesture state. */
        internal fun adopt(revision: Int): Boolean {
            synchronized(stateLock) {
                val owner = renderRevisions[revision] ?: return false
                if (closed || terminalState != ParamTransactionTerminalState.Active || revision != latestRevision || owner.phase != ParamRenderRevisionPhase.Produced) return false
                adoptedRevision?.takeIf { it != revision }?.let { previous ->
                    renderRevisions[previous]?.apply { phase = ParamRenderRevisionPhase.Closed; terminalReason = "replaced by adoption $revision" }
                }
                owner.phase = ParamRenderRevisionPhase.Adopted
                owner.outputOwned = false
                owner.adoptionIdentity = "$id:$revision"
                return true
            }
        }

        /** A failed newer render left the previously adopted output in place. */
        internal fun failRender(revision: Int, reason: String): Boolean {
            synchronized(stateLock) {
                val owner = renderRevisions[revision] ?: return false
                if (owner.phase == ParamRenderRevisionPhase.Adopted || owner.phase == ParamRenderRevisionPhase.Closed) return false
                owner.phase = ParamRenderRevisionPhase.Failed
                owner.terminalReason = reason
                owner.outputOwned = false
                return true
            }
        }

        /** A render ended with nothing adopted. */
        internal fun cancelRender(revision: Int, reason: String): Boolean {
            synchronized(stateLock) {
                val owner = renderRevisions[revision] ?: return false
                if (owner.phase == ParamRenderRevisionPhase.Adopted || owner.phase == ParamRenderRevisionPhase.Closed) return false
                owner.phase = ParamRenderRevisionPhase.Canceled
                owner.terminalReason = reason
                owner.outputOwned = false
                return true
            }
        }

        /** Terminal commit; the only normal path to [ParamTransactionTerminalState.Committed]. */
        internal fun commit(): Boolean {
            synchronized(stateLock) {
                if (closed || terminalState != ParamTransactionTerminalState.Active) return false
                terminalState = ParamTransactionTerminalState.Committed
                return true
            }
        }

        /** Terminal rollback; the only normal path to [ParamTransactionTerminalState.RolledBack]. */
        internal fun rollback(): Boolean {
            synchronized(stateLock) {
                if (closed || terminalState != ParamTransactionTerminalState.Active) return false
                terminalState = ParamTransactionTerminalState.RolledBack
                return true
            }
        }

        /**
         * Atomically take and transfer ownership of [historyHandoff] from the
         * transaction to the caller. Prevents race between [close] and a late
         * history result. Returns null if this transaction is closed or the
         * snapshot has already been taken for another purpose.
         */
        internal fun takeOwnedSnapshot(): EditorHistorySnapshot? {
            synchronized(stateLock) {
                if (closed || historyCommitted) return null
                return historyHandoff.take()?.snapshot
            }
        }

        override fun close() {
            synchronized(stateLock) {
                if (closed) return
                closed = true
                terminalState = ParamTransactionTerminalState.Closed
                renderRevisions.values.forEach { owner ->
                    if (owner.phase != ParamRenderRevisionPhase.Adopted) owner.phase = ParamRenderRevisionPhase.Closed
                    owner.job?.cancel()
                    owner.outputOwned = false
                }
            }
            renderJob?.cancel()
            historyJob?.cancel()
            historyHandle?.close()
            historyHandle = null
            if (!historyCommitted) historyHandoff.close()
            start.close()
        }
    }
    private var restoreDraftToken: Long = 0L
    internal val trackerSession: TrackerSession? = DebugMemoryTracker.createEditorSession(this)
    internal val tracker: TrackerDiagnostics = DebugMemoryTracker.diagnostics(trackerSession)
    internal val historyCoordinator =
        EditorHistoryCoordinator(app.applicationContext, viewModelScope, trackerSession)
    private val uiStateOwnership: UiStateOwnershipReconciler? =
        trackerSession?.let(::UiStateOwnershipReconciler)
    internal val bitmapLeaseLedger = BitmapLeaseLedger()
    internal val selectionMaskOwnership =
        SelectionMaskOwnershipLedger(
            byteBudget = { BitmapMemoryBudget.selectionMaskBudgetBytes() },
            layerBudget = { BitmapMemoryBudget.maxSelectionMaskLayers() },
            pinBitmap = { bitmap -> bitmapLeaseLedger.pinBitmap(bitmap) },
        )
    private var historyIoJob: Job? = null
    private var memoryRecoveryToken: Long = 0L
    private var pendingMemoryRetry: MemoryRetryDescriptor? = null
    private var memoryRecoveryJob: Job? = null
    private var automaticRetryAttempt: MemoryRetryDescriptor? = null
    private var strongRetryAttempt: MemoryRetryDescriptor? = null

    init {
        val persistedEngine = correctionEngineSettings.read()
        _uiState.update {
            it.copy(
                correctionEngineState =
                    it.correctionEngineState.copy(defaultEngine = persistedEngine),
            )
        }
        BitmapMemoryBudget.initialize(app.applicationContext)
        ThumbnailBitmapCache.setByteBudget(BitmapMemoryBudget.thumbnailBudgetBytes())
        tracker.activateDocument(historyCoordinator.currentGeneration())
        if (BuildConfig.DEBUG) {
            val probeGeneration = ModelAvailabilityRegistry.beginProbe()
            viewModelScope.launch(Dispatchers.IO) {
                ModelAvailabilityRegistry.probePackagedCapabilities(
                    app.applicationContext,
                    probeGeneration,
                )
            }
        } else {
            val releaseGeneration = ModelAvailabilityRegistry.beginProbe()
            viewModelScope.launch(Dispatchers.IO) {
                ModelAvailabilityRegistry.probeReleasePackagedCapabilities(
                    app.applicationContext,
                    releaseGeneration,
                )
            }
        }
    }

    internal fun createBrushSelectionInternal(allowRecovery: Boolean = true) {
        if (!canEnterEditorAction(allowMaskSupersession = true)) return
        createBrushSelectionAsyncInternal(allowRecovery)
    }

    private fun createBrushSelectionAsyncInternal(allowRecovery: Boolean) {
        invalidateComparison()
        val start = acquireEditorSnapshot("createBrushSelection") ?: return
        val state = start.state
        val base = start.originalPreviewBitmap ?: start.previewBitmap
        if (base == null) {
            start.close()
            updateUiState { it.copy(message = "브러시 마스크를 만들 이미지가 없습니다.") }
            return
        }
        val reservation =
            selectionMaskOwnership.reserve(
                owner = "brushSelection:${start.identity.revision}:${UUID.randomUUID()}",
                bytes = BitmapMemoryBudget.bytes(base.width, base.height, Bitmap.Config.ARGB_8888),
                documentLayerDelta = 1,
            )
        if (reservation == null) {
            start.close()
            if (allowRecovery) {
                requestAllocationRecovery(
                    MemoryRetryAction.CreateBrushSelection,
                    BitmapMemoryBudget.bytes(base.width, base.height),
                )
            } else {
                updateUiState { it.copy(message = "선택 마스크 메모리가 부족합니다.") }
            }
            return
        }
        updateUiState { it.copy(isBusy = true) }
        viewModelScope.launch(Dispatchers.Default) {
            var mask: Bitmap? = null
            var before: EditorHistorySnapshot? = null
            try {
                mask = createBitmapOrThrow(base.width, base.height, Bitmap.Config.ARGB_8888)
                mask?.eraseColor(0xFF000000.toInt())
                before = captureHistorySnapshotForLeasedSnapshot(start)
                withContext(Dispatchers.Main) {
                    val live = uiState.value
                    val current =
                        !shuttingDown &&
                            live.sourcePath == start.identity.sourcePath &&
                            live.baseContentToken == start.identity.baseContentToken &&
                            live.revision == start.identity.revision &&
                            historyCoordinator.currentGeneration() == start.identity.generation
                    val preparedMask = checkNotNull(mask)
                    if (current && before != null) {
                        val layer =
                            SelectionLayer(
                                id = "sel_" + UUID.randomUUID().toString().take(8),
                                name = "브러시 마스크 ${live.selectionLayers.count { it.kind == SelectionLayerKind.Brush } + 1}",
                                kind = SelectionLayerKind.Brush,
                                bitmap = preparedMask,
                            )
                        updateUiStateAndRecycleReplaced {
                            it.copy(
                                selectionLayers = it.selectionLayers + layer,
                                activeSelectionLayerId = layer.id,
                                revision = it.revision + 1,
                                isBusy = false,
                                message = "브러시 마스크를 만들었습니다.",
                            )
                        }
                        mask = null
                        val retained = before
                        before = null
                        commitUndoSnapshot(retained!!, clearRedo = true)
                        forceDraftSaveAsync()
                        markMemoryRetrySucceeded(MemoryRetryAction.CreateBrushSelection)
                    } else {
                        if (live.sourcePath == start.identity.sourcePath && live.baseContentToken == start.identity.baseContentToken) {
                            updateUiState { it.copy(isBusy = false) }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                withContext(Dispatchers.Main) {
                    val live = uiState.value
                    if (live.sourcePath == start.identity.sourcePath && live.baseContentToken == start.identity.baseContentToken) {
                        updateUiState { it.copy(isBusy = false, message = "브러시 마스크를 만들지 못했습니다.") }
                        if (allowRecovery && failure is BitmapAllocationRejectedException) {
                            requestAllocationRecovery(MemoryRetryAction.CreateBrushSelection, failure.requiredBytes)
                        }
                    }
                }
            } finally {
                before?.let(::recycleHistorySnapshot)
                mask?.takeIf { !it.isRecycled }?.recycle()
                reservation.close()
                start.close()
            }
        }
    }

    internal fun requestAllocationRecovery(
        action: MemoryRetryAction,
        requiredBytes: Long,
        payload: String? = null,
    ) {
        if (shuttingDown || memoryRecoveryJob?.isActive == true) return
        val state = _uiState.value
        val flags = historyCoordinator.flags()
        val (targetEntryId, navigationDirection, coordinatorGeneration) =
            when (action) {
                MemoryRetryAction.HistoryUndo -> {
                    val targetId = historyCoordinator.navigationTargetId(true)
                    Triple(targetId, true, historyCoordinator.currentGeneration())
                }
                MemoryRetryAction.HistoryRedo -> {
                    val targetId = historyCoordinator.navigationTargetId(false)
                    Triple(targetId, false, historyCoordinator.currentGeneration())
                }
                else -> Triple<String?, Boolean?, String?>(null, null, null)
            }
        val descriptor =
            MemoryRetryDescriptor(
                token = ++memoryRecoveryToken,
                action = action,
                requiredBytes = requiredBytes,
                sourcePath = state.sourcePath,
                baseContentToken = state.baseContentToken,
                revision = state.revision,
                payload = payload,
                navigationDirection = navigationDirection,
                targetEntryId = targetEntryId,
                coordinatorGeneration = coordinatorGeneration,
            )
        if (strongRetryAttempt.matchesRetryFailure(action, state)) {
            strongRetryAttempt = null
            pendingMemoryRetry = null
            updateUiStateAndRecycleReplaced {
                it.copy(
                    memoryRecoveryRequest = null,
                    isBusy = false,
                    message = "정리 후에도 현재 작업에 필요한 메모리를 확보하지 못했습니다. 이미지와 적용된 편집은 안전하게 유지됩니다.",
                )
            }
            return
        }
        if (automaticRetryAttempt.matchesRetryFailure(action, state)) {
            automaticRetryAttempt = null
            pendingMemoryRetry = descriptor
            updateUiStateAndRecycleReplaced {
                it.copy(
                    memoryRecoveryRequest =
                        MemoryRecoveryRequest(descriptor.token, mayMoveOldHistory = true),
                    isBusy = false,
                    message = "자동 정리 후에도 현재 작업에 더 많은 메모리가 필요합니다.",
                )
            }
            return
        }
        pendingMemoryRetry = descriptor
        memoryRecoveryJob =
            viewModelScope.launch {
                val protectedEntryId =
                    if (
                        descriptor.action == MemoryRetryAction.HistoryUndo ||
                            descriptor.action == MemoryRetryAction.HistoryRedo
                    ) {
                        descriptor.targetEntryId
                    } else null
                val cleanupResult =
                    performMemoryCleanup(strong = false, protectedEntryId = protectedEntryId)
                val isHistoryAction =
                    descriptor.action == MemoryRetryAction.HistoryUndo ||
                        descriptor.action == MemoryRetryAction.HistoryRedo
                val generalRetryOk =
                    cleanupResult.reclaimedResources &&
                        isMemoryRetryCurrent(descriptor) &&
                        BitmapMemoryBudget.canAllocate(requiredBytes)
                val historyRetryOk =
                    isHistoryAction &&
                        cleanupResult.reclaimedResources &&
                        isMemoryRetryCurrent(descriptor) &&
                        BitmapMemoryBudget.canAllocate(requiredBytes) &&
                        cleanupResult.historyRecoveryCompleted &&
                        !cleanupResult.historyRecoverySuperseded &&
                        cleanupResult.historyDiskBudgetSatisfied
                val retryOk = if (isHistoryAction) historyRetryOk else generalRetryOk
                if (retryOk) {
                    pendingMemoryRetry = null
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            memoryRecoveryRequest = null,
                            message = "메모리를 자동으로 정리했습니다. 작업을 다시 시도합니다.",
                        )
                    }
                    automaticRetryAttempt = descriptor
                    memoryRecoveryJob = null
                    performMemoryRetry(descriptor)
                } else if (isMemoryRetryCurrent(descriptor)) {
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            memoryRecoveryRequest =
                                MemoryRecoveryRequest(descriptor.token, mayMoveOldHistory = true),
                            message = "현재 작업에 더 많은 메모리가 필요합니다.",
                        )
                    }
                }
            }
    }

    fun retryPendingMemoryRecovery(token: Long) {
        val descriptor = pendingMemoryRetry?.takeIf { it.token == token } ?: return
        // Validate staleness BEFORE any destructive cleanup
        if (!isMemoryRetryCurrent(descriptor)) {
            // Stale request: clear dialog state without destructive cleanup
            pendingMemoryRetry = null
            updateUiStateAndRecycleReplaced { it.copy(memoryRecoveryRequest = null) }
            return
        }
        if (memoryRecoveryJob?.isActive == true) return
        val protectedEntryId =
            when (descriptor.action) {
                MemoryRetryAction.HistoryUndo,
                MemoryRetryAction.HistoryRedo -> descriptor.targetEntryId
                else -> null
            }
        memoryRecoveryJob =
            viewModelScope.launch {
                val cleanupResult =
                    performMemoryCleanup(strong = true, protectedEntryId = protectedEntryId)
                val isHistoryAction =
                    descriptor.action == MemoryRetryAction.HistoryUndo ||
                        descriptor.action == MemoryRetryAction.HistoryRedo
                val generalRetryOk =
                    cleanupResult.reclaimedResources &&
                        isMemoryRetryCurrent(descriptor) &&
                        BitmapMemoryBudget.canAllocate(descriptor.requiredBytes)
                val historyRetryOk =
                    isHistoryAction &&
                        cleanupResult.reclaimedResources &&
                        isMemoryRetryCurrent(descriptor) &&
                        BitmapMemoryBudget.canAllocate(descriptor.requiredBytes) &&
                        cleanupResult.historyRecoveryCompleted &&
                        !cleanupResult.historyRecoverySuperseded &&
                        cleanupResult.historyDiskBudgetSatisfied
                val retryOk = if (isHistoryAction) historyRetryOk else generalRetryOk
                if (retryOk) {
                    pendingMemoryRetry = null
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            memoryRecoveryRequest = null,
                            message = "메모리 정리를 완료했습니다. 작업을 다시 시도합니다.",
                        )
                    }
                    strongRetryAttempt = descriptor
                    memoryRecoveryJob = null
                    performMemoryRetry(descriptor)
                } else if (isMemoryRetryCurrent(descriptor)) {
                    pendingMemoryRetry = null
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            memoryRecoveryRequest = null,
                            message = "정리 후에도 현재 작업에 필요한 메모리를 확보하지 못했습니다. 이미지와 적용된 편집은 안전하게 유지됩니다.",
                        )
                    }
                }
            }
    }

    fun cancelPendingMemoryRecovery(token: Long) {
        if (pendingMemoryRetry?.token != token) return
        pendingMemoryRetry = null
        updateUiStateAndRecycleReplaced { it.copy(memoryRecoveryRequest = null) }
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        if (
            shuttingDown ||
                level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                memoryRecoveryJob?.isActive == true
        )
            return
        memoryRecoveryJob =
            viewModelScope.launch {
                performMemoryCleanup(
                    strong = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                )
            }
    }

    private suspend fun performMemoryCleanup(
        strong: Boolean,
        protectedEntryId: String? = null,
    ): MemoryCleanupResult {
        var reclaimedResources = false
        if (strong && BuildConfig.DEBUG) {
            val hadComparison =
                comparisonJob != null || ExperimentalComparisonStore.latest.value != null
            invalidateComparison()
            if (hadComparison) reclaimedResources = true
        }
        fun clearCompleted(job: Job?): Job? {
            if (job != null && !job.isActive) reclaimedResources = true
            return job?.takeIf { it.isActive }
        }
        renderJob = clearCompleted(renderJob)
        exportJob = clearCompleted(exportJob)
        comparisonJob = clearCompleted(comparisonJob)
        selectionLivePreviewJob = clearCompleted(selectionLivePreviewJob)
        cropJob = clearCompleted(cropJob)
        managedEdits.clearCompleted()
        draftSaveJob = clearCompleted(draftSaveJob)
        transactionFinishJob = clearCompleted(transactionFinishJob)
        historyIoJob = clearCompleted(historyIoJob)
        if (ThumbnailBitmapCache.evictUnleased() > 0L) reclaimedResources = true
        if (RemasterModelSession.unloadIdleNow()) reclaimedResources = true
        if (
            !_uiState.value.isBusy &&
                renderJob?.isActive != true &&
                exportJob?.isActive != true &&
                nativeSession != 0L
        ) {
            releaseNativeSession()
            reclaimedResources = true
        }
        val recoveryResult = historyCoordinator.recover(strong, protectedEntryId)
        if (recoveryResult.reclaimedRamBytes > 0L) reclaimedResources = true
        updateHistoryFlags()
        return MemoryCleanupResult(
            reclaimedResources = reclaimedResources,
            historyRecoveryCompleted = !recoveryResult.superseded,
            historyDiskBudgetSatisfied = recoveryResult.diskBudgetSatisfied,
            historyRecoverySuperseded = recoveryResult.superseded,
        )
    }

    private fun isMemoryRetryCurrent(descriptor: MemoryRetryDescriptor): Boolean {
        val state = _uiState.value
        if (shuttingDown) return false
        if (pendingMemoryRetry != descriptor) return false
        if (state.sourcePath != descriptor.sourcePath) return false
        if (state.baseContentToken != descriptor.baseContentToken) return false
        if (state.revision != descriptor.revision) return false
        val currentGen = historyCoordinator.currentGeneration()
        if (
            descriptor.coordinatorGeneration != null &&
                descriptor.coordinatorGeneration != currentGen
        )
            return false
        // For navigation retries, also validate target still exists
        if (
            descriptor.action == MemoryRetryAction.HistoryUndo ||
                descriptor.action == MemoryRetryAction.HistoryRedo
        ) {
            val direction = descriptor.navigationDirection ?: return false
            val targetId = descriptor.targetEntryId ?: return false
            if (historyCoordinator.navigationTargetId(direction) != targetId) return false
        }
        return true
    }

    private fun performMemoryRetry(descriptor: MemoryRetryDescriptor) {
        if (!isDocumentIdentityCurrent(descriptor)) return
        when (descriptor.action) {
            MemoryRetryAction.CreateBrushSelection -> createBrushSelectionInternal()
            MemoryRetryAction.SubjectSelection -> addSubjectSelectionFromEdgeModel()
            MemoryRetryAction.MaskAwareRemaster -> applyMaskAwareRemaster()
            MemoryRetryAction.FlareNight -> applyFlareOriginalMvp()
            MemoryRetryAction.FlareSun -> applySunFlareOriginalMvp()
            MemoryRetryAction.DuplicateSelection -> duplicateActiveSelectionLayer()
            MemoryRetryAction.BackgroundSelection -> createBackgroundSelectionFromActive()
            MemoryRetryAction.AutoStraightenCrop -> autoStraightenCrop()
            MemoryRetryAction.ApplySelectionNative -> applyActiveSelectionLocalEditNativeBaked()
            MemoryRetryAction.ExportPreview -> exportPreview()
            MemoryRetryAction.OpenImage -> descriptor.payload?.let { openImage(Uri.parse(it)) }
            MemoryRetryAction.RestoreDraft -> retryDraftRestoreAfterMemory()
            MemoryRetryAction.RotatePreview -> rotatePreview90()
            MemoryRetryAction.HistoryUndo -> descriptor.payload?.let { navigateHistory(true, it) }
            MemoryRetryAction.HistoryRedo -> descriptor.payload?.let { navigateHistory(false, it) }
        }
    }

    private fun retryDraftRestoreAfterMemory() {
        val token = ++restoreDraftToken
        val revision = _uiState.value.revision
        viewModelScope.launch {
            restoreDraftIfAvailable(getApplication<Application>(), token, revision)
        }
    }

    private fun isDocumentIdentityCurrent(descriptor: MemoryRetryDescriptor): Boolean {
        val state = _uiState.value
        return state.sourcePath == descriptor.sourcePath &&
            state.baseContentToken == descriptor.baseContentToken &&
            state.revision == descriptor.revision
    }

    private fun isDocumentIdentityCurrent(
        sourcePath: String?,
        baseContentToken: String?,
        revision: Int,
    ): Boolean {
        val state = _uiState.value
        return !shuttingDown &&
            state.sourcePath == sourcePath &&
            state.baseContentToken == baseContentToken &&
            state.revision == revision
    }

    internal fun markMemoryRetrySucceeded(action: MemoryRetryAction) {
        if (automaticRetryAttempt?.action == action) automaticRetryAttempt = null
        if (strongRetryAttempt?.action == action) strongRetryAttempt = null
    }

    fun updateUiState(transform: (EditorUiState) -> EditorUiState) {
        updateUiStateAndRecycleReplaced(transform)
    }

    /**
     * Atomically update UI state; recycles displaced Bitmaps retained only by the previous state.
     */
    internal fun updateUiStateAndRecycleReplaced(transform: (EditorUiState) -> EditorUiState) {
        var previousState: EditorUiState? = null
        var nextState: EditorUiState? = null
        bitmapLeaseLedger.withStateTransition {
            _uiState.update { current ->
                previousState = current
                transform(current)
                    .withVisibleNativeContractIfChangedFrom(current)
                    .also { nextState = it }
            }
            val prev = previousState
            val next = nextState
            if (prev != null && next != null) {
                selectionMaskOwnership.reconcileActiveState(next.selectionLayers)
                uiStateOwnership?.reconcile(prev, next, historyCoordinator.currentGeneration())
                recycleBitmaps(bitmapLeaseLedger.replaceState(prev, next))
            }
        }
    }

    /** Authoritative direct adoption path for state replacements that cannot use flow update. */
    private fun commitUiState(
        expected: EditorUiState,
        next: EditorUiState,
        replaceDocument: Boolean = false,
        adoptedNativeSession: Long = 0L,
    ): Boolean {
        val settled = next.withVisibleNativeContractIfChangedFrom(expected)
        return bitmapLeaseLedger.withStateTransition {
            if (!_uiState.compareAndSet(expected, settled)) return@withStateTransition false
            val generation =
                if (replaceDocument) {
                    val oldGeneration = historyCoordinator.currentGeneration()
                    historyCoordinator.replaceDocument()
                    val newGeneration = historyCoordinator.currentGeneration()
                    tracker.activateDocument(newGeneration, oldGeneration)
                    historyCoordinator.refreshDiagnostics()
                    newGeneration
                } else {
                    historyCoordinator.currentGeneration()
                }
            uiStateOwnership?.reconcile(expected, settled, generation)
            selectionMaskOwnership.reconcileActiveState(settled.selectionLayers)
            if (adoptedNativeSession != 0L)
                tracker.rebindNativeSessionGeneration(adoptedNativeSession, generation)
            recycleBitmaps(bitmapLeaseLedger.replaceState(expected, settled))
            true
        }
    }

    private fun recycleBitmaps(bitmaps: List<Bitmap>) {
        bitmaps.forEach { bitmap -> runCatching { if (!bitmap.isRecycled) bitmap.recycle() } }
    }

    private fun EditorUiState.withVisibleNativeContractIfChangedFrom(
        previous: EditorUiState,
    ): EditorUiState {
        val rendered = correctionEngineState.visiblePreview as? VisiblePreviewState.Rendered
            ?: return this
        if (
            previewBitmap === previous.previewBitmap &&
                correctionEngineState.visiblePreview == previous.correctionEngineState.visiblePreview
        ) {
            return this
        }
        return copy(
            algorithmContracts =
                algorithmContracts.copy(
                    nativeRenderContract = rendered.algorithmVersion,
                    migratedFromLegacy = rendered.migratedFromAlgorithmVersion,
                )
        )
    }

    /**
     * Starts a superseding bitmap/model edit. Callers must gate adoption with
     * [isManagedEditCurrent].
     */
    internal fun launchManagedEdit(block: suspend (Long) -> Unit): Job =
        launchManagedEditWithPreparedResources(block)

    private fun invalidateComparison() {
        if (!BuildConfig.DEBUG) return
        val hadComparison =
            comparisonJob != null ||
                _uiState.value.comparisonBusy ||
                ExperimentalComparisonStore.latest.value != null
        if (!hadComparison) return
        comparisonEpoch += 1L
        comparisonJob?.cancel()
        comparisonJob = null
        ExperimentalComparisonStore.clear()
        if (!shuttingDown) {
            updateUiStateAndRecycleReplaced {
                if (it.comparisonBusy) it.copy(comparisonBusy = false) else it
            }
        }
    }

    internal fun launchManagedEditWithPreparedResources(
        block: suspend (Long) -> Unit,
        handoff: PreparedResourceHandoff? = null,
    ): Job {
        invalidateComparison()
        return managedEdits.launch(handoff, block)
    }

    private fun launchManagedRenderWithPreparedResources(
        block: suspend (Long) -> Unit,
        handoff: PreparedResourceHandoff? = null,
    ): Job {
        val job = launchManagedEditWithPreparedResources(block, handoff)
        renderJob = job
        job.invokeOnCompletion { if (renderJob === job) renderJob = null }
        if (job.isCompleted && renderJob === job) renderJob = null
        return job
    }

    internal fun isManagedEditCurrent(token: Long, revision: Int): Boolean =
        !shuttingDown && managedEdits.isCurrent(token) && _uiState.value.revision == revision

    /** Changes the persisted default only; the current document is unchanged until explicitly applied. */
    fun setDefaultCorrectionEngine(engine: CorrectionEngine) {
        if (shuttingDown || engine == _uiState.value.correctionEngineState.defaultEngine) return
        correctionEngineSettings.write(engine)
        updateUiState {
            it.copy(correctionEngineState = it.correctionEngineState.copy(defaultEngine = engine))
        }
    }

    fun applyCorrectionEngineToCurrentDocument(engine: CorrectionEngine) {
        if (shuttingDown) return
        prepareForMaskInteraction()
        if (brushTransactionState != BrushTransactionState.Idle || brushSettlementJob?.isActive == true) {
            updateUiState { it.copy(message = "브러시 작업이 끝난 뒤 다시 시도해 주세요.") }
            return
        }
        invalidateSelectionPreview()
        invalidateCropOperation()
        correctionEngineEpoch += 1L
        invalidateManagedEdits()
        renderJob?.cancel()
        renderJob = null
        activeParamRenderRevision = null
        invalidateExport()

        val before = _uiState.value
        var engineUndoSnapshot: EditorHistorySnapshot? = captureCurrentHistorySnapshot(HistorySnapshotStorage.MetadataOnly)
        val nextRevision = before.revision + 1
        val identity =
            CorrectionEngineOperationIdentity(
                engineEpoch = correctionEngineEpoch,
                documentGeneration = historyCoordinator.currentGeneration(),
                baseContentToken = before.baseContentToken,
                revision = nextRevision,
            )
        updateUiStateAndRecycleReplaced {
            it.copy(
                correctionEngineState =
                    it.correctionEngineState.copy(
                        pendingEngine = engine,
                    ),
                revision = nextRevision,
                isBusy = before.previewBitmap != null,
                message =
                    if (before.previewBitmap == null) "No document loaded"
                    else "Rerendering with ${engine.displayName}",
            )
        }
        val liveBase = before.originalPreviewBitmap ?: before.previewBitmap
        if (liveBase == null) {
            updateUiState {
                if (it.revision == nextRevision && correctionEngineEpoch == identity.engineEpoch) {
                    it.copy(
                        isBusy = false,
                        correctionEngineState = it.correctionEngineState.withoutDocument(),
                    )
                } else it
            }
            engineUndoSnapshot?.let(::recycleHistorySnapshot)
            return
        }
        val ownedBase =
            runCatching { liveBase.copyOrThrow(Bitmap.Config.ARGB_8888, true) }
                .getOrElse {
                    engineUndoSnapshot?.let(::recycleHistorySnapshot)
                    engineUndoSnapshot = null
                    updateUiState {
                        if (it.revision == nextRevision && correctionEngineEpoch == identity.engineEpoch) {
                            val failure =
                                RenderResult.Failure(
                                    operation = RenderOperation.EngineSwitch,
                                    requestedRoute = RouteResolver.defaultNativeRoute(engine),
                                    attemptedRoute = RouteResolver.defaultNativeRoute(engine),
                                    kind = RenderFailureKind.AllocationRejected,
                                    message = "Unable to prepare engine rerender",
                                )
                            it.copy(
                                isBusy = false,
                                correctionEngineState =
                                    it.correctionEngineState.withFailedRender(engine, failure),
                                message = "Unable to prepare engine rerender",
                            )
                        } else {
                            it
                        }
                    }
                    return
                }
        launchManagedRenderWithPreparedResources({ operationToken ->
            val renderSlot = OwnedHandoff<OwnedRenderResult>()
            var renderedOwner: Bitmap? = null
            try {
                val switchState =
                    before.copy(
                        correctionEngineState =
                            before.correctionEngineState.copy(documentEngine = engine)
                    )
                withContext(Dispatchers.Default) {
                    val result =
                        EditorRenderer.render(
                            createRenderRequest(
                                state = switchState,
                                operation = RenderOperation.EngineSwitch,
                                basePreview = ownedBase,
                                revision = nextRevision,
                                assignedEngine = engine,
                            )
                        )
                    renderSlot.publish(OwnedRenderResult(result))
                }
                val renderOwner = checkNotNull(renderSlot.take())
                val renderResult = renderOwner.result
                if (renderResult is RenderResult.Success) renderedOwner = renderOwner.takeOutput()
                val current = _uiState.value
                val canAdopt =
                    isManagedEditCurrent(operationToken, nextRevision) &&
                        identity.matches(
                            correctionEngineEpoch,
                            historyCoordinator.currentGeneration(),
                            current.baseContentToken,
                            current.revision,
                        ) &&
                        current.correctionEngineState.pendingEngine == engine
                when (renderResult) {
                    is RenderResult.Success -> {
                        renderedOwner = renderResult.output
                        if (canAdopt) {
                            val adopted = checkNotNull(renderedOwner)
                            updateUiStateAndRecycleReplaced {
                                it.copy(
                                    previewBitmap = adopted,
                                    isBusy = false,
                                    correctionEngineState =
                                        it.correctionEngineState.withSuccessfulRender(
                                            documentEngine = engine,
                                            result = renderResult,
                                        ),
                                    message =
                                        if (
                                            renderResult.decision ==
                                                RenderRouteDecision.RuntimeFallbackToV1
                                        ) {
                                            "엔진 2 처리에 실패해 이번 결과만 엔진 1로 표시합니다."
                                        } else {
                                            "${engine.displayName} 활성"
                                        },
                                )
                            }
                            renderedOwner = null
                            settleAdoptedEditHistory(engineUndoSnapshot)
                            engineUndoSnapshot = null
                            forceDraftSaveAsync()
                        }
                    }
                    is RenderResult.Failure -> {
                        if (canAdopt) {
                            updateUiState {
                                it.copy(
                                    isBusy = false,
                                    correctionEngineState =
                                        it.correctionEngineState.withFailedRender(
                                            requestedEngine = engine,
                                            result = renderResult,
                                        ),
                                    message = "엔진 전환에 실패했습니다: ${renderResult.message}",
                                )
                            }
                        }
                    }
                    is RenderResult.Cancelled -> {
                        if (canAdopt) {
                            updateUiState {
                                it.copy(
                                    isBusy = false,
                                    correctionEngineState =
                                        it.correctionEngineState.copy(pendingEngine = null),
                                    message = "엔진 전환이 취소되었습니다.",
                                )
                            }
                        }
                    }
                }
            } catch (ce: CancellationException) {
                val current = _uiState.value
                if (
                    identity.matches(
                        correctionEngineEpoch,
                        historyCoordinator.currentGeneration(),
                        current.baseContentToken,
                        current.revision,
                    ) &&
                        current.correctionEngineState.pendingEngine == engine
                ) {
                    updateUiState {
                        it.copy(
                            isBusy = false,
                            correctionEngineState =
                                it.correctionEngineState.copy(pendingEngine = null),
                        )
                    }
                }
                throw ce
            } finally {
                renderSlot.close()
                renderedOwner?.takeUnless(Bitmap::isRecycled)?.recycle()
                ownedBase.takeUnless(Bitmap::isRecycled)?.recycle()
                engineUndoSnapshot?.let(::recycleHistorySnapshot)
                engineUndoSnapshot = null
            }
        })
    }

    /**
     * Applies a debug-only feature route override. Only rerenders when the [nativeRender]
     * override changed (since feature-specific override changes only affect future Flare/
     * Remaster/Subject operations, not the current preview).
     */
    fun updateExperimentalLab(transform: (DebugFeatureOverrides) -> DebugFeatureOverrides) {
        if (shuttingDown || !BuildConfig.DEBUG) return
        val engine = _uiState.value.correctionEngineState.documentEngine
        val before = ExperimentalLabController.debugOverrides()
        val after = transform(before)
        if (before == after) return
        ExperimentalLabController.updateDebugOverrides { after }
        val beforeNative =
            RouteResolver.resolveNativeRoute(
                RouteRequest(
                    assignedDocumentEngine = engine,
                    operation = RenderOperation.EngineSwitch,
                    debugOverride = before.nativeRender,
                    fallbackPolicy = _uiState.value.correctionEngineState.fallbackPolicy,
                ),
            )
        val afterNative =
            RouteResolver.resolveNativeRoute(
                RouteRequest(
                    assignedDocumentEngine = engine,
                    operation = RenderOperation.EngineSwitch,
                    debugOverride = after.nativeRender,
                    fallbackPolicy = _uiState.value.correctionEngineState.fallbackPolicy,
                ),
            )
        if (beforeNative != afterNative && _uiState.value.previewBitmap != null) {
            applyCorrectionEngineToCurrentDocument(engine)
        }
    }

    fun generateDebugComparison() =
        generateDebugComparison(editorResolution = false, mode = DebugComparisonMode.NativeRoutes)

    fun generateEditorResolutionDebugComparison() =
        generateDebugComparison(editorResolution = true, mode = DebugComparisonMode.NativeRoutes)

    fun generateProcessingEngineComparison() =
        generateDebugComparison(editorResolution = false, mode = DebugComparisonMode.ProcessingEngines)

    private fun generateDebugComparison(
        editorResolution: Boolean,
        mode: DebugComparisonMode,
    ) {
        if (!BuildConfig.DEBUG || shuttingDown) return
        val state = _uiState.value
        val source = state.originalPreviewBitmap ?: state.previewBitmap ?: return
        val referenceEngines =
            EngineSelection(
                noiseEngine = NoiseEngine.FastEdgeAware,
                detailEngine = DetailEngine.MaskedUnsharp,
                toneEngine = ToneEngine.HistogramAuto,
                hazeEngine = DehazeEngine.FastContrast,
            )
        val selectedEngines = state.engineSelection()
        if (mode == DebugComparisonMode.ProcessingEngines && selectedEngines == referenceEngines) {
            updateUiStateAndRecycleReplaced {
                it.copy(message = "세부 보정 방식에서 비교할 대체 알고리즘을 먼저 선택해 주세요.")
            }
            return
        }
        val comparisonRoute =
            if (mode == DebugComparisonMode.ProcessingEngines) {
                state.correctionEngineState.previewRoute
                    ?: if (state.correctionEngineState.documentEngine == CorrectionEngine.Engine2) {
                        NativeRenderRoute.V2
                    } else {
                        NativeRenderRoute.V1
                    }
            } else {
                null
            }
        if (state.isBusy || state.maintenanceBusy) {
            updateUiStateAndRecycleReplaced {
                it.copy(message = "현재 작업이 끝난 뒤 비교를 생성해 주세요.")
            }
            return
        }

        invalidateComparison()
        val longest = max(source.width, source.height).coerceAtLeast(1)
        val scale =
            if (editorResolution) 1f
            else (DEBUG_COMPARISON_MAX_SIDE.toFloat() / longest).coerceAtMost(1f)
        val width = (source.width * scale).roundToInt().coerceAtLeast(1)
        val height = (source.height * scale).roundToInt().coerceAtLeast(1)
        val sourceLayers = state.selectionLayers.filter(SelectionLayer::enabled)
        val enabledLayerCount = sourceLayers.size
        val bitmapBytes = BitmapMemoryBudget.bytes(width, height)
        val requiredBytes =
            BitmapMemoryBudget.saturatingMultiply(
                bitmapBytes,
                (12L + enabledLayerCount * 2L),
            )
        if (!BitmapMemoryBudget.canAllocate(requiredBytes)) {
            updateUiStateAndRecycleReplaced {
                it.copy(
                    message =
                        if (editorResolution) {
                            "편집 해상도 비교에 필요한 메모리를 확보할 수 없습니다."
                        } else {
                            "비교 미리보기에 필요한 메모리를 확보할 수 없습니다."
                        }
                )
            }
            return
        }
        val comparisonTracker =
            beginMemoryTracking(
                when {
                    mode == DebugComparisonMode.ProcessingEngines -> "processingEngineComparison"
                    editorResolution -> "debugComparisonEditorResolution"
                    else -> "debugComparison"
                },
                snapshotState = "rendering",
                transientReserveBytes = requiredBytes,
            )

        var ownedBase: Bitmap? = null
        val ownedLayers = ArrayList<SelectionLayer>(sourceLayers.size)
        try {
            ownedBase =
                if (source.width == width && source.height == height) {
                    source.copyOrThrow()
                } else {
                    createScaledBitmapOrThrow(source, width, height, true)
                }
            sourceLayers.forEach { layer ->
                val copiedMask =
                    if (layer.bitmap.width == width && layer.bitmap.height == height) {
                        layer.bitmap.copyOrThrow()
                    } else {
                        createScaledBitmapOrThrow(layer.bitmap, width, height, true)
                    }
                ownedLayers += layer.copy(bitmap = copiedMask)
            }
        } catch (t: Throwable) {
            ownedBase?.takeUnless(Bitmap::isRecycled)?.recycle()
            ownedLayers.forEach { it.bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
            comparisonTracker?.end()
            updateUiStateAndRecycleReplaced {
                it.copy(message = "비교 입력을 준비하지 못했습니다: ${t.message ?: "메모리 부족"}")
            }
            return
        }

        val epoch = ++comparisonEpoch
        val base = checkNotNull(ownedBase)
        val baselineRoute = comparisonRoute ?: NativeRenderRoute.V1
        val experimentalRoute = comparisonRoute ?: NativeRenderRoute.V2
        val v1Request =
            createRenderRequest(
                state = state,
                operation = RenderOperation.DebugComparison,
                basePreview = base,
                revision = state.revision,
                engines =
                    if (mode == DebugComparisonMode.ProcessingEngines) referenceEngines
                    else selectedEngines,
                selectionLayers = ownedLayers,
                storedRequestedRoute = baselineRoute,
                exactRoute = baselineRoute,
                storedDecision = RenderRouteDecision.StoredVisibleTruth,
                fallbackPolicy = FallbackPolicy.NoFallback,
                diagnostics = comparisonTracker,
            )
        val v2Request =
            createRenderRequest(
                state = state,
                operation = RenderOperation.DebugComparison,
                basePreview = base,
                revision = state.revision,
                engines = selectedEngines,
                selectionLayers = ownedLayers,
                storedRequestedRoute = experimentalRoute,
                exactRoute = experimentalRoute,
                storedDecision = RenderRouteDecision.StoredVisibleTruth,
                fallbackPolicy = FallbackPolicy.NoFallback,
                diagnostics = comparisonTracker,
            )
        val comparisonIdentity =
            DebugComparisonIdentity(
                epoch = epoch,
                documentGeneration = v1Request.identity.documentGeneration,
                baseContentToken = v1Request.identity.baseContentToken,
                revision = v1Request.identity.revision,
            )
        updateUiStateAndRecycleReplaced {
            it.copy(
                comparisonBusy = true,
                message =
                    when {
                        mode == DebugComparisonMode.ProcessingEngines ->
                            "기본·선택 알고리즘 비교 미리보기를 생성하는 중입니다."
                        editorResolution -> "편집 해상도 비교 준비 중 · V1 단계"
                        else -> "V1·V2 비교 미리보기를 생성하는 중입니다."
                    },
            )
        }

        val launched =
            viewModelScope.launch {
                var v1Output: Bitmap? = null
                var v2Output: Bitmap? = null
                var v1Display: Bitmap? = null
                var v2Display: Bitmap? = null
                try {
                    val pair =
                        withContext(Dispatchers.Default) {
                            currentCoroutineContext().ensureActive()
                            val v1 = EditorRenderer.render(v1Request).successOrThrow()
                            v1Output = v1.output
                            currentCoroutineContext().ensureActive()
                            withContext(Dispatchers.Main) {
                                if (isDebugComparisonCurrent(comparisonIdentity)) {
                                    updateUiStateAndRecycleReplaced {
                                        it.copy(
                                            message =
                                                when {
                                                    mode == DebugComparisonMode.ProcessingEngines ->
                                                        "기본·선택 알고리즘 비교 처리 중 · 선택 단계"
                                                    editorResolution ->
                                                        "편집 해상도 비교 처리 중 · V2 단계"
                                                    else ->
                                                        "V1·V2 비교 미리보기 처리 중 · V2 단계"
                                                }
                                        )
                                    }
                                }
                            }
                            val v2 = EditorRenderer.render(v2Request).successOrThrow()
                            v2Output = v2.output
                            v1 to v2
                        }
                    if (!isDebugComparisonCurrent(comparisonIdentity)) return@launch
                    val displayScale =
                        (DEBUG_COMPARISON_MAX_SIDE.toFloat() / max(width, height))
                            .coerceAtMost(1f)
                    val displayWidth = (width * displayScale).roundToInt().coerceAtLeast(1)
                    val displayHeight = (height * displayScale).roundToInt().coerceAtLeast(1)
                    v1Display =
                        if (displayWidth == width && displayHeight == height) {
                            checkNotNull(v1Output).copyOrThrow()
                        } else {
                            createScaledBitmapOrThrow(
                                checkNotNull(v1Output),
                                displayWidth,
                                displayHeight,
                                true,
                            )
                        }
                    v2Display =
                        if (displayWidth == width && displayHeight == height) {
                            checkNotNull(v2Output).copyOrThrow()
                        } else {
                            createScaledBitmapOrThrow(
                                checkNotNull(v2Output),
                                displayWidth,
                                displayHeight,
                                true,
                            )
                        }
                    val v1Pixels = IntArray(displayWidth * displayHeight)
                    val v2Pixels = IntArray(displayWidth * displayHeight)
                    checkNotNull(v1Display)
                        .getPixels(v1Pixels, 0, displayWidth, 0, 0, displayWidth, displayHeight)
                    checkNotNull(v2Display)
                        .getPixels(v2Pixels, 0, displayWidth, 0, 0, displayWidth, displayHeight)
                    val displayLayers =
                        if (displayWidth == width && displayHeight == height) {
                            ownedLayers
                        } else {
                            ownedLayers.map { layer ->
                                layer.copy(
                                    bitmap =
                                        createScaledBitmapOrThrow(
                                            layer.bitmap,
                                            displayWidth,
                                            displayHeight,
                                            true,
                                        )
                                )
                            }
                        }
                    val maskPixels =
                        try {
                            comparisonMaskArgb(displayLayers, displayWidth, displayHeight)
                        } finally {
                            if (displayLayers !== ownedLayers) {
                                displayLayers.forEach {
                                    it.bitmap.takeUnless(Bitmap::isRecycled)?.recycle()
                                }
                            }
                        }
                    val artifact =
                        QualityRegressionMetricsV2
                            .debugArtifact(
                                fixtureVersion = "document-${state.baseContentToken}",
                                baseline = v1Pixels,
                                experimental = v2Pixels,
                                width = displayWidth,
                                height = displayHeight,
                                maskArgb = maskPixels,
                            )
                            .copy(
                                resolutionLevel =
                                    if (editorResolution) {
                                        DebugComparisonResolution.EditorWorking
                                    } else {
                                        DebugComparisonResolution.BoundedPreview
                                    },
                                evaluatedWidth = width,
                                evaluatedHeight = height,
                                baselineContracts =
                                    state.algorithmContracts.copy(
                                        nativeRenderContract =
                                            if (baselineRoute == NativeRenderRoute.V2) {
                                                AlgorithmContracts.NATIVE_V2
                                            } else {
                                                AlgorithmContracts.NATIVE_V1
                                            },
                                    ),
                                experimentalContracts =
                                    state.algorithmContracts.copy(
                                        nativeRenderContract =
                                            if (experimentalRoute == NativeRenderRoute.V2) {
                                                AlgorithmContracts.NATIVE_V2
                                            } else {
                                                AlgorithmContracts.NATIVE_V1
                                            },
                                    ),
                                baseProvenance = state.baseProvenance,
                                algorithmDecision =
                                    if (mode == DebugComparisonMode.ProcessingEngines) {
                                        "기본 처리 → ${selectedEngines.compactLabel()}"
                                    } else {
                                        "동일 문서 V1·V2"
                                    },
                                baselineLabel =
                                    if (mode == DebugComparisonMode.ProcessingEngines) "기본 처리" else "V1",
                                experimentalLabel =
                                    if (mode == DebugComparisonMode.ProcessingEngines) "선택 처리" else "V2",
                                knownTransientBytes = requiredBytes,
                                durationMillis = pair.first.durationMillis + pair.second.durationMillis,
                            )
                    val ownedArtifact = OwnedDebugComparisonArtifact.create(artifact)
                    if (!isDebugComparisonCurrent(comparisonIdentity)) {
                        ownedArtifact.close()
                        return@launch
                    }
                    try {
                        ExperimentalComparisonStore.publishDebug(ownedArtifact)
                    } catch (failure: Throwable) {
                        ownedArtifact.close()
                        throw failure
                    }
                    updateUiStateAndRecycleReplaced {
                        if (isDebugComparisonCurrent(comparisonIdentity)) {
                            it.copy(
                                comparisonBusy = false,
                                message =
                                    when {
                                        mode == DebugComparisonMode.ProcessingEngines ->
                                            "기본·선택 알고리즘 비교 미리보기를 생성했습니다."
                                        editorResolution -> "편집 해상도 V1·V2 비교를 생성했습니다."
                                        else -> "V1·V2 비교 미리보기를 생성했습니다."
                                    },
                            )
                        } else {
                            it
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    if (isDebugComparisonCurrent(comparisonIdentity)) {
                        updateUiStateAndRecycleReplaced {
                            it.copy(
                                comparisonBusy = false,
                                message = "비교 미리보기 생성에 실패했습니다: ${t.message ?: "알 수 없는 오류"}",
                            )
                        }
                    }
                } finally {
                    v1Output?.takeUnless(Bitmap::isRecycled)?.recycle()
                    v2Output?.takeUnless(Bitmap::isRecycled)?.recycle()
                    v1Display?.takeUnless(Bitmap::isRecycled)?.recycle()
                    v2Display?.takeUnless(Bitmap::isRecycled)?.recycle()
                    base.takeUnless(Bitmap::isRecycled)?.recycle()
                    ownedLayers.forEach { it.bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
                    comparisonTracker?.end()
                    if (comparisonEpoch == epoch) {
                        updateUiStateAndRecycleReplaced {
                            if (it.comparisonBusy) it.copy(comparisonBusy = false) else it
                        }
                    }
                }
            }
        comparisonJob = launched
        launched.invokeOnCompletion { if (comparisonJob === launched) comparisonJob = null }
        if (launched.isCompleted && comparisonJob === launched) comparisonJob = null
    }

    fun cancelDebugComparison() {
        if (!BuildConfig.DEBUG || !(_uiState.value.comparisonBusy)) return
        invalidateComparison()
        updateUiStateAndRecycleReplaced {
            it.copy(message = "V1·V2 비교 생성을 취소했습니다.")
        }
    }

    private fun isDebugComparisonCurrent(identity: DebugComparisonIdentity): Boolean {
        val state = _uiState.value
        return BuildConfig.DEBUG &&
            !shuttingDown &&
            identity.matches(
                epoch = comparisonEpoch,
                documentGeneration = historyCoordinator.currentGeneration(),
                baseContentToken = state.baseContentToken,
                revision = state.revision,
            )
    }

    internal fun isManagedEditTokenCurrent(token: Long): Boolean =
        !shuttingDown && managedEdits.isCurrent(token)

    internal fun isShuttingDown(): Boolean = shuttingDown

    internal fun currentDocumentGeneration(): String = historyCoordinator.currentGeneration()

    internal fun createRenderRequest(
        state: EditorUiState,
        operation: RenderOperation,
        basePreview: Bitmap,
        revision: Int,
        assignedEngine: CorrectionEngine = state.correctionEngineState.documentEngine,
        params: EditParams = state.params,
        engines: EngineSelection = state.engineSelection(),
        look: PresetColorLook? = state.presetLook,
        quickEffects: List<ActiveQuickEffect> = state.activeQuickEffects,
        selectionLayers: List<SelectionLayer> = state.selectionLayers,
        documentGeneration: String = currentDocumentGeneration(),
        storedRequestedRoute: NativeRenderRoute? = null,
        exactRoute: NativeRenderRoute? = null,
        storedDecision: RenderRouteDecision? = null,
        storedAlgorithmVersion: String? = null,
        storedParticipation: RenderParticipation? = null,
        fallbackPolicy: FallbackPolicy = state.correctionEngineState.fallbackPolicy,
        diagnostics: MemoryTrackerScope? = null,
    ): RenderRequest {
        return RenderRequest(
            operation = operation,
            basePreview = basePreview,
            params = params,
            engines = engines,
            assignedDocumentEngine = assignedEngine,
            identity =
                RenderIdentity(
                    documentGeneration = documentGeneration,
                    baseContentToken = state.baseContentToken,
                    revision = revision,
                ),
            debugOverride =
                ExperimentalLabController.debugOverrides().nativeRender
                    .takeIf { exactRoute == null },
            storedRequestedRoute = storedRequestedRoute,
            exactRoute = exactRoute,
            storedDecision = storedDecision,
            storedAlgorithmVersion = storedAlgorithmVersion,
            storedParticipation = storedParticipation,
            fallbackPolicy = fallbackPolicy,
            look = look,
            quickEffects = quickEffects,
            selectionLayers = selectionLayers,
            diagnostics = diagnostics,
        )
    }

    internal fun canEnterEditorAction(allowMaskSupersession: Boolean = false): Boolean {
        if (!settleForEditorAction()) return false
        return canEnterEditorActionPure(allowMaskSupersession)
    }

    /**
     * Settles interactive owners (pending parameter transaction, brush stroke,
     * selection transaction) so the caller's first action cannot capture
     * working pixels or observe a still-live optimistic transaction. Returns
     * false while a live owner remains or during shutdown. Side-effecting:
     * this is the only entrypoint that may mutate settlement state.
     */
    internal fun settleForEditorAction(): Boolean {
        if (shuttingDown) return false
        abortPendingParameterEdit()
        // An editor action is an intent to capture a new start state. Settle interactive
        // owners before answering so the caller's first click cannot capture working pixels
        // or observe a still-live optimistic selection transaction.
        if (brushTransactionState == BrushTransactionState.Finishing ||
            brushTransactionState == BrushTransactionState.Cancelling
        ) {
            updateUiState { it.copy(message = "브러시 작업이 마무리되는 중입니다. 잠시 후 다시 시도해 주세요.") }
            return false
        }
        if (brushTransactionState != BrushTransactionState.Idle) cancelBrushStroke()
        if (selectionParamTransaction != null) settleSelectionParamTransactionForSupersession()
        if (brushTransactionState != BrushTransactionState.Idle) return false
        if (selectionParamTransaction != null) return false
        return true
    }

    /**
     * Pure readiness predicate: answers whether an editor action may start
     * from the current settled state. Must never mutate state; callers invoke
     * [settleForEditorAction] first.
     */
    internal fun canEnterEditorActionPure(allowMaskSupersession: Boolean = false): Boolean {
        if (shuttingDown) return false
        if (historyCoordinator.flags().busy) return false
        val state = _uiState.value
        return !state.isBusy || allowMaskSupersession && isBusyOwnedByMaskSupersedable()
    }

    private suspend fun invalidateRemovedHistoryThumbnails(
        context: Context,
        result: SavedExportHistoryMutation,
    ) {
        withContext(Dispatchers.IO) {
            savedExportHistoryMutex.withLock {
                val retained = savedExportHistoryStore.load().map { it.uriString }.toSet()
                result.removedUris.filterNot(retained::contains).forEach {
                    ThumbnailBitmapCache.invalidate("export:$it")
                }
            }
        }
    }

    /**
     * Unified export-identity predicate. The owning [ExportIdentity.owningJob]
     * is captured at operation creation and compared to the registered export
     * job, so a transient `NonCancellable` context swap inside the pipeline
     * cannot make a stale export impersonate a newer one, and a newer export
     * bumping `exportToken` immediately invalidates every older identity.
     */
    private fun isCurrentExport(identity: ExportIdentity): Boolean {
        val owningJob = identity.owningJob
        if (
            !owningJob.isActive ||
                exportJob !== owningJob ||
                exportToken != identity.token ||
                shuttingDown
        ) {
            return false
        }
        val state = _uiState.value
        return state.sourcePath == identity.sourcePath &&
            state.baseContentToken == identity.baseToken &&
            state.revision == identity.revision
    }

    internal fun beginCropOperation(): Long = ++cropOperationToken

    internal fun invalidateCropOperation() {
        cropOperationToken += 1L
    }

    internal fun isCropOperationCurrent(token: Long): Boolean = cropOperationToken == token

    internal fun isCropResultCurrent(token: Long, revision: Int): Boolean =
        !shuttingDown && isCropOperationCurrent(token) && _uiState.value.revision == revision

    internal fun beginSelectionPreview(transaction: SelectionParamTransaction): Long {
        return synchronized(selectionTransactionGate) {
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
            token
        }
    }

    /**
     * Captures the exact state for the currently authorized live-preview tick.
     *
     * The ordinary editor snapshot gate deliberately rejects an active parameter gesture;
     * this narrower path is the only exception.  Transaction identity and preview token are
     * checked while the bitmap lifetime authority registers the lease, so a superseding state
     * publication cannot make the worker copy a different set of references.
     */
    internal fun acquireSelectionPreviewSnapshot(
        transaction: SelectionParamTransaction,
        previewToken: Long,
        expectedRevision: Int,
        activeLayerId: String,
    ): LeasedEditorSnapshot? = bitmapLeaseLedger.withStateTransition {
        synchronized(selectionTransactionGate) {
            if (
                selectionParamTransaction !== transaction ||
                    transaction.settled ||
                    transaction.committed ||
                    transaction.latestPreviewToken != previewToken ||
                    transaction.previewRevision != expectedRevision ||
                    transaction.previewLayerId != activeLayerId
            ) {
                return@withStateTransition null
            }
            val state = uiState.value
            val generation = historyCoordinator.currentGeneration()
            if (
                state.sourcePath != transaction.sourcePath ||
                    state.baseContentToken != transaction.baseContentToken ||
                    state.revision != expectedRevision ||
                    state.activeSelectionLayerId != activeLayerId ||
                    generation != transaction.documentGeneration ||
                    state.selectionLayers.none { it.id == activeLayerId }
            ) {
                return@withStateTransition null
            }
            bitmapLeaseLedger.capture("selectionLivePreview", state, generation)
        }
    }

    internal fun isSelectionPreviewCurrent(
        transaction: SelectionParamTransaction,
        token: Long,
        revision: Int,
        baseToken: String,
        activeId: String?,
    ): Boolean {
        if (shuttingDown) return false
        if (selectionParamTransaction !== transaction) return false
        val state = _uiState.value
        val generation = historyCoordinator.currentGeneration()
        return SelectionPreviewIdentity(
                gestureId = transaction.gestureId,
                previewToken = token,
                revision = revision,
                documentGeneration = transaction.documentGeneration,
                baseContentToken = baseToken,
                activeSelectionLayerId = activeId,
            )
            .matches(
                activeGestureId = selectionParamTransaction?.gestureId,
                latestPreviewToken = transaction.latestPreviewToken,
                stateRevision = state.revision,
                stateDocumentGeneration = generation,
                stateBaseContentToken = state.baseContentToken,
                stateActiveSelectionLayerId = state.activeSelectionLayerId,
            )
    }

    internal fun settleSelectionPreviewBusyIfOwned(
        transaction: SelectionParamTransaction,
        token: Long,
        revision: Int,
        baseToken: String,
        activeId: String?,
    ) {
        if (selectionParamTransaction !== transaction) return
        val generation = historyCoordinator.currentGeneration()
        val identity =
            SelectionPreviewIdentity(
                gestureId = transaction.gestureId,
                previewToken = token,
                revision = revision,
                documentGeneration = transaction.documentGeneration,
                baseContentToken = baseToken,
                activeSelectionLayerId = activeId,
            )
        _uiState.update { state ->
            if (
                state.isBusy &&
                    identity.matches(
                        activeGestureId = selectionParamTransaction?.gestureId,
                        latestPreviewToken = transaction.latestPreviewToken,
                        stateRevision = state.revision,
                        stateDocumentGeneration = generation,
                        stateBaseContentToken = state.baseContentToken,
                        stateActiveSelectionLayerId = state.activeSelectionLayerId,
                    )
            ) {
                state.copy(isBusy = false)
            } else {
                state
            }
        }
    }

    internal fun beginSelectionParamGesture(): Boolean {
        if (selectionParamTransaction != null) return false
        val leased = acquireEditorSnapshot("selectionParamGesture") ?: return false
        val state = leased.state
        // Defer the full Exact snapshot capture to a worker. The transaction holds a
        // Deferred handle so consumers (settlement, rollback) await the actual snapshot.
        // The lightweight params-only transaction identity is created synchronously so the
        // user-driven preview updates remain gated on Main.
        val pendingSnapshot = prepareHistorySnapshot("selectionParamGesture", leased)
        synchronized(selectionTransactionGate) {
            selectionParamTransaction =
                SelectionParamTransaction(
                    gestureId = ++selectionGestureCounter,
                    snapshot = null,
                    startRevision = state.revision,
                    startState = state,
                    startLease = leased,
                    sourcePath = state.sourcePath,
                    documentGeneration = leased.identity.generation,
                    baseContentToken = state.baseContentToken,
                    activeSelectionLayerId = state.activeSelectionLayerId,
                    previewTestHooks = SelectionPreviewPreparationGateway.captureHooksForOperation(),
                )
            selectionParamTransaction?.let { tx ->
                tx.pendingSnapshot = pendingSnapshot
            }
        }
        return true
    }

    internal fun startSelectionParamGesture(): Boolean {
        if (shuttingDown) return false
        prepareForMaskInteraction()
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return false
        return beginSelectionParamGesture()
    }

    internal fun markSelectionPreviewSucceeded(
        transaction: SelectionParamTransaction,
        token: Long,
        revision: Int,
        baseToken: String,
        activeId: String?,
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
        synchronized(selectionTransactionGate) { selectionParamTransaction }

    internal fun bindSelectionPreviewJob(
        transaction: SelectionParamTransaction,
        job: Job,
        revision: Int,
        baseToken: String,
        activeId: String?,
    ) {
        transaction.previewJob?.cancel()
        transaction.previewJob = job
        transaction.previewRevision = revision
        transaction.previewBaseToken = baseToken
        transaction.previewLayerId = activeId
        selectionLivePreviewJob = job
        job.invokeOnCompletion {
            if (transaction.previewJob === job) transaction.previewJob = null
            if (selectionLivePreviewJob === job) selectionLivePreviewJob = null
        }
        if (job.isCompleted) {
            if (transaction.previewJob === job) transaction.previewJob = null
            if (selectionLivePreviewJob === job) selectionLivePreviewJob = null
        }
    }

    internal fun finishSelectionParamGesture() {
        val transaction = selectionParamTransaction ?: return
        if (transactionFinishJob?.isActive == true && transaction.finished != true) return
        val job =
            viewModelScope.launch {
                var settled = false
                try {
                    transaction.previewJob?.join()
                    settleSelectionParamTransaction(transaction)
                    settled = true
                    transaction.finished = true
                } finally {
                    // A late finally may only rollback while this job still owns the active slot.
                    if (
                        !settled &&
                            selectionParamTransaction === transaction &&
                            transaction.finishJobRef === coroutineContext[Job]
                    ) {
                        restoreSelectionParamTransaction(transaction)
                    }
                }
            }
        transactionFinishJob = job
        transaction.finishJobRef = job
    }

    /** Test synchronization seam: joins the production settlement job without sampling state. */
    internal suspend fun awaitSelectionParamGestureFinishedForTest() {
        transactionFinishJob?.join()
    }

    /**
     * Settle the active transaction: commit on success + current + token match, otherwise restore.
     *
     * No-op when [transaction] is no longer the active one. Clears the active slot itself.
     */
    private suspend fun settleSelectionParamTransaction(transaction: SelectionParamTransaction) {
        if (selectionParamTransaction !== transaction) return
        transaction.awaitPendingSnapshot()
        if (transaction.settled) return
        transaction.settled = true
        val state = _uiState.value
        val finalToken = transaction.finalPreviewToken
        val finalRevision = transaction.finalPreviewRevision
        val finalBaseToken = transaction.finalPreviewBaseToken
        val finalLayerId = transaction.finalPreviewLayerId
        val previewValid =
            transaction.succeeded &&
                finalToken != null &&
                transaction.latestPreviewToken == finalToken &&
                finalRevision != null &&
                finalBaseToken != null &&
                finalLayerId != null &&
                transaction.previewJob?.isActive != true
        val stillCurrent =
            !shuttingDown &&
                state.baseContentToken == transaction.baseContentToken &&
                state.activeSelectionLayerId == transaction.activeSelectionLayerId
        if (
            previewValid &&
                stillCurrent &&
                state.revision == finalRevision &&
                state.baseContentToken == finalBaseToken &&
                state.activeSelectionLayerId == finalLayerId
        ) {
            if (transaction.historyPreparationFailed || transaction.snapshot == null) {
                restoreSelectionParamTransactionFields(transaction)
                transaction.snapshot?.let(::recycleHistorySnapshot)
                transaction.snapshot = null
                updateUiState { it.copy(message = "실행 취소 기록을 준비하지 못해 선택 편집을 적용하지 않았습니다.") }
                clearSelectionParamTransaction(transaction)
                return
            }
            if (!transaction.committed) {
                transaction.committed = true
                val snapshot = transaction.snapshot
                if (snapshot != null) commitUndoSnapshot(snapshot, clearRedo = true)
            }
            forceDraftSaveAsync()
            clearSelectionParamTransaction(transaction)
        } else if (stillCurrent && !transaction.hasOptimisticLiveParams(state)) {
            transaction.snapshot?.let { recycleHistorySnapshot(it) }
            clearSelectionParamTransaction(transaction)
        } else {
            restoreSelectionParamTransaction(transaction)
        }
    }

    private suspend fun restoreSelectionParamTransaction(transaction: SelectionParamTransaction) {
        if (selectionParamTransaction !== transaction) return
        transaction.awaitPendingSnapshot()
        if (transaction.committed) {
            clearSelectionParamTransaction(transaction)
            return
        }
        if (shuttingDown) {
            transaction.snapshot?.let { recycleHistorySnapshot(it) }
            clearSelectionParamTransaction(transaction)
            return
        }
        restoreSelectionParamTransactionFields(transaction)
        transaction.snapshot?.let(::recycleHistorySnapshot)
        transaction.snapshot = null
        clearSelectionParamTransaction(transaction)
    }

    /** Restores only fields owned by the optimistic selection-parameter transaction. */
    private fun restoreSelectionParamTransactionFields(transaction: SelectionParamTransaction) {
        val current = _uiState.value
        if (
            current.sourcePath != transaction.sourcePath ||
                current.baseContentToken != transaction.baseContentToken ||
                historyCoordinator.currentGeneration() != transaction.documentGeneration
        ) {
            return
        }
        updateUiStateAndRecycleReplaced { state ->
            if (
                state.sourcePath != transaction.sourcePath ||
                    state.baseContentToken != transaction.baseContentToken ||
                    historyCoordinator.currentGeneration() != transaction.documentGeneration
            ) {
                state
            } else {
                state.copy(
                    selectionLayers = transaction.startState.selectionLayers,
                    activeSelectionLayerId = transaction.startState.activeSelectionLayerId,
                    previewBitmap = transaction.startState.previewBitmap,
                    correctionEngineState = transaction.startState.correctionEngineState,
                    revision = transaction.startState.revision,
                    isBusy = false,
                )
            }
        }
    }

    private fun clearSelectionParamTransaction(transaction: SelectionParamTransaction) {
        val pending: PendingHistorySnapshot?
        val startLease: LeasedEditorSnapshot?
        synchronized(selectionTransactionGate) {
            if (selectionParamTransaction !== transaction) return
            selectionParamTransaction = null
            transaction.previewJob = null
            if (transactionFinishJob === transaction.finishJobRef) transactionFinishJob = null
            transaction.finishJobRef = null
            pending = transaction.pendingSnapshot
            transaction.pendingSnapshot = null
            startLease = transaction.startLease
            transaction.startLease = null
        }
        pending?.close()
        startLease?.close()
    }

    private fun settleSelectionParamTransactionForSupersession(
        cancelPreviewJob: Boolean = true,
    ) {
        val tx = selectionParamTransaction ?: return
        if (cancelPreviewJob) tx.previewJob?.cancel()
        selectionPreviewCounter += 1L
        if (selectionParamTransaction === tx && !tx.committed) {
            tx.settled = true
            val current = _uiState.value
            val sameDocument =
                !shuttingDown &&
                    current.sourcePath == tx.sourcePath &&
                    current.baseContentToken == tx.baseContentToken &&
                    historyCoordinator.currentGeneration() == tx.documentGeneration
            if (sameDocument) {
                restoreSelectionParamTransactionFields(tx)
            }
            tx.snapshot?.let(::recycleHistorySnapshot)
            tx.snapshot = null
            clearSelectionParamTransaction(tx)
        }
    }

    internal suspend fun recordSelectionPreviewFailure(
        transaction: SelectionParamTransaction,
        previewToken: Long,
        expectedRevision: Int,
        baseToken: String,
        activeId: String,
        kind: SelectionPreviewFailureKind,
        message: String,
        failure: Throwable?,
    ) {
        if (kind == SelectionPreviewFailureKind.Cancelled) return
        withContext(Dispatchers.Main) {
            if (
                kind == SelectionPreviewFailureKind.StaleOrSuperseded ||
                    !isSelectionPreviewCurrent(
                        transaction,
                        previewToken,
                        expectedRevision,
                        baseToken,
                        activeId,
                    )
            ) {
                return@withContext
            }
            val before = _uiState.value
            if (
                before.baseContentToken != baseToken ||
                    before.revision != expectedRevision ||
                    before.activeSelectionLayerId != activeId ||
                    historyCoordinator.currentGeneration() != transaction.documentGeneration
            ) {
                return@withContext
            }
            settleSelectionParamTransactionForSupersession(cancelPreviewJob = false)
            updateUiStateAndRecycleReplaced { current ->
                if (
                    current.baseContentToken != baseToken ||
                        historyCoordinator.currentGeneration() != transaction.documentGeneration
                ) {
                    current
                } else {
                    val failedRender =
                        if (kind == SelectionPreviewFailureKind.RenderFailure) {
                            (failure as? RenderFailedException)?.failure?.let { renderFailure ->
                                current.correctionEngineState.withFailedRender(
                                    current.correctionEngineState.documentEngine,
                                    renderFailure,
                                )
                            }
                        } else null
                    current.copy(
                        isBusy = false,
                        correctionEngineState = failedRender ?: current.correctionEngineState,
                        message =
                            when (kind) {
                                SelectionPreviewFailureKind.AllocationFailure ->
                                    "\uBA54\uBAA8\uB9AC\uAC00 \uBD80\uC871\uD558\uC5EC \uC120\uD0DD \uB9C8\uC2A4\uD06C \uBBF8\uB9AC\uBCF4\uAE30\uB97C \uC900\uBE44\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4."
                                SelectionPreviewFailureKind.RenderFailure ->
                                    "\uC120\uD0DD \uB9C8\uC2A4\uD06C \uBBF8\uB9AC\uBCF4\uAE30 \uB80C\uB354\uB9C1\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4."
                                else ->
                                    "\uC120\uD0DD \uB9C8\uC2A4\uD06C \uBBF8\uB9AC\uBCF4\uAE30\uB97C \uC801\uC6A9\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4."
                            },
                    )
                }
            }
        }
    }

    internal fun isBusyOwnedByMaskSupersedable(): Boolean {
        val state = _uiState.value
        if (
            activeParamRenderRevision != null &&
                activeParamRenderRevision == state.revision &&
                renderJob?.isActive == true
        )
            return true
        val transaction = selectionParamTransaction
        if (transaction != null && transaction.previewJob?.isActive == true) {
            if (
                transaction.previewRevision != null &&
                    transaction.previewRevision == state.revision &&
                    transaction.previewBaseToken == state.baseContentToken &&
                    transaction.previewLayerId == state.activeSelectionLayerId
            ) {
                return true
            }
        }
        return false
    }

    internal fun negateBrushStrokeDuringShutdownIfPresent() {
        if (brushTransactionState != BrushTransactionState.Idle) cancelBrushStroke()
    }

    internal fun invalidateSelectionPreview() {
        prepareForMaskInteraction()
    }

    internal fun beginBrushStroke(): Boolean {
        if (shuttingDown) return false
        if (brushTransactionState != BrushTransactionState.Idle) return true
        prepareForMaskInteraction()
        if (historyCoordinator.flags().busy) return false
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return false
        val state = _uiState.value
        val layerId = state.activeSelectionLayerId ?: return false
        val layer = state.selectionLayers.firstOrNull { it.id == layerId } ?: return false
        if (state.params != lastSuccessfullyRenderedParams || activeParamRenderRevision != null)
            return false
        // Pre-capture bitmap REFERENCES for the rollback snapshot before installing the owned
        // mask. The painter will mutate the owned mask; the references captured here are the
        // originals and remain pristine for rollback. The bitmap copy work runs on Default.
        val leased = acquireEditorSnapshot("brushStroke") ?: return false
        val reservation =
            selectionMaskOwnership.reserve(
                owner = "brush:${state.revision}:${layerId}",
                bytes = BitmapMemoryBudget.bytes(layer.bitmap.width, layer.bitmap.height, Bitmap.Config.ARGB_8888),
            ) ?: run {
                leased.close()
                return false
            }
        val strokeId = ++brushStrokeCounter
        val producerLease = leased.retain("brush-preparation:$strokeId")
        if (producerLease == null) {
            reservation.close()
            leased.close()
            updateUiState { it.copy(message = "브러시 준비에 실패했습니다.") }
            return false
        }
        brushStartSnapshot = leased
        brushMaskReservation = reservation
        /*
            runCatching { layer.bitmap.copyOrThrow(Bitmap.Config.ARGB_8888, true) }
                .getOrElse { failure ->
                    if (failure is BitmapAllocationRejectedException) {
                        updateUiStateAndRecycleReplaced {
                            it.copy(message = "메모리가 부족하여 브러시 작업을 시작하지 못했습니다.")
                        }
                    }
                    return false
                }
        */
        // Tag the owned working mask with a named owner so the selection-mask ledger can
        // identify brush-strokes in flight and defer recycling until the stroke ends.
        brushLayerId = layerId
        brushBaseToken = state.baseContentToken
        brushRevision = state.revision
        brushChanged = false
        brushEpochCounter = 0L
        _brushPreviewEpoch.value = 0L
        brushLastX = Float.NaN
        brushLastY = Float.NaN
        pendingBrushPoints.clear()
        brushTransactionState = BrushTransactionState.Preparing
        val capturedBaseContentToken = state.baseContentToken
        val capturedRevision = state.revision
        val transactionIdentity =
            BrushTransactionIdentity(
                strokeId = strokeId,
                documentGeneration = historyCoordinator.currentGeneration(),
                sourcePath = state.sourcePath,
                baseContentToken = capturedBaseContentToken,
                startRevision = capturedRevision,
                activeLayerId = layerId,
            )
        brushIdentity = transactionIdentity
        brushWorkingMask = null
        /* updateUiState { current ->
            current.copy(
                selectionLayers =
                    current.selectionLayers.map { item ->
                        if (item.id == layerId) item.copy(bitmap = ownedMask) else item
                    }
            )
        } */
        // Defer the full Exact history snapshot bitmap copies to a worker — copying
        // previewBitmap + originalPreviewBitmap + every layer bitmap on Main blocks the
        // gesture thread. Finish/cancel await this job before touching brushingSnapshot.
        brushSnapshotJob =
            viewModelScope.launch(Dispatchers.Default) {
                var ownedMask: Bitmap? = null
                var snapshot: EditorHistorySnapshot? = null
                try {
                    ownedMask = layer.bitmap.copyOrThrow(Bitmap.Config.ARGB_8888, true)
                    normalizeBrushMaskStorage(checkNotNull(ownedMask))
                    snapshot =
                        captureHistorySnapshotFromRefs(
                            producerLease.state,
                            producerLease.previewBitmap,
                            producerLease.originalPreviewBitmap,
                            producerLease.selectionLayers.map { it.id to it.bitmap },
                            documentGeneration = producerLease.identity.generation,
                        )
                    withContext(Dispatchers.Main) {
                        val currentIdentity = brushIdentity?.strokeId == strokeId
                        val canAdopt =
                            currentIdentity &&
                                snapshot != null &&
                                brushTransactionState in
                                    setOf(BrushTransactionState.Preparing, BrushTransactionState.Finishing) &&
                                brushLayerId == layerId &&
                                brushBaseToken == capturedBaseContentToken &&
                                brushRevision == capturedRevision &&
                                _uiState.value.revision == capturedRevision &&
                                _uiState.value.baseContentToken == capturedBaseContentToken
                        if (canAdopt) {
                            brushOwnedMaskHandle = acquireMaskOwner(ownedMask, MaskOwnerKind.BrushWorkingCopy)
                            if (brushOwnedMaskHandle == null) {
                                snapshot?.recycleBitmaps()
                                snapshot = null
                                ownedMask?.takeIf { !it.isRecycled }?.recycle()
                                ownedMask = null
                            } else {
                                val adoptedMask = ownedMask!!
                                updateUiState { current ->
                                    current.copy(
                                        selectionLayers = current.selectionLayers.map { item ->
                                            if (item.id == layerId) item.copy(bitmap = adoptedMask) else item
                                        }
                                    )
                                }
                                brushWorkingMask = adoptedMask
                                ownedMask = null
                                brushIdentity = transactionIdentity.copy(workingMask = adoptedMask)
                                brushingSnapshot = snapshot
                                snapshot = null
                                if (brushTransactionState == BrushTransactionState.Preparing) {
                                    brushTransactionState = BrushTransactionState.Active
                                }
                                val queued = pendingBrushPoints.toList()
                                pendingBrushPoints.clear()
                                queued.forEach { (x, y) -> paintActiveSelectionAt(x, y) }
                                brushStartSnapshot?.close()
                                brushStartSnapshot = null
                            }
                        }
                        snapshot?.recycleBitmaps()
                        ownedMask?.takeIf { !it.isRecycled }?.recycle()
                        if (currentIdentity && !canAdopt) settleBrushPreparationFailure(strokeId)
                        if (brushIdentity?.strokeId == strokeId) brushSnapshotJob = null
                    }
                } catch (cancelled: CancellationException) {
                    val abandonedSnapshot = snapshot
                    val abandonedMask = ownedMask
                    snapshot = null
                    ownedMask = null
                    abandonedSnapshot?.recycleBitmaps()
                    abandonedMask?.takeIf { !it.isRecycled }?.recycle()
                    withContext(Dispatchers.Main) {
                        if (brushIdentity?.strokeId == strokeId) settleBrushPreparationFailure(strokeId)
                    }
                    throw cancelled
                } catch (_: Throwable) {
                    val abandonedSnapshot = snapshot
                    val abandonedMask = ownedMask
                    snapshot = null
                    ownedMask = null
                    abandonedSnapshot?.recycleBitmaps()
                    abandonedMask?.takeIf { !it.isRecycled }?.recycle()
                    withContext(Dispatchers.Main) {
                        if (brushIdentity?.strokeId == strokeId) settleBrushPreparationFailure(strokeId)
                    }
                } finally {
                    producerLease.close()
                }
            }
        return true
    }

    /**
     * Capture an Exact history snapshot using pre-captured bitmap references (taken before
     * the state was mutated). Used by [beginBrushStroke] so the rollback target reflects the
     * pre-paint bitmaps. Runs bitmap copies on the calling dispatcher (typically Default).
     */
    private suspend fun captureHistorySnapshotFromRefs(
        state: EditorUiState,
        previewRef: Bitmap?,
        originalRef: Bitmap?,
        layerRefs: List<Pair<String, Bitmap>>,
        storage: HistorySnapshotStorage = HistorySnapshotStorage.Exact,
        documentGeneration: String? = null,
    ): EditorHistorySnapshot? {
        val effectiveStorage =
            if (storage == HistorySnapshotStorage.MetadataOnly && state.supportsMetadataOnlyHistory()) {
                storage
            } else {
                HistorySnapshotStorage.Exact
            }
        val required =
            if (effectiveStorage == HistorySnapshotStorage.Exact) {
                state.historyBitmapBytesFor(previewRef, originalRef, layerRefs)
            } else {
                0L
            }
        if (!historyCoordinator.canCapture(required)) {
            withContext(Dispatchers.Main) {
                updateUiStateAndRecycleReplaced {
                    it.copy(message = "메모리가 부족하여 되돌리기 기록을 저장하지 못했습니다. 편집은 계속할 수 있습니다.")
                }
            }
            return null
        }
        val reservations = ArrayList<MaskReservation>(state.selectionLayers.size)
        if (effectiveStorage == HistorySnapshotStorage.Exact) {
            val reservedBitmaps = identityBitmapSet()
            for (layer in state.selectionLayers) {
                if (!reservedBitmaps.add(layer.bitmap)) continue
                val reservation =
                    selectionMaskOwnership.reserve(
                        owner = "history:${documentGeneration ?: historyCoordinator.currentGeneration()}:${layer.id}",
                        bytes = BitmapMemoryBudget.bytes(layer.bitmap),
                        documentLayerDelta = 0,
                    )
                if (reservation == null) {
                    reservations.forEach(MaskReservation::close)
                    return null
                }
                reservations += reservation
            }
        }
        val snapshot =
            try {
                state.toHistorySnapshotFromRefs(
                    effectiveStorage,
                    previewRef,
                    originalRef,
                    layerRefs,
                ).also {
                    val generation = documentGeneration ?: historyCoordinator.currentGeneration()
                    it.coordinatorGeneration = generation
                    it.attachLocalDiagnostics(trackerSession, generation)
                }
            } catch (cancelled: CancellationException) {
                reservations.forEach(MaskReservation::close)
                throw cancelled
            } catch (_: Throwable) {
                reservations.forEach(MaskReservation::close)
                return null
            }
        snapshot.maskReservations += reservations
        return snapshot
    }

    /**
     * Captures the exact gesture-start references and performs all full-resolution history
     * copies on Default. Callers must await this before adoption and cancel it on abort.
     */
    internal fun prepareHistorySnapshot(
        tag: String,
        storage: HistorySnapshotStorage = HistorySnapshotStorage.Exact,
    ): PendingHistorySnapshot? {
        val leased = acquireEditorSnapshot(tag) ?: return null
        val pending = prepareHistorySnapshot(tag, leased, storage)
        leased.close()
        return pending
    }

    internal fun prepareHistorySnapshot(
        tag: String,
        leased: LeasedEditorSnapshot,
        storage: HistorySnapshotStorage = HistorySnapshotStorage.Exact,
    ): PendingHistorySnapshot {
        val pending = PendingHistorySnapshot(CompletableDeferred())
        val producerLease = leased.retain("history-producer:$tag")
        if (producerLease == null) {
            pending.complete(null)
            return pending
        }
        val producer = viewModelScope.launch(Dispatchers.Default) {
            try {
                pending.complete(
                    captureHistorySnapshotForLeasedSnapshot(producerLease, storage)
                )
            } catch (cancelled: CancellationException) {
                pending.producerCancelled()
                throw cancelled
            } catch (failure: Throwable) {
                pending.fail(failure)
            } finally {
                pending.producerFinished()
                producerLease.close()
            }
        }
        pending.attachProducer(producer)
        return pending
    }

    internal suspend fun captureHistorySnapshotForLeasedSnapshot(
        leased: LeasedEditorSnapshot,
        storage: HistorySnapshotStorage = HistorySnapshotStorage.Exact,
    ): EditorHistorySnapshot? =
        captureHistorySnapshotFromRefs(
            leased.state,
            leased.previewBitmap,
            leased.originalPreviewBitmap,
            leased.selectionLayers.map { it.id to it.bitmap },
            storage,
            leased.identity.generation,
        )

    internal fun markBrushChanged(changed: Boolean) {
        brushChanged = brushChanged || changed
    }

    internal fun isBrushStrokeCurrent(layerId: String?): Boolean {
        val identity = brushIdentity ?: return false
        val workingMask = identity.workingMask ?: return false
        val state = _uiState.value
        val currentLayer = state.selectionLayers.firstOrNull { it.id == identity.activeLayerId }
        return layerId == identity.activeLayerId &&
            state.sourcePath == identity.sourcePath &&
            state.baseContentToken == identity.baseContentToken &&
            state.revision == identity.startRevision &&
            state.activeSelectionLayerId == identity.activeLayerId &&
            currentLayer?.bitmap === workingMask &&
            historyCoordinator.currentGeneration() == identity.documentGeneration
    }

    internal fun hasActiveBrushStroke(): Boolean = brushTransactionState != BrushTransactionState.Idle

    internal fun isBrushPreparing(): Boolean = brushTransactionState == BrushTransactionState.Preparing

    internal fun queueBrushPoint(x: Float, y: Float) {
        if (brushTransactionState != BrushTransactionState.Preparing) return
        if (pendingBrushPoints.size >= 128) pendingBrushPoints.removeFirst()
        pendingBrushPoints.addLast(x to y)
    }

    internal fun nextBrushPreviewEpoch(): Long {
        val epoch = ++brushEpochCounter
        _brushPreviewEpoch.value = epoch
        return epoch
    }

    private fun clearBrushTransaction(strokeId: Long) {
        if (brushIdentity?.strokeId != strokeId) return
        brushOwnedMaskHandle?.close()
        brushOwnedMaskHandle = null
        brushMaskReservation?.close()
        brushMaskReservation = null
        brushStartSnapshot?.close()
        brushStartSnapshot = null
        brushingSnapshot = null
        brushLayerId = null
        brushBaseToken = null
        brushChanged = false
        brushLastX = Float.NaN
        brushLastY = Float.NaN
        pendingBrushPoints.clear()
        brushIdentity = null
        brushWorkingMask = null
        brushSnapshotJob = null
        brushTransactionState = BrushTransactionState.Idle
    }

    private fun settleBrushPreparationFailure(strokeId: Long) {
        if (brushIdentity?.strokeId != strokeId) return
        clearBrushTransaction(strokeId)
        if (!shuttingDown) updateUiState { it.copy(message = "브러시 준비에 실패했습니다.") }
    }

    internal fun finishBrushStroke() {
        if (brushTransactionState == BrushTransactionState.Idle ||
            brushTransactionState == BrushTransactionState.Finishing ||
            brushTransactionState == BrushTransactionState.Cancelling
        ) return
        val strokeId = brushIdentity?.strokeId ?: return
        brushTransactionState = BrushTransactionState.Finishing
        settleBrushStroke(strokeId)
    }

    internal fun cancelBrushStroke() {
        if (brushTransactionState == BrushTransactionState.Idle ||
            brushTransactionState == BrushTransactionState.Cancelling
        ) return
        val strokeId = brushIdentity?.strokeId ?: return
        brushTransactionState = BrushTransactionState.Cancelling
        brushSnapshotJob?.cancel()
        brushSnapshotJob = null
        brushSettlementJob?.cancel()
        brushSettlementJob = null
        val snapshot = brushingSnapshot
        val identity = brushIdentity
        val transactionCurrent =
            identity != null &&
                brushWorkingMask != null &&
                isBrushStrokeCurrent(identity.activeLayerId)
        if (snapshot != null && transactionCurrent) {
            restoreSnapshotWithoutHistory(snapshot, preserveRevision = identity?.startRevision)
            brushingSnapshot = null
        } else if (snapshot != null) {
            recycleHistorySnapshot(snapshot)
            brushingSnapshot = null
        }
        clearBrushTransaction(strokeId)
    }

    private fun settleBrushStroke(strokeId: Long) {
        brushSettlementJob?.cancel()
        val preparation = brushSnapshotJob
        brushSettlementJob = viewModelScope.launch {
            try {
                preparation?.join()
                withContext(Dispatchers.Main) {
                    if (brushIdentity?.strokeId != strokeId) return@withContext
                    val snapshot = brushingSnapshot
                    val identity = brushIdentity
                    val transactionCurrent =
                        identity != null &&
                            brushWorkingMask != null &&
                            isBrushStrokeCurrent(identity.activeLayerId)
                    val changed = brushChanged
                    if (snapshot == null) {
                        clearBrushTransaction(strokeId)
                        return@withContext
                    }
                    if (!transactionCurrent) {
                        recycleHistorySnapshot(snapshot)
                        brushingSnapshot = null
                        clearBrushTransaction(strokeId)
                        return@withContext
                    }
                    if (!changed) {
                        restoreSnapshotWithoutHistory(
                            snapshot,
                            preserveRevision = identity?.startRevision,
                        )
                        brushingSnapshot = null
                        clearBrushTransaction(strokeId)
                        return@withContext
                    }
                    updateUiState {
                        it.copy(
                            revision = (identity?.startRevision ?: it.revision) + 1,
                            isBusy = false,
                        )
                    }
                    if (commitUndoSnapshot(snapshot, clearRedo = true)) {
                        brushingSnapshot = null
                        historyIoJob?.join()
                        forceDraftSaveAsync()
                    } else {
                        recycleHistorySnapshot(snapshot)
                        brushingSnapshot = null
                    }
                    clearBrushTransaction(strokeId)
                }
            } finally {
                if (brushIdentity?.strokeId == strokeId) {
                    withContext(NonCancellable + Dispatchers.Main) { clearBrushTransaction(strokeId) }
                }
            }
        }
    }

    private fun restoreSnapshotWithoutHistory(
        snapshot: EditorHistorySnapshot,
        retainedFailure: RenderResult.Failure? = null,
        preserveRevision: Int? = null,
    ) {
        val metadataOnly = snapshot.storage == HistorySnapshotStorage.MetadataOnly
        updateUiState { current ->
            current.copy(
                params = snapshot.params,
                correctionEngineState =
                    current.correctionEngineState.copy(
                        documentEngine = snapshot.correctionEngine,
                        pendingEngine = null,
                        visiblePreview = snapshot.toVisiblePreviewState(),
                        lastRenderFailure =
                            retainedFailure?.let {
                                current.correctionEngineState
                                    .withFailedRender(snapshot.correctionEngine, it)
                                    .lastRenderFailure
                            },
                    ),
                noiseEngine = snapshot.noiseEngine,
                detailEngine = snapshot.detailEngine,
                toneEngine = snapshot.toneEngine,
                hazeEngine = snapshot.hazeEngine,
                baseBitmapDirty = snapshot.baseBitmapDirty,
                baseContentToken = snapshot.baseContentToken,
                previewBitmap = if (metadataOnly) current.previewBitmap else snapshot.previewBitmap,
                originalPreviewBitmap =
                    if (metadataOnly) current.originalPreviewBitmap
                    else snapshot.originalPreviewBitmap,
                presetLook = snapshot.presetLook,
                cropState = snapshot.cropState,
                selectionLayers =
                    if (metadataOnly) current.selectionLayers else snapshot.selectionLayers,
                activeSelectionLayerId =
                    if (metadataOnly) current.activeSelectionLayerId
                    else snapshot.activeSelectionLayerId,
                selectionPaintSettings =
                    if (metadataOnly) current.selectionPaintSettings
                    else snapshot.selectionPaintSettings,
                showSelectionOverlay =
                    if (metadataOnly) current.showSelectionOverlay
                    else snapshot.showSelectionOverlay,
                activeQuickEffects = snapshot.activeQuickEffects,
                flareGuardRuntimeStatus = snapshot.flareGuardRuntimeStatus,
                algorithmContracts = snapshot.algorithmContracts,
                baseProvenance = snapshot.baseProvenance,
                isBusy = false,
                revision = preserveRevision ?: current.revision + 1,
            )
        }
        if (!metadataOnly) snapshot.releaseBitmapOwnership()
    }

    private fun invalidateManagedEdits() {
        invalidateComparison()
        managedEdits.invalidate()
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
        return isDraftPayloadDocumentCurrent(payload) &&
            payload.expectedPointerGenerationId == draftPointerBaseline
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
        return result.epoch == draftOperationEpoch &&
            draftResultMatchesState(result, _uiState.value)
    }

    private fun draftResultMatchesState(result: DraftSaveResult, current: EditorUiState): Boolean {
        return result.expectedPointerGenerationId == draftPointerBaseline &&
            draftResultMatchesDocumentState(result, current)
    }

    private fun draftResultMatchesDocumentState(
        result: DraftSaveResult,
        current: EditorUiState,
    ): Boolean {
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

    private fun claimAsyncBusyOwner(
        operationType: String,
        identity: DocumentIdentity,
    ): AsyncBusyOwner? {
        synchronized(this) {
            if (asyncBusyOwner != null || shuttingDown) return null
            val owner =
                AsyncBusyOwner(
                    token = ++asyncBusyCounter,
                    operationType = operationType,
                    documentGeneration = identity.generation,
                    sourcePath = identity.sourcePath,
                    baseContentToken = identity.baseContentToken,
                    startRevision = identity.revision,
                )
            asyncBusyOwner = owner
            updateUiState { it.copy(isBusy = true) }
            return owner
        }
    }

    private fun releaseAsyncBusyOwner(owner: AsyncBusyOwner) {
        synchronized(this) {
            if (asyncBusyOwner !== owner) return
            asyncBusyOwner = null
            if (!shuttingDown) updateUiState { it.copy(isBusy = false) }
        }
    }

    /** Applies a selection-layer edit from one leased start state. */
    internal fun applyAsyncSelectionLayerEdit(
        layerId: String,
        tag: String,
        message: String,
        delete: Boolean = false,
        invert: Boolean = false,
        clear: Boolean = false,
    ): Boolean {
        val leased = acquireEditorSnapshot(tag) ?: return false
        val layer = leased.selectionLayers.firstOrNull { it.id == layerId }
        if (layer == null) {
            leased.close()
            return false
        }
        val busyOwner = claimAsyncBusyOwner(tag, leased.identity)
        if (busyOwner == null) {
            leased.close()
            return false
        }
        invalidateComparison()
        // Publish the action slot before the worker starts.  Otherwise a
        // second edit can capture state while this exact before-snapshot is
        // still being prepared.
        viewModelScope.launch(Dispatchers.Default) {
            var before: EditorHistorySnapshot? = null
            var replacement: Bitmap? = null
            var replacementReservation: MaskReservation? = null
            try {
                before = captureHistorySnapshotFromRefs(
                    leased.state,
                    leased.previewBitmap,
                    leased.originalPreviewBitmap,
                    leased.selectionLayers.map { it.id to it.bitmap },
                )
                if (before == null) return@launch
                if (clear) {
                    replacementReservation =
                        reserveSelectionMaskCopy(
                            owner = "clear-mask:${leased.identity.generation}:$layerId",
                            source = layer.bitmap,
                            config = Bitmap.Config.ARGB_8888,
                        ) ?: throw BitmapAllocationRejectedException(BitmapMemoryBudget.bytes(layer.bitmap))
                    replacement = layer.bitmap.copyOrThrow(Bitmap.Config.ARGB_8888, true)
                    replacement?.eraseColor(0xFF000000.toInt())
                }
                withContext(Dispatchers.Main) {
                    val current = _uiState.value
                    val currentLayer = current.selectionLayers.firstOrNull { it.id == layerId }
                    val currentIdentity =
                        current.sourcePath == leased.identity.sourcePath &&
                            current.baseContentToken == leased.identity.baseContentToken &&
                            current.revision == leased.identity.revision &&
                            historyCoordinator.currentGeneration() == leased.identity.generation &&
                            currentLayer?.bitmap === layer.bitmap
                    if (currentIdentity) {
                        val nextLayers =
                            when {
                                delete -> current.selectionLayers.filterNot { it.id == layerId }
                                else -> current.selectionLayers.map { item ->
                                    if (item.id != layerId) item
                                    else item.copy(
                                        bitmap = replacement ?: item.bitmap,
                                        inverted = if (invert) !item.inverted else item.inverted,
                                    )
                                }
                            }
                        updateUiStateAndRecycleReplaced {
                            it.copy(
                                selectionLayers = nextLayers,
                                activeSelectionLayerId =
                                    if (delete && it.activeSelectionLayerId == layerId) {
                                        nextLayers.lastOrNull()?.id
                                    } else it.activeSelectionLayerId,
                                revision = it.revision + 1,
                                message = message,
                            )
                        }
                        replacement = null
                        if (commitUndoSnapshot(before!!, clearRedo = true)) before = null
                        forceDraftSaveAsync()
                    }
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) {
                    if (!shuttingDown) updateUiState { it.copy(message = "선택 마스크 편집에 실패했습니다.") }
                }
            } finally {
                replacement?.takeIf { !it.isRecycled }?.recycle()
                replacementReservation?.close()
                before?.let(::recycleHistorySnapshot)
                withContext(NonCancellable + Dispatchers.Main) {
                    releaseAsyncBusyOwner(busyOwner)
                }
                leased.close()
            }
        }
        return true
    }

    internal fun applyAsyncMetadataEdit(
        tag: String,
        transform: (EditorUiState) -> EditorUiState,
    ): Boolean {
        val leased = acquireEditorSnapshot(tag) ?: return false
        val busyOwner = claimAsyncBusyOwner(tag, leased.identity)
        if (busyOwner == null) {
            leased.close()
            return false
        }
        val startIdentity = leased.identity
        val pending = prepareHistorySnapshot(tag, leased)
        viewModelScope.launch(Dispatchers.Default) {
            var snapshot: EditorHistorySnapshot? = null
            try {
                snapshot = pending.await()
                withContext(Dispatchers.Main) {
                    val current = _uiState.value
                    val currentIdentity =
                        current.sourcePath == startIdentity.sourcePath &&
                            current.baseContentToken == startIdentity.baseContentToken &&
                            current.revision == startIdentity.revision &&
                            historyCoordinator.currentGeneration() == startIdentity.generation
                    if (snapshot != null && currentIdentity) {
                        updateUiStateAndRecycleReplaced { state ->
                            transform(state).copy(revision = state.revision + 1)
                        }
                        settleAdoptedEditHistory(snapshot)
                        snapshot = null
                        forceDraftSaveAsync()
                    }
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } finally {
                snapshot?.let(::recycleHistorySnapshot)
                pending.close()
                withContext(NonCancellable + Dispatchers.Main) {
                    releaseAsyncBusyOwner(busyOwner)
                }
                leased.close()
            }
        }
        return true
    }

    internal fun captureCurrentHistorySnapshot(
        storage: HistorySnapshotStorage = HistorySnapshotStorage.Exact
    ): EditorHistorySnapshot? {
        val state = uiState.value
        val effectiveStorage =
            if (
                storage == HistorySnapshotStorage.MetadataOnly &&
                    state.supportsMetadataOnlyHistory()
            )
                storage
            else HistorySnapshotStorage.Exact
        val required =
            if (effectiveStorage == HistorySnapshotStorage.Exact) state.historyBitmapBytes() else 0L
        if (!historyCoordinator.canCapture(required)) {
            updateUiStateAndRecycleReplaced {
                it.copy(message = "메모리가 부족하여 되돌리기 기록을 저장하지 못했습니다. 편집은 계속할 수 있습니다.")
            }
            return null
        }
        return runCatching {
                state.toHistorySnapshot(effectiveStorage).also {
                    val generation = historyCoordinator.currentGeneration()
                    it.coordinatorGeneration = generation
                    it.attachLocalDiagnostics(trackerSession, generation)
                }
            }
            .getOrNull()
            ?.takeIf { snapshot -> admitHistoryMaskReservations(snapshot, "syncHistory") }
    }

    private fun admitHistoryMaskReservations(
        snapshot: EditorHistorySnapshot,
        ownerPrefix: String,
    ): Boolean {
        val reservations = ArrayList<MaskReservation>(snapshot.selectionLayers.size)
        val reservedBitmaps = identityBitmapSet()
        snapshot.selectionLayers.forEach { layer ->
            if (!reservedBitmaps.add(layer.bitmap)) return@forEach
            val reservation =
                selectionMaskOwnership.reserve(
                    owner = "$ownerPrefix:${snapshot.coordinatorGeneration ?: "unknown"}:${layer.id}",
                    bytes = BitmapMemoryBudget.bytes(layer.bitmap),
                    documentLayerDelta = 0,
                )
            if (reservation == null) {
                reservations.forEach(MaskReservation::close)
                snapshot.recycleBitmaps()
                return false
            }
            reservations += reservation
        }
        snapshot.maskReservations += reservations
        return true
    }

    internal fun commitUndoSnapshot(snapshot: EditorHistorySnapshot, clearRedo: Boolean): Boolean {
        if (shuttingDown) {
            snapshot.recycleBitmaps()
            return false
        }
        val reserve = _uiState.value.historyBitmapBytes()
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        snapshot.claimCoordinatorOwnership()
        val job =
            viewModelScope.launch {
                started.set(true)
                val result = historyCoordinator.admitAdoptedSnapshot(snapshot, clearRedo, reserve)
                if (historyIoJob === currentCoroutineContext()[Job]) historyIoJob = null
                updateHistoryFlags()
                if (!result.retained) {
                    updateUiStateAndRecycleReplaced {
                        it.copy(message = "메모리가 부족하여 되돌리기 기록을 저장하지 못했습니다. 편집은 계속할 수 있습니다.")
                    }
                } else if (result.movedToStorage) {
                    updateUiStateAndRecycleReplaced { it.copy(message = "오래된 편집 기록을 저장소로 옮겼습니다.") }
                }
            }
        historyIoJob = job
        job.invokeOnCompletion {
            if (!started.get()) snapshot.recycleBitmaps()
            if (historyIoJob === job) historyIoJob = null
        }
        if (job.isCompleted && historyIoJob === job) historyIoJob = null
        return true
    }

    internal fun recycleHistorySnapshot(snapshot: EditorHistorySnapshot) {
        snapshot.recycleBitmaps()
    }

    fun persistDraftSnapshot() {
        forceDraftSaveAsync()
    }

    suspend fun persistDraftSnapshotNow(): Boolean {
        if (shuttingDown) return false
        // This is the app-level save-and-leave boundary. Settle the visible
        // parameter transaction first, then await the filesystem commit. Android
        // teardown itself cannot provide this suspend guarantee.
        settleParameterTransaction(SettlementReason.SaveAndLeave)
        if (shuttingDown) return false
        val (epoch, previous) = beginDraftSaveOperation()
        val testSeam = DraftSaveTestSeam.capture()
        previous?.join()
        val currentJob = currentCoroutineContext()[Job]
        if (currentJob != null) draftSaveJob = currentJob
        return try {
            persistDraftSnapshotInternal(epoch, SettlementReason.ManualDraftSave, testSeam)
        } finally {
            if (draftSaveJob === currentJob) draftSaveJob = null
        }
    }

    internal fun scheduleDraftAutosave(delayMs: Long = 2000L) {
        if (shuttingDown) return
        val (epoch, _) = beginDraftSaveOperation()
        val testSeam = DraftSaveTestSeam.capture()
        val job =
            viewModelScope.launch {
                try {
                    delay(delayMs)
                    persistDraftSnapshotInternal(epoch, SettlementReason.Autosave, testSeam)
                } finally {
                    if (draftSaveJob === currentCoroutineContext()[Job]) draftSaveJob = null
                }
            }
        draftSaveJob = job
        if (job.isCompleted && draftSaveJob === job) draftSaveJob = null
    }

    private fun forceDraftSaveAsync() {
        if (shuttingDown) return
        val (epoch, _) = beginDraftSaveOperation()
        val testSeam = DraftSaveTestSeam.capture()
        val job =
            viewModelScope.launch {
                try {
                    persistDraftSnapshotInternal(epoch, SettlementReason.ManualDraftSave, testSeam)
                } finally {
                    if (draftSaveJob === currentCoroutineContext()[Job]) draftSaveJob = null
                }
            }
        draftSaveJob = job
        job.invokeOnCompletion { if (draftSaveJob === job) draftSaveJob = null }
        if (job.isCompleted && draftSaveJob === job) draftSaveJob = null
    }

    private suspend fun persistDraftSnapshotInternal(
        draftEpoch: Long,
        reason: SettlementReason,
        testSeam: DraftSaveTestSeam?,
    ): Boolean {
        val context = getApplication<Application>()
        val expectedPointer =
            withContext(Dispatchers.IO) {
                draftSaveMutex.withLock {
                    if (draftEpoch != draftOperationEpoch || shuttingDown) return@withLock null
                    val diskPointer = currentDraftGenerationId(context)
                    if (diskPointer != draftPointerBaseline) return@withLock null
                    DraftPointerSnapshot(diskPointer)
                }
            } ?: return false
        settleParameterTransaction(reason)
        val draftSnapshot = acquireEditorSnapshot("draftSave") ?: return false
        val draftState = draftSnapshot.state
        ParameterLifecycleTestHook.notifyDraftCaptureBegan(draftEpoch)
        testSeam?.awaitRelease()
        val draftTracker =
            beginMemoryTracking(
                "persistDraftSnapshot",
                snapshotState = "copying",
                transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
            )
        val payload =
            try {
                withContext(Dispatchers.Default) {
                    createDraftSavePayload(
                        context,
                        draftState,
                        draftEpoch,
                        expectedPointer.generationId,
                    )
                }
            } catch (ce: CancellationException) {
                draftTracker?.end()
                throw ce
            } catch (t: Throwable) {
                draftTracker?.end()
                logDraftSaveFailure(t)
                updateUiStateAndRecycleReplaced {
                    if (
                        draftEpoch == draftOperationEpoch &&
                            it.revision == draftState.revision &&
                            it.baseContentToken == draftState.baseContentToken &&
                            sameCanonicalPath(it.sourcePath, draftState.sourcePath)
                    )
                        it.copy(
                            message =
                                "\uc784\uc2dc \uc800\uc7a5\uc5d0 \uc2e4\ud328\ud588\uc2b5\ub2c8\ub2e4. \ud3b8\uc9d1\uc740 \uacc4\uc18d\ud560 \uc218 \uc788\uc2b5\ub2c8\ub2e4."
                        )
                    else it
                }
                return false
            } finally {
                draftSnapshot.close()
            }
        payload.dirtyBitmapCopy?.let { draftTracker?.track(it, "persistDraftSnapshot:dirtyCopy") }
        payload.editedPreviewCopy?.let {
            draftTracker?.track(it, "persistDraftSnapshot:previewCopy")
        }
        payload.selectionLayers.forEach {
            draftTracker?.track(it.bitmap, "persistDraftSnapshot:selectionCopy:${it.id}")
        }
        val owningJob = currentCoroutineContext()[Job]
        var committed: DraftSaveResult? = null
        var settled = false
        try {
            withContext(Dispatchers.IO) {
                draftSaveMutex.withLock {
                    committed =
                        saveDraftSnapshot(context, payload) {
                            owningJob?.isActive != false &&
                                !shuttingDown &&
                                isDraftPayloadCurrent(payload)
                        }
                    committed?.let { saved ->
                        // Publication is predicate-guarded up to the pointer commit.
                        // Post-commit UI/old-generation settlement remains cancellable:
                        // teardown must not retain a ViewModel-owned coroutine. A valid
                        // committed pointer is recoverable on the next process start.
                        settled = settleCommittedDraft(context, saved, payload, owningJob)
                    }
                }
            }
        } catch (_: CancellationException) {
            // A published pointer must be settled even when dispatcher return delivers
            // cancellation.
        } catch (t: Throwable) {
            logDraftSaveFailure(t)
        }
        if (committed == null) {
            payload.recycleOwnedBitmaps()
            draftTracker?.end()
            updateUiStateAndRecycleReplaced {
                if (owningJob?.isActive != false && isDraftPayloadDocumentCurrent(payload)) {
                    it.copy(
                        message =
                            "\uc784\uc2dc \uc800\uc7a5\uc5d0 \uc2e4\ud328\ud588\uc2b5\ub2c8\ub2e4. \ud3b8\uc9d1\uc740 \uacc4\uc18d\ud560 \uc218 \uc788\uc2b5\ub2c8\ub2e4."
                    )
                } else it
            }
            return false
        }
        payload.recycleOwnedBitmaps()
        draftTracker?.end()
        return settled
    }

    private suspend fun settleCommittedDraft(
        context: Context,
        saved: DraftSaveResult,
        payload: DraftSavePayload,
        owningJob: Job?,
    ): Boolean {
        val current =
            owningJob?.isActive != false &&
                draftSaveJob === owningJob &&
                isDraftResultCurrent(saved)
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
            val next =
                expected.copy(
                    draftSavedAtMillis = saved.savedAtMillis,
                    draftSourcePath = saved.sourcePath,
                    draftBaseContentToken = saved.baseContentToken,
                    draftGenerationId = saved.generationId,
                    draftGenerationSourcePath = saved.sourcePath,
                    draftGenerationThumbnailPath = saved.thumbnailPath,
                )
            adopted = commitUiState(expected, next)
        }
        saved.expectedPointerGenerationId?.let { ThumbnailBitmapCache.invalidate("draft:$it") }
        withContext(Dispatchers.IO) {
            runCatching { persistLegacyDraftCompatibility(context, payload, saved) }
                .onFailure(::logDraftSaveFailure)
            runCatching { deleteAllDraftGenerationsExcept(context, saved.generationDirectory) }
                .onFailure(::logDraftSaveFailure)
        }
        return true
    }

    internal fun markParamsSuccessfullyRendered(params: EditParams) {
        lastSuccessfullyRenderedParams = params
    }

    fun appContext(): Context = getApplication<Application>().applicationContext

    fun appApplication(): Application = getApplication()

    internal fun debugResidentOwnership(): String {
        if (!DebugMemoryTracker.isEnabled()) return "release build - tracking disabled"
        val state = uiState.value
        val sb = StringBuilder()
        sb.append("=== EditorViewModel Resident Ownership ===\n")
        sb.append("revision=${state.revision} baseContentToken=${state.baseContentToken}\n")
        sb.append("nativeSession=${nativeSession} shuttingDown=${shuttingDown}\n")
        sb.append(
            "renderJob=${renderJob?.isActive} exportJob=${exportJob?.isActive} managedEditJob=${managedEdits.job?.isActive}\n"
        )
        sb.append("historyIoJob=${historyIoJob?.isActive} draftSaveJob=${draftSaveJob?.isActive}\n")
        sb.append(
            "selectionLivePreviewJob=${selectionLivePreviewJob?.isActive} cropJob=${cropJob?.isActive}\n"
        )
        sb.append("selectionParamTransaction=${selectionParamTransaction?.gestureId}\n")
        sb.append("brushingSnapshot=${brushingSnapshot != null}\n")
        sb.append("ThumbnailBitmapCache resident: see DebugMemoryTracker\n")
        sb.append(tracker.debugString())
        return sb.toString()
    }

    init {
        val startupRestoreToken = ++restoreDraftToken
        val startupRevision = _uiState.value.revision
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val engines = loadEngineSelection(context)
                updateUiStateAndRecycleReplaced {
                    it.copy(
                        noiseEngine = engines.noiseEngine,
                        detailEngine = engines.detailEngine,
                        toneEngine = engines.toneEngine,
                        hazeEngine = engines.hazeEngine,
                    )
                }
                restoreDraftIfAvailable(context, startupRestoreToken, startupRevision)
                val historyResult =
                    withContext(Dispatchers.IO) {
                        val currentRetention = savedExportHistoryStore.loadRetention()
                        savedExportHistoryStore.loadOrRebuildWithMutation(currentRetention)
                    }
                invalidateRemovedHistoryThumbnails(context, historyResult)
                updateUiStateAndRecycleReplaced {
                    if (historyResult.revision != savedExportHistoryStore.revision) it
                    else
                        it.copy(
                            savedExports = historyResult.items,
                            exportHistoryRetention =
                                historyResult.retention ?: it.exportHistoryRetention,
                        )
                }
            } finally {
                startupInitCompletion.complete(Unit)
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
        updateUiStateAndRecycleReplaced {
            it.copy(isBusy = true, revision = invalidateRevision, message = openingMessage)
        }
        viewModelScope.launch {
            var preview: Bitmap? = null
            var createdSession = 0L
            var sourceFile: File? = null
            val opTracker = beginMemoryTracking("openImage", snapshotState = "decoding")
            try {
                val context = getApplication<Application>()
                withContext(Dispatchers.IO) {
                    val copiedSource = copyUriToCache(context, uri)
                    sourceFile = copiedSource
                    val decoded =
                        decodeSampledMutableBitmapWithExif(
                            copiedSource.absolutePath,
                            maxSide = 2048,
                            opTracker,
                        )
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
                Log.i(
                    FLARE_GUARD_AI_TAG,
                    "Opened image with EXIF orientation: ${openedSource.name} preview=${decodedPreview.width}x${decodedPreview.height}",
                )
                createdSession = nativeCreateSessionOrTest(openedSource.absolutePath)
                tracker.registerNativeSession(
                    handle = createdSession,
                    documentGeneration = historyCoordinator.currentGeneration(),
                    sourceIdentity = decodedPreview.hashCode().toString(),
                    state = "created",
                )
                if (
                    shuttingDown ||
                        openToken != restoreDraftToken ||
                        _uiState.value.revision != invalidateRevision
                ) {
                    preview?.recycle()
                    preview = null
                    sourceFile?.delete()
                    releaseNativeSessionHandle(createdSession)
                    createdSession = 0L
                    return@launch
                }
                val previousSession = nativeSession
                val previousState = _uiState.value
                val nextState =
                    previousState.copy(
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
                        correctionEngineState =
                            previousState.correctionEngineState.forOpenedDocument(
                                previousState.correctionEngineState.defaultEngine
                            ),
                        recoveryDebugInfo = null,
                        showRecoveryDebugCard = false,
                        revision = invalidateRevision + 1,
                        message =
                            "\uC6D0\uBCF8 \uCE90\uC2DC\uAC00 \uC644\uB8CC\uB418\uC5C8\uC2B5\uB2C8\uB2E4: ${decodedPreview.width}x${decodedPreview.height} preview",
                    )
                nativeSession = createdSession
                if (
                    !commitUiState(
                        previousState,
                        nextState,
                        replaceDocument = true,
                        adoptedNativeSession = createdSession,
                    )
                ) {
                    nativeSession = previousSession
                    error("open image adoption superseded")
                }
                createdSession = 0L
                tracker.updateNativeSession(nativeSession, "active")
                lastSuccessfullyRenderedParams = EditParams()
                preview = null
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
                if (
                    !shuttingDown &&
                        openToken == restoreDraftToken &&
                        _uiState.value.revision == invalidateRevision
                ) {
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            isBusy = false,
                            message =
                                "\uC774\uBBF8\uC9C0\uB97C \uC5F4\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4: ${t.message}",
                        )
                    }
                    if (t is BitmapAllocationRejectedException) {
                        requestAllocationRecovery(
                            MemoryRetryAction.OpenImage,
                            t.requiredBytes,
                            uri.toString(),
                        )
                    }
                }
            } finally {
                opTracker?.end()
            }
        }
    }

    fun updateParams(transform: (EditParams) -> EditParams) {
        if (shuttingDown) return
        prepareForGlobalParamEdit()
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return
        val next = transform(_uiState.value.params)
        if (next == _uiState.value.params) return
        val transaction =
            parameterGesture ?: run {
                val source = acquireEditorSnapshot("updateParams") ?: return
                ParameterGestureTransaction(
                    id = ++parameterGestureCounter,
                    start = source,
                    lifecycleInstallation = ParameterLifecycleTestHook.capture(),
                    historyPublishSeam = HistoryPublishTestSeam.capture(),
                ).also {
                    parameterGesture = it
                    lastSuccessfullyRenderedParams = source.state.params
                    ParameterLifecycleTestHook.notifyTransactionCreated(it.lifecycleInstallation, it.id)
                }
            }
        val nextRevision = _uiState.value.revision + 1
        transaction.latestParams = next
        transaction.latestRevision = nextRevision
        transaction.requestRender(nextRevision, next)
        transaction.windowExpired = false
        transaction.inactivityGeneration++
        paramUndoWindowJob?.cancel()
        val tickGeneration = transaction.inactivityGeneration
        paramUndoWindowJob = viewModelScope.launch {
            delay(900L)
            if (transaction.inactivityGeneration == tickGeneration) {
                transaction.windowExpired = true
                transaction.lifecycleInstallation?.hooks?.onInactivityTimerFired?.invoke(transaction.id)
                maybeCloseParameterGesture(transaction)
            }
        }
        updateUiState { it.copy(params = next, revision = nextRevision, isBusy = true) }
        updateUiState { it.copy(message = "미리보기를 렌더링하는 중입니다.") }
        renderJob?.cancel()
        activeParamRenderRevision = nextRevision
        val tracker = beginMemoryTracking(
            "updateParams",
            snapshotState = "rendering",
            transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
        )
        launchManagedRenderWithPreparedResources(
            { operationToken ->
                val thisJob = coroutineContext[Job]
                if (!transaction.prepareRender(nextRevision, operationToken, thisJob)) return@launchManagedRenderWithPreparedResources
                val baseSlot = OwnedHandoff<OwnedBitmap>()
                val renderSlot = OwnedHandoff<OwnedRenderSuccess>()
                var base: Bitmap? = null
                var output: Bitmap? = null
                try {
                    delay(120L)
                    if (transaction.historyHandle == null &&
                        transaction.historyFailure == null
                    ) {
                        transaction.historyHandle =
                            prepareHistorySnapshot("updateParams:${transaction.id}", transaction.start)
                        transaction.historyJob = viewModelScope.launch(Dispatchers.Default) {
                            try {
                                val snap = transaction.historyHandle?.await()
                                if (snap != null) {
                                    var owned: OwnedHistorySnapshot? = OwnedHistorySnapshot(snap)
                                    // The scoped owner gate is cancellable: terminal ViewModel
                                    // settlement must be able to stop publication before the
                                    // history coordinator closes.
                                    try {
                                        transaction.historyPublishSeam?.awaitRelease()
                                        val handoffOwned = checkNotNull(owned)
                                        owned = null
                                        if (transaction.historyHandoff.publish(handoffOwned)) {
                                            transaction.historySnapshotPublished = true
                                            transaction.lifecycleInstallation?.hooks?.onHistoryPublished?.invoke(transaction.id)
                                        }
                                    } finally {
                                        owned?.close()
                                    }
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (failure: Throwable) {
                                synchronized(transaction.stateLock) {
                                    transaction.historyFailure = failure
                                }
                            }
                        }
                    }
                    if (!transaction.markRendering(nextRevision)) {
                        error("parameter transaction terminal=${transaction.currentTerminalState()}, cannot start render")
                    }
                    transaction.historyJob?.join()
                    transaction.historyFailure?.let { throw it }
                    if (transaction.historyHandle != null && !transaction.historySnapshotPublished) {
                        error("parameter history preparation produced no snapshot")
                    }
                    withContext(Dispatchers.Default) {
                        baseSlot.publish(OwnedBitmap((transaction.start.originalPreviewBitmap ?: transaction.start.previewBitmap)
                            ?.copyOrThrow() ?: error("missing parameter preview source")))
                    }
                    base = checkNotNull(baseSlot.take()?.take())
                    tracker?.track(base, "updateParams:base")
                    transaction.lifecycleInstallation?.hooks?.onRenderRequestStarted?.invoke(nextRevision)
                    withContext(Dispatchers.Default) {
                        renderSlot.publish(
                            OwnedRenderSuccess(
                                EditorRenderer.render(
                                    createRenderRequest(
                                        state = transaction.start.state,
                                        operation = RenderOperation.NativePreview,
                                        basePreview = checkNotNull(base),
                                        revision = nextRevision,
                                        params = next,
                                        diagnostics = tracker,
                                    )
                                ).successOrThrow()
                            )
                        )
                    }
                    check(transaction.markProduced(nextRevision)) { "parameter render $nextRevision produced after terminal transition" }
                    transaction.lifecycleInstallation?.hooks?.onRenderOutputProduced?.invoke(nextRevision)
                    val owner = checkNotNull(renderSlot.take())
                    val result = owner.result
                    output = checkNotNull(owner.takeOutput())
                    tracker?.track(output, "updateParams:output")
                    if (isManagedEditCurrent(operationToken, nextRevision)) {
                        if (transaction.adopt(nextRevision)) {
                            transaction.adoptedRevision = nextRevision
                            transaction.adoptedParams = next
                            updateUiStateAndRecycleReplaced {
                                it.copy(
                                    params = next,
                                    previewBitmap = output,
                                    isBusy = false,
                                    correctionEngineState = it.correctionEngineState.withSuccessfulRender(
                                        transaction.start.state.correctionEngineState.documentEngine,
                                        result.copy(output = checkNotNull(output)),
                                    ),
                                )
                            }
                            activeParamRenderRevision = null
                            output = null
                            transaction.lifecycleInstallation?.hooks?.onRenderOutputAdopted?.invoke(nextRevision)
                            maybeCloseParameterGesture(transaction)
                        } else {
                            transaction.cancelRender(nextRevision, "adoption rejected")
                            if (activeParamRenderRevision == nextRevision) activeParamRenderRevision = null
                        }
                    } else {
                        transaction.cancelRender(nextRevision, "stale managed edit")
                        if (activeParamRenderRevision == nextRevision) activeParamRenderRevision = null
                    }
                } catch (ce: CancellationException) {
                    transaction.cancelRender(nextRevision, "canceled")
                    if (activeParamRenderRevision == nextRevision) activeParamRenderRevision = null
                    throw ce
                } catch (failure: Throwable) {
                    transaction.failRender(nextRevision, failure.message ?: failure::class.java.simpleName)
                    if (activeParamRenderRevision == nextRevision) activeParamRenderRevision = null
                    if (isManagedEditCurrent(operationToken, nextRevision)) {
                        val retainedParams = transaction.adoptedParams
                        if (retainedParams != null) {
                            // A newer render failed: keep the previously adopted
                            // output; only the latest adopted revision decides.
                            updateUiState { it.copy(params = retainedParams, isBusy = false) }
                        } else {
                            takePendingParameterSnapshotForRollback(transaction)?.let {
                                restoreSnapshotWithoutHistory(it, (failure as? RenderFailedException)?.failure)
                            }
                            updateUiState { it.copy(params = lastSuccessfullyRenderedParams, isBusy = false) }
                        }
                    }
                } finally {
                    output?.takeUnless(Bitmap::isRecycled)?.recycle()
                    base?.takeUnless(Bitmap::isRecycled)?.recycle()
                    baseSlot.close()
                    renderSlot.close()
                    tracker?.end()
                    if (transaction.renderJob === thisJob) transaction.renderJob = null
                    maybeCloseParameterGesture(transaction)
                }
            },
            PreparedResourceHandoff.create("parameterRender", { tracker?.end() }, {
                if (activeParamRenderRevision == nextRevision) {
                    activeParamRenderRevision = null
                    if (_uiState.value.revision == nextRevision) updateUiState { it.copy(isBusy = false) }
                }
            }),
        ).also { transaction.renderJob = it }
    }

    fun applyAutoEnhance() {
        if (shuttingDown) return
        prepareForExternalEdit()
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return
        val current = _uiState.value
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
        val autoEnhanceTracker =
            beginMemoryTracking(
                "applyAutoEnhance",
                snapshotState = "rendering",
                transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
            )
        var undoSnapshot: EditorHistorySnapshot? =
            captureCurrentHistorySnapshot(HistorySnapshotStorage.MetadataOnly)
        val ownedBase =
            runCatching { basePreview.copyOrThrow() }
                .getOrElse {
                    autoEnhanceTracker?.end()
                    undoSnapshot?.let(::recycleHistorySnapshot)
                    undoSnapshot = null
                    updateUiStateAndRecycleReplaced { it.copy(message = "자동 보정 준비에 실패했습니다.") }
                    return
                }
        autoEnhanceTracker?.track(ownedBase, "applyAutoEnhance:ownedBase")
        val nextRevision = startRevision + 1
        renderJob?.cancel()
        updateUiStateAndRecycleReplaced {
            it.copy(isBusy = true, revision = nextRevision, message = "자동 보정값을 분석하는 중입니다")
        }
        launchManagedEditWithPreparedResources({ operationToken ->
            var rendered: Bitmap? = null
            var renderSuccess: RenderResult.Success? = null
            try {
                val nextParams =
                    withContext(Dispatchers.Default) { computeAutoEnhanceParams(ownedBase) }
                withContext(Dispatchers.Default) {
                    renderSuccess =
                        EditorRenderer.render(
                            createRenderRequest(
                                state = current,
                                operation = RenderOperation.AutoEnhance,
                                basePreview = ownedBase,
                                revision = nextRevision,
                                params = nextParams,
                                engines = engines,
                                look = presetLook,
                                quickEffects = quickEffects,
                                diagnostics = autoEnhanceTracker,
                            )
                        ).successOrThrow()
                    rendered = checkNotNull(renderSuccess).output
                }
                autoEnhanceTracker?.track(rendered!!, "applyAutoEnhance:rendered")
                if (
                    isManagedEditCurrent(operationToken, nextRevision) &&
                        uiState.value.sourcePath == sourcePath &&
                        uiState.value.baseContentToken == baseContentToken &&
                        !isShuttingDown()
                ) {
                    val adopted = rendered!!
                    lastSuccessfullyRenderedParams = nextParams
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            params = nextParams,
                            previewBitmap = adopted,
                            isBusy = false,
                            correctionEngineState =
                                it.correctionEngineState.withSuccessfulRender(
                                    current.correctionEngineState.documentEngine,
                                    checkNotNull(renderSuccess),
                                ),
                            message = "자동 보정이 적용되었습니다",
                        )
                    }
                    rendered = null
                    settleAdoptedEditHistory(undoSnapshot)
                    undoSnapshot = null
                    scheduleDraftAutosave()
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (
                    isManagedEditCurrent(operationToken, nextRevision) &&
                        uiState.value.sourcePath == sourcePath &&
                        uiState.value.baseContentToken == baseContentToken
                ) {
                    (t as? RenderFailedException)?.failure?.let { failure ->
                        updateUiState {
                            it.copy(
                                correctionEngineState =
                                    it.correctionEngineState.withFailedRender(
                                        current.correctionEngineState.documentEngine,
                                        failure,
                                    )
                            )
                        }
                    }
                    updateUiStateAndRecycleReplaced {
                        it.copy(isBusy = false, message = "자동 보정에 실패했습니다: ${t.message}")
                    }
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } finally {
                rendered?.takeIf { !it.isRecycled }?.recycle()
            }
        }, PreparedResourceHandoff.create(
            "autoEnhance",
            { ownedBase.takeIf { !it.isRecycled }?.recycle() },
            { undoSnapshot?.let(::recycleHistorySnapshot); undoSnapshot = null },
            { autoEnhanceTracker?.end() },
            {
                val live = _uiState.value
                if (live.revision == nextRevision && live.sourcePath == sourcePath &&
                    live.baseContentToken == baseContentToken) {
                    updateUiState { it.copy(isBusy = false) }
                }
            },
        ))
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
        message: String,
    ) {
        if (isShuttingDown()) return
        val current = prepareForExternalEdit()
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return
        val nextEngines =
            EngineSelection(
                    noiseEngine = noiseEngine ?: current.noiseEngine,
                    detailEngine = detailEngine ?: current.detailEngine,
                    toneEngine = toneEngine ?: current.toneEngine,
                    hazeEngine = hazeEngine ?: current.hazeEngine,
                )
                .coerceImplemented()
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
                    message = message,
                )
            }
            return
        }
        val engineTracker =
            beginMemoryTracking(
                "applyEngineChange",
                snapshotState = "rendering",
                transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
            )
        var undoSnapshot: EditorHistorySnapshot? =
            captureCurrentHistorySnapshot(HistorySnapshotStorage.MetadataOnly)
        var ownedBase: Bitmap? =
            runCatching { basePreview.copyOrThrow() }
                .getOrElse {
                    engineTracker?.end()
                    undoSnapshot?.let(::recycleHistorySnapshot)
                    undoSnapshot = null
                    updateUiStateAndRecycleReplaced { it.copy(message = "처리 엔진 변경 준비에 실패했습니다.") }
                    return
                }
        engineTracker?.track(checkNotNull(ownedBase), "applyEngineChange:ownedBase")
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
                message = "$message. 미리보기를 다시 렌더링하는 중입니다",
            )
        }
        renderJob?.cancel()
        launchManagedRenderWithPreparedResources({ operationToken ->
            var rendered: Bitmap? = null
            var renderSuccess: RenderResult.Success? = null
            try {
                withContext(Dispatchers.Default) {
                    renderSuccess =
                        EditorRenderer.render(
                            createRenderRequest(
                                state = current,
                                operation = RenderOperation.ProcessingEngineChange,
                                basePreview = checkNotNull(ownedBase),
                                revision = nextRevision,
                                params = params,
                                engines = nextEngines,
                                look = presetLook,
                                quickEffects = quickEffects,
                                diagnostics = engineTracker,
                            )
                        ).successOrThrow()
                    rendered = checkNotNull(renderSuccess).output
                }
                engineTracker?.track(rendered!!, "applyEngineChange:rendered")
                val identityUnchanged =
                    uiState.value.sourcePath == sourcePath &&
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
                            correctionEngineState =
                                it.correctionEngineState.withSuccessfulRender(
                                    current.correctionEngineState.documentEngine,
                                    checkNotNull(renderSuccess),
                                ),
                            message = message,
                        )
                    }
                    rendered = null
                    settleAdoptedEditHistory(undoSnapshot)
                    undoSnapshot = null
                    scheduleDraftAutosave()
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val failureIdentityUnchanged =
                    uiState.value.sourcePath == sourcePath &&
                        uiState.value.baseContentToken == baseContentToken
                if (
                    isManagedEditCurrent(operationToken, nextRevision) && failureIdentityUnchanged
                ) {
                    (t as? RenderFailedException)?.failure?.let { failure ->
                        updateUiState {
                            it.copy(
                                correctionEngineState =
                                    it.correctionEngineState.withFailedRender(
                                        current.correctionEngineState.documentEngine,
                                        failure,
                                    )
                            )
                        }
                    }
                    updateUiStateAndRecycleReplaced {
                        it.copy(isBusy = false, message = "미리보기 렌더링에 실패했습니다: ${t.message}")
                    }
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } finally {
                rendered?.takeIf { !it.isRecycled }?.recycle()
            }
        }, PreparedResourceHandoff.create(
            "engineChange",
            { ownedBase?.takeIf { !it.isRecycled }?.recycle(); ownedBase = null },
            { undoSnapshot?.let(::recycleHistorySnapshot); undoSnapshot = null },
            { engineTracker?.end() },
            {
                val live = _uiState.value
                if (live.revision == nextRevision && live.sourcePath == sourcePath &&
                    live.baseContentToken == baseContentToken) {
                    updateUiState { it.copy(isBusy = false) }
                }
            },
        ))
    }

    fun resetAdjustments() {
        if (isShuttingDown()) return
        prepareForExternalEdit()
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return
        val startSnapshot = acquireEditorSnapshot("resetAdjustments") ?: return
        val current = startSnapshot.state
        val sourcePath = current.sourcePath
        if (sourcePath == null) {
            startSnapshot.close()
            updateUiStateAndRecycleReplaced { it.copy(message = "초기화할 이미지가 없습니다.") }
            return
        }
        val baseContentToken = current.baseContentToken
        val startRevision = current.revision
        val resetTracker =
            beginMemoryTracking(
                "resetAdjustments",
                snapshotState = "decoding",
                transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
            )
        var pendingHistory: PendingHistorySnapshot? =
            prepareHistorySnapshot("resetAdjustments", startSnapshot)
        val nextRevision = startRevision + 1
        renderJob?.cancel()
        invalidateExport()
        var decoded: Bitmap? = null
        updateUiStateAndRecycleReplaced {
            it.copy(isBusy = true, revision = nextRevision, message = "초기화하는 중입니다")
        }
        launchManagedRenderWithPreparedResources({ operationToken ->
            var undoSnapshot: EditorHistorySnapshot? =
                pendingHistory?.await()
            pendingHistory = null
            try {
                withContext(Dispatchers.IO) {
                    val result =
                        decodeSampledMutableBitmapWithExif(sourcePath, maxSide = 2048, resetTracker)
                    decoded = result
                }
                val identityUnchanged =
                    uiState.value.sourcePath == sourcePath &&
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
                            correctionEngineState =
                                it.correctionEngineState.copy(
                                    pendingEngine = null,
                                    visiblePreview = VisiblePreviewState.Original,
                                    lastRenderFailure = null,
                                ),
                            message = "초기화가 완료되었습니다",
                        )
                    }
                    decoded = null
                    settleAdoptedEditHistory(undoSnapshot)
                    undoSnapshot = null
                    forceDraftSaveAsync()
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val failureIdentityUnchanged =
                    uiState.value.sourcePath == sourcePath &&
                        uiState.value.baseContentToken == baseContentToken
                if (
                    isManagedEditCurrent(operationToken, nextRevision) && failureIdentityUnchanged
                ) {
                    (t as? RenderFailedException)?.failure?.let { failure ->
                        updateUiState {
                            it.copy(
                                correctionEngineState =
                                    it.correctionEngineState.withFailedRender(
                                        current.correctionEngineState.documentEngine,
                                        failure,
                                    )
                            )
                        }
                    }
                    updateUiStateAndRecycleReplaced {
                        it.copy(isBusy = false, message = "초기화에 실패했습니다: ${t.message}")
                    }
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } finally {
                decoded?.takeIf { !it.isRecycled }?.recycle()
                startSnapshot.close()
            }
        }, PreparedResourceHandoff.create(
            "resetAdjustments",
            { decoded?.takeIf { !it.isRecycled }?.recycle(); decoded = null },
            {
                pendingHistory?.close()
                pendingHistory = null
                startSnapshot.close()
            },
            { resetTracker?.end() },
            {
                val live = _uiState.value
                if (live.revision == nextRevision && live.sourcePath == sourcePath &&
                    live.baseContentToken == baseContentToken) {
                    updateUiState { it.copy(isBusy = false) }
                }
            },
        ))
    }

    fun applyPresetLook(
        params: EditParams,
        look: PresetColorLook?,
        message: String,
    ): PresetApplyResult {
        if (isShuttingDown()) return PresetApplyResult.Rejected
        val current = prepareForExternalEdit()
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable())
            return PresetApplyResult.Rejected
        val basePreview = current.originalPreviewBitmap ?: current.previewBitmap
        if (basePreview == null) {
            updateUiStateAndRecycleReplaced { it.copy(message = "적용할 이미지가 없습니다.") }
            return PresetApplyResult.Rejected
        }
        if (params == current.params && look == current.presetLook) {
            return PresetApplyResult.AlreadyApplied
        }
        val presetTracker =
            beginMemoryTracking(
                "applyPresetLook",
                snapshotState = "rendering",
                transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
            )
        var undoSnapshot: EditorHistorySnapshot? =
            captureCurrentHistorySnapshot(HistorySnapshotStorage.MetadataOnly)
        var ownedBase: Bitmap? =
            runCatching { basePreview.copyOrThrow() }
                .getOrElse {
                    presetTracker?.end()
                    undoSnapshot?.let(::recycleHistorySnapshot)
                    undoSnapshot = null
                    updateUiStateAndRecycleReplaced { it.copy(message = "프리셋 적용 준비에 실패했습니다.") }
                    return PresetApplyResult.Rejected
                }
        presetTracker?.track(checkNotNull(ownedBase), "applyPresetLook:ownedBase")
        val sourcePath = current.sourcePath
        val baseContentToken = current.baseContentToken
        val engines = current.engineSelection()
        val quickEffects = current.activeQuickEffects
        val startRevision = current.revision
        val nextRevision = startRevision + 1
        renderJob?.cancel()
        updateUiStateAndRecycleReplaced {
            it.copy(isBusy = true, revision = nextRevision, message = message)
        }
        launchManagedRenderWithPreparedResources({ operationToken ->
            var rendered: Bitmap? = null
            var renderSuccess: RenderResult.Success? = null
            try {
                withContext(Dispatchers.Default) {
                    renderSuccess =
                        EditorRenderer.render(
                            createRenderRequest(
                                state = current,
                                operation = RenderOperation.Preset,
                                basePreview = checkNotNull(ownedBase),
                                revision = nextRevision,
                                params = params,
                                engines = engines,
                                look = look,
                                quickEffects = quickEffects,
                                diagnostics = presetTracker,
                            )
                        ).successOrThrow()
                    rendered = checkNotNull(renderSuccess).output
                }
                presetTracker?.track(rendered!!, "applyPresetLook:rendered")
                val identityUnchanged =
                    uiState.value.sourcePath == sourcePath &&
                        uiState.value.baseContentToken == baseContentToken
                if (isManagedEditCurrent(operationToken, nextRevision) && identityUnchanged) {
                    val adopted = rendered!!
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            params = params,
                            presetLook = look,
                            previewBitmap = adopted,
                            isBusy = false,
                            correctionEngineState =
                                it.correctionEngineState.withSuccessfulRender(
                                    current.correctionEngineState.documentEngine,
                                    checkNotNull(renderSuccess),
                                ),
                            message = message,
                        )
                    }
                    lastSuccessfullyRenderedParams = params
                    rendered = null
                    settleAdoptedEditHistory(undoSnapshot)
                    undoSnapshot = null
                    scheduleDraftAutosave()
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                val failureIdentityUnchanged =
                    uiState.value.sourcePath == sourcePath &&
                        uiState.value.baseContentToken == baseContentToken
                if (
                    isManagedEditCurrent(operationToken, nextRevision) && failureIdentityUnchanged
                ) {
                    (t as? RenderFailedException)?.failure?.let { failure ->
                        updateUiState {
                            it.copy(
                                correctionEngineState =
                                    it.correctionEngineState.withFailedRender(
                                        current.correctionEngineState.documentEngine,
                                        failure,
                                    )
                            )
                        }
                    }
                    updateUiStateAndRecycleReplaced {
                        it.copy(isBusy = false, message = "프로필 적용에 실패했습니다.")
                    }
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } finally {
                rendered?.takeIf { !it.isRecycled }?.recycle()
            }
        }, PreparedResourceHandoff.create(
            "presetApplication",
            { ownedBase?.takeIf { !it.isRecycled }?.recycle(); ownedBase = null },
            { undoSnapshot?.let(::recycleHistorySnapshot); undoSnapshot = null },
            { presetTracker?.end() },
            {
                val live = _uiState.value
                if (live.revision == nextRevision && live.sourcePath == sourcePath &&
                    live.baseContentToken == baseContentToken) {
                    updateUiState { it.copy(isBusy = false) }
                }
            },
        ))
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
            val result =
                withContext(Dispatchers.IO) {
                    savedExportHistoryStore.saveRetention(retention)
                    savedExportHistoryStore.prune(retention)
                }
            invalidateRemovedHistoryThumbnails(context, result)
            updateUiStateAndRecycleReplaced {
                if (result.revision != savedExportHistoryStore.revision) it
                else
                    it.copy(
                        exportHistoryRetention = retention,
                        savedExports = result.items,
                        message =
                            if (it.isBusy) it.message
                            else "내보낸 사진 기록 자동 정리가 ${retention.label}으로 설정되었습니다",
                    )
            }
        }
    }

fun exportPreview() {
        if (shuttingDown) return
        prepareForExternalEdit()
        val state = _uiState.value
        val sourcePath = state.sourcePath
        val exportBusyMessage =
            "${state.exportFormat.label} 형식, ${state.exportResolution.label} 목표 해상도로 내보내는 중입니다."
        if (sourcePath == null) {
            val missingMsg =
                "\uB0B0\ub9AC\ubc88\uc6d0 \uc774\ubbf8\uc9c0\uAC00 \uC5C5\uC2B5\uB2C8\uB2E4"
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
        val exportVisible =
            state.correctionEngineState.visiblePreview as? VisiblePreviewState.Rendered
        val exportActualRoute = exportVisible?.actualRoute
        val exportRequestedRoute = exportVisible?.requestedRoute
        val exportDecision = exportVisible?.decision
        val exportAlgorithmVersion = exportVisible?.algorithmVersion
        val exportParticipation = exportVisible?.participation
        val exportDocumentEngine = state.correctionEngineState.documentEngine
        val exportDocumentGeneration = currentDocumentGeneration()
        val exportRevision = state.revision
        val exportBaseToken = state.baseContentToken
        val exportDirty = state.baseBitmapDirty
        val exportRetention = state.exportHistoryRetention
        var ownedDirtyBase: Bitmap? = null
        var ownedExportLayers: List<SelectionLayer> = emptyList()
        var exportPrepareTracker: MemoryTrackerScope? =
            beginMemoryTracking("exportPreview:prepare", snapshotState = "copying")
        var dirtyBaseEdge = 0L
        try {
            ownedExportLayers = state.selectionLayers.copyBitmapsOwned()
            ownedExportLayers.forEach {
                exportPrepareTracker?.track(it.bitmap, "exportPreview:selection:${it.id}")
            }
        } catch (failure: Throwable) {
            ownedExportLayers.forEach { it.bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
            exportPrepareTracker?.end()
            updateUiStateAndRecycleReplaced {
                it.copy(message = "선택 마스크를 내보내기용으로 준비하지 못했습니다.")
            }
            if (failure is BitmapAllocationRejectedException) {
                requestAllocationRecovery(MemoryRetryAction.ExportPreview, failure.requiredBytes)
            }
            return
        }
        if (exportDirty) {
            val liveBase = state.originalPreviewBitmap ?: state.previewBitmap
            val dirtyPeakBytes =
                liveBase?.let { bitmap ->
                    val scale = exportResolution.scalePercent / 100f
                    BitmapMemoryBudget.saturatingAdd(
                        BitmapMemoryBudget.bytes(bitmap),
                        BitmapMemoryBudget.bytes(
                            (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                            (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                            bitmap.config,
                        ),
                    )
                } ?: Long.MAX_VALUE
            if (liveBase == null || !BitmapMemoryBudget.canAllocate(dirtyPeakBytes)) {
                ownedExportLayers.forEach { it.bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
                ownedExportLayers = emptyList()
                exportPrepareTracker?.end()
                exportPrepareTracker = null
                updateUiStateAndRecycleReplaced {
                    it.copy(message = "메모리가 부족하여 현재 해상도로 내보낼 수 없습니다. 다른 해상도 또는 이미지를 사용해 주세요.")
                }
                requestAllocationRecovery(MemoryRetryAction.ExportPreview, dirtyPeakBytes)
                return
            }
            ownedDirtyBase =
                runCatching { liveBase.copyOrThrow(Bitmap.Config.ARGB_8888, true) }
                    .getOrElse { failure ->
                        ownedExportLayers.forEach {
                            it.bitmap.takeUnless(Bitmap::isRecycled)?.recycle()
                        }
                        ownedExportLayers = emptyList()
                        exportPrepareTracker?.end()
                        exportPrepareTracker = null
                        updateUiStateAndRecycleReplaced {
                            it.copy(message = "메모리가 부족하여 내보내기를 준비하지 못했습니다.")
                        }
                        if (failure is BitmapAllocationRejectedException)
                            requestAllocationRecovery(
                                MemoryRetryAction.ExportPreview,
                                failure.requiredBytes,
                            )
                        return
                    }
            dirtyBaseEdge =
                exportPrepareTracker?.track(checkNotNull(ownedDirtyBase), "exportPreview:dirtyBase")
                    ?: 0L
        } else if (!canPreflightCleanExport(sourcePath, exportResolution)) {
            ownedExportLayers.forEach { it.bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
            ownedExportLayers = emptyList()
            exportPrepareTracker?.end()
            exportPrepareTracker = null
            updateUiStateAndRecycleReplaced {
                it.copy(message = "메모리가 부족하여 현재 해상도로 내보낼 수 없습니다. 다른 해상도 또는 이미지를 사용해 주세요.")
            }
            requestAllocationRecovery(
                MemoryRetryAction.ExportPreview,
                estimateCleanExportPeakBytes(sourcePath, exportResolution),
            )
            return
        }
        invalidateExport()
        val token = exportToken
        val fileName = "KeplerStudio_${exportTimestamp()}.${exportFormat.extension}"
        updateUiStateAndRecycleReplaced { it.copy(isBusy = true, message = exportBusyMessage) }
        val exportHandoff =
            PreparedResourceHandoff.create(
                "dirtyExport",
                {
                    ownedDirtyBase?.takeIf { !it.isRecycled }?.recycle()
                    ownedDirtyBase = null
                },
                {
                    ownedExportLayers.forEach {
                        it.bitmap.takeUnless(Bitmap::isRecycled)?.recycle()
                    }
                    ownedExportLayers = emptyList()
                },
                { exportPrepareTracker?.release(dirtyBaseEdge) },
                {
                    exportPrepareTracker?.end()
                    exportPrepareTracker = null
                },
                {
                    val live = _uiState.value
                    if (
                        !shuttingDown &&
                            exportToken == token &&
                            live.sourcePath == sourcePath &&
                            live.baseContentToken == exportBaseToken &&
                            live.revision == exportRevision
                    ) {
                        updateUiStateAndRecycleReplaced { it.copy(isBusy = false) }
                    }
                },
            )
        val launchedJob =
            viewModelScope.launch {
                if (!exportHandoff.claimForChild()) return@launch
                try {
                    val exportTracker =
                        beginMemoryTracking(
                            "exportPreview",
                            snapshotState = "rendering",
                            transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
                        )
                    ownedDirtyBase?.let {
                        exportTracker?.track(it, "exportPreview:dirtyBase")
                        exportPrepareTracker?.release(dirtyBaseEdge)
                    }
                    var ownedLayersForExport = ownedExportLayers
                    ownedExportLayers = emptyList()
                    ownedLayersForExport.forEach {
                        exportTracker?.track(it.bitmap, "exportPreview:selection:${it.id}")
                    }
                    exportPrepareTracker?.end()
                    exportPrepareTracker = null
                    // The owning coroutine Job is captured here, before the
                    // pipeline starts, so the single ExportIdentity remains
                    // stable for the entire handoff and survives the
                    // NonCancellable window the pipeline enters at publish.
                    val exportCoroutine = currentCoroutineContext()[Job] ?: return@launch
                    // Register this export's owning Job on the path now so the
                    // first identity check inside `executeExportPipeline` is
                    // evaluated against the authoritative (and active) owner
                    // even when the coroutine dispatcher runs the body's first
                    // synchronous segment before `viewModelScope.launch`
                    // returns to its caller.
                    exportJob = exportCoroutine
                    val exportIdentity =
                        ExportIdentity(
                            token = token,
                            sourcePath = sourcePath,
                            baseToken = exportBaseToken,
                            revision = exportRevision,
                            owningJob = exportCoroutine,
                        )
                    // The export test seam is captured at operation creation so
                    // a late old operation cannot consume a later test's seam.
                    val exportSeam = ExportTestSeam.capture()
                    try {
                        val context = getApplication<Application>()
                        val rows: ExportRowStore =
                            exportSeam?.rowStore ?: AndroidExportRowStore(context)
                        val historyStore: SavedExportHistoryStore =
                            exportSeam?.historyStore ?: savedExportHistoryStore
                        val exportedResolutionLabel = { width: Int, height: Int ->
                            "$width x $height"
                        }
                        val pipelineRequest = ExportRowRequest(fileName, exportFormat)

                        // The render callback hands `ownedDirtyBase` and the
                        // selection-layer Bitmaps to the existing renderer
                        // contracts (exactly-once ownership) and returns the
                        // rendered Bitmap, which becomes pipeline-owned. If the
                        // export is already stale at render entry, the
                        // prepared resources stay caller-owned (the outer
                        // finally settles them) and the render returns null so
                        // the pipeline creates no pending row.
                        val renderCallback: suspend () -> Bitmap? = {
                            if (!isCurrentExport(exportIdentity)) {
                                null
                            } else {
                                withContext(Dispatchers.Default) {
                                    val rendered =
                                        if (exportDirty) {
                                            renderEditedExportFromBitmap(
                                                ownedBaseBitmap =
                                                    checkNotNull(ownedDirtyBase).also {
                                                        ownedDirtyBase = null
                                                    },
                                                resolution = exportResolution,
                                                selectionLayers =
                                                    ownedLayersForExport.also {
                                                        ownedLayersForExport = emptyList()
                                                    },
                                                diagnostics = exportTracker,
                                                requestFactory = { base, layers ->
                                                    exportActualRoute?.let { actualRoute ->
                                                        RenderRequest(
                                                            operation =
                                                                RenderOperation.ExportDirty,
                                                            basePreview = base,
                                                            params = exportParams,
                                                            engines = exportEngines,
                                                            assignedDocumentEngine =
                                                                exportDocumentEngine,
                                                            identity =
                                                                RenderIdentity(
                                                                    exportDocumentGeneration,
                                                                    exportBaseToken,
                                                                    exportRevision + 1,
                                                                ),
                                                            storedRequestedRoute =
                                                                exportRequestedRoute
                                                                    ?: actualRoute,
                                                            exactRoute = actualRoute,
                                                            storedDecision = exportDecision,
                                                            storedAlgorithmVersion =
                                                                exportAlgorithmVersion,
                                                            storedParticipation =
                                                                exportParticipation,
                                                            fallbackPolicy =
                                                                FallbackPolicy.NoFallback,
                                                            look = exportLook,
                                                            quickEffects = exportQuickEffects,
                                                            selectionLayers = layers,
                                                            diagnostics = exportTracker,
                                                        )
                                                    }
                                                },
                                            )
                                        } else {
                                            renderEditedExport(
                                                sourcePath = sourcePath,
                                                resolution = exportResolution,
                                                selectionLayers =
                                                    ownedLayersForExport.also {
                                                        ownedLayersForExport = emptyList()
                                                    },
                                                diagnostics = exportTracker,
                                                requestFactory = { base, layers ->
                                                    exportActualRoute?.let { actualRoute ->
                                                        RenderRequest(
                                                            operation =
                                                                RenderOperation.ExportClean,
                                                            basePreview = base,
                                                            params = exportParams,
                                                            engines = exportEngines,
                                                            assignedDocumentEngine =
                                                                exportDocumentEngine,
                                                            identity =
                                                                RenderIdentity(
                                                                    exportDocumentGeneration,
                                                                    exportBaseToken,
                                                                    exportRevision + 1,
                                                                ),
                                                            storedRequestedRoute =
                                                                exportRequestedRoute
                                                                    ?: actualRoute,
                                                            exactRoute = actualRoute,
                                                            storedDecision = exportDecision,
                                                            storedAlgorithmVersion =
                                                                exportAlgorithmVersion,
                                                            storedParticipation =
                                                                exportParticipation,
                                                            fallbackPolicy =
                                                                FallbackPolicy.NoFallback,
                                                            look = exportLook,
                                                            quickEffects = exportQuickEffects,
                                                            selectionLayers = layers,
                                                            diagnostics = exportTracker,
                                                        )
                                                    }
                                                },
                                            )
                                        }
                                    markMemoryRetrySucceeded(MemoryRetryAction.ExportPreview)
                                    rendered
                                }
                            }
                        }

                        // persistMetadata runs inside the pipeline's
                        // NonCancellable window, after publication: the gallery
                        // row is already committed. It commits the saved-export
                        // history atomically through the history store and
                        // invalidates any thumbnails that fell out of history.
                        // A throw here is converted by the pipeline into
                        // PublishedWithMetadataFailure so the visible image is
                        // never deleted on a history failure. The returned
                        // mutation is consumed by the UI settlement below.
                        val persistMetadata: suspend (
                            Uri,
                            Int,
                            Int,
                        ) -> SavedExportHistoryMutation = { publishedUri, width, height ->
                            val savedItem =
                                SavedExport(
                                    displayName = fileName,
                                    uriString = publishedUri.toString(),
                                    formatLabel = exportFormat.label,
                                    resolutionLabel = exportedResolutionLabel(width, height),
                                    timestampMillis = System.currentTimeMillis(),
                                )
                            val mutation =
                                historyStore.commit(savedItem, historyStore.loadRetention())
                            invalidateRemovedHistoryThumbnails(context, mutation)
                            mutation
                        }

                        val outcome =
                            executeExportPipeline(
                                request = pipelineRequest,
                                rows = rows,
                                isCurrent = { isCurrentExport(exportIdentity) },
                                render = renderCallback,
                                persistMetadata = persistMetadata,
                            )

                        // Final UI settlement runs with the export identity's
                        // owning Job captured at creation, so the NonCancellable
                        // window used below cannot make a stale export
                        // impersonate a newer one. Every publish-side branch
                        // re-checks isCurrentExport(exportIdentity).
                        withContext(NonCancellable + Dispatchers.IO) {
                            when (outcome) {
                                is ExportPipelineResult.Published<*> -> {
                                    val mutation =
                                        outcome.metadata as SavedExportHistoryMutation
                                    val currentRevision = historyStore.revision
                                    updateUiStateAndRecycleReplaced { current ->
                                        val merged =
                                            if (mutation.revision == currentRevision) {
                                                mutation.items
                                            } else {
                                                current.savedExports
                                            }
                                        if (isCurrentExport(exportIdentity)) {
                                            current.copy(
                                                isBusy = false,
                                                savedExports = merged,
                                                message =
                                                    "이미지가 Gallery > Pictures/KeplerStudio에 저장되었고, 앱 내 내보낸 사진 기록에도 추가되었습니다.",
                                            )
                                        } else {
                                            current.copy(savedExports = merged)
                                        }
                                    }
                                }
                                is ExportPipelineResult.PublishedWithMetadataFailure -> {
                                    lastExportFailureForTest = outcome.failure
                                    if (isCurrentExport(exportIdentity)) {
                                        updateUiStateAndRecycleReplaced { current ->
                                            current.copy(
                                                isBusy = false,
                                                message =
                                                    "이미지는 갤러리에 저장되었지만 앱 내 내보낸 사진 기록을 저장하지 못했습니다.",
                                            )
                                        }
                                    }
                                }
                                is ExportPipelineResult.Failed -> {
                                    lastExportFailureForTest = outcome.failure
                                    if (isCurrentExport(exportIdentity)) {
                                        val failure = outcome.failure
                                        val isAllocationFailure =
                                            failure is BitmapAllocationRejectedException
                                        updateUiStateAndRecycleReplaced { current ->
                                            current.copy(
                                                isBusy = false,
                                                message =
                                                    "내보내기에 실패했습니다: ${failure.message ?: ""}",
                                            )
                                        }
                                        if (isAllocationFailure) {
                                            requestAllocationRecovery(
                                                MemoryRetryAction.ExportPreview,
                                                (failure as BitmapAllocationRejectedException)
                                                    .requiredBytes,
                                            )
                                        }
                                    }
                                }
                                is ExportPipelineResult.CleanupFailed -> {
                                    lastExportFailureForTest = outcome.cleanupFailure
                                    if (isCurrentExport(exportIdentity)) {
                                        updateUiStateAndRecycleReplaced { current ->
                                            current.copy(
                                                isBusy = false,
                                                message =
                                                    "내보내기에 실패했고 임시 파일 삭제도 실패했습니다: ${outcome.cleanupFailure.message ?: ""}",
                                            )
                                        }
                                    }
                                }
                                is ExportPipelineResult.Stale -> {
                                    lastExportFailureForTest =
                                        IllegalStateException("stale")
                                    // When the export identity is stale but the
                                    // token hasn't been superseded by a newer
                                    // export (revision-only mutation), this
                                    // stale owner must clear its own busy state
                                    // — no newer owner exists to take it over.
                                    if (exportIdentity.token == exportToken) {
                                        updateUiStateAndRecycleReplaced { current ->
                                            current.copy(isBusy = false)
                                        }
                                    }
                                }
                            }
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        // A cancelled export that hasn't been superseded by a
                        // newer owner must clear its busy state.
                        if (exportJob === exportCoroutine) {
                            updateUiStateAndRecycleReplaced { current ->
                                current.copy(isBusy = false)
                            }
                        }
                        throw ce
                    } catch (t: Throwable) {
                        // A pre-publish throw from render preparation is the
                        // only non-pipeline exception path. Pipeline-render
                        // exceptions surface as ExportPipelineResult.Failed;
                        // reaching here implies a transfer/Settlement failure.
                        lastExportFailureForTest = t
                        if (isCurrentExport(exportIdentity)) {
                            updateUiStateAndRecycleReplaced { current ->
                                current.copy(
                                    isBusy = false,
                                    message = "내보내기에 실패했습니다: ${t.message ?: ""}",
                                )
                            }
                        } else if (exportJob === exportCoroutine) {
                            // The owning export was cancelled/stale without a
                            // newer owner taking over; this owner must clear busy.
                            updateUiStateAndRecycleReplaced { current ->
                                current.copy(isBusy = false)
                            }
                        }
                        if (t is BitmapAllocationRejectedException) {
                            requestAllocationRecovery(
                                MemoryRetryAction.ExportPreview,
                                t.requiredBytes,
                            )
                        }
                    } finally {
                        // The pipeline recycles the rendered export Bitmap that
                        // render() returned; the ViewModel must not recycle it
                        // again. Only prepared resources that were never
                        // handed to the render path remain caller-owned here.
                        val owned = identityBitmapSet()
                        ownedDirtyBase?.let(owned::add)
                        ownedLayersForExport.forEach { owned.add(it.bitmap) }
                        owned.forEach { if (!it.isRecycled) it.recycle() }
                        ownedDirtyBase = null
                        ownedLayersForExport = emptyList()
                        if (exportJob === currentCoroutineContext()[Job]) exportJob = null
                        exportTracker?.end()
                    }
                } finally {
                    exportHandoff.settleChildOwned()
                }
            }
        exportJob = launchedJob
        launchedJob.invokeOnCompletion {
            exportHandoff.settleCallerOwned()
            if (exportJob === launchedJob) exportJob = null
        }
        if (launchedJob.isCompleted && exportJob === launchedJob) exportJob = null
    }

    private fun beginMaintenance(message: String): Boolean {
        var current = _uiState.value
        while (true) {
            if (current.maintenanceBusy) return false
            if (
                commitUiState(
                    current,
                    current.copy(maintenanceBusy = true, message = message),
                )
            ) {
                return true
            }
            current = _uiState.value
        }
    }

    private fun finishMaintenance() {
        updateUiStateAndRecycleReplaced {
            if (it.maintenanceBusy) it.copy(maintenanceBusy = false) else it
        }
    }

    fun clearDraft() {
        if (!beginMaintenance("임시 저장을 삭제하는 중입니다")) return
        val context = getApplication<Application>()
        invalidateDraftOperations()
        val clearEpoch = draftOperationEpoch
        viewModelScope.launch {
            try {
                draftSaveJob?.cancelAndJoin()
                val cleared =
                    withContext(Dispatchers.IO) {
                        draftSaveMutex.withLock {
                            if (clearEpoch != draftOperationEpoch) return@withLock false
                            val expectedPointer = currentDraftGenerationId(context)
                            val expectedBaseline = draftPointerBaseline
                            if (expectedPointer != expectedBaseline) return@withLock false
                            // Capture one stable visible state snapshot
                            val visibleBefore = _uiState.value
                            val liveSourcePath = visibleBefore.sourcePath
                            // Snapshot previous prefs for rollback
                            val prefs =
                                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                            val prevPrefs = snapshotDraftPreferences(prefs)
                            // Clear pointer and baseline
                            if (!clearCurrentDraftGenerationPointer(context)) return@withLock false
                            draftPointerBaseline = null
                            // Clear legacy prefs
                            val committed =
                                prefs
                                    .edit()
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
                                val prefsRestored =
                                    restoreDraftPreferencesOrThrow(
                                        prefs,
                                        prevPrefs,
                                        IllegalStateException("failed to clear draft prefs"),
                                    )
                                if (!prefsRestored) {
                                    logDraftSaveFailure(
                                        IllegalStateException("clearDraft pref rollback failed")
                                    )
                                }
                                val pointerRestored =
                                    if (expectedPointer != null) {
                                        publishDraftGeneration(context, expectedPointer)
                                    } else {
                                        true
                                    }
                                if (!pointerRestored) {
                                    logDraftSaveFailure(
                                        IllegalStateException("clearDraft pointer rollback failed")
                                    )
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
                                if (
                                    state.draftSavedAtMillis == null &&
                                        state.draftSourcePath == null &&
                                        state.draftBaseContentToken == null &&
                                        state.draftGenerationId == null &&
                                        state.draftGenerationSourcePath == null &&
                                        state.draftGenerationThumbnailPath == null &&
                                        state.recoveryDebugInfo == null &&
                                        state.showRecoveryDebugCard == false
                                ) {
                                    adopted = true
                                } else if (
                                    state.draftSavedAtMillis == visibleBefore.draftSavedAtMillis &&
                                        state.draftSourcePath == visibleBefore.draftSourcePath &&
                                        state.draftBaseContentToken ==
                                            visibleBefore.draftBaseContentToken &&
                                        state.draftGenerationId ==
                                            visibleBefore.draftGenerationId &&
                                        state.draftGenerationSourcePath ==
                                            visibleBefore.draftGenerationSourcePath &&
                                        state.draftGenerationThumbnailPath ==
                                            visibleBefore.draftGenerationThumbnailPath &&
                                        state.recoveryDebugInfo ==
                                            visibleBefore.recoveryDebugInfo &&
                                        state.showRecoveryDebugCard ==
                                            visibleBefore.showRecoveryDebugCard
                                ) {
                                    val updated =
                                        state.copy(
                                            draftSavedAtMillis = null,
                                            draftSourcePath = null,
                                            draftBaseContentToken = null,
                                            draftGenerationId = null,
                                            draftGenerationSourcePath = null,
                                            draftGenerationThumbnailPath = null,
                                            recoveryDebugInfo = null,
                                            showRecoveryDebugCard = false,
                                        )
                                    adopted = commitUiState(state, updated)
                                } else {
                                    // State changed - rollback
                                    val prefsRestored =
                                        restoreDraftPreferencesOrThrow(
                                            prefs,
                                            prevPrefs,
                                            IllegalStateException("clear superseded"),
                                        )
                                    if (!prefsRestored) {
                                        logDraftSaveFailure(
                                            IllegalStateException(
                                                "clearDraft supersession pref rollback failed"
                                            )
                                        )
                                    }
                                    val pointerRestored =
                                        if (expectedPointer != null) {
                                            publishDraftGeneration(context, expectedPointer)
                                        } else {
                                            true
                                        }
                                    if (!pointerRestored) {
                                        logDraftSaveFailure(
                                            IllegalStateException(
                                                "clearDraft supersession pointer rollback failed"
                                            )
                                        )
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
                            val liveSourceCanonical =
                                liveSourcePath?.let {
                                    runCatching { File(it).canonicalFile }.getOrNull()
                                }
                            val legacyDraftSourceCanonical =
                                legacyDraftSourcePath?.let {
                                    runCatching { File(it).canonicalFile }.getOrNull()
                                }
                            runCatching {
                                    persistentDraftDirectory(context).listFiles()?.forEach { file ->
                                        val canonical =
                                            runCatching { file.canonicalFile }.getOrNull()
                                        val isLiveSource =
                                            canonical != null &&
                                                liveSourceCanonical != null &&
                                                canonical == liveSourceCanonical
                                        if (isLiveSource) return@forEach
                                        if (file.name.endsWith(".tmp")) {
                                            val deleted = file.delete()
                                            if (!deleted)
                                                logDraftSaveFailure(
                                                    IllegalStateException(
                                                        "failed to delete temp file: ${file.absolutePath}"
                                                    )
                                                )
                                            return@forEach
                                        }
                                        val matchesLegacySource =
                                            canonical != null &&
                                                legacyDraftSourceCanonical != null &&
                                                canonical == legacyDraftSourceCanonical
                                        val isOwnedDraft =
                                            matchesLegacySource && isOwnedDraftSource(context, file)
                                        if (matchesLegacySource && isOwnedDraft) {
                                            val deleted = file.delete()
                                            if (!deleted)
                                                logDraftSaveFailure(
                                                    IllegalStateException(
                                                        "failed to delete legacy draft source: ${file.absolutePath}"
                                                    )
                                                )
                                        }
                                    }
                                }
                                .onFailure { logDraftSaveFailure(it) }
                            runCatching {
                                    expectedPointer?.let { deleteDraftGenerationById(context, it) }
                                }
                                .onFailure { logDraftSaveFailure(it) }
                            runCatching {
                                    val thumbFile = persistentDraftThumbnailFile(context)
                                    if (thumbFile.isFile) {
                                        val deleted = thumbFile.delete()
                                        if (!deleted)
                                            logDraftSaveFailure(
                                                IllegalStateException(
                                                    "failed to delete draft thumbnail: ${thumbFile.absolutePath}"
                                                )
                                            )
                                    }
                                }
                                .onFailure { logDraftSaveFailure(it) }
                            runCatching {
                                    expectedPointer?.let {
                                        ThumbnailBitmapCache.invalidate("draft:$it")
                                    }
                                    legacyDraftSourcePath?.let {
                                        ThumbnailBitmapCache.invalidate("draft:legacy:$it")
                                    }
                                }
                                .onFailure { logDraftSaveFailure(it) }
                            true
                        }
                    }
                if (!cleared) {
                    updateUiStateAndRecycleReplaced {
                        if (clearEpoch == draftOperationEpoch && !it.isBusy)
                            it.copy(message = "임시 저장 삭제에 실패했습니다. 기존 임시 저장을 유지합니다.")
                        else it
                    }
                    return@launch
                }
                updateUiStateAndRecycleReplaced {
                    if (clearEpoch != draftOperationEpoch) it
                    else it.copy(message = "자동복구용 임시저장 기록을 삭제했습니다. 현재 편집 화면은 유지됩니다")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                logDraftSaveFailure(t)
                updateUiStateAndRecycleReplaced {
                    if (clearEpoch == draftOperationEpoch && !it.isBusy)
                        it.copy(message = "임시 저장 삭제에 실패했습니다. 기존 임시 저장을 유지합니다.")
                    else it
                }
            } finally {
                finishMaintenance()
            }
        }
    }

    fun dismissRecoveryDebugCard() {
        updateUiStateAndRecycleReplaced { it.copy(showRecoveryDebugCard = false) }
    }

    fun cleanupOldTemporarySources() {
        if (!beginMaintenance("오래된 임시 원본을 정리하는 중입니다")) return
        val context = getApplication<Application>()
        val activeSourcePath = _uiState.value.sourcePath
        viewModelScope.launch {
            try {
                val removedCount =
                    withContext(Dispatchers.IO) {
                        cleanupTemporarySourceFiles(context, activeSourcePath = activeSourcePath)
                    }
                updateUiStateAndRecycleReplaced {
                    it.copy(message = "7일이 지난 임시 원본 캐시를 정리했습니다. 삭제된 파일: ${removedCount}개")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                updateUiStateAndRecycleReplaced {
                    it.copy(message = "임시 원본 정리에 실패했습니다: ${t.message ?: "알 수 없는 오류"}")
                }
            } finally {
                finishMaintenance()
            }
        }
    }

    fun clearSavedExports() {
        if (!beginMaintenance("저장 기록을 비우는 중입니다")) return
        val context = getApplication<Application>()
        viewModelScope.launch {
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        savedExportHistoryStore.clear()
                    }
                invalidateRemovedHistoryThumbnails(context, result)
                updateUiStateAndRecycleReplaced {
                    if (result.revision != savedExportHistoryStore.revision) it
                    else
                        it.copy(
                            savedExports = result.items,
                            message =
                                if (it.isBusy) it.message else "내보낸 사진 기록을 모두 비웠습니다. 갤러리 파일은 삭제되지 않습니다",
                        )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                updateUiStateAndRecycleReplaced {
                    it.copy(message = "저장 기록을 비우지 못했습니다: ${t.message ?: "알 수 없는 오류"}")
                }
            } finally {
                finishMaintenance()
            }
        }
    }

    fun removeSavedExport(uriString: String) {
        if (!beginMaintenance("저장 기록을 삭제하는 중입니다")) return
        val context = getApplication<Application>()
        viewModelScope.launch {
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        savedExportHistoryStore.remove(uriString)
                    }
                invalidateRemovedHistoryThumbnails(context, result)
                updateUiStateAndRecycleReplaced {
                    if (result.revision != savedExportHistoryStore.revision) it
                    else
                        it.copy(
                            savedExports = result.items,
                            message =
                                if (it.isBusy) it.message
                                else "선택한 내보낸 사진 기록을 삭제했습니다. 갤러리 파일은 삭제되지 않습니다",
                        )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                updateUiStateAndRecycleReplaced {
                    it.copy(message = "저장 기록 삭제에 실패했습니다: ${t.message ?: "알 수 없는 오류"}")
                }
            } finally {
                finishMaintenance()
            }
        }
    }

    fun updateViewport(viewport: ViewportState) {
        val current = _uiState.value
        val image = current.previewBitmap ?: current.originalPreviewBitmap
        val safeScale = viewport.scale.takeIf { it.isFinite() && it >= 1f } ?: 1f
        val safeViewportWidth = viewport.viewportWidth.coerceAtLeast(0)
        val safeViewportHeight = viewport.viewportHeight.coerceAtLeast(0)
        val geometry =
            if (image != null) {
                PreviewGeometry(
                    container = IntSize(safeViewportWidth, safeViewportHeight),
                    imageWidth = image.width,
                    imageHeight = image.height,
                    zoom = safeScale,
                    pan = viewport.offset,
                )
            } else null
        val settled =
            viewport.copy(
                scale = safeScale,
                offset = geometry?.clampedPan() ?: androidx.compose.ui.geometry.Offset.Zero,
                viewportWidth = safeViewportWidth,
                viewportHeight = safeViewportHeight,
            )
        updateUiStateAndRecycleReplaced { it.copy(viewport = settled) }
        // TODO v0.2: viewport가 scale 임계값 이상이면 ROI 타일 렌더 Job 발행.
    }

    fun undoEdit() = navigateHistory(undo = true)

    fun redoEdit() = navigateHistory(undo = false)

    private fun navigateHistory(undo: Boolean, expectedTargetId: String? = null) {
        if (!canEnterEditorAction()) return
        if (historyCoordinator.flags().busy || historyIoJob?.isActive == true) {
            updateUiStateAndRecycleReplaced {
                it.copy(message = "편집 기록을 정리하는 중입니다. 잠시 후 다시 시도해 주세요.")
            }
            return
        }
        invalidateSelectionPreview()
        abortPendingParameterEdit()
        invalidateCropOperation()
        val flags = historyCoordinator.flags()
        if ((undo && !flags.canUndo) || (!undo && !flags.canRedo)) {
            updateUiStateAndRecycleReplaced {
                it.copy(message = if (undo) "되돌리기 편집 기록이 없습니다." else "다시 실행할 편집 기록이 없습니다.")
            }
            return
        }
        renderJob?.cancel()
        invalidateExport()
        updateUiStateAndRecycleReplaced {
            it.copy(isBusy = true, message = "저장된 편집 기록을 불러오는 중입니다.")
        }
        val navTracker =
            beginMemoryTracking(
                if (undo) "navigateHistory:undo" else "navigateHistory:redo",
                snapshotState = "navigating",
                transientReserveBytes = _uiState.value.historyBitmapBytes(),
            )
        val navJob =
            viewModelScope.launch {
                try {
                    val result =
                        historyCoordinator.navigate(
                            undoDirection = undo,
                            expectedTargetId = expectedTargetId,
                            currentCaptureBytes = _uiState.value.historyBitmapBytes(),
                            captureCurrent = { preferredStorage, targetBaseToken ->
                                captureHistorySnapshotForNavigation(
                                    preferredStorage,
                                    targetBaseToken,
                                )
                            },
                            materialize = { snapshot, register ->
                                materializeHistorySnapshot(snapshot, register, navTracker)
                            },
                            preflightSelectionMasks = { preflight ->
                                reserveSelectionMaskCandidateBytes(
                                    owner = "history-cold-materialize",
                                    bytes = preflight.uniqueMaskBytes,
                                    documentLayerCount = preflight.layerCount,
                                ).takeUnless {
                                    it is SelectionMaskOwnershipLedger.MaskAdmission.Rejected
                                }
                            },
                            adopt = { snapshot ->
                                val prefix = if (undo) "이전 편집 상태를 적용했습니다" else "다음 편집 상태를 적용했습니다"
                                applyHistorySnapshot(
                                    snapshot,
                                    buildHistoryAppliedMessage(_uiState.value, snapshot, prefix),
                                )
                            },
                        )
                    when (result) {
                        is HistoryNavigationResult.Adopted -> {
                            scheduleDraftAutosave()
                            updateHistoryFlags()
                            if (result.movedToStorage)
                                updateUiStateAndRecycleReplaced {
                                    it.copy(message = "오래된 편집 기록을 저장소로 옮겼습니다.")
                                }
                        }
                        is HistoryNavigationResult.Unavailable ->
                            updateUiStateAndRecycleReplaced {
                                it.copy(
                                    isBusy = false,
                                    message =
                                        if (undo) "되돌리기 편집 기록이 없습니다." else "다시 실행할 편집 기록이 없습니다.",
                                )
                            }
                        is HistoryNavigationResult.Busy ->
                            updateUiStateAndRecycleReplaced {
                                it.copy(
                                    isBusy = false,
                                    message = "편집 기록을 정리하는 중입니다. 잠시 후 다시 시도해 주세요.",
                                )
                            }
                        is HistoryNavigationResult.MemoryRejected -> {
                            updateUiStateAndRecycleReplaced { it.copy(isBusy = false) }
                            val targetId = historyCoordinator.navigationTargetId(undo)
                            if (
                                targetId != null &&
                                    (expectedTargetId == null || expectedTargetId == targetId)
                            ) {
                                requestAllocationRecovery(
                                    if (undo) MemoryRetryAction.HistoryUndo
                                    else MemoryRetryAction.HistoryRedo,
                                    result.requiredBytes,
                                    targetId,
                                )
                            }
                        }
                        is HistoryNavigationResult.Failed ->
                            updateUiStateAndRecycleReplaced {
                                it.copy(
                                    isBusy = false,
                                    message = "저장된 편집 기록을 불러오지 못했습니다. 현재 편집과 기록은 유지됩니다.",
                                )
                            }
                    }
                } catch (ce: CancellationException) {
                    if (!shuttingDown) updateUiStateAndRecycleReplaced { it.copy(isBusy = false) }
                    throw ce
                } catch (_: Throwable) {
                    if (!shuttingDown)
                        updateUiStateAndRecycleReplaced {
                            it.copy(
                                isBusy = false,
                                message = "저장된 편집 기록을 불러오지 못했습니다. 현재 편집과 기록은 유지됩니다.",
                            )
                        }
                } finally {
                    if (historyIoJob === currentCoroutineContext()[Job]) historyIoJob = null
                    updateHistoryFlags()
                    navTracker?.end()
                }
            }
        historyIoJob = navJob
        navJob.invokeOnCompletion {
            navTracker?.end()
            if (historyIoJob === navJob) historyIoJob = null
        }
        if (navJob.isCompleted && historyIoJob === navJob) historyIoJob = null
    }

    internal fun clearRedoAfterAdoptedEdit() {
        if (shuttingDown) return
        val job =
            viewModelScope.launch {
                historyCoordinator.clearRedoAfterAdoptedEdit()
                if (historyIoJob === currentCoroutineContext()[Job]) historyIoJob = null
                updateHistoryFlags()
            }
        historyIoJob = job
        job.invokeOnCompletion { if (historyIoJob === job) historyIoJob = null }
        if (job.isCompleted && historyIoJob === job) historyIoJob = null
    }

    internal fun settleAdoptedEditHistory(snapshot: EditorHistorySnapshot?): Boolean {
        automaticRetryAttempt = null
        strongRetryAttempt = null
        return if (snapshot != null) commitUndoSnapshot(snapshot, clearRedo = true)
        else {
            clearRedoAfterAdoptedEdit()
            updateUiStateAndRecycleReplaced {
                it.copy(message = "편집은 적용했지만 메모리가 부족하여 이번 되돌리기 기록은 저장하지 못했습니다.")
            }
            false
        }
    }

    fun rotatePreview90() {
        if (shuttingDown) return
        rotatePreview90Async()
    }

    private fun rotatePreview90Async() {
        settleParameterTransactionBeforeExternalEdit()
        if (uiState.value.isBusy) return
        invalidateSelectionPreview()
        invalidateManagedEdits()
        renderJob?.cancel()
        prepareForExternalEdit()
        val start = acquireEditorSnapshot("rotatePreview90") ?: return
        val state = start.state
        val preview = start.previewBitmap
        if (preview == null || preview.isRecycled) {
            start.close()
            updateUiState { it.copy(message = "\uD68C\uC804\uD560 \uC774\uBBF8\uC9C0\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4.") }
            return
        }
        val pendingHistory = prepareHistorySnapshot("rotatePreview90", start)
        updateUiState { it.copy(isBusy = true) }
        viewModelScope.launch(Dispatchers.Default) {
            var before: EditorHistorySnapshot? = null
            var rotatedPreview: Bitmap? = null
            var rotatedOriginal: Bitmap? = null
            val rotatedMasks = ArrayList<Bitmap>(start.selectionLayers.size)
            try {
                before = pendingHistory.await()
                rotatedPreview = rotateBitmap90(preview)
                rotatedOriginal = when {
                    start.originalPreviewBitmap == null -> null
                    start.originalPreviewBitmap === preview -> rotatedPreview
                    else -> rotateBitmap90(start.originalPreviewBitmap)
                }
                start.selectionLayers.forEach { rotatedMasks += rotateBitmap90(it.bitmap) }
                val nextCrop =
                    state.cropState.copy(
                        cropLeft = 1f - state.cropState.cropBottom,
                        cropTop = state.cropState.cropLeft,
                        cropRight = 1f - state.cropState.cropTop,
                        cropBottom = state.cropState.cropRight,
                        aspectRatio = state.cropState.aspectRatio.rotatedForQuarterTurn(),
                        rotationDegrees = state.cropState.rotationDegrees,
                    ).normalized()
                withContext(Dispatchers.Main) {
                    val current = uiState.value
                    val currentIdentity =
                        !shuttingDown &&
                            current.sourcePath == start.identity.sourcePath &&
                            current.baseContentToken == start.identity.baseContentToken &&
                            current.revision == start.identity.revision &&
                            currentDocumentGeneration() == start.identity.generation
                    if (currentIdentity && before != null) {
                        val nextPreview = checkNotNull(rotatedPreview)
                        val nextOriginal = rotatedOriginal
                        val nextMasks = rotatedMasks.toList()
                        updateUiStateAndRecycleReplaced { live ->
                            live.copy(
                                previewBitmap = nextPreview,
                                originalPreviewBitmap = nextOriginal,
                                selectionLayers =
                                    live.selectionLayers.mapIndexed { index, layer ->
                                        layer.copy(bitmap = nextMasks[index])
                                    },
                                cropState = nextCrop,
                                baseBitmapDirty = true,
                                baseContentToken = newBaseContentToken(),
                                revision = live.revision + 1,
                                isBusy = false,
                                message = "\uBBF8\uB9AC\uBDF0\uC744 90\uB3C4 \uD68C\uC804\uD588\uC2B5\uB2C8\uB2E4.",
                            )
                        }
                        rotatedPreview = null
                        rotatedOriginal = null
                        rotatedMasks.clear()
                        val retained = before
                        before = null
                        settleAdoptedEditHistory(retained)
                        forceDraftSaveAsync()
                    } else if (current.sourcePath == start.identity.sourcePath &&
                        current.baseContentToken == start.identity.baseContentToken) {
                        updateUiState { it.copy(isBusy = false) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                withContext(Dispatchers.Main) {
                    if (uiState.value.sourcePath == start.identity.sourcePath &&
                        uiState.value.baseContentToken == start.identity.baseContentToken) {
                        updateUiState { it.copy(isBusy = false, message = "\uBBF8\uB9AC\uBDF0 \uD68C\uC804\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4.") }
                        if (failure is BitmapAllocationRejectedException) {
                            requestAllocationRecovery(MemoryRetryAction.RotatePreview, failure.requiredBytes)
                        }
                    }
                }
            } finally {
                before?.let(::recycleHistorySnapshot)
                pendingHistory.close()
                val cleanup = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
                rotatedPreview?.let(cleanup::add)
                rotatedOriginal?.let(cleanup::add)
                rotatedMasks.forEach(cleanup::add)
                cleanup.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
                start.close()
            }
        }
    }

    fun applySpotCleanup() {
        applyNativeSpecialEffects(
            title = "기본 정리",
            failureMessage = "기본 정리 적용에 실패했습니다.",
            effect = ActiveQuickEffect(QuickEffectKind.SpotCleanup),
        )
    }

    fun applyChromaticAberrationReduction() {
        applyNativeSpecialEffects(
            title = "색수차 완화",
            failureMessage = "색수차 완화 적용에 실패했습니다.",
            effect = ActiveQuickEffect(QuickEffectKind.ChromaticAberrationReduction),
        )
    }

    fun applyVignetteCorrection() {
        applyNativeSpecialEffects(
            title = "주변부 어두움 완화",
            failureMessage = "주변부 어두움 완화 적용에 실패했습니다.",
            effect = ActiveQuickEffect(QuickEffectKind.VignetteCorrection),
        )
    }

    fun applyOpticsCorrection() {
        applyNativeSpecialEffects(
            title = "통합 광학 보정",
            failureMessage = "통합 광학 보정 적용에 실패했습니다.",
            effect = ActiveQuickEffect(QuickEffectKind.OpticsCorrection),
        )
    }

    fun applySoftBlur(strength: Float = 0.32f) {
        applyNativeSpecialEffects(
            title = "부드러운 흐림",
            failureMessage = "부드러운 흐림 적용에 실패했습니다.",
            effect =
                ActiveQuickEffect(
                    kind = QuickEffectKind.SoftBlur,
                    strength = strength.toQuickEffectStrength(),
                ),
        )
    }

    private fun applyNativeSpecialEffects(
        title: String,
        failureMessage: String,
        effect: ActiveQuickEffect,
    ) {
        if (isShuttingDown()) return
        prepareForExternalEdit()
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) return
        val startSnapshot = acquireEditorSnapshot("nativeSpecialEffects") ?: return
        val current = startSnapshot.state
        val baseOriginal = startSnapshot.originalPreviewBitmap ?: startSnapshot.previewBitmap
        if (baseOriginal == null || baseOriginal.isRecycled) {
            startSnapshot.close()
            updateUiStateAndRecycleReplaced { it.copy(message = "적용할 이미지가 없습니다.") }
            return
        }
        val currentQuickEffects = current.activeQuickEffects
        val nextActiveQuickEffects = currentQuickEffects.toggle(effect)
        if (nextActiveQuickEffects == currentQuickEffects) {
            startSnapshot.close()
            return
        }
        var pendingHistory: PendingHistorySnapshot? =
            prepareHistorySnapshot("nativeSpecialEffects", startSnapshot)
        var ownedBase: Bitmap? = null
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
            it.copy(isBusy = true, revision = nextRevision, message = "$title 적용 중입니다.")
        }
        launchManagedRenderWithPreparedResources({ operationToken ->
            var undoSnapshot: EditorHistorySnapshot? =
                pendingHistory?.await()
            pendingHistory = null
            var renderedPreview: Bitmap? = null
            var renderSuccess: RenderResult.Success? = null
            val effectsTracker =
                beginMemoryTracking(
                    "applyNativeSpecialEffects:$title",
                    snapshotState = "rendering",
                    transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
                )
            try {
                ownedBase = baseOriginal.copyOrThrow()
                effectsTracker?.track(
                    checkNotNull(ownedBase),
                    "applyNativeSpecialEffects:ownedBase",
                )
                withContext(Dispatchers.Default) {
                    renderSuccess =
                        EditorRenderer.render(
                            createRenderRequest(
                                state = current,
                                operation = RenderOperation.QuickEffect,
                                basePreview = checkNotNull(ownedBase),
                                revision = nextRevision,
                                params = params,
                                engines = engines,
                                look = presetLook,
                                quickEffects = requestedQuickEffects,
                                diagnostics = effectsTracker,
                            )
                        ).successOrThrow()
                    renderedPreview = checkNotNull(renderSuccess).output
                }
                effectsTracker?.track(renderedPreview!!, "applyNativeSpecialEffects:rendered")
                val identityUnchanged =
                    uiState.value.sourcePath == sourcePath &&
                        uiState.value.baseContentToken == baseContentToken
                if (isManagedEditCurrent(operationToken, nextRevision) && identityUnchanged) {
                    val adoptedPreview = renderedPreview!!
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            previewBitmap = adoptedPreview,
                            activeQuickEffects = requestedQuickEffects,
                            isBusy = false,
                            correctionEngineState =
                                it.correctionEngineState.withSuccessfulRender(
                                    current.correctionEngineState.documentEngine,
                                    checkNotNull(renderSuccess),
                                ),
                            message =
                                if (
                                    requestedQuickEffects.any { active -> active.matches(effect) }
                                ) {
                                    "$title 적용했습니다. 다시 누르면 해제할 수 있습니다."
                                } else {
                                    "$title 적용을 해제했습니다."
                                },
                        )
                    }
                    renderedPreview = null
                    settleAdoptedEditHistory(undoSnapshot)
                    undoSnapshot = null
                    forceDraftSaveAsync()
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(FLARE_GUARD_AI_TAG, "$title native special effect failed", t)
                val failureIdentityUnchanged =
                    uiState.value.sourcePath == sourcePath &&
                        uiState.value.baseContentToken == baseContentToken
                if (
                    isManagedEditCurrent(operationToken, nextRevision) && failureIdentityUnchanged
                ) {
                    (t as? RenderFailedException)?.failure?.let { failure ->
                        updateUiState {
                            it.copy(
                                correctionEngineState =
                                    it.correctionEngineState.withFailedRender(
                                        current.correctionEngineState.documentEngine,
                                        failure,
                                    )
                            )
                        }
                    }
                    updateUiStateAndRecycleReplaced {
                        it.copy(isBusy = false, message = failureMessage)
                    }
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } finally {
                renderedPreview?.takeIf { !it.isRecycled }?.recycle()
                ownedBase?.takeIf { !it.isRecycled }?.recycle()
                effectsTracker?.end()
                startSnapshot.close()
            }
        }, PreparedResourceHandoff.create(
            "nativeSpecialEffects",
            {
                pendingHistory?.close()
                pendingHistory = null
                startSnapshot.close()
            },
            {
                val live = _uiState.value
                if (live.revision == nextRevision && live.sourcePath == sourcePath &&
                    live.baseContentToken == baseContentToken) {
                    updateUiState { it.copy(isBusy = false) }
                }
            },
        ))
    }

    fun applyFlareGuardAiOrRulePreview(context: Context, mode: FlareGuardMode) {
        if (shuttingDown) return
        settleParameterTransactionBeforeExternalEdit()
        if (uiState.value.isBusy && !isBusyOwnedByMaskSupersedable()) {
            return
        }
        prepareForExternalEdit()
        val startSnapshot = acquireEditorSnapshot("flareGuard") ?: return
        val current = startSnapshot.state
        val baseOriginal =
            startSnapshot.originalPreviewBitmap ?: startSnapshot.previewBitmap
                ?: run {
                    startSnapshot.close()
                    return
                }
        if (baseOriginal.isRecycled) {
            startSnapshot.close()
            updateUiStateAndRecycleReplaced { it.copy(message = "번짐 완화를 적용할 이미지가 없습니다.") }
            return
        }
        val label =
            when (mode) {
                FlareGuardMode.NightLight -> "번짐 영역 감지"
                FlareGuardMode.DaySun -> "태양 번짐 영역 감지"
            }
        val nextRevision = current.revision + 1
        var pendingHistory: PendingHistorySnapshot? =
            prepareHistorySnapshot("flareGuard", startSnapshot)
        val sourcePath = current.sourcePath
        val baseContentToken = current.baseContentToken
        val params = current.params
        val engines = current.engineSelection()
        val presetLook = current.presetLook
        val quickEffects = current.activeQuickEffects
        val appContext = context.applicationContext
        val documentGeneration = historyCoordinator.currentGeneration()
        val flareOverride = ExperimentalLabController.debugOverrides().flareGuard
        updateUiStateAndRecycleReplaced {
            it.copy(
                isBusy = true,
                revision = nextRevision,
                message = "$label 처리 중입니다.",
                flareGuardRuntimeStatus = "플레어 마스크 모델 상태를 확인하는 중입니다.",
            )
        }
        Log.i(
            FLARE_GUARD_AI_TAG,
            "Starting FlareGuard preview: mode=$mode source=${baseOriginal.width}x${baseOriginal.height} revision=$nextRevision",
        )
        launchManagedRenderWithPreparedResources({ operationToken ->
            var flareGuardResult: FlareGuardApplyResult? = null
            var flareGuardBitmap: Bitmap? = null
            var renderedPreview: Bitmap? = null
            var renderSuccess: RenderResult.Success? = null
            var resolvedFlareRoute: ResolvedFeatureRoute<FlareGuardRoute>? = null
            var undoSnapshotOwned: EditorHistorySnapshot? =
                pendingHistory?.await()
            pendingHistory = null
            var ownedBaseOwned: Bitmap? = null
            val flareTracker =
                beginMemoryTracking(
                    "applyFlareGuardAiOrRulePreview",
                    snapshotState = "rendering",
                    transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
                )
            try {
                ownedBaseOwned = baseOriginal.copyOrThrow()
                flareTracker?.track(checkNotNull(ownedBaseOwned), "applyFlareGuard:ownedBase")
                val result =
                    withContext(Dispatchers.Default) {
                        val inferenceJob = currentCoroutineContext()[Job]
                        val modelOperation =
                            ModelOperationContext(
                                operationToken = operationToken,
                                documentGeneration = documentGeneration,
                                documentIdentity = sourcePath,
                                isCurrent = { token, generation ->
                                    !shuttingDown &&
                                        managedEdits.isCurrent(token) &&
                                        historyCoordinator.currentGeneration() == generation &&
                                        _uiState.value.sourcePath == sourcePath &&
                                        _uiState.value.baseContentToken == baseContentToken &&
                                        _uiState.value.revision == nextRevision
                                },
                                isCancelled = { inferenceJob?.isActive == false },
                            )
                        val preloadedModel =
                            if (flareOverride == FlareGuardRoute.V2ModelAssisted) {
                                FlareGuardModelRunner.loadValidated(appContext)
                            } else {
                                null
                            }
                        val flareResolution =
                            RouteResolver.resolveFlareRoute(
                                current.correctionEngineState.documentEngine,
                                flareOverride,
                                modelAvailable = preloadedModel is ModelLoadResult.Ready,
                            )
                        resolvedFlareRoute = flareResolution
                        val flareAlgorithm = flareResolution.actualRoute
                        val r =
                            if (flareAlgorithm == FlareGuardRoute.V1 ||
                                flareAlgorithm == FlareGuardRoute.ForcedV1Fallback
                            ) {
                                applyFlareGuardModelOrRuleResultV0(
                                    appContext,
                                    checkNotNull(ownedBaseOwned),
                                    mode,
                                    allowRuleFallback = true,
                                    diagnostics = flareTracker,
                                    operation = modelOperation,
                                )
                            } else {
                                applyExperimentalFlareGuardV2(
                                    context = appContext,
                                    source = checkNotNull(ownedBaseOwned),
                                    flareMode = mode,
                                    algorithmMode = flareAlgorithm,
                                    strength =
                                        when (mode) {
                                            FlareGuardMode.NightLight -> 0.52f
                                            FlareGuardMode.DaySun -> 0.44f
                                        },
                                    diagnostics = flareTracker,
                                    operation = modelOperation,
                                    preloadedModel = preloadedModel,
                                )
                            }
                        flareGuardResult = r
                        r
                    }
                flareGuardBitmap =
                    result.adoptToOrNull("applyFlareGuard:flareGuardBitmap")
                        ?: error("FlareGuard result ownership was already settled")
                renderedPreview =
                    withContext(Dispatchers.Default) {
                        renderSuccess =
                            EditorRenderer.render(
                                createRenderRequest(
                                    state = current,
                                    operation = RenderOperation.FlareGuard,
                                    basePreview = checkNotNull(flareGuardBitmap),
                                    revision = nextRevision,
                                    params = params,
                                    engines = engines,
                                    look = presetLook,
                                    quickEffects = quickEffects,
                                    diagnostics = flareTracker,
                                )
                            ).successOrThrow().let { success ->
                                val status = checkNotNull(flareGuardResult).status
                                success.copy(
                                    participation =
                                        RenderParticipation(
                                            model =
                                                status ==
                                                    FlareGuardRuntimeStatus.ExperimentalV2Model ||
                                                    status ==
                                                        FlareGuardRuntimeStatus.ModelInferenceSuccess,
                                            rule =
                                                status ==
                                                    FlareGuardRuntimeStatus.ExperimentalV2Rule ||
                                                    status ==
                                                        FlareGuardRuntimeStatus.ModelUnavailableRuleFallback ||
                                                    status ==
                                                        FlareGuardRuntimeStatus.ModelFailedRuleFallback,
                                        ),
                                )
                            }
                        checkNotNull(renderSuccess).output.also { renderedPreview = it }
                    }
                flareTracker?.track(renderedPreview!!, "applyFlareGuard:renderedPreview")
                val adoptionIdentityUnchanged =
                    !shuttingDown &&
                        _uiState.value.sourcePath == sourcePath &&
                        _uiState.value.baseContentToken == baseContentToken &&
                        _uiState.value.revision == nextRevision &&
                        managedEdits.isCurrent(operationToken)
                if (
                    isManagedEditCurrent(operationToken, nextRevision) && adoptionIdentityUnchanged
                ) {
                    val adoptedOriginal = flareGuardBitmap!!
                    val adoptedPreview = renderedPreview!!
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            originalPreviewBitmap = adoptedOriginal,
                            previewBitmap = adoptedPreview,
                            baseBitmapDirty = true,
                            baseContentToken = newBaseContentToken(),
                            isBusy = false,
                            correctionEngineState =
                                it.correctionEngineState.withSuccessfulRender(
                                    current.correctionEngineState.documentEngine,
                                    checkNotNull(renderSuccess),
                                ),
                            message = flareGuardResult!!.status.uiText,
                            flareGuardRuntimeStatus = flareGuardResult!!.status.uiText,
                        ).withBakedFeatureProvenance(
                            provenance =
                                BakedFeatureProvenance(
                                    feature = BakedFeatureType.FlareGuard,
                                    operationId = operationToken.toString(),
                                    sequence =
                                        (it.baseProvenance.operations.lastOrNull()?.sequence ?: 0L) +
                                            1L,
                                    requestedRoute =
                                        checkNotNull(resolvedFlareRoute).requestedRoute.name,
                                    actualRoute =
                                        checkNotNull(resolvedFlareRoute).actualRoute.name,
                                    participation =
                                        when (flareGuardResult!!.algorithmDecision) {
                                            FlareGuardV2Decision.ModelRuleFused ->
                                                RenderParticipation(model = true, rule = true)
                                            FlareGuardV2Decision.ModelAccepted ->
                                                RenderParticipation(model = true)
                                            else ->
                                                when (flareGuardResult!!.status) {
                                                    FlareGuardRuntimeStatus.ExperimentalV2Model,
                                                    FlareGuardRuntimeStatus.ModelInferenceSuccess ->
                                                        RenderParticipation(model = true)
                                                    FlareGuardRuntimeStatus.ExperimentalV2Rule,
                                                    FlareGuardRuntimeStatus.ModelUnavailableRuleFallback,
                                                    FlareGuardRuntimeStatus.ModelFailedRuleFallback ->
                                                        RenderParticipation(rule = true)
                                                    else -> RenderParticipation()
                                                }
                                        },
                                    capabilityPhase =
                                        ModelAvailabilityRegistry.state.value[
                                                ModelFeature.FlareGuard]
                                            ?.phase,
                                    outcome =
                                        when {
                                            flareGuardResult!!.fallbackReason != null ->
                                                FeatureExecutionOutcome.Fallback
                                            flareGuardResult!!.algorithmDecision in
                                                setOf(
                                                    FlareGuardV2Decision.MaskInsignificant,
                                                    FlareGuardV2Decision.NoOpZeroStrength,
                                                    FlareGuardV2Decision.NoOpSafetyFallback,
                                                ) -> FeatureExecutionOutcome.NoOp
                                            flareGuardResult!!.algorithmDecision ==
                                                FlareGuardV2Decision.ModelRejectedByQuality ->
                                                FeatureExecutionOutcome.QualityRejected
                                            else -> FeatureExecutionOutcome.Applied
                                        },
                                    fallbackReason =
                                        flareGuardResult!!.fallbackReason?.toString(),
                                    mask = flareGuardResult!!.maskSummary,
                                    stageContract =
                                        if (flareGuardResult!!.algorithmDecision != null) {
                                            AlgorithmContracts.FLARE_V2
                                        } else {
                                            AlgorithmContracts.FLARE_V1
                                        },
                                    timestampMillis = System.currentTimeMillis(),
                                ),
                            nativeRenderContract = checkNotNull(renderSuccess).algorithmVersion,
                        )
                    }
                    flareGuardBitmap = null
                    renderedPreview = null
                    settleAdoptedEditHistory(undoSnapshotOwned)
                    undoSnapshotOwned = null
                    forceDraftSaveAsync()
                    Log.i(
                        FLARE_GUARD_AI_TAG,
                        "Finished FlareGuard preview: mode=$mode status=${flareGuardResult!!.status} output=${flareGuardResult!!.bitmap.width}x${flareGuardResult!!.bitmap.height}",
                    )
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(FLARE_GUARD_AI_TAG, "FlareGuard preview failed", t)
                val failureIdentityUnchanged =
                    !shuttingDown &&
                        _uiState.value.sourcePath == sourcePath &&
                        _uiState.value.baseContentToken == baseContentToken &&
                        _uiState.value.revision == nextRevision &&
                        managedEdits.isCurrent(operationToken)
                if (
                    isManagedEditCurrent(operationToken, nextRevision) && failureIdentityUnchanged
                ) {
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            isBusy = false,
                            message = "번짐 영역 감지에 실패했습니다.",
                            flareGuardRuntimeStatus = "번짐 영역 감지에 실패했습니다.",
                        )
                    }
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } finally {
                flareGuardResult?.recycleIfOwned()
                flareGuardBitmap?.takeIf { !it.isRecycled }?.recycle()
                renderedPreview?.takeIf { !it.isRecycled }?.recycle()
                ownedBaseOwned?.takeIf { !it.isRecycled }?.recycle()
                undoSnapshotOwned?.let(::recycleHistorySnapshot)
                flareTracker?.end()
                startSnapshot.close()
            }
        }, PreparedResourceHandoff.create(
            "modelFlareGuard",
            {
                pendingHistory?.close()
                pendingHistory = null
                startSnapshot.close()
            },
            {
                val live = _uiState.value
                if (live.revision == nextRevision && live.sourcePath == sourcePath &&
                    live.baseContentToken == baseContentToken) {
                    updateUiState { it.copy(isBusy = false) }
                }
            },
        ))
    }

    private suspend fun restoreCurrentDraftGeneration(
        context: Context,
        restoreToken: Long,
        restoreStartRevision: Int,
    ): GenerationRestoreOutcome {
        val pointer =
            withContext(Dispatchers.IO) { currentDraftGenerationId(context) }
                ?: return GenerationRestoreOutcome.Absent
        val validated =
            withContext(Dispatchers.IO) { validateCurrentDraftGeneration(context) }
                ?: return if (
                    withContext(Dispatchers.IO) { currentDraftGenerationId(context) } != pointer
                ) {
                    GenerationRestoreOutcome.Stale
                } else {
                    GenerationRestoreOutcome.Invalid(pointer)
                }
        if (validated.directory.root.name != pointer) return GenerationRestoreOutcome.Stale
        val manifest = validated.manifest
        val engines =
            runCatching {
                    EngineSelection(
                        NoiseEngine.valueOf(manifest.noiseEngine),
                        DetailEngine.valueOf(manifest.detailEngine),
                        ToneEngine.valueOf(manifest.toneEngine),
                        DehazeEngine.valueOf(manifest.hazeEngine),
                    )
                }
                .getOrNull() ?: return GenerationRestoreOutcome.Invalid(pointer)
        val exportFormat =
            runCatching { ExportFormat.valueOf(manifest.exportFormat) }.getOrNull()
                ?: return GenerationRestoreOutcome.Invalid(pointer)
        val exportResolution =
            runCatching { ExportResolution.valueOf(manifest.exportResolution) }.getOrNull()
                ?: return GenerationRestoreOutcome.Invalid(pointer)
        updateUiStateAndRecycleReplaced {
            if (restoreToken == restoreDraftToken && it.revision == restoreStartRevision) {
                it.copy(isBusy = true, message = "임시저장된 편집을 불러오는 중입니다")
            } else it
        }
        var ownedBase: Bitmap? = null
        var ownedRendered: Bitmap? = null
        var restoreRenderSuccess: RenderResult.Success? = null
        val ownedMasks = ArrayList<Bitmap>(validated.maskFiles.size)
        var createdSession = 0L
        var ownedWorkingSource: File? = null
        var restoreSettlementLocked = false
        var restorePreviousBaseline: String? = null
        var restoreBaselineChanged = false
        var restoreStateAdopted = false
        var restoreMaskAdmission: SelectionMaskOwnershipLedger.MaskAdmission? = null
        val restoreTracker =
            beginMemoryTracking(
                "restoreCurrentDraftGeneration",
                snapshotState = "decoding",
                transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
            )
        try {
            withContext(Dispatchers.IO) {
                val workingSource = copyGenerationSourceToWorkingFile(context, validated.sourceFile)
                ownedWorkingSource = workingSource
                val decodedBase =
                    decodeSampledMutableBitmapWithExif(
                        workingSource.absolutePath,
                        maxSide = 2048,
                        restoreTracker,
                )
                ownedBase = decodedBase
            }
            val base = checkNotNull(ownedBase)
            val maskPlan =
                withContext(Dispatchers.IO) {
                    if (validated.maskFiles.size != manifest.selectionLayers.size) {
                        error("draft mask metadata count mismatch")
                    }
                    validated.maskFiles.map { file ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(file.absolutePath, bounds)
                        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                            error("draft mask bounds are invalid")
                        }
                        if (bounds.outWidth != base.width || bounds.outHeight != base.height) {
                            error("draft mask geometry mismatch")
                        }
                        file to BitmapMemoryBudget.bytes(
                            bounds.outWidth,
                            bounds.outHeight,
                            Bitmap.Config.ARGB_8888,
                        )
                    }
                }
            val plannedMaskBytes =
                maskPlan.sumOf { it.second }
            restoreMaskAdmission =
                reserveSelectionMaskCandidateBytes(
                    owner = "draft-restore:$pointer",
                    bytes = plannedMaskBytes,
                    documentLayerCount = manifest.selectionLayers.size,
                ).also { admission ->
                    if (admission is SelectionMaskOwnershipLedger.MaskAdmission.Rejected) {
                        throw BitmapAllocationRejectedException(plannedMaskBytes)
                    }
                }
            withContext(Dispatchers.IO) {
                maskPlan.forEach { (file, _) ->
                    val mask = decodeMutableBitmapOrThrow(file.absolutePath)
                    try {
                        ownedMasks += mask
                        restoreTracker?.track(mask, "restoreDraft:selection:${file.name}")
                    } catch (t: Throwable) {
                        if (!mask.isRecycled) mask.recycle()
                        throw t
                    }
                }
            }
            val nextRevision = restoreStartRevision + 1
            val layers =
                manifest.selectionLayers.mapIndexed { index, entry ->
                    SelectionLayer(
                        id = entry.id,
                        name = entry.name,
                        kind = SelectionLayerKind.valueOf(entry.kind),
                        bitmap = ownedMasks[index],
                        enabled = entry.enabled,
                        inverted = entry.inverted,
                        opacity = entry.opacity,
                        localParams = entry.localParams,
                    )
                }
            withContext(Dispatchers.Default) {
                val draftDocumentEngine =
                    runCatching { CorrectionEngine.valueOf(manifest.correctionEngine) }
                        .getOrDefault(CorrectionEngine.Engine1)
                val restoreState =
                    _uiState.value.copy(
                        params = manifest.params,
                        presetLook = manifest.presetLook,
                        activeQuickEffects = manifest.activeQuickEffects,
                        selectionLayers = layers,
                        noiseEngine = engines.noiseEngine,
                        detailEngine = engines.detailEngine,
                        toneEngine = engines.toneEngine,
                        hazeEngine = engines.hazeEngine,
                        correctionEngineState =
                            _uiState.value.correctionEngineState.copy(
                                documentEngine = draftDocumentEngine,
                                pendingEngine = null,
                                visiblePreview = VisiblePreviewState.NoDocument,
                                lastRenderFailure = null,
                            ),
                    )
                val storedRoute =
                    manifest.previewRoute
                        ?.let { runCatching { NativeRenderRoute.valueOf(it) }.getOrNull() }
                        ?.takeUnless { it == NativeRenderRoute.Compare }
                        ?: NativeRenderRoute.V1
                val storedRequestedRoute =
                    manifest.requestedRoute
                        ?.let { runCatching { NativeRenderRoute.valueOf(it) }.getOrNull() }
                        ?.takeUnless { it == NativeRenderRoute.Compare }
                        ?: storedRoute
                val storedDecision =
                    manifest.renderDecision
                        ?.let { runCatching { RenderRouteDecision.valueOf(it) }.getOrNull() }
                        ?: when (manifest.previewResultClass) {
                            PreviewResultClass.V2FallbackToV1.name ->
                                RenderRouteDecision.RuntimeFallbackToV1
                            PreviewResultClass.DebugForcedV1.name ->
                                RenderRouteDecision.DebugForcedV1
                            PreviewResultClass.DebugForcedV2.name ->
                                RenderRouteDecision.DebugForcedV2
                            else -> RenderRouteDecision.FollowDocument
                        }
                restoreRenderSuccess =
                    EditorRenderer.render(
                        createRenderRequest(
                            state = restoreState,
                            operation = RenderOperation.DraftRestore,
                            basePreview = base,
                            revision = nextRevision,
                            assignedEngine = draftDocumentEngine,
                            params = manifest.params,
                            engines = engines,
                            look = manifest.presetLook,
                            quickEffects = manifest.activeQuickEffects,
                            selectionLayers = layers,
                            storedRequestedRoute = storedRequestedRoute,
                            exactRoute = storedRoute,
                            storedDecision = storedDecision,
                            storedAlgorithmVersion =
                                manifest.algorithmContracts.nativeVersionForMetadataRestore(
                                    manifest.algorithmVersion
                                ),
                            storedParticipation = manifest.renderParticipation,
                            diagnostics = restoreTracker,
                        )
                    ).successOrThrow()
                ownedRendered = checkNotNull(restoreRenderSuccess).output
                restoreTracker?.track(checkNotNull(ownedRendered), "restoreDraft:rendered")
            }
            withContext(Dispatchers.IO) {
                val session =
                    nativeCreateSessionOrTest(
                        checkNotNull(ownedWorkingSource).absolutePath
                    )
                createdSession = session
            }
            if (createdSession == 0L) error("draft native session creation failed")
            draftSaveMutex.lock()
            restoreSettlementLocked = true
            val pointerStillCurrent =
                withContext(Dispatchers.IO) { currentDraftGenerationId(context) == pointer }
            if (
                shuttingDown ||
                    restoreToken != restoreDraftToken ||
                    _uiState.value.revision != restoreStartRevision ||
                    !pointerStillCurrent ||
                    draftPointerBaseline != pointer
            ) {
                if (
                    !shuttingDown &&
                        restoreToken == restoreDraftToken &&
                        _uiState.value.revision == restoreStartRevision
                ) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false) }
                }
                return GenerationRestoreOutcome.Stale
            }
            val activeLayerId =
                manifest.activeSelectionLayerId?.takeIf { id -> layers.any { it.id == id } }
            val previousState = _uiState.value
            val nextState =
                previousState.copy(
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
                    correctionEngineState =
                        previousState.correctionEngineState.withSuccessfulRender(
                            documentEngine = draftCorrectionEngine(manifest.correctionEngine),
                            result = checkNotNull(restoreRenderSuccess),
                        ),
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
                    algorithmContracts =
                        manifest.algorithmContracts.copy(
                            nativeRenderContract =
                                checkNotNull(restoreRenderSuccess).algorithmVersion,
                            migratedFromLegacy =
                                checkNotNull(restoreRenderSuccess)
                                    .migratedFromAlgorithmVersion,
                        ),
                    baseProvenance = manifest.baseProvenance,
                    viewport = ViewportState(),
                    flareGuardRuntimeStatus = null,
                    revision = nextRevision,
                    message =
                        if (checkNotNull(restoreRenderSuccess).migratedFromAlgorithmVersion != null) {
                            "임시저장 편집을 현재 알고리즘으로 마이그레이션했습니다."
                        } else {
                            "임시저장된 편집을 불러왔습니다"
                        },
                )
            val previousSession = nativeSession
            restorePreviousBaseline = draftPointerBaseline
            draftPointerBaseline = pointer
            restoreBaselineChanged = true
            nativeSession = createdSession
            tracker.registerNativeSession(
                createdSession,
                historyCoordinator.currentGeneration(),
                nextState.sourcePath.orEmpty(),
                "restored",
            )
            if (
                !commitUiState(
                    previousState,
                    nextState,
                    replaceDocument = true,
                    adoptedNativeSession = createdSession,
                )
            ) {
                nativeSession = previousSession
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
            runCatching { releaseNativeSessionHandle(previousSession) }
                .onFailure { logDraftSaveFailure(it) }
            runCatching { deleteOwnedWorkingSource(context, previousState.sourcePath) }
                .onFailure { logDraftSaveFailure(it) }
            return GenerationRestoreOutcome.Restored
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            logDraftSaveFailure(t)
            if (restoreStateAdopted) {
                return GenerationRestoreOutcome.Restored
            }
            if (t is BitmapAllocationRejectedException) {
                if (
                    !shuttingDown &&
                        restoreToken == restoreDraftToken &&
                        _uiState.value.revision == restoreStartRevision
                ) {
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            isBusy = false,
                            message = "메모리가 부족하여 임시저장 복구를 완료하지 못했습니다. 기존 편집과 임시저장은 안전합니다.",
                        )
                    }
                    requestAllocationRecovery(MemoryRetryAction.RestoreDraft, t.requiredBytes)
                }
                return GenerationRestoreOutcome.MemoryRejected(t.requiredBytes)
            }
            if (withContext(Dispatchers.IO) { currentDraftGenerationId(context) } != pointer) {
                if (
                    !shuttingDown &&
                        restoreToken == restoreDraftToken &&
                        _uiState.value.revision == restoreStartRevision
                ) {
                    updateUiStateAndRecycleReplaced { it.copy(isBusy = false) }
                }
                return GenerationRestoreOutcome.Stale
            }
            if (
                !shuttingDown &&
                    restoreToken == restoreDraftToken &&
                    _uiState.value.revision == restoreStartRevision
            ) {
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
            restoreTracker?.end()
            restoreMaskAdmission?.close()
        }
    }

    private suspend fun restoreDraftIfAvailable(
        context: Context,
        restoreToken: Long,
        restoreStartRevision: Int,
    ) {
        if (
            shuttingDown ||
                restoreToken != restoreDraftToken ||
                restoreStartRevision != _uiState.value.revision
        )
            return
        val generationRestore =
            restoreCurrentDraftGeneration(context, restoreToken, restoreStartRevision)
        if (generationRestore == GenerationRestoreOutcome.Restored) return
        if (generationRestore == GenerationRestoreOutcome.Stale) return
        if (generationRestore is GenerationRestoreOutcome.MemoryRejected) return
        if (generationRestore is GenerationRestoreOutcome.Invalid) {
            val cleared =
                withContext(Dispatchers.IO) {
                    draftSaveMutex.withLock {
                        if (
                            restoreToken != restoreDraftToken ||
                                restoreStartRevision != _uiState.value.revision
                        )
                            return@withLock false
                        val actualPointer = currentDraftGenerationId(context)
                        if (actualPointer != generationRestore.generationId) {
                            draftPointerBaseline =
                                when {
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
        val restoreSnapshot =
            try {
                withContext(Dispatchers.IO) {
                    draftSaveMutex.withLock {
                        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        val storedSourcePath =
                            prefs.getString(KEY_DRAFT_SOURCE, null) ?: return@withLock null
                        val draftSavedAt = prefs.getLong(KEY_DRAFT_SAVED_AT, 0L).takeIf { it > 0L }
                        cleanupDraftTemporaryFiles(context)
                        DraftRestoreSnapshot(
                            preferences = DraftPreferencesSnapshot(prefs.all.toMap()),
                            savedAtMillis = draftSavedAt,
                            recovery = resolveDraftRecovery(context, storedSourcePath),
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
                            message =
                                "\uC784\uC2DC\uC800\uC7A5 \uBCF5\uAD6C \uC815\uBCF4\uB97C \uD655\uC778\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4. \uD3B8\uC9D1\uC740 \uACC4\uC18D\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4.",
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
                showRecoveryDebugCard = true,
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
        val params =
            EditParams(
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
                luminanceNoiseReduction =
                    draftPrefs.getFloat(KEY_DRAFT_LUMINANCE_NOISE_REDUCTION, legacyNoiseReduction),
                colorNoiseReduction =
                    draftPrefs.getFloat(KEY_DRAFT_COLOR_NOISE_REDUCTION, legacyNoiseReduction),
                noiseDetailProtection =
                    draftPrefs.getFloat(KEY_DRAFT_NOISE_DETAIL_PROTECTION, 0.50f),
            )
        val exportFormat =
            enumValueOrDefault(draftPrefs.getString(KEY_DRAFT_FORMAT, null), ExportFormat.Jpeg)
        val exportResolution =
            enumValueOrDefault(
                draftPrefs.getString(KEY_DRAFT_RESOLUTION, null),
                ExportResolution.Full,
            )
        val presetLook =
            runCatching {
                    presetColorLookFromJson(
                        draftPrefs.getString(KEY_DRAFT_LOOK, null)?.let(::JSONObject)
                    )
                }
                .getOrNull()
        val engines = _uiState.value.engineSelection()
        updateUiStateAndRecycleReplaced {
            it.copy(draftSavedAtMillis = draftSavedAt, draftSourcePath = sourcePath)
        }
        updateUiStateAndRecycleReplaced {
            it.copy(
                isBusy = true,
                message =
                    "\uC784\uC2DC\uC800\uC7A5\uB41C \uD3B8\uC9D1\uC744 \uBD88\uB7EC\uC624\uB294 \uC911\uC785\uB2C8\uB2E4",
            )
        }
        var preview: Bitmap? = null
        var rendered: Bitmap? = null
        var restoreRenderSuccess: RenderResult.Success? = null
        var createdSession = 0L
        var expectedRestoreRevision: Int? = null
        val restoreTracker =
            beginMemoryTracking(
                "restoreLegacyDraft",
                snapshotState = "decoding",
                transientReserveBytes = BitmapMemoryBudget.operationReserveBytes(),
            )
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
                val decoded =
                    decodeSampledMutableBitmapWithExif(sourcePath, maxSide = 2048, restoreTracker)
                preview = decoded
            }
            val nextRevision = _uiState.value.revision + 1
            expectedRestoreRevision = nextRevision
            val activeQuickEffects =
                draftPrefs.getString(KEY_DRAFT_QUICK_EFFECTS, null).parseQuickEffects()
            withContext(Dispatchers.Default) {
                val restoreState = _uiState.value
                restoreRenderSuccess =
                    EditorRenderer.render(
                        createRenderRequest(
                            state = restoreState,
                            operation = RenderOperation.DraftRestore,
                            basePreview = checkNotNull(preview),
                            revision = nextRevision,
                            assignedEngine = CorrectionEngine.Engine1,
                            params = params,
                            engines = engines,
                            look = presetLook,
                            quickEffects = activeQuickEffects,
                            selectionLayers = emptyList(),
                            exactRoute = NativeRenderRoute.V1,
                            diagnostics = restoreTracker,
                        )
                    ).successOrThrow()
                rendered = checkNotNull(restoreRenderSuccess).output
                restoreTracker?.track(checkNotNull(rendered), "restoreLegacyDraft:rendered")
            }
            if (
                shuttingDown ||
                    restoreToken != restoreDraftToken ||
                    _uiState.value.revision != restoreStartRevision
            ) {
                recycleOwnedRestoreBitmaps()
                return
            }
            createdSession = nativeCreateSessionOrTest(sourcePath)
            tracker.registerNativeSession(
                handle = createdSession,
                documentGeneration = historyCoordinator.currentGeneration(),
                sourceIdentity = sourcePath.hashCode().toString(),
                state = "restored",
            )
            if (
                shuttingDown ||
                    restoreToken != restoreDraftToken ||
                    _uiState.value.revision != restoreStartRevision
            ) {
                recycleOwnedRestoreBitmaps()
                releaseNativeSessionHandle(createdSession)
                createdSession = 0L
                return
            }
            val previousSession = nativeSession
            val adoptedPreview = preview!!
            val adoptedRendered = rendered!!
            val previousState = _uiState.value
            val nextState =
                previousState.copy(
                    isBusy = false,
                    sourcePath = sourcePath,
                    baseBitmapDirty = false,
                    baseContentToken =
                        draftPrefs.getString(KEY_DRAFT_BASE_TOKEN, null) ?: newBaseContentToken(),
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
                    correctionEngineState =
                        previousState.correctionEngineState.withSuccessfulRender(
                            documentEngine = CorrectionEngine.Engine1,
                            result = checkNotNull(restoreRenderSuccess),
                        ),
                    revision = nextRevision,
                    message =
                        "\uC784\uC2DC\uC800\uC7A5\uB41C \uD3B8\uC9D1\uC744 \uBD88\uB7EC\uC654\uC2B5\uB2C8\uB2E4",
                )
            nativeSession = createdSession
            if (
                !commitUiState(
                    previousState,
                    nextState,
                    replaceDocument = true,
                    adoptedNativeSession = createdSession,
                )
            ) {
                nativeSession = previousSession
                error("legacy draft adoption superseded")
            }
            createdSession = 0L
            preview = null
            rendered = null
            lastSuccessfullyRenderedParams = params
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
            val isRestoreStillCurrent =
                !shuttingDown &&
                    restoreToken == restoreDraftToken &&
                    (currentRevision == restoreStartRevision ||
                        currentRevision == expectedRestoreRevision)
            if (isRestoreStillCurrent) {
                updateUiStateAndRecycleReplaced {
                    it.copy(
                        isBusy = false,
                        message =
                            "\uC784\uC2DC\uC800\uC7A5\uC744 \uBD88\uB7EC\uC624\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4: ${t.message}",
                    )
                }
                if (t is BitmapAllocationRejectedException) {
                    requestAllocationRecovery(MemoryRetryAction.RestoreDraft, t.requiredBytes)
                }
            }
        } finally {
            restoreTracker?.end()
        }
    }

    private fun commitPendingParameterTransaction(transaction: ParameterGestureTransaction) {
        val adoptedParams = transaction.adoptedParams ?: run {
            transaction.rollback()
            val startState = transaction.start.state
            val startRevision = startState.revision
            updateUiStateAndRecycleReplaced {
                it.copy(
                    params = startState.params,
                    previewBitmap = startState.previewBitmap,
                    correctionEngineState = startState.correctionEngineState,
                    revision = it.revision + 1,
                    isBusy = false,
                )
            }
            closeParameterGesture(transaction)
            transaction.lifecycleInstallation?.hooks?.onRollbackAdoptedStartState?.invoke(startRevision)
            return
        }
        transaction.lifecycleInstallation?.hooks?.onTransactionCommitBegan?.invoke(transaction.id)
        if (!transaction.historyCommitted) {
            if (!transaction.commit()) return
            val snapshot = transaction.takeOwnedSnapshot()
            if (snapshot != null) settleAdoptedEditHistory(snapshot)
            transaction.historyCommitted = true
        }
        lastSuccessfullyRenderedParams = adoptedParams
        scheduleDraftAutosave()
        maybeCloseParameterGesture(transaction)
        transaction.lifecycleInstallation?.hooks?.onTransactionCommitted?.invoke(checkNotNull(transaction.adoptedRevision))
    }

    private fun takePendingParameterSnapshotForRollback(
        transaction: ParameterGestureTransaction,
    ): EditorHistorySnapshot? {
        transaction.rollback()
        val snapshot = transaction.takeOwnedSnapshot()
        transaction.historyCommitted = true
        closeParameterGesture(transaction)
        transaction.lifecycleInstallation?.hooks?.onRollbackAdoptedStartState?.invoke(transaction.start.state.revision)
        return snapshot
    }

    private fun maybeCloseParameterGesture(transaction: ParameterGestureTransaction) {
        if (parameterGesture !== transaction || !transaction.windowExpired) return
        if (transaction.renderJob?.isActive == true || transaction.historyJob?.isActive == true) return
        if (transaction.adoptedParams == null) {
            transaction.rollback()
            val startState = transaction.start.state
            val startRevision = startState.revision
            updateUiStateAndRecycleReplaced {
                it.copy(
                    params = startState.params,
                    previewBitmap = startState.previewBitmap,
                    correctionEngineState = startState.correctionEngineState,
                    revision = it.revision + 1,
                    isBusy = false,
                )
            }
            closeParameterGesture(transaction)
            transaction.lifecycleInstallation?.hooks?.onRollbackAdoptedStartState?.invoke(startRevision)
            return
        }
        if (!transaction.historyCommitted && transaction.historySnapshotPublished) {
            commitPendingParameterTransaction(transaction)
            return
        }
        closeParameterGesture(transaction)
    }

    private fun closeParameterGesture(transaction: ParameterGestureTransaction) {
        if (parameterGesture !== transaction) return
        parameterGesture = null
        paramUndoWindowJob?.cancel()
        paramUndoWindowJob = null
        transaction.close()
        transaction.lifecycleInstallation?.hooks?.onTransactionClosed?.invoke(transaction.id)
    }

    internal fun discardPendingParamUndoSnapshot() {
        parameterGesture?.let(::closeParameterGesture)
        closeParamUndoWindow()
    }

    /**
     * Explicit settlement coordinator used before any external edit action
     * (crop, rotate, undo/redo, selection, brush, engine change, image
     * replacement, draft restore, export, comparison source changes, etc.).
     *
     * **At least one adopted preview exists:**
     * 1. Commit the before-history snapshot immediately (exactly once).
     * 2. Commit the latest ADOPTED revision only — pending renders for newer
     *    requested revisions are cancelled and never influence the commit.
     * 3. Retain matching params and pixels.
     * 4. Update lastSuccessfullyRenderedParams to the adopted params.
     * 5. Cancel inactivity timer.
     * 6. Close transaction ownership.
     * 7. Schedule Draft save after commit.
     *
     * **No adopted preview:**
     * 1. Cancel pending render and history jobs.
     * 2. Consume or close the pending history snapshot.
     * 3. Restore the exact transaction-start state (params, pixels, engine
     *    state) regardless of how many newer requests were issued.
     * 4. Recycle transient outputs.
     * 5. Create no history entry.
     * 6. Close transaction.
     *
     * Settlement completes before the external action captures state.
     * This method is safe to call when no parameter transaction is active.
     */
    internal fun settleParameterTransaction(reason: SettlementReason): SettlementResult {
        val unresolved =
            parameterGesture != null ||
                activeParamRenderRevision != null ||
                _uiState.value.params != lastSuccessfullyRenderedParams
        if (!unresolved) return SettlementResult.NoTransaction

        renderJob?.cancel()
        activeParamRenderRevision = null
        val transaction = parameterGesture

        paramUndoWindowJob?.cancel()
        paramUndoWindowJob = null

        val shouldScheduleDraft = reason == SettlementReason.ExternalEdit ||
            reason == SettlementReason.DocumentReplacement ||
            reason == SettlementReason.Export

        if (transaction != null) {
            transaction.windowExpired = true
            val adoptedParams = transaction.adoptedParams
            if (adoptedParams != null) {
                transaction.lifecycleInstallation?.hooks?.onTransactionCommitBegan?.invoke(transaction.id)
                if (!transaction.historyCommitted) {
                    transaction.commit()
                    val snapshot = transaction.takeOwnedSnapshot()
                    if (snapshot != null) {
                        if (reason == SettlementReason.Shutdown) {
                            recycleHistorySnapshot(snapshot)
                        } else {
                            settleAdoptedEditHistory(snapshot)
                        }
                    }
                    transaction.historyCommitted = true
                }
                lastSuccessfullyRenderedParams = adoptedParams
                val settledRevision = _uiState.value.revision + 1
                updateUiStateAndRecycleReplaced {
                    it.copy(
                        params = adoptedParams,
                        revision = settledRevision,
                        isBusy = false,
                    )
                }
                updateHistoryFlags()
                if (shouldScheduleDraft) scheduleDraftAutosave()
                closeParameterGesture(transaction)
                transaction.lifecycleInstallation?.hooks?.onTransactionCommitted?.invoke(settledRevision)
                return SettlementResult.Committed(settledRevision)
            }
            transaction.rollback()
            val startState = transaction.start.state
            val startRevision = startState.revision
            updateUiStateAndRecycleReplaced {
                it.copy(
                    params = startState.params,
                    previewBitmap = startState.previewBitmap,
                    correctionEngineState = startState.correctionEngineState,
                    revision = it.revision + 1,
                    isBusy = false,
                )
            }
            closeParameterGesture(transaction)
            transaction.lifecycleInstallation?.hooks?.onRollbackAdoptedStartState?.invoke(startRevision)
            return SettlementResult.RolledBack(startRevision)
        } else {
            updateUiStateAndRecycleReplaced {
                it.copy(
                    params = lastSuccessfullyRenderedParams,
                    revision = it.revision + 1,
                    isBusy = false,
                )
            }
            discardPendingParamUndoSnapshot()
            return SettlementResult.NoTransaction
        }
    }

    internal fun settleParameterTransactionBeforeExternalEdit(): SettlementResult {
        return settleParameterTransaction(SettlementReason.ExternalEdit)
    }

    internal fun abortPendingParameterEdit() {
        settleParameterTransactionBeforeExternalEdit()
    }

    private fun prepareForMaskInteraction(): EditorUiState {
        abortPendingParameterEdit()
        if (brushTransactionState != BrushTransactionState.Idle &&
            brushTransactionState != BrushTransactionState.Finishing &&
            brushTransactionState != BrushTransactionState.Cancelling
        ) {
            cancelBrushStroke()
        }
        settleSelectionParamTransactionForSupersession()
        return uiState.value
    }

    private fun prepareForGlobalParamEdit(): EditorUiState {
        if (brushTransactionState != BrushTransactionState.Idle &&
            brushTransactionState != BrushTransactionState.Finishing &&
            brushTransactionState != BrushTransactionState.Cancelling
        ) {
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
    }

    private fun applyHistorySnapshot(snapshot: EditorHistorySnapshot, message: String): Boolean {
        val current = _uiState.value
        val metadataOnly = snapshot.storage == HistorySnapshotStorage.MetadataOnly
        if (
            metadataOnly &&
                (current.baseContentToken != snapshot.baseContentToken ||
                    current.originalPreviewBitmap == null ||
                    current.selectionLayers.isNotEmpty() ||
                    current.cropState != snapshot.cropState ||
                    snapshot.previewBitmap == null)
        )
            return false
        val candidateAdmission =
            if (metadataOnly) {
                null
            } else if (snapshot.candidateAdmission != null) {
                snapshot.candidateAdmission.also { snapshot.candidateAdmission = null }
            } else {
                reserveSelectionMaskCandidate(
                    owner = "history-adopt:${snapshot.coordinatorGeneration ?: "unknown"}",
                    layers = snapshot.selectionLayers,
                    bytesAlreadyReserved =
                        snapshot.maskReservations.isNotEmpty() || snapshot.candidateAdmission != null,
                ).takeUnless {
                    it is SelectionMaskOwnershipLedger.MaskAdmission.Rejected
                }
            }
        if (!metadataOnly && candidateAdmission == null) return false
        return try {
            invalidateSelectionPreview()
            lastSuccessfullyRenderedParams = snapshot.params
            updateUiStateAndRecycleReplaced {
                it.copy(
                    params = snapshot.params,
                    correctionEngineState =
                        it.correctionEngineState.copy(
                            documentEngine = snapshot.correctionEngine,
                            pendingEngine = null,
                            visiblePreview = snapshot.toVisiblePreviewState(),
                            lastRenderFailure = null,
                        ),
                    noiseEngine = snapshot.noiseEngine,
                    detailEngine = snapshot.detailEngine,
                    toneEngine = snapshot.toneEngine,
                    hazeEngine = snapshot.hazeEngine,
                    baseBitmapDirty = snapshot.baseBitmapDirty,
                    baseContentToken = snapshot.baseContentToken,
                    previewBitmap = snapshot.previewBitmap,
                    originalPreviewBitmap =
                        if (metadataOnly) it.originalPreviewBitmap
                        else snapshot.originalPreviewBitmap,
                    presetLook = snapshot.presetLook,
                    cropState = snapshot.cropState,
                    selectionLayers =
                        if (metadataOnly) it.selectionLayers else snapshot.selectionLayers,
                    activeSelectionLayerId =
                        if (metadataOnly) it.activeSelectionLayerId
                        else snapshot.activeSelectionLayerId,
                    selectionPaintSettings =
                        if (metadataOnly) it.selectionPaintSettings
                        else snapshot.selectionPaintSettings,
                    showSelectionOverlay =
                        if (metadataOnly) it.showSelectionOverlay
                        else snapshot.showSelectionOverlay,
                    activeQuickEffects = snapshot.activeQuickEffects,
                    flareGuardRuntimeStatus = snapshot.flareGuardRuntimeStatus,
                    algorithmContracts = snapshot.algorithmContracts,
                    baseProvenance = snapshot.baseProvenance,
                    isBusy = false,
                    revision = it.revision + 1,
                    message = message,
                )
            }
            snapshot.releaseBitmapOwnership()
            runCatching {
                saveEngineSelection(
                    getApplication<Application>(),
                    EngineSelection(
                        snapshot.noiseEngine,
                        snapshot.detailEngine,
                        snapshot.toneEngine,
                        snapshot.hazeEngine,
                    ),
                )
            }
            true
        } catch (_: Throwable) {
            false
        } finally {
            candidateAdmission?.close()
        }
    }

    private suspend fun captureHistorySnapshotForNavigation(
        preferredStorage: HistorySnapshotStorage,
        targetBaseToken: String,
    ): EditorHistorySnapshot? {
        val leased = acquireEditorSnapshot("historyNavigation") ?: return null
        val state = leased.state
        val storage =
            if (
                preferredStorage == HistorySnapshotStorage.MetadataOnly &&
                    state.baseContentToken == targetBaseToken &&
                    state.supportsMetadataOnlyHistory()
            ) {
                HistorySnapshotStorage.MetadataOnly
            } else {
                HistorySnapshotStorage.Exact
            }
        val required =
            if (storage == HistorySnapshotStorage.Exact) state.historyBitmapBytes() else 0L
        if (!BitmapMemoryBudget.canAllocate(required)) {
            leased.close()
            return null
        }
        return try {
            withContext(Dispatchers.Default) {
                state.toHistorySnapshot(storage).also { snapshot ->
                    val generation = historyCoordinator.currentGeneration()
                    snapshot.coordinatorGeneration = generation
                    snapshot.attachLocalDiagnostics(trackerSession, generation)
                }
            }
        } catch (failure: BitmapAllocationRejectedException) {
            throw failure
        } catch (_: Throwable) {
            null
        } finally {
            leased.close()
        }
    }

    private suspend fun materializeHistorySnapshot(
        snapshot: EditorHistorySnapshot,
        register: (EditorHistorySnapshot) -> Unit,
        diagnostics: MemoryTrackerScope?,
    ): EditorHistorySnapshot? {
        if (snapshot.storage == HistorySnapshotStorage.Exact) return snapshot.also(register)
        val leased = acquireEditorSnapshot("historyMaterialization") ?: return null
        val current = leased.state
        val base = leased.originalPreviewBitmap ?: run {
            leased.close()
            return null
        }
        if (
            current.baseContentToken != snapshot.baseContentToken ||
                current.selectionLayers.isNotEmpty() ||
                current.cropState != snapshot.cropState
        ) {
            leased.close()
            return null
        }
        var ownedBase: Bitmap? = null
        var rendered: Bitmap? = null
        var renderSuccess: RenderResult.Success? = null
        var baseEdge = 0L
        var renderedEdge = 0L
        return try {
            withContext(Dispatchers.Default) {
                ownedBase = base.copyOrThrow()
                baseEdge =
                    diagnostics?.track(checkNotNull(ownedBase), "navigateHistory:metadataBase")
                        ?: 0L
                val storedRoute =
                    snapshot.previewRoute
                        ?: when (snapshot.previewEngine) {
                            CorrectionEngine.Engine2 -> NativeRenderRoute.V2
                            else -> NativeRenderRoute.V1
                        }
                renderSuccess =
                    EditorRenderer.render(
                        createRenderRequest(
                            state = current,
                            operation = RenderOperation.HistoryMaterialization,
                            basePreview = checkNotNull(ownedBase),
                            revision = current.revision + 1,
                            assignedEngine = snapshot.correctionEngine,
                            params = snapshot.params,
                            engines =
                                EngineSelection(
                                    snapshot.noiseEngine,
                                    snapshot.detailEngine,
                                    snapshot.toneEngine,
                                    snapshot.hazeEngine,
                                ),
                            look = snapshot.presetLook,
                            quickEffects = snapshot.activeQuickEffects,
                            selectionLayers = emptyList(),
                            storedRequestedRoute = snapshot.requestedRoute,
                            exactRoute = storedRoute,
                            storedDecision = snapshot.renderDecision,
                            storedAlgorithmVersion =
                                snapshot.algorithmContracts.nativeVersionForMetadataRestore(
                                    snapshot.algorithmVersion
                                ),
                            storedParticipation = snapshot.renderParticipation,
                            fallbackPolicy = FallbackPolicy.NoFallback,
                            diagnostics = diagnostics,
                        )
                    ).successOrThrow()
                rendered = checkNotNull(renderSuccess).output
                renderedEdge =
                    diagnostics?.track(checkNotNull(rendered), "navigateHistory:metadataRendered")
                        ?: 0L
            }
            val success = checkNotNull(renderSuccess)
            snapshot.copy(
                requestedRoute = success.requestedRoute,
                previewEngine =
                    if (success.actualRoute == NativeRenderRoute.V2)
                        CorrectionEngine.Engine2
                    else CorrectionEngine.Engine1,
                previewRoute = success.actualRoute,
                previewResultClass =
                    when (success.decision) {
                        RenderRouteDecision.RuntimeFallbackToV1 ->
                            PreviewResultClass.V2FallbackToV1
                        RenderRouteDecision.DebugForcedV1 ->
                            PreviewResultClass.DebugForcedV1
                        RenderRouteDecision.DebugForcedV2 ->
                            PreviewResultClass.DebugForcedV2
                        else ->
                            if (success.actualRoute == NativeRenderRoute.V2)
                                PreviewResultClass.V2
                            else PreviewResultClass.V1
                    },
                fallbackReason =
                    if (success.decision == RenderRouteDecision.RuntimeFallbackToV1)
                        RenderFallbackReason.V2RenderFailed
                    else null,
                renderDecision = success.decision,
                renderParticipation = success.participation,
                algorithmVersion = success.algorithmVersion,
                algorithmContracts =
                    snapshot.algorithmContracts.copy(
                        nativeRenderContract = success.algorithmVersion,
                        migratedFromLegacy = success.migratedFromAlgorithmVersion,
                    ),
                previewBitmap = rendered,
                resourcesReleased = false,
            ).also {
                it.coordinatorGeneration = snapshot.coordinatorGeneration
                it.attachLocalDiagnostics(
                    trackerSession,
                    snapshot.coordinatorGeneration ?: historyCoordinator.currentGeneration(),
                )
                register(it)
                rendered = null
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (failure: BitmapAllocationRejectedException) {
            throw failure
        } catch (_: Throwable) {
            null
        } finally {
            ownedBase?.takeIf { !it.isRecycled }?.recycle()
            diagnostics?.release(baseEdge)
            rendered?.takeIf { !it.isRecycled }?.recycle()
            if (rendered != null) diagnostics?.release(renderedEdge)
            leased.close()
        }
    }

    private fun updateHistoryFlags() {
        val flags = historyCoordinator.flags()
        updateUiStateAndRecycleReplaced {
            it.copy(canUndo = flags.canUndo, canRedo = flags.canRedo)
        }
    }

    private fun clearEditHistory() {
        automaticRetryAttempt = null
        strongRetryAttempt = null
        pendingMemoryRetry = null
        historyIoJob?.cancel()
        historyIoJob = null
        discardPendingParamUndoSnapshot()
        historyCoordinator.replaceDocument()
        tracker.activateDocument(
            historyCoordinator.currentGeneration(),
            tracker.currentDocumentGeneration().ifEmpty { null },
        )
        historyCoordinator.refreshDiagnostics()
        if (!shuttingDown) updateUiStateAndRecycleReplaced { it.copy(memoryRecoveryRequest = null) }
        updateHistoryFlags()
    }

    private fun releaseNativeSessionHandle(session: Long) {
        if (session != 0L) {
            tracker.unregisterNativeSession(session)
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
        // Android teardown is a non-suspending terminal ownership boundary. The
        // app-level leave action has already awaited Draft persistence; onCleared
        // only blocks new work, resolves visible parameter state synchronously,
        // cancels owned jobs, and releases memory/history ownership. It does not
        // claim that filesystem persistence can be completed here.
        shuttingDown = true
        settleParameterTransaction(SettlementReason.Shutdown)
        brushSnapshotJob?.cancel()
        brushSettlementJob?.cancel()
        val shutdownBrushSnapshot = brushingSnapshot
        val shutdownBrushIdentity = brushIdentity
        val shutdownBrushCurrent =
            shutdownBrushSnapshot != null &&
                shutdownBrushIdentity != null &&
                brushWorkingMask != null &&
                isBrushStrokeCurrent(shutdownBrushIdentity.activeLayerId)
        if (shutdownBrushSnapshot != null && shutdownBrushCurrent) {
            restoreSnapshotWithoutHistory(
                shutdownBrushSnapshot,
                preserveRevision = shutdownBrushIdentity?.startRevision,
            )
        } else {
            shutdownBrushSnapshot?.let(::recycleHistorySnapshot)
        }
        brushOwnedMaskHandle?.close()
        brushOwnedMaskHandle = null
        brushMaskReservation?.close()
        brushMaskReservation = null
        brushTransactionState = BrushTransactionState.Idle
        brushStartSnapshot?.close()
        brushStartSnapshot = null
        brushingSnapshot = null
        brushIdentity = null
        brushWorkingMask = null
        invalidateManagedEdits()
        invalidateDraftOperations()
        renderJob?.cancel()
        invalidateExport()
        invalidateSelectionPreview()
        paramUndoWindowJob?.cancel()
        cropJob?.cancel()
        transactionFinishJob?.cancel()
        memoryRecoveryJob?.cancel()
        pendingMemoryRetry = null
        releaseNativeSession()
        // The model session is process-global but editor-owned; ensure editor teardown
        // invalidates its command generation and settles the registry out of Ready.
        RemasterModelSession.unload()
        historyIoJob?.cancel()
        discardPendingParamUndoSnapshot()
        historyCoordinator.close()
        uiStateOwnership?.releaseAll()
        bitmapLeaseLedger.releaseState(uiState.value)
        recycleBitmaps(bitmapLeaseLedger.shutdown())
        tracker.logSnapshot("preTrackerClose")
        tracker.close()
        super.onCleared()
    }
}

internal data class DebugComparisonIdentity(
    val epoch: Long,
    val documentGeneration: String,
    val baseContentToken: String,
    val revision: Int,
) {
    fun matches(
        epoch: Long,
        documentGeneration: String,
        baseContentToken: String,
        revision: Int,
    ): Boolean =
        this.epoch == epoch &&
            this.documentGeneration == documentGeneration &&
            this.baseContentToken == baseContentToken &&
            this.revision == revision
}

private fun comparisonMaskArgb(
    layers: List<SelectionLayer>,
    width: Int,
    height: Int,
): IntArray? {
    val enabled = layers.filter(SelectionLayer::enabled)
    if (enabled.isEmpty()) return null
    val combined = IntArray(width * height)
    val scratch = IntArray(width * height)
    enabled.forEach { layer ->
        layer.bitmap.getPixels(scratch, 0, width, 0, 0, width, height)
        for (index in scratch.indices) {
            var value = (scratch[index] ushr 16) and 0xff
            if (layer.inverted) value = 255 - value
            value = (value * layer.opacity.coerceIn(0f, 1f)).roundToInt()
            if (value > combined[index]) combined[index] = value
        }
    }
    return IntArray(combined.size) { index ->
        val value = combined[index]
        -0x1000000 or (value shl 16) or (value shl 8) or value
    }
}

private const val DEBUG_COMPARISON_MAX_SIDE = 720

internal enum class MemoryRetryAction {
    CreateBrushSelection,
    SubjectSelection,
    MaskAwareRemaster,
    FlareNight,
    FlareSun,
    DuplicateSelection,
    BackgroundSelection,
    AutoStraightenCrop,
    ApplySelectionNative,
    ExportPreview,
    OpenImage,
    RestoreDraft,
    RotatePreview,
    HistoryUndo,
    HistoryRedo,
}

internal data class MemoryRetryDescriptor(
    val token: Long,
    val action: MemoryRetryAction,
    val requiredBytes: Long,
    val sourcePath: String?,
    val baseContentToken: String,
    val revision: Int,
    val payload: String?,
    // Navigation-specific identity for target-aware recovery
    val navigationDirection: Boolean? = null, // true = undo, false = redo
    val targetEntryId: String? = null,
    val coordinatorGeneration: String? = null,
    val sourceBranchSize: Int = 0,
    val destinationBranchSize: Int = 0,
)

internal data class MemoryCleanupResult(
    /** Whether any RAM/resource was released anywhere in the cleanup path. */
    val reclaimedResources: Boolean,
    /** Whether history recovery completed (not busy/superseded) for the requested transaction. */
    val historyRecoveryCompleted: Boolean,
    /** Whether the protected disk budget was satisfied after eligible eviction. */
    val historyDiskBudgetSatisfied: Boolean,
    /** True when the history operation was busy or superseded — result is not retry-safe. */
    val historyRecoverySuperseded: Boolean,
)

private fun MemoryRetryDescriptor?.matchesRetryFailure(
    action: MemoryRetryAction,
    state: EditorUiState,
): Boolean =
    this != null &&
        this.action == action &&
        this.sourcePath == state.sourcePath &&
        this.baseContentToken == state.baseContentToken &&
        state.revision.toLong() in this.revision.toLong()..(this.revision.toLong() + 1L)

internal data class EditorHistorySnapshot(
    val params: EditParams,
    val correctionEngine: CorrectionEngine,
    val requestedRoute: NativeRenderRoute? = null,
    val previewEngine: CorrectionEngine? = null,
    val previewRoute: NativeRenderRoute? = null,
    val previewResultClass: PreviewResultClass? = null,
    val fallbackReason: RenderFallbackReason? = null,
    val renderDecision: RenderRouteDecision? = null,
    val renderParticipation: RenderParticipation = RenderParticipation(),
    val algorithmVersion: String? = null,
    val noiseEngine: NoiseEngine,
    val detailEngine: DetailEngine,
    val toneEngine: ToneEngine,
    val hazeEngine: DehazeEngine,
    val baseBitmapDirty: Boolean,
    val baseContentToken: String,
    var previewBitmap: Bitmap?,
    var originalPreviewBitmap: Bitmap?,
    val presetLook: PresetColorLook?,
    val cropState: CropState,
    var selectionLayers: List<SelectionLayer>,
    val activeSelectionLayerId: String?,
    val selectionPaintSettings: SelectionPaintSettings,
    val showSelectionOverlay: Boolean,
    val activeQuickEffects: List<ActiveQuickEffect>,
    val flareGuardRuntimeStatus: String?,
    val storage: HistorySnapshotStorage = HistorySnapshotStorage.Exact,
    val algorithmContracts: AlgorithmContractSet =
        AlgorithmContractSet.fromLegacy(algorithmVersion),
    val baseProvenance: BaseProvenanceChain = BaseProvenanceChain(),
    var resourcesReleased: Boolean = false,
    var coordinatorGeneration: String? = null,
    private var localDiagnostics: HistorySnapshotDiagnostics? = null,
    @Transient
    private var admissionOwner: HistorySnapshotAdmissionOwner = HistorySnapshotAdmissionOwner.Caller,
) {
    @Transient
    internal var maskReservations: MutableList<MaskReservation> = mutableListOf()

    @Transient
    internal var candidateAdmission: AutoCloseable? = null

    internal fun claimCoordinatorOwnership() {
        admissionOwner = HistorySnapshotAdmissionOwner.Coordinator
    }

    internal fun callerOwnsBeforeAdmission(): Boolean =
        admissionOwner == HistorySnapshotAdmissionOwner.Caller

    internal fun attachLocalDiagnostics(session: TrackerSession?, generation: String) {
        if (
            session == null ||
                storage == HistorySnapshotStorage.MetadataOnly ||
                localDiagnostics != null
        )
            return
        localDiagnostics = HistorySnapshotDiagnostics.acquire(session, this, generation)
    }

    internal fun transferDiagnosticsToCoordinator() {
        localDiagnostics?.release()
        localDiagnostics = null
    }

    internal fun releaseLocalDiagnostics() {
        localDiagnostics?.release()
        localDiagnostics = null
    }
}

internal enum class HistorySnapshotAdmissionOwner {
    Caller,
    Coordinator,
}

/** Handle-only snapshot ownership. The weak session reference cannot retain an editor ViewModel. */
internal class HistorySnapshotDiagnostics(session: TrackerSession, private val handles: LongArray) {
    private val session = java.lang.ref.WeakReference(session)

    fun release() {
        val target = session.get() ?: return
        handles.forEach(target::releaseEdge)
    }

    companion object {
        fun acquire(
            session: TrackerSession,
            snapshot: EditorHistorySnapshot,
            generation: String,
        ): HistorySnapshotDiagnostics {
            val handles = ArrayList<Long>()
            fun track(bitmap: Bitmap?, owner: String) {
                bitmap?.takeUnless(Bitmap::isRecycled)?.let {
                    session
                        .registerBitmap(it, owner, "HistorySnapshot:local", 0L, generation)
                        .takeIf { handle -> handle != 0L }
                        ?.let(handles::add)
                }
            }
            track(snapshot.previewBitmap, "HistorySnapshot:preview")
            track(snapshot.originalPreviewBitmap, "HistorySnapshot:original")
            snapshot.selectionLayers.forEach {
                track(it.bitmap, "HistorySnapshot:selection:${it.id}")
            }
            return HistorySnapshotDiagnostics(session, handles.toLongArray())
        }
    }
}

/**
 * One instance per gesture. Old preview/finish jobs must re-confirm they still own this
 *
 * transaction (by identity) and its finish job before mutating state.
 */
internal class SelectionParamTransaction(
    val gestureId: Long,
    var snapshot: EditorHistorySnapshot?,
    val startRevision: Int,
    val startState: EditorUiState,
    var startLease: LeasedEditorSnapshot?,
    val sourcePath: String?,
    val documentGeneration: String,
    val baseContentToken: String,
    val activeSelectionLayerId: String?,
    val previewTestHooks: SelectionPreviewPreparationGateway.Installation?,
) {
    /**
     * Optional deferred history snapshot capture. When the snapshot is captured asynchronously
     * (e.g. from beginSelectionParamGesture off Main), this holds the work and consumers
     * (settlement, rollback) must await it before reading [snapshot].
     */
    @Volatile var pendingSnapshot: PendingHistorySnapshot? = null

    /**
     * Await the pending snapshot capture (if any). When complete, the result is materialized
     * into [snapshot] and the pending job cleared. Callers that need the snapshot must invoke
     * this before reading [snapshot].
     */
    suspend fun awaitPendingSnapshot() {
        val pending = pendingSnapshot ?: return
        pendingSnapshot = null
        try {
            val result = pending.await()
            if (this.snapshot == null) {
                this.snapshot = result
                if (result == null) historyPreparationFailed = true
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            historyPreparationFailed = true
            // Snapshot capture failed; leave snapshot null so consumers fall through to
            // no-history rollback (acceptable: the user can retry the gesture).
        } finally {
            pending.close()
        }
    }

    var latestPreviewToken: Long = 0L
    var finalPreviewToken: Long? = null
    var finalPreviewRevision: Int? = null
    var finalPreviewBaseToken: String? = null
    var finalPreviewLayerId: String? = null
    var previewJob: Job? = null
    @Volatile var succeeded: Boolean = false
    var committed: Boolean = false
    var settled: Boolean = false
    var historyPreparationFailed: Boolean = false
    @Volatile var finished: Boolean = false
    @Volatile var finishJobRef: Job? = null
    var previewRevision: Int? = null
    var previewBaseToken: String? = null
    var previewLayerId: String? = null

    fun hasOptimisticLiveParams(state: EditorUiState): Boolean {
        if (activeSelectionLayerId == null) return false
        val liveLayer =
            state.selectionLayers.firstOrNull { it.id == activeSelectionLayerId } ?: return true
        val captured = snapshot ?: return true
        val snapshotLayer =
            captured.selectionLayers.firstOrNull { it.id == activeSelectionLayerId } ?: return true
        return liveLayer.localParams != snapshotLayer.localParams
    }
}

private fun EditorUiState.toHistorySnapshot(
    storage: HistorySnapshotStorage
): EditorHistorySnapshot {
    if (storage == HistorySnapshotStorage.MetadataOnly) {
        check(supportsMetadataOnlyHistory())
        return EditorHistorySnapshot(
            params = params,
            correctionEngine = correctionEngineState.documentEngine,
            requestedRoute = correctionEngineState.requestedRoute,
            previewEngine = correctionEngineState.previewEngine,
            previewRoute = correctionEngineState.previewRoute,
            previewResultClass = correctionEngineState.previewResultClass,
            fallbackReason = correctionEngineState.fallbackReason,
            renderDecision =
                (correctionEngineState.visiblePreview as? VisiblePreviewState.Rendered)?.decision,
            renderParticipation =
                correctionEngineState.participation ?: RenderParticipation(),
            algorithmVersion = correctionEngineState.algorithmVersion,
            noiseEngine = noiseEngine,
            detailEngine = detailEngine,
            toneEngine = toneEngine,
            hazeEngine = hazeEngine,
            baseBitmapDirty = baseBitmapDirty,
            baseContentToken = baseContentToken,
            previewBitmap = null,
            originalPreviewBitmap = null,
            presetLook = presetLook,
            cropState = cropState,
            selectionLayers = emptyList(),
            activeSelectionLayerId = null,
            selectionPaintSettings = selectionPaintSettings,
            showSelectionOverlay = showSelectionOverlay,
            activeQuickEffects = activeQuickEffects,
            flareGuardRuntimeStatus = flareGuardRuntimeStatus,
            storage = storage,
            algorithmContracts = algorithmContracts,
            baseProvenance = baseProvenance,
        )
    }
    var previewCopy: Bitmap? = null
    var originalCopy: Bitmap? = null
    val selectionCopies = ArrayList<SelectionLayer>(selectionLayers.size)
    try {
        previewCopy = previewBitmap?.copyOrThrow(Bitmap.Config.ARGB_8888, true)
        originalCopy =
            if (originalPreviewBitmap == null) {
                null
            } else if (originalPreviewBitmap === previewBitmap) {
                previewCopy
            } else {
                originalPreviewBitmap.copyOrThrow(Bitmap.Config.ARGB_8888, true)
            }
        selectionLayers.forEach { layer ->
            selectionCopies.add(
                layer.copy(bitmap = layer.bitmap.copyOrThrow(Bitmap.Config.ARGB_8888, true))
            )
        }
        return EditorHistorySnapshot(
            params = params,
            correctionEngine = correctionEngineState.documentEngine,
            requestedRoute = correctionEngineState.requestedRoute,
            previewEngine = correctionEngineState.previewEngine,
            previewRoute = correctionEngineState.previewRoute,
            previewResultClass = correctionEngineState.previewResultClass,
            fallbackReason = correctionEngineState.fallbackReason,
            renderDecision =
                (correctionEngineState.visiblePreview as? VisiblePreviewState.Rendered)?.decision,
            renderParticipation =
                correctionEngineState.participation ?: RenderParticipation(),
            algorithmVersion = correctionEngineState.algorithmVersion,
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
            flareGuardRuntimeStatus = flareGuardRuntimeStatus,
            storage = storage,
            algorithmContracts = algorithmContracts,
            baseProvenance = baseProvenance,
        )
    } catch (t: Throwable) {
        previewCopy?.takeIf { it !== originalCopy && !it.isRecycled }?.recycle()
        originalCopy?.takeIf { !it.isRecycled }?.recycle()
        selectionCopies.forEach { it.bitmap.takeIf { bitmap -> !bitmap.isRecycled }?.recycle() }
        throw t
    }
}

/**
 * Capture an Exact snapshot using pre-captured bitmap references. Used by callers that
 * need to snapshot a moment-in-time before mutating state on the calling thread; the bitmap
 * copy work itself can then run on a worker.
 */
private fun EditorUiState.toHistorySnapshotFromRefs(
    storage: HistorySnapshotStorage,
    previewRef: Bitmap?,
    originalRef: Bitmap?,
    layerRefs: List<Pair<String, Bitmap>>,
): EditorHistorySnapshot {
    if (storage == HistorySnapshotStorage.MetadataOnly) {
        check(supportsMetadataOnlyHistory())
        return toHistorySnapshot(storage)
    }
    val refsById = HashMap<String, Bitmap>(layerRefs.size)
    layerRefs.forEach { (id, b) -> refsById[id] = b }
    var previewCopy: Bitmap? = null
    var originalCopy: Bitmap? = null
    val selectionCopies = ArrayList<SelectionLayer>(selectionLayers.size)
    try {
        previewCopy = previewRef?.copyOrThrow(Bitmap.Config.ARGB_8888, true)
        originalCopy =
            when {
                originalRef == null -> null
                originalRef === previewRef -> previewCopy
                else -> originalRef.copyOrThrow(Bitmap.Config.ARGB_8888, true)
            }
        selectionLayers.forEach { layer ->
            val source = refsById[layer.id] ?: layer.bitmap
            selectionCopies.add(layer.copy(bitmap = source.copyOrThrow(Bitmap.Config.ARGB_8888, true)))
        }
        return EditorHistorySnapshot(
            params = params,
            correctionEngine = correctionEngineState.documentEngine,
            requestedRoute = correctionEngineState.requestedRoute,
            previewEngine = correctionEngineState.previewEngine,
            previewRoute = correctionEngineState.previewRoute,
            previewResultClass = correctionEngineState.previewResultClass,
            fallbackReason = correctionEngineState.fallbackReason,
            renderDecision =
                (correctionEngineState.visiblePreview as? VisiblePreviewState.Rendered)?.decision,
            renderParticipation =
                correctionEngineState.participation ?: RenderParticipation(),
            algorithmVersion = correctionEngineState.algorithmVersion,
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
            flareGuardRuntimeStatus = flareGuardRuntimeStatus,
            storage = storage,
            algorithmContracts = algorithmContracts,
            baseProvenance = baseProvenance,
        )
    } catch (t: Throwable) {
        previewCopy?.takeIf { it !== originalCopy && !it.isRecycled }?.recycle()
        originalCopy?.takeIf { !it.isRecycled }?.recycle()
        selectionCopies.forEach { it.bitmap.takeIf { bitmap -> !bitmap.isRecycled }?.recycle() }
        throw t
    }
}

private fun EditorHistorySnapshot.toVisiblePreviewState(): VisiblePreviewState =
    when (previewResultClass) {
        PreviewResultClass.NoDocument -> VisiblePreviewState.NoDocument
        PreviewResultClass.Original -> VisiblePreviewState.Original
        else -> {
            val actual =
                previewRoute
                    ?: when (previewEngine) {
                        CorrectionEngine.Engine2 -> NativeRenderRoute.V2
                        else -> NativeRenderRoute.V1
                    }
            val requested =
                requestedRoute
                    ?: if (previewResultClass == PreviewResultClass.V2FallbackToV1)
                        NativeRenderRoute.V2
                    else actual
            val decision =
                renderDecision
                    ?: when (previewResultClass) {
                        PreviewResultClass.V2FallbackToV1 ->
                            RenderRouteDecision.RuntimeFallbackToV1
                        PreviewResultClass.DebugForcedV1 ->
                            RenderRouteDecision.DebugForcedV1
                        PreviewResultClass.DebugForcedV2 ->
                            RenderRouteDecision.DebugForcedV2
                        else -> RenderRouteDecision.StoredVisibleTruth
                    }
            VisiblePreviewState.Rendered(
                requestedRoute = requested,
                actualRoute = actual,
                decision = decision,
                algorithmVersion =
                    algorithmVersion
                        ?: PixelContractVersion.current(actual),
                participation = renderParticipation,
            )
        }
    }

private fun buildHistoryAppliedMessage(
    current: EditorUiState,
    target: EditorHistorySnapshot,
    prefix: String,
): String {
    val changedParams = historyParamSummaries(current.params, target.params)
    if (changedParams.isNotEmpty()) {
        return "$prefix: ${changedParams.take(3).joinToString(", ")}"
    }
    if (current.correctionEngineState.documentEngine != target.correctionEngine) {
        return "$prefix: ${target.correctionEngine.displayName} 문서 상태"
    }
    if (
        current.correctionEngineState.previewRoute != target.previewRoute ||
            current.correctionEngineState.previewResultClass != target.previewResultClass
    ) {
        val routeLabel =
            when (target.previewResultClass) {
                PreviewResultClass.Original -> "원본"
                PreviewResultClass.V2FallbackToV1 -> "엔진 1 폴백"
                PreviewResultClass.DebugForcedV1 -> "개발자 지정 엔진 1"
                PreviewResultClass.DebugForcedV2 -> "개발자 지정 엔진 2"
                PreviewResultClass.V2 -> "엔진 2"
                PreviewResultClass.V1 -> "엔진 1"
                else -> "미리보기"
            }
        return "$prefix: $routeLabel"
    }
    val changedImageState =
        current.presetLook != target.presetLook ||
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

private fun historyParamSummaries(current: EditParams, target: EditParams): List<String> =
    listOfNotNull(
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
        historyAbsoluteSliderSummary(
            "노이즈 감소",
            current.luminanceNoiseReduction,
            target.luminanceNoiseReduction,
        ),
        historyAbsoluteSliderSummary(
            "색상 노이즈 감소",
            current.colorNoiseReduction,
            target.colorNoiseReduction,
        ),
        historyAbsoluteSliderSummary(
            "디테일 보호",
            current.noiseDetailProtection,
            target.noiseDetailProtection,
        ),
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

internal fun EditorViewModel.acquireEditorSnapshot(tag: String): LeasedEditorSnapshot? =
    if (hasActiveBrushStroke() || selectionParamTransaction != null) {
        updateUiState {
            it.copy(message = "현재 브러시 또는 선택 미리보기가 마무리되는 중입니다.")
        }
        null
    }
    else bitmapLeaseLedger.withStateTransition {
        bitmapLeaseLedger.capture(tag, uiState.value, historyCoordinator.currentGeneration())
    }

/**
 * Pin a single Bitmap against retirement/recycle until the returned handle's `close()` runs.
 * Used by transient consumers (e.g. histogram sampler) that need to keep one source Bitmap
 * alive across a worker hop without claiming the broader document identity.
 */
internal fun EditorViewModel.pinBitmapLease(bitmap: Bitmap?): BitmapLease.BitmapPin? =
    bitmapLeaseLedger.pinBitmap(bitmap)

/**
 * Acquire a typed named-owner handle for a selection-mask bitmap. The bitmap will not be
 * recycled while the returned handle is open. Every mask-reference site should declare
 * which [MaskOwnerKind] role it is serving.
 */
internal fun EditorViewModel.acquireMaskOwner(
    bitmap: Bitmap?,
    kind: MaskOwnerKind,
): MaskOwnerHandle? = selectionMaskOwnership.acquire(bitmap, kind)

internal class MaskReservationBatch internal constructor(
    private val reservations: List<MaskReservation>,
) : AutoCloseable {
    override fun close() = reservations.forEach(MaskReservation::close)
}

internal fun EditorViewModel.reserveSelectionMaskCopies(
    owner: String,
    layers: List<SelectionLayer>,
): MaskReservationBatch? {
    val unique = identityBitmapSet()
    val reservations = ArrayList<MaskReservation>(layers.size)
    for (layer in layers) {
        if (!unique.add(layer.bitmap)) continue
        val reservation =
            reserveSelectionMaskCopy(
                owner = "$owner:${layer.id}",
                source = layer.bitmap,
                config = Bitmap.Config.ARGB_8888,
            ) ?: run {
                reservations.forEach(MaskReservation::close)
                return null
            }
        reservations += reservation
    }
    return MaskReservationBatch(reservations)
}

internal fun EditorViewModel.reserveSelectionMaskCopy(
    owner: String,
    source: Bitmap,
    config: Bitmap.Config? = source.config,
    documentLayerDelta: Int = 0,
): MaskReservation? =
    selectionMaskOwnership.reserve(
        owner = owner,
        bytes = BitmapMemoryBudget.bytes(source.width, source.height, config),
        documentLayerDelta = documentLayerDelta,
    )

internal fun EditorViewModel.reserveSelectionMaskOutput(
    owner: String,
    bytes: Long,
): MaskReservation? =
    if (bytes <= 0L) null else selectionMaskOwnership.reserve(owner = owner, bytes = bytes, documentLayerDelta = 0)

internal fun selectionMaskCandidateBytes(layers: List<SelectionLayer>): Long {
    val unique = identityBitmapSet()
    layers.forEach { unique.add(it.bitmap) }
    return unique.sumOf { BitmapMemoryBudget.bytes(it) }
}

internal fun EditorViewModel.reserveSelectionMaskCandidateBytes(
    owner: String,
    bytes: Long,
    documentLayerCount: Int,
): SelectionMaskOwnershipLedger.MaskAdmission =
    selectionMaskOwnership.reserveDocumentCandidate(
        owner = owner,
        bytes = bytes,
        documentLayerCount = documentLayerCount,
    )

internal fun EditorViewModel.reserveSelectionMaskCandidate(
    owner: String,
    layers: List<SelectionLayer>,
    bytesAlreadyReserved: Boolean = false,
): SelectionMaskOwnershipLedger.MaskAdmission =
    selectionMaskOwnership.reserveDocumentCandidate(
        owner = owner,
        bytes = if (bytesAlreadyReserved) 0L else selectionMaskCandidateBytes(layers),
        documentLayerCount = layers.size,
    )

private fun identityBitmapSet(): MutableSet<Bitmap> =
    Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())

internal fun Bitmap.copyOrThrow(
    config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    mutable: Boolean = true,
): Bitmap {
    check(!isRecycled) { "bitmap is recycled" }
    val required = BitmapMemoryBudget.bytes(width, height, config)
    if (!BitmapMemoryBudget.canAllocate(required)) throw BitmapAllocationRejectedException(required)
    return try {
        copy(config, mutable) ?: throw IllegalStateException("bitmap copy failed")
    } catch (_: OutOfMemoryError) {
        throw BitmapAllocationRejectedException(required)
    }
}

internal fun createBitmapOrThrow(
    width: Int,
    height: Int,
    config: Bitmap.Config = Bitmap.Config.ARGB_8888,
): Bitmap {
    val required = BitmapMemoryBudget.bytes(width, height, config)
    if (!BitmapMemoryBudget.canAllocate(required)) throw BitmapAllocationRejectedException(required)
    return try {
        Bitmap.createBitmap(width, height, config)
    } catch (_: OutOfMemoryError) {
        throw BitmapAllocationRejectedException(required)
    }
}

/**
 * Pre-checks whether a selection-mask layer of [width * height] pixels may be admitted into
 * the current document without exceeding the bitmaps-per-document mask budget.
 *
 * Callers must pass the current document identity (sourcePath, baseContentToken) to gate
 * against document replacement races. If admission is denied, the action must leave the
 * previous document unchanged.
 */
internal fun EditorViewModel.tryAdmitSelectionMaskLayer(
    targetWidth: Int,
    targetHeight: Int,
): Boolean {
    val state = uiState.value
    val existingBytes =
        state.selectionLayers.sumOf { BitmapMemoryBudget.bytes(it.bitmap) }
    val candidateBytes = BitmapMemoryBudget.bytes(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val currentCount = state.selectionLayers.size
    return BitmapMemoryBudget.canAdmitSelectionLayer(
        existingBytes, candidateBytes, currentCount,
    )
}

internal fun createScaledBitmapOrThrow(
    bitmap: Bitmap,
    width: Int,
    height: Int,
    filter: Boolean,
): Bitmap {
    val required = BitmapMemoryBudget.bytes(width, height, bitmap.config)
    if (!BitmapMemoryBudget.canAllocate(required)) throw BitmapAllocationRejectedException(required)
    return try {
        Bitmap.createScaledBitmap(bitmap, width, height, filter)
    } catch (_: OutOfMemoryError) {
        throw BitmapAllocationRejectedException(required)
    }
}

internal fun EditorHistorySnapshot.recycleBitmaps() {
    if (resourcesReleased) {
        Log.w(FLARE_GUARD_AI_TAG, "History bitmap release underflow: snapshot already released")
        return
    }
    releaseLocalDiagnostics()
    maskReservations.forEach(MaskReservation::close)
    maskReservations.clear()
    candidateAdmission?.close()
    candidateAdmission = null
    resourcesReleased = true
    val bitmaps = identityBitmapSet()
    previewBitmap?.let(bitmaps::add)
    originalPreviewBitmap?.let(bitmaps::add)
    selectionLayers.forEach { bitmaps.add(it.bitmap) }
    bitmaps.forEach { if (!it.isRecycled) it.recycle() }
    previewBitmap = null
    originalPreviewBitmap = null
    selectionLayers = emptyList()
}

internal fun EditorHistorySnapshot.releaseBitmapOwnership() {
    check(!resourcesReleased) { "history snapshot resources already released" }
    releaseLocalDiagnostics()
    maskReservations.forEach(MaskReservation::close)
    maskReservations.clear()
    candidateAdmission?.close()
    candidateAdmission = null
    resourcesReleased = true
    previewBitmap = null
    originalPreviewBitmap = null
    selectionLayers = emptyList()
}

private enum class DebugComparisonMode {
    NativeRoutes,
    ProcessingEngines,
}

internal data class EngineSelection(
    val noiseEngine: NoiseEngine,
    val detailEngine: DetailEngine,
    val toneEngine: ToneEngine,
    val hazeEngine: DehazeEngine,
) {
    fun compactLabel(): String =
        listOf(noiseEngine.label, detailEngine.label, toneEngine.label, hazeEngine.label)
            .joinToString(" / ")
}

internal fun EditorUiState.engineSelection(): EngineSelection =
    EngineSelection(
        noiseEngine = noiseEngine,
        detailEngine = detailEngine,
        toneEngine = toneEngine,
        hazeEngine = hazeEngine,
    )

private data class LumaStats(
    val p01: Float,
    val p05: Float,
    val p50: Float,
    val p95: Float,
    val p99: Float,
    val mean: Float,
    val chromaMean: Float,
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
        noiseReduction = noiseReduction,
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
        chromaMean = chromaSum / count,
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
            val restored =
                try {
                    preferences
                        .edit()
                        .remove(KEY_DRAFT_SOURCE)
                        .apply {
                            if (previousPointer != null)
                                putString(KEY_DRAFT_SOURCE, previousPointer)
                        }
                        .commit()
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
    val debugInfo: RecoveryDebugInfo,
)

private fun resolveDraftRecovery(
    context: Context,
    storedSourcePath: String,
): DraftRecoveryResolution {
    val storedSource = File(storedSourcePath)
    val persistentSource = persistentDraftSourceFile(context)
    val storedExists = storedSource.isFile
    val persistentExists = persistentSource.isFile
    val storedInCache = storedSource.absolutePath.startsWith(context.cacheDir.absolutePath)
    val sourceFile =
        when {
            storedExists && isOwnedDraftSource(context, storedSource) -> storedSource
            storedExists -> migrateDraftSourceIfNeeded(context, storedSource.absolutePath)
            storedInCache && persistentExists -> {
                runCatching {
                    context
                        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_DRAFT_SOURCE, persistentSource.absolutePath)
                        .commit()
                }
                persistentSource
            }
            storedSource.absolutePath == persistentSource.absolutePath && persistentExists ->
                persistentSource
            else -> null
        }
    return DraftRecoveryResolution(
        sourceFile = sourceFile,
        missingLegacyCacheDraft = storedInCache && !storedExists,
        debugInfo =
            RecoveryDebugInfo(
                draftSourcePath = sourceFile?.absolutePath ?: storedSource.absolutePath,
                draftSourceExists = sourceFile?.isFile == true || storedExists,
                filesDirDraftPath = persistentSource.absolutePath,
                filesDirDraftExists = persistentExists,
            ),
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

private fun persistDraftSourceFileIfNeeded(
    context: Context,
    sourcePath: String,
): DraftSourceResult? {
    val source = File(sourcePath).takeIf { it.isFile } ?: return null
    if (isOwnedDraftSource(context, source)) return DraftSourceResult(source, changed = false)
    return persistDraftSourceFile(context, source.absolutePath)?.let {
        DraftSourceResult(it, changed = true)
    }
}

private fun persistDraftBitmapFile(context: Context, bitmap: Bitmap): File? {
    val draftSource = File(persistentDraftDirectory(context), "source_${UUID.randomUUID()}.img")
    val temp = File(draftSource.parentFile, "${draftSource.name}.tmp")
    try {
        FileOutputStream(temp).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "failed to encode draft bitmap"
            }
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
    return candidate.parentFile == directory &&
        candidate.name.startsWith("source_") &&
        candidate.extension == "img" &&
        candidate.isFile
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
    first != null &&
        second != null &&
        runCatching { File(first).canonicalFile == File(second).canonicalFile }.getOrDefault(false)

private fun sameOptionalCanonicalPath(first: String?, second: String?): Boolean =
    if (first == null || second == null) first == second else sameCanonicalPath(first, second)

private fun deleteObsoleteDraftSources(context: Context, keep: File, preservePath: String?) {
    val directory = persistentDraftDirectory(context)
    val preserve = preservePath?.let { runCatching { File(it).canonicalFile }.getOrNull() }
    directory.listFiles()?.forEach { file ->
        val owned = file.name.startsWith("source_") && file.extension == "img"
        if (owned && file.canonicalFile != keep.canonicalFile && file.canonicalFile != preserve)
            file.delete()
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

private data class TrackedDecode(val bitmap: Bitmap, val edge: Long)

private fun decodeSampledMutableBitmap(
    path: String,
    maxSide: Int,
    diagnostics: MemoryTrackerScope?,
): TrackedDecode {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "지원하지 않는 이미지이거나 디코딩에 실패했습니다" }
    var sample = 1
    val longest = max(bounds.outWidth, bounds.outHeight)
    while (longest / sample > maxSide) sample *= 2
    val estimatedBytes =
        BitmapMemoryBudget.bytes(
            (bounds.outWidth + sample - 1) / sample,
            (bounds.outHeight + sample - 1) / sample,
        )
    val decodePeakBytes = BitmapMemoryBudget.saturatingMultiply(estimatedBytes, 2L)
    if (!BitmapMemoryBudget.canAllocate(decodePeakBytes))
        throw BitmapAllocationRejectedException(decodePeakBytes)
    val options =
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
    var decoded: Bitmap? = null
    var decodedEdge = 0L
    try {
        decoded = requireNotNull(BitmapFactory.decodeFile(path, options)) { "미리보기 디코딩에 실패했습니다" }
        if (decoded!!.config == Bitmap.Config.ARGB_8888 && decoded!!.isMutable) {
            return TrackedDecode(decoded!!, diagnostics?.track(decoded!!, "decode:source") ?: 0L)
        }
        decodedEdge = diagnostics?.track(decoded!!, "decode:source") ?: 0L
        val mutable = decoded!!.copyOrThrow(Bitmap.Config.ARGB_8888, true)
        val mutableEdge = diagnostics?.track(mutable, "decode:mutableResult") ?: 0L
        decoded!!.recycle()
        diagnostics?.release(decodedEdge)
        decoded = null
        return TrackedDecode(mutable, mutableEdge)
    } catch (t: Throwable) {
        decoded?.takeUnless(Bitmap::isRecycled)?.recycle()
        diagnostics?.release(decodedEdge)
        if (t is OutOfMemoryError) throw BitmapAllocationRejectedException(decodePeakBytes)
        throw t
    }
}

private fun decodeSampledMutableBitmapWithExif(
    path: String,
    maxSide: Int,
    diagnostics: MemoryTrackerScope? = null,
): Bitmap {
    val decoded = decodeSampledMutableBitmap(path, maxSide, diagnostics)
    return applyExifOrientation(path, decoded.bitmap, decoded.edge, diagnostics)
}

private fun applyExifOrientation(
    path: String,
    bitmap: Bitmap,
    bitmapEdge: Long,
    diagnostics: MemoryTrackerScope?,
): Bitmap {
    val orientation =
        runCatching {
                ExifInterface(path)
                    .getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    )
            }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
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
    val transformPeakBytes =
        BitmapMemoryBudget.saturatingMultiply(BitmapMemoryBudget.bytes(bitmap), 2L)
    if (!BitmapMemoryBudget.canAllocate(transformPeakBytes)) {
        if (!bitmap.isRecycled) bitmap.recycle()
        throw BitmapAllocationRejectedException(transformPeakBytes)
    }
    var transformed: Bitmap? = null
    var mutable: Bitmap? = null
    var transformedEdge = 0L
    var mutableEdge = 0L
    try {
        transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (transformed !== bitmap)
            transformedEdge = diagnostics?.track(transformed!!, "decode:exifTransformed") ?: 0L
        mutable = transformed!!.copyOrThrow(Bitmap.Config.ARGB_8888, true)
        mutableEdge = diagnostics?.track(mutable!!, "decode:exifMutableResult") ?: 0L
        if (transformed !== bitmap && !transformed!!.isRecycled) transformed!!.recycle()
        diagnostics?.release(transformedEdge)
        if (mutable !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        diagnostics?.release(bitmapEdge)
        val result = mutable!!
        mutable = null
        transformed = null
        Log.i(
            FLARE_GUARD_AI_TAG,
            "Applied EXIF orientation=$orientation -> ${result.width}x${result.height}",
        )
        return result
    } catch (t: Throwable) {
        mutable?.takeUnless(Bitmap::isRecycled)?.recycle()
        diagnostics?.release(mutableEdge)
        transformed?.takeUnless { it === bitmap || it.isRecycled }?.recycle()
        diagnostics?.release(transformedEdge)
        if (transformed !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        diagnostics?.release(bitmapEdge)
        if (t is OutOfMemoryError) throw BitmapAllocationRejectedException(transformPeakBytes)
        throw t
    }
}

private fun rotateBitmap90(bitmap: Bitmap): Bitmap {
    val matrix = Matrix().apply { postRotate(90f) }
    val requiredBytes = BitmapMemoryBudget.bytes(bitmap)
    if (!BitmapMemoryBudget.canAllocate(requiredBytes))
        throw BitmapAllocationRejectedException(requiredBytes)
    return try {
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    } catch (_: OutOfMemoryError) {
        throw BitmapAllocationRejectedException(requiredBytes)
    }
}

private fun CropAspectRatio.rotatedForQuarterTurn(): CropAspectRatio =
    when (this) {
        CropAspectRatio.FourThree -> CropAspectRatio.ThreeFour
        CropAspectRatio.ThreeFour -> CropAspectRatio.FourThree
        CropAspectRatio.SixteenNine -> CropAspectRatio.NineSixteen
        CropAspectRatio.NineSixteen -> CropAspectRatio.SixteenNine
        else -> this
    }

internal suspend fun renderEditedBitmap(
    basePreview: Bitmap,
    params: EditParams,
    engines: EngineSelection,
    revision: Int,
    look: PresetColorLook? = null,
    quickEffects: List<ActiveQuickEffect> = emptyList(),
    route: NativeRenderRoute,
    diagnostics: MemoryTrackerScope? = null,
): Bitmap {
    var working: Bitmap? = basePreview.copyOrThrow(Bitmap.Config.ARGB_8888, true)
    return try {
        check(route != NativeRenderRoute.Compare)
        val selection =
            routingForCorrectionEngine(CorrectionEngine.Engine1).copy(nativeRender = route)
        val plan = RenderPipelinePlanner.create(selection, params, quickEffects)
        renderBitmapInNative(
            checkNotNull(working),
            plan.v1Params,
            engines,
            revision,
            look,
            diagnostics,
        )
        applyActiveQuickEffectsToBitmap(
            checkNotNull(working),
            plan.v1QuickEffects,
            revision,
            diagnostics,
        )
        val corrected =
            try {
                applyExperimentalNativeCorrections(
                    checkNotNull(working),
                    plan,
                    diagnostics,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (allocation: BitmapAllocationRejectedException) {
                throw allocation
            } catch (failure: Throwable) {
                throw V2RenderException(failure)
            }
        if (corrected !== working) {
            working?.takeUnless(Bitmap::isRecycled)?.recycle()
            working = corrected
        }
        applySelectedToneEngine(checkNotNull(working), engines.toneEngine)
        checkNotNull(working).also { working = null }
    } catch (t: Throwable) {
        working?.takeUnless(Bitmap::isRecycled)?.recycle()
        throw t
    }
}

private data class NativeSpecialEffectOp(val effect: Int, val strength: Float)

internal suspend fun applyActiveQuickEffectsToBitmap(
    bitmap: Bitmap,
    quickEffects: List<ActiveQuickEffect>,
    revision: Int,
    diagnostics: MemoryTrackerScope? = null,
) {
    quickEffects.forEach { effect ->
        effect.toNativeOperations().forEach { operation ->
            val result =
                NativePhotoCore.nativeApplySpecialEffectInPlace(
                    bitmap = bitmap,
                    effect = operation.effect,
                    strength = operation.strength.coerceIn(0f, 1f),
                    revision = revision,
                    diagnostics = diagnostics,
                )
            if (result < 0) {
                throw IllegalStateException(
                    "native special effect failed: effect=${operation.effect} code=$result"
                )
            }
        }
    }
}

private fun canPreflightCleanExport(sourcePath: String, resolution: ExportResolution): Boolean {
    val requiredBytes = estimateCleanExportPeakBytes(sourcePath, resolution)
    return requiredBytes != Long.MAX_VALUE && BitmapMemoryBudget.canAllocate(requiredBytes)
}

private fun estimateCleanExportPeakBytes(sourcePath: String, resolution: ExportResolution): Long {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(sourcePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Long.MAX_VALUE
    var sample = 1
    val longest = max(bounds.outWidth, bounds.outHeight)
    while (longest / sample > EXPORT_MAX_SIDE) sample *= 2
    val decodedWidth = (bounds.outWidth + sample - 1) / sample
    val decodedHeight = (bounds.outHeight + sample - 1) / sample
    val decodedBytes = BitmapMemoryBudget.bytes(decodedWidth, decodedHeight)
    val scale = resolution.scalePercent / 100f
    val scaledBytes =
        BitmapMemoryBudget.bytes(
            (decodedWidth * scale).roundToInt().coerceAtLeast(1),
            (decodedHeight * scale).roundToInt().coerceAtLeast(1),
        )
    return BitmapMemoryBudget.saturatingAdd(
        BitmapMemoryBudget.saturatingMultiply(decodedBytes, 3L),
        scaledBytes,
    )
}

private suspend fun renderEditedExport(
    sourcePath: String,
    resolution: ExportResolution,
    selectionLayers: List<SelectionLayer>,
    diagnostics: MemoryTrackerScope? = null,
    requestFactory: (Bitmap, List<SelectionLayer>) -> RenderRequest?,
): Bitmap {
    var decoded: Bitmap? = null
    try {
        decoded =
            decodeSampledMutableBitmapWithExif(sourcePath, maxSide = EXPORT_MAX_SIDE, diagnostics)
        val result =
            renderEditedExportFromBitmap(
                ownedBaseBitmap = checkNotNull(decoded).also { decoded = null },
                resolution = resolution,
                selectionLayers = selectionLayers,
                diagnostics = diagnostics,
                requestFactory = requestFactory,
            )
        decoded = null
        return result
    } catch (t: Throwable) {
        decoded?.takeUnless(Bitmap::isRecycled)?.recycle()
        selectionLayers.forEach { it.bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
        throw t
    }
}

private suspend fun renderEditedExportFromBitmap(
    ownedBaseBitmap: Bitmap,
    resolution: ExportResolution,
    selectionLayers: List<SelectionLayer>,
    diagnostics: MemoryTrackerScope? = null,
    requestFactory: (Bitmap, List<SelectionLayer>) -> RenderRequest?,
): Bitmap {
    var baseOwner: Bitmap? = ownedBaseBitmap
    var working: Bitmap? = null
    var scaled: Bitmap? = null
    val preparedLayers = ArrayList<SelectionLayer>(selectionLayers.size)
    try {
        val base = checkNotNull(baseOwner)
        selectionLayers.forEach { layer ->
            val mask =
                if (layer.bitmap.width == base.width && layer.bitmap.height == base.height) {
                    layer.bitmap
                } else {
                    createScaledBitmapOrThrow(layer.bitmap, base.width, base.height, true)
                }
            preparedLayers += layer.copy(bitmap = mask)
        }
        val request = requestFactory(base, preparedLayers)
        if (request == null) {
            working = base
            baseOwner = null
        } else {
            working = EditorRenderer.render(request).successOrThrow().output
        }
        scaled = scaleBitmapForExport(checkNotNull(working), resolution, diagnostics)
        if (scaled !== working) working?.takeUnless(Bitmap::isRecycled)?.recycle()
        val result = checkNotNull(scaled)
        working = null
        scaled = null
        return result
    } finally {
        val owned = identityBitmapSet()
        baseOwner?.let(owned::add)
        working?.let(owned::add)
        scaled?.let(owned::add)
        selectionLayers.forEach { owned.add(it.bitmap) }
        preparedLayers.forEach { owned.add(it.bitmap) }
        owned.forEach { it.takeUnless(Bitmap::isRecycled)?.recycle() }
    }
}

private suspend fun applyExperimentalNativeCorrections(
    source: Bitmap,
    plan: RenderPipelinePlan,
    diagnostics: MemoryTrackerScope? = null,
): Bitmap {
    val correctionParams = plan.v2Params ?: return source
    val destination =
        createBitmapOrThrow(source.width, source.height, Bitmap.Config.ARGB_8888)
    val edge = diagnostics?.track(destination, "nativeCorrectionsV2:transactionalDestination") ?: 0L
    try {
        val result =
            NativePhotoCore.nativeApplyCorrectionsV2(
                source,
                destination,
                correctionParams,
                diagnostics,
            )
        if (result < 0) {
            throw IllegalStateException("native V2 correction failed: code=$result")
        }
        return destination
    } catch (failure: Throwable) {
        destination.takeUnless(Bitmap::isRecycled)?.recycle()
        diagnostics?.release(edge)
        throw failure
    }
}

private suspend fun renderBitmapInNative(
    bitmap: Bitmap,
    params: EditParams,
    engines: EngineSelection,
    revision: Int,
    look: PresetColorLook? = null,
    diagnostics: MemoryTrackerScope? = null,
): Int {
    val result =
        NativePhotoCore.nativeRenderPreviewInPlace(
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
            look,
            diagnostics,
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

private fun lutValue(
    luts: Array<IntArray>,
    tilesX: Int,
    tilesY: Int,
    tx: Int,
    ty: Int,
    bin: Int,
): Float {
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

private fun scaleBitmapForExport(
    bitmap: Bitmap,
    resolution: ExportResolution,
    diagnostics: MemoryTrackerScope? = null,
): Bitmap {
    if (resolution.scalePercent >= 100) return bitmap
    val scale = resolution.scalePercent / 100f
    val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
    return createScaledBitmapOrThrow(bitmap, width, height, true).also {
        diagnostics?.track(it, "export:scaledResult")
    }
}

private class AndroidExportRowStore(
    private val context: Context,
) : ExportRowStore {
    override suspend fun insertPending(request: ExportRowRequest): Uri =
        withContext(Dispatchers.IO) {
            createExportPendingRow(context, request.fileName, request.format)
        }

    override suspend fun encode(uri: Uri, bitmap: Bitmap, format: ExportFormat) {
        withContext(Dispatchers.IO) {
            encodeExportRow(context, uri, bitmap, format)
        }
    }

    override suspend fun publish(uri: Uri) {
        withContext(Dispatchers.IO) { publishExportRow(context, uri) }
    }

    override suspend fun delete(uri: Uri) {
        withContext(Dispatchers.IO) { deletePendingExportRow(context, uri) }
    }
}

private fun createExportPendingRow(
    context: Context,
    fileName: String,
    format: ExportFormat,
): Uri {
    val resolver = context.contentResolver
    val values =
        ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, format.mimeType)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/KeplerStudio",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    val uri =
        resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("저장 위치를 만들 수 없습니다")
    return uri
}

private fun encodeExportRow(
    context: Context,
    uri: Uri,
    bitmap: Bitmap,
    format: ExportFormat,
) {
    if (format == ExportFormat.Heif) {
        writeHeifToUri(context, uri, bitmap)
    } else {
        writeCompressedBitmapToUri(context, uri, bitmap, format)
    }
}

private fun publishExportRow(context: Context, uri: Uri) {
    val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
    val resolver = context.contentResolver
    val updated = resolver.update(uri, values, null, null)
    require(updated > 0) { "failed to publish media store row" }
}

private fun deletePendingExportRow(context: Context, uri: Uri) {
    val deleted = context.contentResolver.delete(uri, null, null)
    require(deleted > 0) { "failed to delete pending media store row" }
}

private fun writeCompressedBitmapToUri(
    context: Context,
    uri: Uri,
    bitmap: Bitmap,
    format: ExportFormat,
) {
    val compressFormat =
        when (format) {
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
    val descriptor =
        context.contentResolver.openFileDescriptor(uri, "w") ?: error("HEIF 저장 스트림을 열 수 없습니다")
    descriptor.use { pfd ->
        val writer =
            HeifWriter.Builder(
                    pfd.fileDescriptor,
                    bitmap.width,
                    bitmap.height,
                    HeifWriter.INPUT_MODE_BITMAP,
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
    val originalSourcePath: String? = null,
    val algorithmContracts: AlgorithmContractSet = AlgorithmContractSet(),
    val baseProvenance: BaseProvenanceChain = BaseProvenanceChain(),
)

private class DraftPreferencesSnapshot(private val values: Map<String, *>) {
    fun getString(key: String, default: String?): String? = values[key] as? String ?: default

    fun getFloat(key: String, default: Float): Float =
        (values[key] as? Number)?.toFloat() ?: default
}

private data class DraftRestoreSnapshot(
    val preferences: DraftPreferencesSnapshot,
    val savedAtMillis: Long?,
    val recovery: DraftRecoveryResolution,
)

private data class DraftPointerSnapshot(val generationId: String?)

private sealed class GenerationRestoreOutcome {
    data object Restored : GenerationRestoreOutcome()

    data object Absent : GenerationRestoreOutcome()

    data object Stale : GenerationRestoreOutcome()

    data class MemoryRejected(val requiredBytes: Long) : GenerationRestoreOutcome()

    data class Invalid(val generationId: String) : GenerationRestoreOutcome()
}

public sealed class PresetApplyResult {
    data object Accepted : PresetApplyResult()

    data object AlreadyApplied : PresetApplyResult()

    data object Rejected : PresetApplyResult()
}

private fun EditorUiState.historyBitmapBytes(): Long {
    val bitmaps = identityBitmapSet()
    previewBitmap?.let(bitmaps::add)
    originalPreviewBitmap?.let(bitmaps::add)
    selectionLayers.forEach { bitmaps.add(it.bitmap) }
    return BitmapMemoryBudget.saturatingAdd(*bitmaps.map(BitmapMemoryBudget::bytes).toLongArray())
}

private fun EditorUiState.historyBitmapBytesFor(
    previewRef: Bitmap?,
    originalRef: Bitmap?,
    layerRefs: List<Pair<String, Bitmap>>,
): Long {
    val bitmaps = identityBitmapSet()
    previewRef?.let(bitmaps::add)
    originalRef?.let(bitmaps::add)
    layerRefs.forEach { (_, b) -> bitmaps.add(b) }
    return BitmapMemoryBudget.saturatingAdd(*bitmaps.map(BitmapMemoryBudget::bytes).toLongArray())
}

private fun EditorUiState.supportsMetadataOnlyHistory(): Boolean =
    originalPreviewBitmap != null && selectionLayers.isEmpty() && activeSelectionLayerId == null

internal fun EditorHistorySnapshot.bitmapBytes(): Long {
    val bitmaps = identityBitmapSet()
    previewBitmap?.let(bitmaps::add)
    originalPreviewBitmap?.let(bitmaps::add)
    selectionLayers.forEach { bitmaps.add(it.bitmap) }
    return BitmapMemoryBudget.saturatingAdd(*bitmaps.map(BitmapMemoryBudget::bytes).toLongArray())
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
    val correctionEngine: CorrectionEngine,
    val correctionEngineState: CorrectionEngineState,
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
    val originalSourcePath: String? = null,
    val algorithmContracts: AlgorithmContractSet = AlgorithmContractSet(),
    val baseProvenance: BaseProvenanceChain = BaseProvenanceChain(),
)

private data class DraftSourceResult(val file: File, val changed: Boolean)

private enum class QuickEffectGroup {
    Remove,
    Optics,
    Blur,
}

private fun createDraftSavePayload(
    context: Context,
    state: EditorUiState,
    epoch: Long,
    expectedPointerGenerationId: String?,
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
        val dirtyBitmapCopy =
            when {
                reusableSource -> null
                !state.baseBitmapDirty && state.sourcePath != null -> null
                else -> copyOwned(state.originalPreviewBitmap ?: state.previewBitmap)
            }
        if (state.baseBitmapDirty && dirtyBitmapCopy == null) error("draft save bitmap is missing")
        val editedPreviewCopy = copyOwned(state.previewBitmap) ?: error("draft preview is missing")
        val copiedLayers =
            state.selectionLayers.map { layer ->
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
            correctionEngine = state.correctionEngineState.documentEngine,
            correctionEngineState = state.correctionEngineState,
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
            originalSourcePath = state.sourcePath,
            algorithmContracts = state.algorithmContracts,
            baseProvenance = state.baseProvenance,
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
    val directory =
        runCatching { File(context.filesDir, "editor_sources").canonicalFile }.getOrNull() ?: return
    val source = runCatching { File(sourcePath).canonicalFile }.getOrNull() ?: return
    if (
        source.parentFile == directory &&
            source.name.startsWith("restored_") &&
            source.extension == "img"
    ) {
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
        }
        .getOrNull()
}

private fun saveDraftSnapshot(
    context: Context,
    payload: DraftSavePayload,
    isCurrent: () -> Boolean,
): DraftSaveResult? {
    val draftSource =
        when {
            payload.previousVisibleDraftPath?.let(::File)?.isFile == true &&
                sameCanonicalPath(
                    context
                        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        .getString(KEY_DRAFT_SOURCE, null),
                    payload.previousVisibleDraftPath,
                ) &&
                context
                    .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_DRAFT_BASE_TOKEN, null) == payload.baseContentToken &&
                isSupportedDraftSource(context, File(payload.previousVisibleDraftPath)) ->
                DraftSourceResult(File(payload.previousVisibleDraftPath), changed = false)
            !payload.baseBitmapDirty && payload.sourcePath != null ->
                persistDraftSourceFileIfNeeded(context, payload.sourcePath)
            payload.dirtyBitmapCopy != null ->
                persistDraftBitmapFile(context, payload.dirtyBitmapCopy)?.let {
                    DraftSourceResult(it, changed = true)
                }
            else -> null
        } ?: return null
    if (!isCurrent()) {
        if (draftSource.changed) draftSource.file.delete()
        return null
    }
    val savedAt = System.currentTimeMillis()
    val generationResult =
        persistDraftGenerationInternal(
            context = context,
            payload = payload,
            draftSourceFile = draftSource.file,
            savedAt = savedAt,
            dirtyBitmapCopy = payload.dirtyBitmapCopy,
            isCurrent = isCurrent,
        )
    if (generationResult == null) {
        if (draftSource.changed && isOwnedDraftSource(context, draftSource.file))
            draftSource.file.delete()
        return null
    }
    return generationResult.copy(
        compatibilitySourceFile = draftSource.file,
        compatibilitySourceChanged = draftSource.changed,
    )
}

private fun persistLegacyDraftCompatibility(
    context: Context,
    payload: DraftSavePayload,
    saved: DraftSaveResult,
) {
    val draftSource = saved.compatibilitySourceFile ?: return
    val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val commitSucceeded =
        try {
            preferences
                .edit()
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
                .putFloat(
                    KEY_DRAFT_LUMINANCE_NOISE_REDUCTION,
                    payload.params.luminanceNoiseReduction,
                )
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
    if (commitSucceeded && saved.compatibilitySourceChanged)
        runCatching { saveDraftThumbnailFile(context, draftSource) }
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
    isCurrent: () -> Boolean,
): DraftSaveResult? {
    val genId = UUID.randomUUID().toString()
    var genDir = newDraftGenerationDirectory(context)
    var pendingResult: DraftSaveResult? = null
    var pointerCommitted = false
    try {
        if (!isCurrent()) return null
        val maskEntries =
            ArrayList<Pair<SelectionLayer, DraftSelectionLayerEntry>>(payload.selectionLayers.size)
        payload.selectionLayers.forEachIndexed { index, layer ->
            val fileName = "mask_${index}.png"
            val entry =
                DraftSelectionLayerEntry(
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
                    sourceIdentity = payload.baseContentToken,
                )
            maskEntries += layer to entry
        }
        val sourceIdentity = payload.baseContentToken
        val sourceBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(draftSourceFile.absolutePath, sourceBounds)
        val thumbnailDimensions =
            payload.editedPreviewCopy?.let { draftThumbnailDimensions(it.width, it.height) }
                ?: (0 to 0)
        val manifest =
            DraftGenerationManifest(
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
                correctionEngine = payload.correctionEngine.name,
                previewEngine = payload.correctionEngineState?.previewEngine?.name,
                previewRoute = payload.correctionEngineState?.previewRoute?.name,
                requestedRoute = payload.correctionEngineState?.requestedRoute?.name,
                previewResultClass = payload.correctionEngineState?.previewResultClass?.name,
                fallbackReason = payload.correctionEngineState?.fallbackReason?.name,
                renderDecision =
                    (payload.correctionEngineState?.visiblePreview as? VisiblePreviewState.Rendered)
                        ?.decision
                        ?.name,
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
                showSelectionOverlay = payload.showSelectionOverlay,
                algorithmVersion = payload.correctionEngineState?.algorithmVersion,
                renderParticipation = payload.correctionEngineState?.participation,
                algorithmContracts = payload.algorithmContracts,
                baseProvenance = payload.baseProvenance,
            )
        if (
            !writeDraftGeneration(
                context = context,
                genDir = genDir,
                manifest = manifest,
                baseBitmapDirty = payload.baseBitmapDirty,
                reusableSourceFile = draftSourceFile.takeIf { !payload.baseBitmapDirty },
                dirtyBitmapCopy = dirtyBitmapCopy ?: payload.dirtyBitmapCopy,
                editedPreviewCopy = checkNotNull(payload.editedPreviewCopy),
                maskEntries = maskEntries,
                isCurrent = isCurrent,
            )
        ) {
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
        val previousDirectory =
            payload.expectedPointerGenerationId?.let {
                findDraftGenerationDirectory(context, it)?.root
            }
        pendingResult =
            DraftSaveResult(
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
                originalSourcePath = payload.sourcePath,
            )
        if (!publishDraftGeneration(context, genDir.root.name)) {
            deleteDraftDirectory(context, genDir)
            return null
        }
        pointerCommitted = true
        return checkNotNull(pendingResult).copy(pointerPublished = true)
    } catch (t: Throwable) {
        if (pointerCommitted && pendingResult != null) {
            rollbackCommittedDraft(
                context,
                checkNotNull(pendingResult).copy(pointerPublished = true),
            )
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
        val previousIsComplete =
            runCatching {
                    saved.expectedPointerGenerationId != null &&
                        saved.previousGenerationDirectory?.let { directory ->
                            findDraftGenerationDirectory(context, saved.expectedPointerGenerationId)
                                ?.root
                                ?.canonicalFile == directory.canonicalFile
                        } == true
                }
                .getOrDefault(false)
        val restoredPrevious =
            previousIsComplete &&
                publishDraftGeneration(context, checkNotNull(saved.expectedPointerGenerationId))
        val rolledBack = restoredPrevious || clearCurrentDraftGenerationPointer(context)
        if (!rolledBack || currentDraftGenerationId(context) == saved.generationId) return
    }
    if (currentDraftGenerationId(context) != saved.generationId) {
        deleteDraftDirectory(context, DraftGenerationDirectory(saved.generationDirectory))
        saved.compatibilitySourceFile
            ?.takeIf { saved.compatibilitySourceChanged && isOwnedDraftSource(context, it) }
            ?.delete()
    }
}

private fun snapshotDraftPreferences(
    preferences: android.content.SharedPreferences
): Map<String, Any?> = preferences.all.filterKeys { key -> key.startsWith("draft_") }

private fun restoreDraftPreferences(
    preferences: android.content.SharedPreferences,
    snapshot: Map<String, Any?>,
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
    original: Throwable,
): Boolean {
    try {
        check(restoreDraftPreferences(preferences, snapshot)) {
            "failed to restore draft preferences"
        }
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

private fun QuickEffectKind.group(): QuickEffectGroup =
    when (this) {
        QuickEffectKind.SpotCleanup -> QuickEffectGroup.Remove
        QuickEffectKind.ChromaticAberrationReduction,
        QuickEffectKind.VignetteCorrection,
        QuickEffectKind.OpticsCorrection -> QuickEffectGroup.Optics
        QuickEffectKind.SoftBlur -> QuickEffectGroup.Blur
    }

private fun ActiveQuickEffect.toNativeOperations(): List<NativeSpecialEffectOp> =
    when (kind) {
        QuickEffectKind.SpotCleanup -> listOf(NativeSpecialEffectOp(effect = 0, strength = 0.58f))
        QuickEffectKind.ChromaticAberrationReduction ->
            listOf(NativeSpecialEffectOp(effect = 1, strength = 0.62f))
        QuickEffectKind.VignetteCorrection ->
            listOf(NativeSpecialEffectOp(effect = 2, strength = 0.45f))
        QuickEffectKind.OpticsCorrection ->
            listOf(
                NativeSpecialEffectOp(effect = 1, strength = 0.62f),
                NativeSpecialEffectOp(effect = 2, strength = 0.45f),
            )
        QuickEffectKind.SoftBlur ->
            listOf(
                NativeSpecialEffectOp(
                    effect = 3,
                    strength =
                        when (strength) {
                            QuickEffectStrength.Weak -> 0.22f
                            QuickEffectStrength.Medium -> 0.38f
                            QuickEffectStrength.Strong -> 0.58f
                        },
                )
            )
    }

private fun Float.toQuickEffectStrength(): QuickEffectStrength =
    when {
        this < 0.30f -> QuickEffectStrength.Weak
        this < 0.48f -> QuickEffectStrength.Medium
        else -> QuickEffectStrength.Strong
    }

private fun List<ActiveQuickEffect>.toDraftString(): String =
    joinToString("|") { "${it.kind.name}:${it.strength.name}" }

private fun String?.parseQuickEffects(): List<ActiveQuickEffect> =
    this?.split('|')
        ?.mapNotNull { token ->
            val parts = token.split(':')
            if (parts.size != 2) return@mapNotNull null
            val kind =
                runCatching { enumValueOf<QuickEffectKind>(parts[0]) }.getOrNull()
                    ?: return@mapNotNull null
            val strength =
                runCatching { enumValueOf<QuickEffectStrength>(parts[1]) }
                    .getOrDefault(QuickEffectStrength.Medium)
            ActiveQuickEffect(kind = kind, strength = strength)
        }
        .orEmpty()

private fun cleanupTemporarySourceFiles(context: Context, activeSourcePath: String?): Int {
    val now = System.currentTimeMillis()
    val maxAgeMs = 7L * 24L * 60L * 60L * 1000L
    val activePath = activeSourcePath?.let { File(it).absolutePath }
    val files =
        context.cacheDir
            .listFiles { file ->
                file.isFile && file.name.startsWith("source_") && file.name.endsWith(".img")
            }
            .orEmpty()
    var removed = 0
    files.forEach { file ->
        val expired = now - file.lastModified() > maxAgeMs
        val isActive = activePath != null && file.absolutePath == activePath
        if (expired && !isActive && file.delete()) removed += 1
    }
    return removed
}

private fun saveEngineSelection(context: Context, engines: EngineSelection) {
    context
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_NOISE_ENGINE, engines.noiseEngine.name)
        .putString(KEY_DETAIL_ENGINE, engines.detailEngine.name)
        .putString(KEY_TONE_ENGINE, engines.toneEngine.name)
        .putString(KEY_HAZE_ENGINE, engines.hazeEngine.name)
        .apply()
}

private fun loadEngineSelection(context: Context): EngineSelection {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    return EngineSelection(
            noiseEngine =
                enumValueOrDefault(
                    prefs.getString(KEY_NOISE_ENGINE, null),
                    NoiseEngine.FastEdgeAware,
                ),
            detailEngine =
                enumValueOrDefault(
                    prefs.getString(KEY_DETAIL_ENGINE, null),
                    DetailEngine.MaskedUnsharp,
                ),
            toneEngine =
                enumValueOrDefault(
                    prefs.getString(KEY_TONE_ENGINE, null),
                    ToneEngine.HistogramAuto,
                ),
            hazeEngine =
                enumValueOrDefault(
                    prefs.getString(KEY_HAZE_ENGINE, null),
                    DehazeEngine.FastContrast,
                ),
        )
        .coerceImplemented()
}

private fun EngineSelection.coerceImplemented(): EngineSelection =
    copy(
        noiseEngine =
            noiseEngine.takeIf { it in IMPLEMENTED_NOISE_ENGINES } ?: NoiseEngine.FastEdgeAware,
        detailEngine =
            detailEngine.takeIf { it in IMPLEMENTED_DETAIL_ENGINES } ?: DetailEngine.MaskedUnsharp,
        toneEngine =
            toneEngine.takeIf { it in IMPLEMENTED_TONE_ENGINES } ?: ToneEngine.HistogramAuto,
        hazeEngine =
            hazeEngine.takeIf { it in IMPLEMENTED_DEHAZE_ENGINES } ?: DehazeEngine.FastContrast,
    )

private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String?, default: T): T =
    runCatching { enumValueOf<T>(name ?: return default) }.getOrDefault(default)

private fun exportTimestamp(): String =
    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

internal const val FLARE_GUARD_AI_TAG = "KeplerFlareAI"
private const val EXPORT_MAX_SIDE = 8192
private const val DRAFT_SOURCE_FILE_NAME = "source.img"
private const val DRAFT_THUMBNAIL_FILE_NAME = "thumbnail.jpg"
private const val PREF_NAME = "kepler_studio_editor"
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
internal const val DRAFT_FORMAT_VERSION = 4
internal val IMPLEMENTED_NOISE_ENGINES =
    listOf(NoiseEngine.FastEdgeAware, NoiseEngine.GuidedFilter, NoiseEngine.NonLocalMeansLite)
internal val IMPLEMENTED_DETAIL_ENGINES =
    listOf(DetailEngine.MaskedUnsharp, DetailEngine.MultiLayerLaplacian)
internal val IMPLEMENTED_TONE_ENGINES =
    listOf(ToneEngine.HistogramAuto, ToneEngine.Clahe, ToneEngine.Filmic, ToneEngine.Sigmoid)
internal val IMPLEMENTED_DEHAZE_ENGINES =
    listOf(
        DehazeEngine.FastContrast,
        DehazeEngine.DarkChannelPrior,
        DehazeEngine.PyramidFusionDcp,
    )
