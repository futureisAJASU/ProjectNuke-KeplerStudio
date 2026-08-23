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
import com.projectnuke.keplerstudio.bridge.nativeCreateSessionHandleOrTest
import com.projectnuke.keplerstudio.bridge.nativeReleaseSessionOrTest
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
import com.projectnuke.keplerstudio.ui.updateActiveSelectionParamsLive
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
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject

private const val HISTORY_BUSY_MESSAGE =
    "편집 기록을 정리하는 중입니다. 잠시 후 다시 시도해 주세요."
private const val HISTORY_MEMORY_MESSAGE =
    "메모리가 부족하여 되돌리기 기록을 저장하지 못했습니다. 편집은 계속할 수 있습니다."
private const val HISTORY_STORAGE_FAILURE_MESSAGE =
    "편집은 적용했지만 되돌리기 기록을 저장소에 저장하지 못했습니다."
private const val HISTORY_STORAGE_BUDGET_MESSAGE =
    "편집은 적용했지만 되돌리기 기록 저장 공간이 부족하여 이번 기록을 유지하지 못했습니다."

internal class PendingHistorySnapshot(
    private val deferred: CompletableDeferred<EditorHistorySnapshot?>,
    private val onCompletedForTest: ((EditorHistorySnapshot?) -> Unit)? = null,
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
        val completion = synchronized(this) {
            if (terminal != Terminal.Pending) {
                false to value
            } else {
                completedResult = value
                terminal = Terminal.CompletedUnclaimed
                deferred.complete(value)
                true to null
            }
        }
        if (completion.first) onCompletedForTest?.invoke(value)
        completion.second?.recycleBitmaps()
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

private data class OpenImageIdentity(
    val token: Long,
    val invalidateRevision: Int,
    val incomingUri: Uri,
    val owningJob: Job,
)

private enum class OpenImageFailureStage {
    Source,
    Decode,
    NativeSession,
    Adoption,
}

internal enum class EditorLeavePhase {
    Idle,
    Quiescing,
    Saving,
    Completed,
    Failed,
    Closed,
}

internal data class EditorLeaveState(
    val token: Long? = null,
    val phase: EditorLeavePhase = EditorLeavePhase.Idle,
    val draftGenerationId: String? = null,
    val message: String? = null,
)

private class EditorLeaveOwner(val token: Long) {
    var phase: EditorLeavePhase = EditorLeavePhase.Quiescing
    var job: Job? = null
    val result = CompletableDeferred<Boolean>()
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
    private var nativeSessionRelease: ((Long) -> Unit)? = null
    private var renderJob: Job? = null
    private var openImageJob: Job? = null
    private var openImageToken: Long = 0L
    private var restoreDraftJob: Job? = null
    /** Startup coordinator owns history/reconciliation independently of the replaceable restore child. */
    private var startupCoordinatorJob: Job? = null
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
    /**
     * Unique legacy-source ownership identity for this ViewModel. All visible
     * Draft roots and document roots under drafts/current are registered under
     * this key so another ViewModel releasing a shared path can never drop
     * this instance's claim.
     */
    private val legacyDraftSourceOwner = LegacyDraftSourceOwnership.OwnerKey.create()
    private val _editorLeaveState = MutableStateFlow(EditorLeaveState())
    internal val editorLeaveState: StateFlow<EditorLeaveState> = _editorLeaveState.asStateFlow()
    private var editorLeaveCounter: Long = 0L
    private var editorLeaveOwner: EditorLeaveOwner? = null

    private fun editorLeaveLocksActions(): Boolean =
        editorLeaveOwner?.phase in setOf(
            EditorLeavePhase.Quiescing,
            EditorLeavePhase.Saving,
            EditorLeavePhase.Completed,
            EditorLeavePhase.Failed,
        )

    /** Pure read-only inspection for tests: current Draft save epoch. */
    internal fun draftEpochForTest(): Long = draftOperationEpoch

    /** Pure read-only inspection for tests: whether a Draft save job is queued/running. */
    internal fun hasActiveDraftSaveJobForTest(): Boolean = draftSaveJob?.isActive == true

    /** Terminal teardown assertion: no child of this ViewModel's owner scope may remain active. */
    internal fun hasActiveViewModelJobsForTest(): Boolean =
        viewModelScope.coroutineContext[Job]?.children?.any { it.isActive } == true

    /** Snapshot the actual owner jobs before ViewModelStore.clear() cancels them. */
    internal fun viewModelJobsForTest(): List<Job> =
        viewModelScope.coroutineContext[Job]?.children?.toList().orEmpty()

    internal fun activeViewModelJobDiagnosticsForTest(): String =
        viewModelScope.coroutineContext[Job]?.children
            ?.filter { !it.isCompleted }
            ?.joinToString(prefix = "[", postfix = "]") { it.toString() }
            ?: "[]"

    internal fun startupCoordinatorActiveForTest(): Boolean = startupCoordinatorJob?.isActive == true

    internal fun restoreDraftChildActiveForTest(): Boolean = restoreDraftJob?.isActive == true

    /** Test-only startup observability; production behavior never reads this field. */
    @Volatile internal var lastStartupStageForTest: StartupInitializationStage? = null

    internal fun memoryRecoveryOwnerPhaseForTest(): String? =
        userMemoryRecoveryOwner?.phase?.name

    internal fun memoryRecoveryOwnerCloseCountForTest(token: Long): Int =
        (userMemoryRecoveryOwner?.takeIf { it.descriptor.token == token }
            ?: lastClosedMemoryRecoveryOwner?.takeIf { it.descriptor.token == token })?.closeCount
            ?: 0

    /** Test-only capture of the most recent export coroutine exception for
     *  diagnostic production tests (never read by production logic). */
    @Volatile internal var lastExportFailureForTest: Throwable? = null

    /** Test-only diagnostics for the external image-open transaction. */
    @Volatile internal var lastOpenImageFailureForTest: Throwable? = null
    @Volatile internal var lastOpenImageCleanupFailureForTest: Throwable? = null

    /** Test-only diagnostic for nonfatal saved-export history failures. */
    @Volatile internal var lastSavedExportHistoryFailureForTest: Throwable? = null

    /** Test-only diagnostic for the most recent editor-leave failure. */
    @Volatile internal var lastEditorLeaveFailureForTest: Throwable? = null

    /** Test-only diagnostic: why the most recent draft snapshot persist returned false. */
    @Volatile internal var lastDraftSaveFailureReasonForTest: String? = null

    /** Pure read-only inspection for tests: current export token (drives
     *  stale-export identity checks across ViewModel state changes). */
    internal fun exportTokenForTest(): Long = exportToken

    /** Pure read-only inspection for tests: whether an export coroutine is live. */
    internal fun exportJobActiveForTest(): Boolean = exportJob?.isActive == true

    /** Test-only cancellation of the currently owned external image-open job. */
    internal fun cancelOpenImageForTest() {
        openImageJob?.cancel()
    }

    /** Test-only cancellation of the currently owned managed render. */
    internal fun cancelCurrentRenderForTest() {
        renderJob?.cancel()
    }

    internal fun openImageJobActiveForTest(): Boolean = openImageJob?.isActive == true

    /** Test-only access to this ViewModel's legacy-source ownership identity. */
    internal fun legacyDraftSourceOwnerKeyForTest(): LegacyDraftSourceOwnership.OwnerKey =
        legacyDraftSourceOwner

    /** Test-only trigger of the production invalidation that cancels the live
     *  export and bumps `exportToken`, equivalent to a document replacement. */
    internal fun invalidateExportForTest() = invalidateExport()

    /** Pure read-only inspection for tests: global saved-export history revision. */
    internal val savedExportHistoryRevisionForTest: Long
        get() = savedExportHistoryStore.revision

    /**
     * Completes when the startup coordinator's own phases settle: the initial
     * Draft restore/supersession, saved-export history load, and startup
     * reconciliation. A later automatic RestoreDraft memory retry is a
     * replaceable child operation and may outlive this signal; interactive
     * readiness is instead [canEnterEditorActionPure].
     */
    internal val startupInitCompletion = CompletableDeferred<Unit>()

    private val savedExportHistoryMutex = Mutex()
    /**
     * Single production owner of the saved-export history DataStore and
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
    internal var draftPointerBaseline: String? = currentDraftGenerationId(app.applicationContext)
    private val managedEdits by lazy(LazyThreadSafetyMode.NONE) {
        ManagedEditLaunchController(viewModelScope)
    }
    @Volatile private var shuttingDown: Boolean = false
    private var cropOperationToken: Long = 0L
    internal var selectionParamTransaction: SelectionParamTransaction? = null
    internal data class PendingSelectionParamStart(
        val prerequisite: HistoryActivityRegistry.Registration,
        val documentGeneration: String,
        val sourcePath: String?,
        val baseContentToken: String,
        val startingDocumentRevision: Int,
        val activeLayerId: String?,
        var latestIntendedLocalParams: EditParams,
        var job: Job? = null,
        var terminalFinish: Boolean = false,
        var closed: Boolean = false,
    )
    private var pendingSelectionParamStart: PendingSelectionParamStart? = null
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
        var job: Job? = null,
        var phase: AsyncBusyPhase = AsyncBusyPhase.Active,
    )
    private enum class AsyncBusyPhase { Active, Cancelling, Closed }
    private var asyncBusyOwner: AsyncBusyOwner? = null
    private var transactionFinishJob: Job? = null
    private val selectionTransactionGate = Any()
    private var brushingSnapshot: EditorHistorySnapshot? = null
    private enum class BrushTransactionState { Idle, Preparing, Active, Finishing, Cancelling }
    @Volatile private var brushTransactionState = BrushTransactionState.Idle
    private var brushStartSnapshot: LeasedEditorSnapshot? = null
    private val pendingBrushPoints = ArrayDeque<Pair<Float, Float>>()
    private var brushSettlementJob: Job? = null
    private enum class PendingBrushTerminal { Finish, Cancel }
    private data class PendingBrushStart(
        val prerequisite: HistoryActivityRegistry.Registration,
        val documentGeneration: String,
        val sourcePath: String?,
        val baseContentToken: String,
        val revision: Int,
        val activeLayerId: String,
        var job: Job? = null,
        var terminalIntent: PendingBrushTerminal? = null,
        var closed: Boolean = false,
    )
    private var pendingBrushStart: PendingBrushStart? = null
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

    /** Read-only test boundary for the active parameter render completion. */
    internal fun parameterRenderJobForTest(): Job? = parameterGesture?.renderJob

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

    /** Pure read-only inspection for navigation memory-recovery regressions. */
    internal fun memoryRecoveryActionForTest(): MemoryRetryAction? =
        userMemoryRecoveryOwner?.descriptor?.action
    internal fun memoryRecoveryRequiredBytesForTest(): Long? =
        userMemoryRecoveryOwner?.descriptor?.requiredBytes
    internal fun memoryRecoveryTokenForTest(): Long? =
        userMemoryRecoveryOwner?.descriptor?.token
    internal fun automaticRetryAttemptForTest(): MemoryRetryAction? = automaticRetryAttempt?.action
    internal fun strongRetryAttemptForTest(): MemoryRetryAction? = strongRetryAttempt?.action
    internal fun trimMemoryCleanupActiveForTest(): Boolean = trimMemoryCleanupJob?.isActive == true

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
        data class Committed(
            val adoptedRevision: Int,
            internal val historyPrerequisite: HistoryActivityRegistry.Registration? = null,
        ) : SettlementResult()
        data class RolledBack(val startRevision: Int) : SettlementResult()
        data object NoTransaction : SettlementResult()
        data object HistoryBusy : SettlementResult()
    }

    internal class OwnedHistorySnapshot(val snapshot: EditorHistorySnapshot) : AutoCloseable {
        private val ownership = AtomicBoolean(true)

        /** Transfers the snapshot to the history coordinator exactly once. */
        fun take(): EditorHistorySnapshot? =
            if (ownership.compareAndSet(true, false)) snapshot else null

        override fun close() {
            if (ownership.compareAndSet(true, false)) snapshot.recycleBitmaps()
        }
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
    private val historyStorageBackend = HistoryStorageBackendTestSeam.capture()
    internal val historyCoordinator =
        EditorHistoryCoordinator(
            app.applicationContext,
            viewModelScope,
            trackerSession,
            storage = historyStorageBackend ?: EditorHistoryStorage(app.applicationContext),
        )
    private val historyActivity =
        HistoryActivityRegistry(
            coordinatorBusy = { historyCoordinator.flags().busy },
            onChanged = { updateHistoryFlags() },
        )
        .also { historyCoordinator.onFlagsChanged = { updateHistoryFlags() } }
    private val uiStateOwnership: UiStateOwnershipReconciler? =
        trackerSession?.let(::UiStateOwnershipReconciler)
    internal val bitmapLeaseLedger = BitmapLeaseLedger()
    internal val selectionMaskOwnership =
        SelectionMaskOwnershipLedger(
            byteBudget = { BitmapMemoryBudget.selectionMaskBudgetBytes() },
            layerBudget = { BitmapMemoryBudget.maxSelectionMaskLayers() },
            pinBitmap = { bitmap -> bitmapLeaseLedger.pinBitmap(bitmap) },
        )
    private val historyIoJob: Job?
        get() = historyActivity.job
    private data class HistoryNavigationIdentity(
        val token: Long,
        val generation: String,
        val sourcePath: String?,
        val baseContentToken: String,
    )
    private var historyNavigationCounter: Long = 0L
    private var activeHistoryNavigation: HistoryNavigationIdentity? = null
    private data class ExternalActionDocumentIdentity(
        val generation: String,
        val sourcePath: String?,
        val baseContentToken: String,
        val revision: Int,
    )
    private var externalActionContinuation: Job? = null
    private enum class ExternalActionContinuationPhase { WaitingForOwnHistory, Resuming, Closed }
    private var externalActionContinuationPhase = ExternalActionContinuationPhase.Closed
    private var externalActionContinuationToken = 0L

    internal sealed interface EditorActionSettlement {
        data class Ready(
            val historyPrerequisite: HistoryActivityRegistry.Registration?,
        ) : EditorActionSettlement

        data object InteractiveOwnerStillSettling : EditorActionSettlement
        data object Closed : EditorActionSettlement
        data object HistoryBusy : EditorActionSettlement
        data object AcceptedActionBusy : EditorActionSettlement
        data object LeavingEditor : EditorActionSettlement
    }

    private enum class MemoryRecoveryPhase {
        Pending,
        Cleaning,
        AwaitingUserDecision,
        Retrying,
        Closed,
    }

    private enum class MemoryRecoverySource {
        Automatic,
        StrongUser,
    }

    private class MemoryRecoveryOwner(
        val descriptor: MemoryRetryDescriptor,
        var source: MemoryRecoverySource,
    ) {
        var phase: MemoryRecoveryPhase = MemoryRecoveryPhase.Pending
        var job: Job? = null
        var closeCount: Int = 0

        fun close() {
            if (phase == MemoryRecoveryPhase.Closed) return
            phase = MemoryRecoveryPhase.Closed
            closeCount += 1
            job?.cancel()
            job = null
        }
    }

    private data class RestoreBusyPublication(
        val token: Long,
        val revision: Int,
        val message: String,
        val retryOwner: MemoryRecoveryOwner? = null,
    )

    private var memoryRecoveryToken: Long = 0L
    private var userMemoryRecoveryOwner: MemoryRecoveryOwner? = null
    private var lastClosedMemoryRecoveryOwner: MemoryRecoveryOwner? = null
    private var trimMemoryCleanupJob: Job? = null
    private var automaticRetryAttempt: MemoryRetryDescriptor? = null
    private var strongRetryAttempt: MemoryRetryDescriptor? = null
    private var restoreBusyPublication: RestoreBusyPublication? = null

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
        val settlement = settleEditorAction()
        if (continueAfterEditorActionSettlement(settlement) {
                createBrushSelectionInternal(allowRecovery)
            }) return
        if (settlement !is EditorActionSettlement.Ready ||
            !canEnterEditorActionPure(allowMaskSupersession = true)
        ) return
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
            if (MemoryRecoveryTestSeam.capture()?.rejectSelectionMaskAdmission == true) {
                null
            } else {
                selectionMaskOwnership.reserve(
                    owner = "brushSelection:${start.identity.revision}:${UUID.randomUUID()}",
                    bytes = BitmapMemoryBudget.bytes(base.width, base.height, Bitmap.Config.ARGB_8888),
                    documentLayerDelta = 1,
                )
            }
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
        selectionTarget: SelectionRetryTarget = SelectionRetryTarget.Irrelevant,
        retryInput: MemoryRetryInput? = null,
    ) {
        if (shuttingDown) return
        val state = _uiState.value
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
                selectionTarget = selectionTarget,
                input = retryInput ?: memoryRetryInputFor(action, state, payload),
                navigationDirection = navigationDirection,
                targetEntryId = targetEntryId,
                coordinatorGeneration = coordinatorGeneration,
            )
        MemoryRecoveryTestSeam.capture()?.recoveryRequested?.complete(descriptor)
        when (classifyRetryFailure(userMemoryRecoveryOwner, descriptor)) {
            RetryFailureArbitration.StrongRetryFailure -> {
                settleStrongRetryFailure(descriptor)
                return
            }
            RetryFailureArbitration.AutomaticRetryFailure -> {
                transferAutomaticRetryFailure(descriptor)
                return
            }
            RetryFailureArbitration.FreshFailure,
            RetryFailureArbitration.UnrelatedFailureWhileRetrying,
            -> Unit
        }
        userMemoryRecoveryOwner?.let { owner ->
            if (
                owner.phase != MemoryRecoveryPhase.Retrying &&
                    owner.descriptor.matchesSameRecoveryRequest(descriptor)
            ) return
            retireUserMemoryRecoveryOwner(owner, clearUi = true)
        }
        val owner = MemoryRecoveryOwner(descriptor, MemoryRecoverySource.Automatic)
        userMemoryRecoveryOwner = owner
        owner.job = launchUserMemoryRecovery(owner, strong = false)
    }

    private fun classifyRetryFailure(
        owner: MemoryRecoveryOwner?,
        currentFailure: MemoryRetryDescriptor,
    ): RetryFailureArbitration {
        val strongAttempt = strongRetryAttempt
        if (
            strongAttempt.matchesRetryFailure(currentFailure) &&
                (owner == null || owner.descriptor === strongAttempt)
        ) {
            return RetryFailureArbitration.StrongRetryFailure
        }
        val automaticAttempt = automaticRetryAttempt
        if (
            automaticAttempt.matchesRetryFailure(currentFailure) &&
                (owner == null || owner.descriptor === automaticAttempt)
        ) {
            return RetryFailureArbitration.AutomaticRetryFailure
        }
        return if (owner?.phase == MemoryRecoveryPhase.Retrying) {
            RetryFailureArbitration.UnrelatedFailureWhileRetrying
        } else {
            RetryFailureArbitration.FreshFailure
        }
    }

    private fun settleStrongRetryFailure(currentFailure: MemoryRetryDescriptor) {
        val attempt = strongRetryAttempt
        if (attempt?.matchesRetryFailure(currentFailure) == true) {
            strongRetryAttempt = null
        }
        userMemoryRecoveryOwner
            ?.takeIf { it.descriptor === attempt }
            ?.let { owner ->
            closeUserMemoryRecoveryOwner(owner, clearUi = false, clearAttempts = false)
            }
        updateUiStateAndRecycleReplaced {
            it.copy(
                memoryRecoveryRequest = null,
                isBusy = false,
                message = "정리 후에도 현재 작업에 필요한 메모리를 확보하지 못했습니다. 이미지와 적용된 편집은 안전하게 유지됩니다.",
            )
        }
    }

    private fun transferAutomaticRetryFailure(currentFailure: MemoryRetryDescriptor) {
        val attempt = automaticRetryAttempt
        if (attempt?.matchesRetryFailure(currentFailure) == true) {
            automaticRetryAttempt = null
        }
        userMemoryRecoveryOwner
            ?.takeIf { it.descriptor === attempt }
            ?.let { owner ->
            closeUserMemoryRecoveryOwner(owner, clearUi = false, clearAttempts = false)
            }
        val owner = MemoryRecoveryOwner(currentFailure, MemoryRecoverySource.Automatic)
        owner.phase = MemoryRecoveryPhase.AwaitingUserDecision
        userMemoryRecoveryOwner = owner
        updateUiStateAndRecycleReplaced {
            it.copy(
                memoryRecoveryRequest =
                    MemoryRecoveryRequest(currentFailure.token, mayMoveOldHistory = true),
                isBusy = false,
                message = "현재 작업에 더 많은 메모리가 필요합니다.",
            )
        }
    }

    private fun memoryRetryInputFor(
        action: MemoryRetryAction,
        state: EditorUiState,
        payload: String?,
    ): MemoryRetryInput =
        when (action) {
            MemoryRetryAction.AutoStraightenCrop -> MemoryRetryInput.Crop(state.cropState)
            MemoryRetryAction.ExportPreview ->
                MemoryRetryInput.Export(state.exportFormat, state.exportResolution)
            MemoryRetryAction.RestoreDraft -> MemoryRetryInput.Draft(payload)
            MemoryRetryAction.RotatePreview ->
                MemoryRetryInput.Rotate(state.cropState)
            else -> MemoryRetryInput.Irrelevant
        }

    private fun restoreDraftRetryMessage(strong: Boolean): String =
        if (strong) {
            "\uBA54\uBAA8\uB9AC \uC815\uB9AC\uB97C \uC644\uB8CC\uD588\uC2B5\uB2C8\uB2E4. \uC791\uC5C5\uC744 \uB2E4\uC2DC \uC2DC\uB3C4\uD569\uB2C8\uB2E4."
        } else {
            "\uBA54\uBAA8\uB9AC\uB97C \uC790\uB3D9\uC73C\uB85C \uC815\uB9AC\uD588\uC2B5\uB2C8\uB2E4. \uC791\uC5C5\uC744 \uB2E4\uC2DC \uC2DC\uB3C4\uD569\uB2C8\uB2E4."
        }

    private fun memoryRetryInputIsCurrent(
        descriptor: MemoryRetryDescriptor,
        state: EditorUiState,
    ): Boolean =
        when (val input = descriptor.input) {
            MemoryRetryInput.Irrelevant -> true
            is MemoryRetryInput.Export ->
                state.exportFormat == input.format && state.exportResolution == input.resolution
            is MemoryRetryInput.Crop -> state.cropState == input.state
            is MemoryRetryInput.Rotate -> state.cropState == input.cropState
            is MemoryRetryInput.Draft ->
                when {
                    input.generationId != null ->
                        currentDraftGenerationId(getApplication<Application>()) == input.generationId
                    input.legacyIdentity != null ->
                        legacyDraftIdentity(getApplication<Application>()) == input.legacyIdentity
                    else -> true
                }
            is MemoryRetryInput.Route ->
                currentMemoryRetryRoute(descriptor.action, state) == input.route
        }

    private fun currentMemoryRetryRoute(
        action: MemoryRetryAction,
        state: EditorUiState,
    ): String? =
        when (action) {
            MemoryRetryAction.SubjectSelection ->
                RouteResolver.resolveSubjectRoute(
                    engine = state.correctionEngineState.documentEngine,
                    debugOverride = ExperimentalLabController.debugOverrides().subjectSelection,
                    modelAvailable =
                        ModelAvailabilityRegistry.state.value[ModelFeature.SubjectSelection]
                            ?.canAttemptModelUse == true,
                ).actualRoute.name
            MemoryRetryAction.MaskAwareRemaster ->
                RouteResolver.resolveRemasterRoute(
                    engine = state.correctionEngineState.documentEngine,
                    debugOverride = ExperimentalLabController.debugOverrides().remaster,
                    modelAvailable =
                        ModelAvailabilityRegistry.state.value[ModelFeature.Remaster]
                            ?.canAttemptModelUse == true,
                ).actualRoute.name
            else -> null
        }

    /**
     * Identity for the legacy Draft protocol only.  The editor preference file
     * also stores engine selections and export-history migration state; those
     * keys are deliberately excluded so an unrelated preference mutation
     * cannot invalidate a restore or memory retry.  The generation pointer is
     * retained in the identity because publishing a generation supersedes the
     * legacy payload even though it shares the same XML file.
     */
    private fun legacyDraftIdentity(context: Context): LegacyDraftIdentity {
        val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val values = preferences.all.toMap()
        val payloadFingerprint =
            values
                .asSequence()
                .filter { (key, _) -> key.startsWith("draft_") }
                .sortedBy { (key, _) -> key }
                .joinToString(separator = "\u001f") { (key, value) ->
                    "key=${canonicalIdentityPart(key)};value=${canonicalPreferenceValue(value)}"
                }
        return LegacyDraftIdentity(
            generationPointer = values[KEY_DRAFT_GENERATION_ID] as? String,
            payloadFingerprint = payloadFingerprint,
        )
    }

    private fun canonicalIdentityPart(value: String): String =
        "${value.length}:$value"

    private fun canonicalPreferenceValue(value: Any?): String =
        when (value) {
            null -> "null"
            is String -> "string:${canonicalIdentityPart(value)}"
            is Boolean -> "boolean:${if (value) "true" else "false"}"
            is Int -> "int:$value"
            is Long -> "long:$value"
            is Float -> "float:${java.lang.Float.floatToIntBits(value)}"
            is Double -> "double:${java.lang.Double.doubleToLongBits(value)}"
            is Set<*> ->
                "set:" +
                    value
                        .map { canonicalPreferenceValue(it) }
                        .sorted()
                        .joinToString(separator = ",", prefix = "[", postfix = "]")
            else -> "${value::class.java.name}:${canonicalIdentityPart(value.toString())}"
        }

    fun retryPendingMemoryRecovery(token: Long) {
        val owner = userMemoryRecoveryOwner?.takeIf {
            it.phase == MemoryRecoveryPhase.AwaitingUserDecision &&
                it.descriptor.token == token
        } ?: return
        // Validate staleness BEFORE any destructive cleanup.
        if (!isMemoryRecoveryOwnerCurrent(owner)) {
            retireUserMemoryRecoveryOwner(owner, clearUi = true)
            return
        }
        if (owner.phase != MemoryRecoveryPhase.AwaitingUserDecision) return
        owner.source = MemoryRecoverySource.StrongUser
        owner.phase = MemoryRecoveryPhase.Pending
        owner.job = launchUserMemoryRecovery(owner, strong = true)
    }

    fun cancelPendingMemoryRecovery(token: Long) {
        val owner = userMemoryRecoveryOwner?.takeIf {
            it.phase == MemoryRecoveryPhase.AwaitingUserDecision &&
                it.descriptor.token == token
        } ?: return
        retireUserMemoryRecoveryOwner(owner, clearUi = true)
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        if (shuttingDown || level < ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) return
        if (trimMemoryCleanupJob?.isActive == true) return
        trimMemoryCleanupJob =
            viewModelScope.launch {
                try {
                    userMemoryRecoveryOwner?.job?.takeIf { it.isActive }?.join()
                    if (shuttingDown) return@launch
                    MemoryRecoveryTestSeam.capture()?.awaitBeforeTrimCleanup()
                    performMemoryCleanup(
                        strong = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                    )
                } finally {
                    if (trimMemoryCleanupJob === currentCoroutineContext()[Job]) {
                        trimMemoryCleanupJob = null
                    }
                }
            }
    }

    private fun launchUserMemoryRecovery(
        owner: MemoryRecoveryOwner,
        strong: Boolean,
    ): Job =
        viewModelScope.launch {
            try {
                trimMemoryCleanupJob?.takeIf { it.isActive }?.join()
                if (!isMemoryRecoveryOwnerCurrent(owner)) {
                    retireUserMemoryRecoveryOwner(owner, clearUi = true)
                    return@launch
                }
                if (strong) {
                    MemoryRecoveryTestSeam.capture()?.awaitBeforeStrongCleanup(owner.descriptor)
                } else {
                    MemoryRecoveryTestSeam.capture()?.awaitBeforeAutomaticCleanup(owner.descriptor)
                }
                if (!isMemoryRecoveryOwnerCurrent(owner)) {
                    retireUserMemoryRecoveryOwner(owner, clearUi = true)
                    return@launch
                }
                owner.phase = MemoryRecoveryPhase.Cleaning
                val protectedEntryId = protectedHistoryTargetFor(owner.descriptor)
                if (
                    (owner.descriptor.action == MemoryRetryAction.HistoryUndo ||
                        owner.descriptor.action == MemoryRetryAction.HistoryRedo) &&
                        protectedEntryId == null
                ) {
                    retireUserMemoryRecoveryOwner(owner, clearUi = true)
                    return@launch
                }
                val cleanupResult =
                    performMemoryCleanup(
                        strong = strong,
                        protectedEntryId = protectedEntryId,
                        owner = owner,
                    )
                if (!isMemoryRecoveryOwnerCurrent(owner) || !cleanupResult.ownerStillCurrent) {
                    retireUserMemoryRecoveryOwner(owner, clearUi = true)
                    return@launch
                }
                val isHistoryAction =
                    owner.descriptor.action == MemoryRetryAction.HistoryUndo ||
                        owner.descriptor.action == MemoryRetryAction.HistoryRedo
                val generalRetryOk =
                    cleanupResult.reclaimedResources &&
                        isMemoryRecoveryOwnerCurrent(owner) &&
                        BitmapMemoryBudget.canAllocate(owner.descriptor.requiredBytes)
                val historyRetryOk =
                    isHistoryAction &&
                        cleanupResult.reclaimedResources &&
                        isMemoryRecoveryOwnerCurrent(owner) &&
                        BitmapMemoryBudget.canAllocate(owner.descriptor.requiredBytes) &&
                        cleanupResult.historyRecoveryCompleted &&
                        !cleanupResult.historyRecoverySuperseded &&
                        cleanupResult.historyDiskBudgetSatisfied
                val retryOk = if (isHistoryAction) historyRetryOk else generalRetryOk
                if (retryOk) {
                    owner.phase = MemoryRecoveryPhase.Retrying
                    val retryMessage =
                        if (owner.descriptor.action == MemoryRetryAction.RestoreDraft) {
                            restoreDraftRetryMessage(strong)
                        } else if (strong) {
                            "\uBA54\uBAA8\uB9AC \uC815\uB9AC\uB97C \uC644\uB8CC\uD588\uC2B5\uB2C8\uB2E4. \uC791\uC5C5\uC744 \uB2E4\uC2DC \uC2DC\uB3C4\uD569\uB2C8\uB2E4."
                        } else {
                            "\uBA54\uBAA8\uB9AC\uB97C \uC790\uB3D9\uC73C\uB85C \uC815\uB9AC\uD588\uC2B5\uB2C8\uB2E4. \uC791\uC5C5\uC744 \uB2E4\uC2DC \uC2DC\uB3C4\uD569\uB2C8\uB2E4."
                        }
                    updateRecoveryUiIfOwned(owner, null, retryMessage)
                    MemoryRecoveryTestSeam.capture()?.onRetryUiPublished(retryMessage)
                    if (owner.descriptor.action == MemoryRetryAction.RestoreDraft) {
                        publishRestoreBusy(
                            restoreDraftToken,
                            owner.descriptor.revision,
                            retryMessage,
                            retryOwner = owner,
                        )
                    }
                    if (strong) strongRetryAttempt = owner.descriptor else automaticRetryAttempt = owner.descriptor
                    if (owner.descriptor.action == MemoryRetryAction.RestoreDraft) {
                        val retryOutcome =
                            if (isMemoryRecoveryOwnerCurrent(owner)) {
                                performMemoryRetry(owner.descriptor, owner)
                                    ?: DraftRestoreRetryOutcome.Stale
                            } else {
                                DraftRestoreRetryOutcome.Stale
                            }
                        when (retryOutcome) {
                                DraftRestoreRetryOutcome.MemoryRejected -> {
                                    // The restore failure is arbitrated by requestAllocationRecovery.
                                    // It either transferred this owner to the next retry state or
                                    // closed it as a terminal retry failure.
                                    if (isMemoryRecoveryOwnerCurrent(owner)) {
                                        closeUserMemoryRecoveryOwner(
                                            owner,
                                            clearUi = true,
                                            clearAttempts = true,
                                        )
                                    }
                                }
                                DraftRestoreRetryOutcome.Restored -> {
                                    clearDraftRetryAttempts(owner.descriptor)
                                    if (isMemoryRecoveryOwnerCurrent(owner)) {
                                        closeUserMemoryRecoveryOwner(
                                            owner,
                                            clearUi = false,
                                            clearAttempts = true,
                                        )
                                    }
                                }
                                DraftRestoreRetryOutcome.Stale,
                                DraftRestoreRetryOutcome.ExactTargetMissing,
                                DraftRestoreRetryOutcome.ExactTargetInvalid,
                                DraftRestoreRetryOutcome.Cancelled,
                                -> settleDraftRestoreRetryTerminal(owner, retryOutcome)
                        }
                        return@launch
                    }
                    if (isMemoryRecoveryOwnerCurrent(owner)) {
                        performMemoryRetry(owner.descriptor, owner)
                    }
                    closeUserMemoryRecoveryOwner(owner, clearUi = false, clearAttempts = false)
                } else if (isMemoryRecoveryOwnerCurrent(owner) && strong) {
                    updateRecoveryUiIfOwned(
                        owner,
                        null,
                        "정리 후에도 현재 작업에 필요한 메모리를 확보하지 못했습니다. 이미지와 적용된 편집은 안전하게 유지됩니다.",
                    )
                    closeUserMemoryRecoveryOwner(owner, clearUi = false, clearAttempts = true)
                } else if (isMemoryRecoveryOwnerCurrent(owner)) {
                    owner.phase = MemoryRecoveryPhase.AwaitingUserDecision
                    updateRecoveryUiIfOwned(
                        owner,
                        MemoryRecoveryRequest(owner.descriptor.token, mayMoveOldHistory = true),
                        "현재 작업에 더 많은 메모리가 필요합니다.",
                    )
                }
            } finally {
                if (owner.job === currentCoroutineContext()[Job]) owner.job = null
            }
        }

    private fun protectedHistoryTargetFor(descriptor: MemoryRetryDescriptor): String? {
        if (
            descriptor.action != MemoryRetryAction.HistoryUndo &&
                descriptor.action != MemoryRetryAction.HistoryRedo
        ) return null
        if (!isMemoryRetryIdentityCurrent(descriptor)) return null
        val direction = descriptor.navigationDirection ?: return null
        val target = descriptor.targetEntryId ?: return null
        return target.takeIf { historyCoordinator.navigationTargetId(direction) == it }
    }

    private fun retireUserMemoryRecoveryOwner(owner: MemoryRecoveryOwner, clearUi: Boolean) {
        if (userMemoryRecoveryOwner !== owner) return
        val ownsRequest = _uiState.value.memoryRecoveryRequest?.token == owner.descriptor.token
        closeUserMemoryRecoveryOwner(owner, clearUi = false, clearAttempts = true)
        if (clearUi && ownsRequest) {
            updateUiStateAndRecycleReplaced { it.copy(memoryRecoveryRequest = null) }
        }
    }

    private fun closeUserMemoryRecoveryOwner(
        owner: MemoryRecoveryOwner,
        clearUi: Boolean,
        clearAttempts: Boolean,
    ) {
        if (userMemoryRecoveryOwner !== owner) return
        owner.close()
        lastClosedMemoryRecoveryOwner = owner
        if (clearAttempts) {
            if (automaticRetryAttempt === owner.descriptor) automaticRetryAttempt = null
            if (strongRetryAttempt === owner.descriptor) strongRetryAttempt = null
        }
        userMemoryRecoveryOwner = null
        if (clearUi) {
            updateUiStateAndRecycleReplaced { current ->
                if (current.memoryRecoveryRequest?.token == owner.descriptor.token) {
                    current.copy(memoryRecoveryRequest = null)
                } else current
            }
        }
    }

    private fun clearDraftRetryAttempts(descriptor: MemoryRetryDescriptor) {
        if (automaticRetryAttempt === descriptor) automaticRetryAttempt = null
        if (strongRetryAttempt === descriptor) strongRetryAttempt = null
    }

    private fun settleDraftRestoreRetryTerminal(
        owner: MemoryRecoveryOwner,
        outcome: DraftRestoreRetryOutcome,
    ) {
        clearDraftRetryAttempts(owner.descriptor)
        if (userMemoryRecoveryOwner === owner) {
            closeUserMemoryRecoveryOwner(owner, clearUi = true, clearAttempts = true)
        }
        if (outcome == DraftRestoreRetryOutcome.Restored) return
        val publication = restoreBusyPublication
        if (
            publication != null &&
                publication.retryOwner === owner &&
                isDocumentIdentityCurrent(owner.descriptor)
        ) {
            restoreBusyPublication = null
            updateUiStateAndRecycleReplaced { current ->
                val ownsMessage = current.message == publication.message
                // Busyg ownership is independent: the retry owns the busy state
                // whenever the publication hasn't been superseded and the document
                // identity hasn't changed.
                val ownsBusy = current.isBusy && publication.retryOwner === owner && isDocumentIdentityCurrent(owner.descriptor)
                if (ownsBusy || ownsMessage || current.memoryRecoveryRequest?.token == owner.descriptor.token) {
                    current.copy(
                        memoryRecoveryRequest = if (current.memoryRecoveryRequest?.token == owner.descriptor.token) null else current.memoryRecoveryRequest,
                        isBusy = if (ownsBusy) false else current.isBusy,
                        message = if (ownsMessage) draftRestoreRetryTerminalMessage(outcome) else current.message,
                    )
                } else current
            }
        }
    }

    private fun draftRestoreRetryTerminalMessage(outcome: DraftRestoreRetryOutcome): String? =
        when (outcome) {
            DraftRestoreRetryOutcome.Stale ->
                "임시저장 복구 대상이 변경되어 복구 시도를 중단했습니다."
            DraftRestoreRetryOutcome.ExactTargetMissing ->
                "임시저장 복구 데이터를 찾을 수 없어 복구를 중단했습니다."
            DraftRestoreRetryOutcome.ExactTargetInvalid ->
                "임시저장 복구 데이터가 유효하지 않아 복구를 중단했습니다."
            DraftRestoreRetryOutcome.Cancelled -> null
            DraftRestoreRetryOutcome.Restored,
            DraftRestoreRetryOutcome.MemoryRejected,
            -> null
        }

    private fun invalidateMemoryRecoveryForDocumentReplacement() {
        userMemoryRecoveryOwner?.let { owner ->
            owner.close()
            lastClosedMemoryRecoveryOwner = owner
        }
        userMemoryRecoveryOwner = null
        automaticRetryAttempt = null
        strongRetryAttempt = null
        restoreBusyPublication = null
    }

    private fun invalidateRestoreDraftRecoveryForDocumentReplacement() {
        val owner = userMemoryRecoveryOwner
        if (owner?.descriptor?.action != MemoryRetryAction.RestoreDraft) return
        owner.close()
        lastClosedMemoryRecoveryOwner = owner
        if (userMemoryRecoveryOwner === owner) userMemoryRecoveryOwner = null
        if (automaticRetryAttempt === owner.descriptor) automaticRetryAttempt = null
        if (strongRetryAttempt === owner.descriptor) strongRetryAttempt = null
        updateUiStateAndRecycleReplaced { current ->
            if (current.memoryRecoveryRequest?.token == owner.descriptor.token) {
                current.copy(memoryRecoveryRequest = null)
            } else current
        }
    }

    private fun updateRecoveryUiIfOwned(
        owner: MemoryRecoveryOwner,
        request: MemoryRecoveryRequest?,
        message: String?,
    ) {
        if (!isMemoryRecoveryOwnerCurrent(owner)) return
        updateUiStateAndRecycleReplaced {
            it.copy(memoryRecoveryRequest = request, message = message ?: it.message)
        }
    }

    private fun isMemoryRecoveryOwnerCurrent(owner: MemoryRecoveryOwner): Boolean =
        userMemoryRecoveryOwner === owner &&
            owner.phase != MemoryRecoveryPhase.Closed &&
            isMemoryRetryIdentityCurrent(owner.descriptor)

    private suspend fun performMemoryCleanup(
        strong: Boolean,
        protectedEntryId: String? = null,
        owner: MemoryRecoveryOwner? = null,
    ): MemoryCleanupResult {
        fun ownerCurrent(): Boolean = owner == null || isMemoryRecoveryOwnerCurrent(owner)
        fun staleResult() =
            MemoryCleanupResult(
                reclaimedResources = false,
                historyRecoveryCompleted = false,
                historyDiskBudgetSatisfied = false,
                historyRecoverySuperseded = true,
                ownerStillCurrent = false,
            )
        if (!ownerCurrent()) return staleResult()
        MemoryRecoveryTestSeam.capture()?.let { it.cleanupStarted += 1 }
        var reclaimedResources = false
        if (MemoryRecoveryTestSeam.capture()?.forceCleanupReclaimedResources == true) {
            reclaimedResources = true
        }
        if (!ownerCurrent()) return staleResult()
        if (strong && BuildConfig.DEBUG) {
            val hadComparison =
                comparisonJob != null || ExperimentalComparisonStore.latest.value != null
            invalidateComparison()
            if (hadComparison) reclaimedResources = true
        }
        if (!ownerCurrent()) return staleResult()
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
        historyActivity.clearCompleted()
        if (!ownerCurrent()) return staleResult()
        if (ThumbnailBitmapCache.evictUnleased() > 0L) reclaimedResources = true
        if (!ownerCurrent()) return staleResult()
        if (RemasterModelSession.unloadIdleNow()) reclaimedResources = true
        if (!ownerCurrent()) return staleResult()
        if (
            !_uiState.value.isBusy &&
                renderJob?.isActive != true &&
                exportJob?.isActive != true &&
                nativeSession != 0L
        ) {
            releaseNativeSession()
            reclaimedResources = true
        }
        if (!ownerCurrent()) return staleResult()
        val recoveryResult = historyCoordinator.recover(strong, protectedEntryId)
        if (!ownerCurrent()) return staleResult()
        if (recoveryResult.reclaimedRamBytes > 0L) reclaimedResources = true
        updateHistoryFlags()
        return MemoryCleanupResult(
            reclaimedResources = reclaimedResources,
            historyRecoveryCompleted = !recoveryResult.superseded,
            historyDiskBudgetSatisfied = recoveryResult.diskBudgetSatisfied,
            historyRecoverySuperseded = recoveryResult.superseded,
            ownerStillCurrent = true,
        )
    }

    private fun isMemoryRetryIdentityCurrent(descriptor: MemoryRetryDescriptor): Boolean {
        val state = _uiState.value
        if (shuttingDown) return false
        if (state.sourcePath != descriptor.sourcePath) return false
        if (state.baseContentToken != descriptor.baseContentToken) return false
        if (state.revision != descriptor.revision) return false
        if (!descriptor.selectionTarget.matchesCurrent(state.activeSelectionLayerId)) return false
        if (!memoryRetryInputIsCurrent(descriptor, state)) return false
        val currentGen = historyCoordinator.currentGeneration()
        if (
            descriptor.coordinatorGeneration != null &&
                descriptor.coordinatorGeneration != currentGen
        )
            return false
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

    private suspend fun performMemoryRetry(
        descriptor: MemoryRetryDescriptor,
        owner: MemoryRecoveryOwner,
    ): DraftRestoreRetryOutcome? {
        if (!isMemoryRecoveryOwnerCurrent(owner)) return null
        return when (descriptor.action) {
            MemoryRetryAction.CreateBrushSelection -> {
                createBrushSelectionInternal()
                null
            }
            MemoryRetryAction.SubjectSelection -> {
                addSubjectSelectionFromEdgeModel()
                null
            }
            MemoryRetryAction.MaskAwareRemaster -> {
                applyMaskAwareRemaster()
                null
            }
            MemoryRetryAction.FlareNight -> {
                applyFlareOriginalMvp()
                null
            }
            MemoryRetryAction.FlareSun -> {
                applySunFlareOriginalMvp()
                null
            }
            MemoryRetryAction.DuplicateSelection -> {
                duplicateActiveSelectionLayer()
                null
            }
            MemoryRetryAction.BackgroundSelection -> {
                createBackgroundSelectionFromActive()
                null
            }
            MemoryRetryAction.AutoStraightenCrop -> {
                autoStraightenCrop()
                null
            }
            MemoryRetryAction.ApplySelectionNative -> {
                applyActiveSelectionLocalEditNativeBaked()
                null
            }
            MemoryRetryAction.ExportPreview -> {
                exportPreview()
                null
            }
            MemoryRetryAction.OpenImage -> {
                descriptor.payload?.let { openImage(Uri.parse(it)) }
                null
            }
            MemoryRetryAction.RestoreDraft -> {
                MemoryRecoveryTestSeam.capture()?.awaitBeforeDraftRestoreRetry(descriptor)
                if (!isMemoryRecoveryOwnerCurrent(owner)) {
                    DraftRestoreRetryOutcome.Stale
                } else {
                retryDraftRestoreAfterMemory(
                    descriptor.input as? MemoryRetryInput.Draft
                        ?: MemoryRetryInput.Draft(descriptor.payload),
                )
                }
            }
            MemoryRetryAction.RotatePreview -> {
                rotatePreview90()
                null
            }
            MemoryRetryAction.HistoryUndo -> {
                descriptor.payload?.let { navigateHistory(true, it) }
                null
            }
            MemoryRetryAction.HistoryRedo -> {
                descriptor.payload?.let { navigateHistory(false, it) }
                null
            }
        }
    }

    private suspend fun retryDraftRestoreAfterMemory(
        input: MemoryRetryInput.Draft,
    ): DraftRestoreRetryOutcome {
        val completion = CompletableDeferred<DraftRestoreRetryOutcome>()
        val token = ++restoreDraftToken
        val revision = _uiState.value.revision
        val target =
            when {
                input.generationId != null -> DraftRestoreTarget.ExactGeneration(input.generationId)
                input.legacyIdentity != null -> DraftRestoreTarget.ExactLegacy(input.legacyIdentity)
                else -> DraftRestoreTarget.CurrentStartup
            }
        val job = viewModelScope.launch {
            try {
                restoreDraftIfAvailable(
                    getApplication<Application>(),
                    token,
                    revision,
                    target,
                    completion,
                )
                // Every exact retry must settle even if a future restore
                // branch returns without explicitly reporting an outcome.
                completion.complete(DraftRestoreRetryOutcome.ExactTargetInvalid)
            } catch (ce: CancellationException) {
                completion.complete(DraftRestoreRetryOutcome.Cancelled)
            } catch (_: Exception) {
                completion.complete(DraftRestoreRetryOutcome.ExactTargetInvalid)
            } catch (t: Throwable) {
                completion.complete(DraftRestoreRetryOutcome.ExactTargetInvalid)
                throw t
            }
        }
        restoreDraftJob = job
        job.invokeOnCompletion { if (restoreDraftJob === job) restoreDraftJob = null }
        return try {
            completion.await()
        } finally {
            if (job.isActive) job.cancelAndJoin()
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

    private fun retryAttemptMatchesSuccessfulOperation(
        attempt: MemoryRetryDescriptor,
        action: MemoryRetryAction,
        successfulSelectionTarget: SelectionRetryTarget,
    ): Boolean {
        if (shuttingDown) return false
        if (attempt.action != action) return false
        val state = _uiState.value
        if (state.sourcePath != attempt.sourcePath) return false
        if (state.baseContentToken != attempt.baseContentToken) return false
        if (!memoryRetryInputIsCurrent(attempt, state)) return false
        if (attempt.selectionTarget != successfulSelectionTarget) return false
        val revisionMatches =
            if (action in REVISION_ADVANCING_MEMORY_RETRY_ACTIONS) {
                state.revision == attempt.revision || state.revision == attempt.revision + 1
            } else {
                state.revision == attempt.revision
            }
        return revisionMatches
    }

    internal fun markMemoryRetrySucceeded(
        action: MemoryRetryAction,
        successfulSelectionLayerId: String? = null,
    ) {
        val successfulSelectionTarget =
            successfulSelectionLayerId?.let(SelectionRetryTarget::Layer)
                ?: SelectionRetryTarget.Irrelevant
        if (automaticRetryAttempt?.let {
            retryAttemptMatchesSuccessfulOperation(it, action, successfulSelectionTarget)
        } == true) {
            automaticRetryAttempt = null
        }
        if (strongRetryAttempt?.let {
            retryAttemptMatchesSuccessfulOperation(it, action, successfulSelectionTarget)
        } == true) {
            strongRetryAttempt = null
        }
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
        registerIncomingDocument: Boolean = true,
        adoptedRestoredWorkingSource: String? = null,
    ): Boolean {
        val settled =
            next.withVisibleNativeContractIfChangedFrom(expected).let {
                if (replaceDocument) it.copy(memoryRecoveryRequest = null) else it
            }
        return bitmapLeaseLedger.withStateTransition {
            if (!_uiState.compareAndSet(expected, settled)) return@withStateTransition false
            if (replaceDocument) {
                IncomingSourceLiveOwnership.replaceDocument(
                    previousPath = expected.sourcePath,
                    nextPath = settled.sourcePath.takeIf { registerIncomingDocument },
                )
                RestoredWorkingSourceOwnership.replaceDocument(
                    previousPath = expected.sourcePath,
                    nextPath = settled.sourcePath.takeIf { adoptedRestoredWorkingSource == null },
                )
                adoptedRestoredWorkingSource?.let { path ->
                    RestoredWorkingSourceOwnership.transferToDocument(
                        File(path),
                        expected.sourcePath,
                    )
                }
            }
            val generation =
                if (replaceDocument) {
                    invalidateMemoryRecoveryForDocumentReplacement()
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
    internal fun canApplyCorrectionEngineForUi(): Boolean {
        val state = _uiState.value
        return state.correctionEngineState.previewResultClass != PreviewResultClass.NoDocument &&
            !state.correctionEngineState.isSwitching &&
            canEnterEditorActionPure()
    }

    fun setDefaultCorrectionEngine(engine: CorrectionEngine) {
        if (shuttingDown || engine == _uiState.value.correctionEngineState.defaultEngine) return
        correctionEngineSettings.write(engine)
        updateUiState {
            it.copy(correctionEngineState = it.correctionEngineState.copy(defaultEngine = engine))
        }
    }

    fun applyCorrectionEngineToCurrentDocument(engine: CorrectionEngine) {
        if (shuttingDown) return
        val settlement = settleParameterTransactionBeforeExternalEdit()
        if (continueAfterOwnParameterSettlement(settlement) {
                applyCorrectionEngineToCurrentDocument(engine)
            }
        ) return
        prepareForMaskInteraction()
        if (brushTransactionState != BrushTransactionState.Idle || brushSettlementJob?.isActive == true) {
            updateUiState { it.copy(message = "브러시 작업이 끝난 뒤 다시 시도해 주세요.") }
            return
        }
        if (!canEnterEditorActionAfterSettlement()) return
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

    internal enum class EditorActionAdmission {
        Ready,
        HistoryBusy,
        AcceptedActionBusy,
        EditorBusy,
        LeavingEditor,
        Closed,
    }

    private fun historyActivityBusy(): Boolean = historyActivity.isBusy()

    private fun ownsHistoryNavigation(identity: HistoryNavigationIdentity): Boolean {
        val state = _uiState.value
        return !shuttingDown &&
            activeHistoryNavigation === identity &&
            currentDocumentGeneration() == identity.generation &&
            state.sourcePath == identity.sourcePath &&
            state.baseContentToken == identity.baseContentToken
    }

    internal fun historyActivityBusyForTest(): Boolean = historyActivityBusy()

    internal fun historyCaptureAvailabilityForTest(requiredBytes: Long): HistoryCaptureAvailability =
        if (historyActivityBusy()) HistoryCaptureAvailability.HistoryBusy
        else historyCoordinator.captureAvailability(requiredBytes)

    internal fun editorActionAdmissionForTest(
        allowMaskSupersession: Boolean = false,
    ): EditorActionAdmission = editorActionAdmission(allowMaskSupersession)

    private fun editorActionAdmission(
        allowMaskSupersession: Boolean,
    ): EditorActionAdmission {
        if (shuttingDown) return EditorActionAdmission.Closed
        if (editorLeaveLocksActions()) {
            return EditorActionAdmission.LeavingEditor
        }
        if (externalActionContinuation?.isActive == true &&
            externalActionContinuationPhase == ExternalActionContinuationPhase.WaitingForOwnHistory
        ) {
            return EditorActionAdmission.AcceptedActionBusy
        }
        if (pendingBrushStart != null) return EditorActionAdmission.AcceptedActionBusy
        if (pendingSelectionParamStart != null) return EditorActionAdmission.AcceptedActionBusy
        if (historyActivityBusy()) return EditorActionAdmission.HistoryBusy
        val state = _uiState.value
        if (!state.isBusy || allowMaskSupersession && isBusyOwnedByMaskSupersedable()) {
            return EditorActionAdmission.Ready
        }
        return EditorActionAdmission.EditorBusy
    }

    private fun reportHistoryBusyAdmission() {
        if (_uiState.value.isBusy) return
        updateUiState { state ->
            if (state.message == HISTORY_BUSY_MESSAGE) state
            else state.copy(message = HISTORY_BUSY_MESSAGE)
        }
    }

    private fun reportAcceptedActionBusyAdmission() {
        if (_uiState.value.isBusy) return
        updateUiState { state ->
            val message =
                "\uD3B8\uC9D1 \uC791\uC5C5\uC744 \uC815\uB9AC\uD558\uB294 \uC911\uC785\uB2C8\uB2E4. \uC7A0\uC2DC \uD6C4 \uB2E4\uC2DC \uC2DC\uB3C4\uD574 \uC8FC\uC138\uC694."
            if (state.message == message) state else state.copy(message = message)
        }
    }

    private fun reportHistorySettlementFailure() {
        if (_uiState.value.isBusy) return
        updateUiState { it.copy(message = "\uD3B8\uC9D1 \uAE30\uB85D\uC744 \uC800\uC7A5\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4. \uB2E4\uC2DC \uC2DC\uB3C4\uD574 \uC8FC\uC138\uC694.") }
    }

    internal fun canEnterEditorActionAfterSettlement(
        allowMaskSupersession: Boolean = false,
    ): Boolean {
        val admission = editorActionAdmission(allowMaskSupersession)
        if (admission == EditorActionAdmission.HistoryBusy) reportHistoryBusyAdmission()
        if (admission == EditorActionAdmission.AcceptedActionBusy) reportAcceptedActionBusyAdmission()
        return admission == EditorActionAdmission.Ready
    }

    /**
     * Continues only an external intent whose own parameter settlement created
     * the currently registered history operation.  This is deliberately not a
     * user-action queue: unrelated history activity is rejected by normal
     * admission and never reaches this helper.
     */
    internal fun continueAfterOwnParameterSettlement(
        settlement: SettlementResult,
        continuation: () -> Unit,
    ): Boolean {
        if (!settleInteractiveOwnersForEditorAction()) return false
        val prerequisite =
            (settlement as? SettlementResult.Committed)?.historyPrerequisite
            ?: return false
        return continueAfterHistoryPrerequisite(prerequisite, continuation)
    }

    internal fun continueAfterEditorActionSettlement(
        settlement: EditorActionSettlement,
        continuation: () -> Unit,
    ): Boolean {
        val prerequisite = (settlement as? EditorActionSettlement.Ready)?.historyPrerequisite
            ?: return false
        return continueAfterHistoryPrerequisite(prerequisite, continuation)
    }

    private fun continueAfterHistoryPrerequisite(
        prerequisite: HistoryActivityRegistry.Registration,
        continuation: () -> Unit,
    ): Boolean {
        if (editorLeaveLocksActions()) return false
        if (!historyActivityBusy()) return false
        if (externalActionContinuation?.isActive == true) return true
        val continuationToken = ++externalActionContinuationToken
        val state = _uiState.value
        val identity = ExternalActionDocumentIdentity(
            generation = historyCoordinator.currentGeneration(),
            sourcePath = state.sourcePath,
            baseContentToken = state.baseContentToken,
            revision = state.revision,
        )
        val continuationJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val prerequisiteOutcome = prerequisite.await()
                when (prerequisiteOutcome) {
                    HistoryPrerequisiteOutcome.Completed -> Unit
                    HistoryPrerequisiteOutcome.Cancelled -> return@launch
                    is HistoryPrerequisiteOutcome.Failed -> {
                        reportHistorySettlementFailure()
                        return@launch
                    }
                }
                if (shuttingDown) return@launch
                val current = _uiState.value
                if (historyCoordinator.currentGeneration() != identity.generation ||
                    current.sourcePath != identity.sourcePath ||
                    current.baseContentToken != identity.baseContentToken ||
                    current.revision != identity.revision ||
                    historyActivity.isBusyExcluding(prerequisite)
                ) return@launch
                if (externalActionContinuationToken != continuationToken) return@launch
                externalActionContinuationPhase = ExternalActionContinuationPhase.Resuming
                if (!canEnterEditorActionPure()) return@launch
                continuation()
            } finally {
                if (externalActionContinuation === coroutineContext[Job]) {
                    externalActionContinuationPhase = ExternalActionContinuationPhase.Closed
                    externalActionContinuation = null
                }
            }
        }
        externalActionContinuationPhase = ExternalActionContinuationPhase.WaitingForOwnHistory
        externalActionContinuation = continuationJob
        continuationJob.start()
        return true
    }

    internal fun settleEditorAction(): EditorActionSettlement {
        if (shuttingDown) return EditorActionSettlement.Closed
        if (editorLeaveLocksActions()) return EditorActionSettlement.LeavingEditor
        if (externalActionContinuation?.isActive == true &&
            externalActionContinuationPhase == ExternalActionContinuationPhase.WaitingForOwnHistory
        ) {
            reportAcceptedActionBusyAdmission()
            return EditorActionSettlement.AcceptedActionBusy
        }
        if (pendingBrushStart != null) {
            reportAcceptedActionBusyAdmission()
            return EditorActionSettlement.AcceptedActionBusy
        }
        if (pendingSelectionParamStart != null) {
            reportAcceptedActionBusyAdmission()
            return EditorActionSettlement.AcceptedActionBusy
        }
        if (historyActivityBusy()) {
            reportHistoryBusyAdmission()
            return EditorActionSettlement.HistoryBusy
        }
        val settlement = settleParameterTransactionBeforeExternalEdit()
        if (!settleInteractiveOwnersForEditorAction()) {
            return EditorActionSettlement.InteractiveOwnerStillSettling
        }
        return EditorActionSettlement.Ready(
            (settlement as? SettlementResult.Committed)?.historyPrerequisite,
        )
    }

    private fun settleInteractiveOwnersForEditorAction(): Boolean {
        if (brushTransactionState == BrushTransactionState.Finishing ||
            brushTransactionState == BrushTransactionState.Cancelling
        ) {
            updateUiState {
                it.copy(
                    message =
                        "\uBE0C\uB7EC\uC2DC \uC791\uC5C5\uC774 \uB9C8\uBB34\uB9AC\uB418\uB294 \uC911\uC785\uB2C8\uB2E4. \uC7A0\uC2DC \uD6C4 \uB2E4\uC2DC \uC2DC\uB3C4\uD574 \uC8FC\uC138\uC694."
                )
            }
            return false
        }
        if (brushTransactionState != BrushTransactionState.Idle) cancelBrushStroke()
        if (selectionParamTransaction != null) settleSelectionParamTransactionForSupersession()
        return brushTransactionState == BrushTransactionState.Idle &&
            selectionParamTransaction == null
    }

    @Deprecated("Use canEnterEditorActionPure; admission predicates must not settle editor state")
    internal fun canEnterEditorAction(allowMaskSupersession: Boolean = false): Boolean =
        canEnterEditorActionPure(allowMaskSupersession)

    /**
     * Settles interactive owners (pending parameter transaction, brush stroke,
     * selection transaction) so the caller's first action cannot capture
     * working pixels or observe a still-live optimistic transaction. Returns
     * false while a live owner remains or during shutdown. Side-effecting:
     * this is the only entrypoint that may mutate settlement state.
     */
    internal fun settleForEditorAction(): Boolean {
        if (shuttingDown) return false
        if (editorLeaveLocksActions()) return false
        if (historyActivityBusy()) return false
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
        return editorActionAdmission(allowMaskSupersession) == EditorActionAdmission.Ready
    }

    private suspend fun invalidateRemovedHistoryThumbnails(
        context: Context,
        result: SavedExportHistoryMutation,
        historyStore: SavedExportHistoryStore = savedExportHistoryStore,
    ) {
        withContext(Dispatchers.IO) {
            savedExportHistoryMutex.withLock {
                val retained = historyStore.load().map { it.uriString }.toSet()
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

    private fun isCurrentExportForUi(identity: ExportIdentity): Boolean =
        isCurrentExport(identity) && !editorLeaveLocksActions()

    /**
     * Export resource ownership is independent of whether leave currently
     * owns the user-facing message.  In particular, a terminal export job
     * must release its own busy bit while the leave screen is Completed or
     * Failed.  Do not add document identity here: an exact export owner may
     * legitimately become document-stale before it terminates.
     */
    private fun isExactExportOwner(identity: ExportIdentity): Boolean =
        !shuttingDown &&
            exportJob === identity.owningJob &&
            exportToken == identity.token

    private fun releaseExportBusyIfOwned(identity: ExportIdentity) {
        if (!isExactExportOwner(identity)) return
        updateUiStateAndRecycleReplaced { current ->
            if (current.isBusy) current.copy(isBusy = false) else current
        }
    }

    private fun releaseExportBusyIfTokenOwned(token: Long) {
        if (shuttingDown || exportToken != token) return
        updateUiStateAndRecycleReplaced { current ->
            if (current.isBusy) current.copy(isBusy = false) else current
        }
    }

    private fun releaseExportBusyIfJobOwned(job: Job) {
        if (shuttingDown || exportJob !== job) return
        updateUiStateAndRecycleReplaced { current ->
            if (current.isBusy) current.copy(isBusy = false) else current
        }
    }

    private fun isCurrentExportRecoveryRequest(identity: ExportIdentity): Boolean {
        if (shuttingDown || editorLeaveLocksActions() || exportToken != identity.token) return false
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
        val settlement = settleEditorAction()
        if (settlement !is EditorActionSettlement.Ready) return false
        val prerequisite = settlement.historyPrerequisite
        if (prerequisite != null) {
            val state = _uiState.value
            val activeLayer =
                state.activeSelectionLayerId?.let { activeId ->
                    state.selectionLayers.firstOrNull { it.id == activeId }
                } ?: return false
            val pending =
                PendingSelectionParamStart(
                    prerequisite = prerequisite,
                    documentGeneration = historyCoordinator.currentGeneration(),
                    sourcePath = state.sourcePath,
                    baseContentToken = state.baseContentToken,
                    startingDocumentRevision = state.revision,
                    activeLayerId = activeLayer.id,
                    latestIntendedLocalParams = activeLayer.localParams,
                )
            pendingSelectionParamStart = pending
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    when (prerequisite.await()) {
                        HistoryPrerequisiteOutcome.Completed -> Unit
                        HistoryPrerequisiteOutcome.Cancelled -> return@launch
                        is HistoryPrerequisiteOutcome.Failed -> {
                            reportHistorySettlementFailure()
                            return@launch
                        }
                    }
                    val current = _uiState.value
                    if (pendingSelectionParamStart !== pending ||
                        pending.closed ||
                        shuttingDown ||
                        historyCoordinator.currentGeneration() != pending.documentGeneration ||
                        current.sourcePath != pending.sourcePath ||
                        current.baseContentToken != pending.baseContentToken ||
                        current.revision != pending.startingDocumentRevision ||
                        current.activeSelectionLayerId != pending.activeLayerId
                    ) return@launch
                    val queuedParams = pending.latestIntendedLocalParams
                    val shouldFinish = pending.terminalFinish
                    pending.closed = true
                    pendingSelectionParamStart = null
                    if (beginSelectionParamGesture()) {
                        updateActiveSelectionParamsLive { queuedParams }
                        if (shouldFinish) finishSelectionParamGesture()
                    }
                } finally {
                    pending.closed = true
                    if (pendingSelectionParamStart === pending) pendingSelectionParamStart = null
                }
            }
            pending.job = job
            job.start()
            return true
        }
        if (!canEnterEditorActionPure(allowMaskSupersession = true)) return false
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

    internal fun pendingSelectionParamStart(): PendingSelectionParamStart? = pendingSelectionParamStart

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
        val pending = pendingSelectionParamStart
        if (pending != null && selectionParamTransaction == null) {
            pending.terminalFinish = true
            return
        }
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
        val settlement = settleEditorAction()
        if (settlement !is EditorActionSettlement.Ready) return false
        val state = _uiState.value
        val layerId = state.activeSelectionLayerId ?: return false
        val layer = state.selectionLayers.firstOrNull { it.id == layerId } ?: return false
        if (state.params != lastSuccessfullyRenderedParams || activeParamRenderRevision != null) {
            return false
        }
        val prerequisite = settlement.historyPrerequisite
        if (prerequisite != null) {
            val pending =
                PendingBrushStart(
                    prerequisite = prerequisite,
                    documentGeneration = historyCoordinator.currentGeneration(),
                    sourcePath = state.sourcePath,
                    baseContentToken = state.baseContentToken,
                    revision = state.revision,
                    activeLayerId = layerId,
                )
            pendingBrushStart = pending
            pendingBrushPoints.clear()
            brushTransactionState = BrushTransactionState.Preparing
            val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    when (prerequisite.await()) {
                        HistoryPrerequisiteOutcome.Completed -> Unit
                        HistoryPrerequisiteOutcome.Cancelled -> return@launch
                        is HistoryPrerequisiteOutcome.Failed -> {
                            reportHistorySettlementFailure()
                            return@launch
                        }
                    }
                    val current = _uiState.value
                    val currentLayer = current.selectionLayers.firstOrNull { it.id == layerId }
                    if (pendingBrushStart !== pending ||
                        pending.closed ||
                        shuttingDown ||
                        historyCoordinator.currentGeneration() != pending.documentGeneration ||
                        current.sourcePath != pending.sourcePath ||
                        current.baseContentToken != pending.baseContentToken ||
                        current.revision != pending.revision ||
                        current.activeSelectionLayerId != layerId ||
                        currentLayer == null
                    ) return@launch
                    val queued = pendingBrushPoints.toList()
                    pendingBrushPoints.clear()
                    val terminal = pending.terminalIntent
                    pending.closed = true
                    pendingBrushStart = null
                    brushTransactionState = BrushTransactionState.Idle
                    when (terminal) {
                        PendingBrushTerminal.Cancel -> return@launch
                        PendingBrushTerminal.Finish -> {
                            if (beginBrushStroke()) {
                                queued.forEach { queueBrushPoint(it.first, it.second) }
                                finishBrushStroke()
                            }
                            return@launch
                        }
                        else -> {
                            if (beginBrushStroke()) queued.forEach { queueBrushPoint(it.first, it.second) }
                        }
                    }
                } finally {
                    pending.closed = true
                    if (pendingBrushStart === pending) {
                        pendingBrushStart = null
                        pendingBrushPoints.clear()
                        brushTransactionState = BrushTransactionState.Idle
                    }
                }
            }
            pending.job = job
            job.start()
            return true
        }
        if (!canEnterEditorActionPure(allowMaskSupersession = true)) return false
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
        val preparationSeam = BrushPreparationTestSeam.capture()
        brushIdentity = transactionIdentity
        brushWorkingMask = null
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
                    preparationSeam?.awaitBeforeAdoption()
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
    private fun historyCaptureAvailable(requiredBytes: Long): Boolean {
        if (historyActivityBusy()) {
            reportHistoryBusyAdmission()
            return false
        }
        return when (val availability = historyCoordinator.captureAvailability(requiredBytes)) {
            HistoryCaptureAvailability.Ready -> true
            HistoryCaptureAvailability.HistoryBusy -> {
                reportHistoryBusyAdmission()
                false
            }
            is HistoryCaptureAvailability.MemoryRejected -> {
                updateUiStateAndRecycleReplaced {
                    it.copy(message = HISTORY_MEMORY_MESSAGE)
                }
                false
            }
        }
    }

    private suspend fun awaitHistoryCaptureAvailability(requiredBytes: Long): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (historyActivityBusy()) {
                yield()
                continue
            }
            when (historyCoordinator.captureAvailability(requiredBytes)) {
                HistoryCaptureAvailability.Ready -> return true
                HistoryCaptureAvailability.HistoryBusy -> yield()
                is HistoryCaptureAvailability.MemoryRejected ->
                    return historyCaptureAvailable(requiredBytes)
            }
        }
    }

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
        if (!awaitHistoryCaptureAvailability(required)) {
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
        val pending =
            PendingHistorySnapshot(
                CompletableDeferred(),
                onCompletedForTest = HistorySnapshotTestSeam.capture()?.onCompleted,
            )
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

    internal fun brushSnapshotJobActiveForTest(): Boolean = brushSnapshotJob?.isActive == true

    internal fun hasPendingBrushStartForTest(): Boolean = pendingBrushStart != null

    internal fun pendingBrushPointCountForTest(): Int = pendingBrushPoints.size

    internal fun pendingBrushFinishRequestedForTest(): Boolean =
        pendingBrushStart?.terminalIntent == PendingBrushTerminal.Finish

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
        ) {
            // Allow deferred finish through pending prerequisite
            if (pendingBrushStart != null && brushIdentity == null) {
                pendingBrushStart?.terminalIntent = PendingBrushTerminal.Finish
                return
            }
            return
        }
        if (pendingBrushStart != null && brushIdentity == null) {
            pendingBrushStart?.terminalIntent = PendingBrushTerminal.Finish
            return
        }
        val strokeId = brushIdentity?.strokeId ?: return
        brushTransactionState = BrushTransactionState.Finishing
        settleBrushStroke(strokeId)
    }

    internal fun cancelBrushStroke() {
        if (brushTransactionState == BrushTransactionState.Idle ||
            brushTransactionState == BrushTransactionState.Cancelling
        ) return
        if (pendingBrushStart != null && brushIdentity == null) {
            val pending = pendingBrushStart ?: return
            pending.terminalIntent = PendingBrushTerminal.Cancel
            pending.closed = true
            pending.job?.cancel()
            pendingBrushStart = null
            pendingBrushPoints.clear()
            brushTransactionState = BrushTransactionState.Idle
            return
        }
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

    private fun publishRestoreBusy(
        restoreToken: Long,
        restoreStartRevision: Int,
        message: String,
        retryOwner: MemoryRecoveryOwner? = null,
    ): Boolean {
        while (true) {
            if (shuttingDown || restoreToken != restoreDraftToken) return false
            val current = _uiState.value
            if (current.revision != restoreStartRevision) return false
            if (
                commitUiState(
                    current,
                    current.copy(isBusy = true, message = message),
                )
            ) {
                val existing = restoreBusyPublication
                restoreBusyPublication = RestoreBusyPublication(
                    token = restoreToken,
                    revision = restoreStartRevision,
                    message = message,
                    retryOwner = retryOwner ?: existing?.takeIf {
                        it.revision == restoreStartRevision
                    }?.retryOwner,
                )
                return true
            }
        }
    }

    private fun settleRestoreBusyPublication(
        token: Long? = null,
        revision: Int? = null,
    ) {
        val publication = restoreBusyPublication ?: return
        if (token != null && publication.token != token) return
        if (revision != null && publication.revision != revision) return
        updateUiStateAndRecycleReplaced { current ->
            if (current.revision != publication.revision) return@updateUiStateAndRecycleReplaced current
            val ownsMessage = current.message == publication.message
            val ownsBusy = current.isBusy
            // Independent busy ownership: always clear busy when the publication
            // hasn't been superseded (token/revision match) and busy is true.
            if (ownsBusy || ownsMessage) {
                current.copy(
                    isBusy = if (ownsBusy) false else current.isBusy,
                    message = if (ownsMessage) null else current.message,
                )
            } else current
        }
        if (restoreBusyPublication == publication) restoreBusyPublication = null
    }

    private fun invalidateExport() {
        exportToken += 1L
        exportJob?.cancel()
    }

    private fun invalidateDraftOperations() {
        settleRestoreBusyPublication()
        invalidateRestoreDraftRecoveryForDocumentReplacement()
        draftOperationEpoch += 1L
        restoreDraftToken += 1L
        restoreDraftJob?.cancel()
        draftSaveJob?.cancel()
    }

    private fun invalidateOpenImage() {
        openImageToken += 1L
        openImageJob?.cancel()
        clearDocumentBusyIfNoExport()
    }

    private fun clearDocumentBusyIfNoExport() {
        if (shuttingDown || exportJob?.isActive == true) return
        updateUiStateAndRecycleReplaced { state ->
            if (state.isBusy) state.copy(isBusy = false) else state
        }
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
            if (asyncBusyOwner != null || shuttingDown || editorLeaveLocksActions()) return null
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
            owner.phase = AsyncBusyPhase.Closed
            asyncBusyOwner = null
            if (!shuttingDown && exportJob?.isActive != true) {
                updateUiState { it.copy(isBusy = false) }
            }
        }
    }

    internal fun activeAsyncBusyOwnerForTest(): String? =
        asyncBusyOwner?.operationType

    internal fun activeAsyncBusyJobForTest(): Job? = asyncBusyOwner?.job

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
        val operationSeam = AsyncBusyTestSeam.capture()
        // Publish the action slot before the worker starts.  Otherwise a
        // second edit can capture state while this exact before-snapshot is
        // still being prepared.
        val job = viewModelScope.launch(Dispatchers.Default, CoroutineStart.LAZY) {
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
                operationSeam?.awaitBeforeAdoption()
                withContext(Dispatchers.Main) {
                    val current = _uiState.value
                    val currentLayer = current.selectionLayers.firstOrNull { it.id == layerId }
                    val currentIdentity =
                        asyncBusyOwner === busyOwner &&
                            busyOwner.phase == AsyncBusyPhase.Active &&
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
        job.invokeOnCompletion {
            if (job.isCancelled) {
                leased.close()
            }
        }
        busyOwner.job = job
        job.start()
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
        val operationSeam = AsyncBusyTestSeam.capture()
        val job = viewModelScope.launch(Dispatchers.Default, CoroutineStart.LAZY) {
            var snapshot: EditorHistorySnapshot? = null
            try {
                snapshot = pending.await()
                operationSeam?.awaitBeforeAdoption()
                withContext(Dispatchers.Main) {
                    val current = _uiState.value
                    val currentIdentity =
                        asyncBusyOwner === busyOwner &&
                            busyOwner.phase == AsyncBusyPhase.Active &&
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
        job.invokeOnCompletion {
            if (job.isCancelled) {
                pending.close()
                leased.close()
            }
        }
        busyOwner.job = job
        job.start()
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
        if (!historyCaptureAvailable(required)) {
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

    internal fun commitUndoSnapshot(
        snapshot: EditorHistorySnapshot,
        clearRedo: Boolean,
        onRegistered: ((HistoryActivityRegistry.Registration) -> Unit)? = null,
    ): Boolean {
        if (shuttingDown) {
            snapshot.recycleBitmaps()
            return false
        }
        val reserve = _uiState.value.historyBitmapBytes()
        val started = java.util.concurrent.atomic.AtomicBoolean(false)
        val historyPublishSeam = HistoryAdmissionTestSeam.capture()
        val feedbackGeneration = snapshot.coordinatorGeneration ?: historyCoordinator.currentGeneration()
        var registration: HistoryActivityRegistry.Registration? = null
        snapshot.claimCoordinatorOwnership()
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                started.set(true)
                try {
                    historyPublishSeam?.awaitBeforeCoordinatorAdmission()
                    val result = historyCoordinator.admitAdoptedSnapshot(snapshot, clearRedo, reserve)
                    if (
                        !shuttingDown &&
                        historyCoordinator.currentGeneration() == feedbackGeneration
                    ) {
                        when (historyAdmissionUserFeedback(result)) {
                            HistoryAdmissionFeedback.None -> Unit
                            HistoryAdmissionFeedback.MemoryWarning ->
                                updateUiStateAndRecycleReplaced {
                                    it.copy(message = HISTORY_MEMORY_MESSAGE)
                                }
                            HistoryAdmissionFeedback.StorageFailure ->
                                updateUiStateAndRecycleReplaced {
                                    it.copy(message = HISTORY_STORAGE_FAILURE_MESSAGE)
                                }
                            HistoryAdmissionFeedback.StorageBudgetWarning ->
                                updateUiStateAndRecycleReplaced {
                                    it.copy(message = HISTORY_STORAGE_BUDGET_MESSAGE)
                                }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    if (!snapshot.resourcesReleased) snapshot.recycleBitmaps()
                    throw cancelled
                } catch (expected: HistoryAdmissionExpectedFailure) {
                    if (!snapshot.resourcesReleased) snapshot.recycleBitmaps()
                    registration?.fail(expected.expectedCause)
                    reportHistorySettlementFailure()
                } catch (failure: Throwable) {
                    if (!snapshot.resourcesReleased) snapshot.recycleBitmaps()
                    throw failure
                }
            }
        job.invokeOnCompletion {
            if (!started.get() && !snapshot.resourcesReleased) snapshot.recycleBitmaps()
        }
        val acceptedRegistration = historyActivity.registerHandle(job)
        registration = acceptedRegistration
        if (acceptedRegistration == null) {
            job.cancel()
            if (!snapshot.resourcesReleased) snapshot.recycleBitmaps()
            return false
        }
        onRegistered?.invoke(acceptedRegistration)
        job.start()
        return true
    }

    internal fun recycleHistorySnapshot(snapshot: EditorHistorySnapshot) {
        snapshot.recycleBitmaps()
    }

    fun persistDraftSnapshot() {
        forceDraftSaveAsync()
    }

    internal enum class EditorLeaveDisposition {
        EmptyAndIdle,
        PendingDocumentOperation,
        EditableDocument,
    }

    internal fun editorLeaveDisposition(): EditorLeaveDisposition {
        val state = _uiState.value
        val editable =
            state.previewBitmap != null ||
                state.originalPreviewBitmap != null ||
                state.canUndo ||
                state.canRedo ||
                state.selectionLayers.isNotEmpty()
        val pending =
            state.isBusy ||
                openImageJob?.isActive == true ||
                restoreDraftJob?.isActive == true ||
                managedEdits.job?.isActive == true ||
                asyncBusyOwner?.job?.isActive == true ||
                cropJob?.isActive == true ||
                selectionLivePreviewJob?.isActive == true ||
                transactionFinishJob?.isActive == true ||
                brushTransactionState != BrushTransactionState.Idle ||
                selectionParamTransaction != null ||
                pendingSelectionParamStart != null ||
                activeHistoryNavigation != null
        return when {
            editable -> EditorLeaveDisposition.EditableDocument
            pending -> EditorLeaveDisposition.PendingDocumentOperation
            else -> EditorLeaveDisposition.EmptyAndIdle
        }
    }

    internal fun cancelPendingDocumentWorkForLeave() {
        invalidateOpenImage()
        restoreDraftToken += 1L
        restoreDraftJob?.cancel()
        invalidateManagedEdits()
        renderJob?.cancel()
        invalidateCropOperation()
        cropJob?.cancel()
        invalidateSelectionPreview()
        externalActionContinuationToken += 1L
        externalActionContinuation?.cancel()
        externalActionContinuation = null
        invalidateMemoryRecoveryForDocumentReplacement()
        updateUiStateAndRecycleReplaced { it.copy(memoryRecoveryRequest = null) }
        clearDocumentBusyIfNoExport()
    }

    private fun claimEditorLeaveOwner(): Pair<EditorLeaveOwner, Boolean>? {
        var startTransaction = false
        val owner = synchronized(this) {
            if (shuttingDown) return null
            val current = editorLeaveOwner
            if (current != null &&
                current.phase != EditorLeavePhase.Closed &&
                current.phase != EditorLeavePhase.Idle
            ) return current to false
            val claimed = EditorLeaveOwner(++editorLeaveCounter)
            editorLeaveOwner = claimed
            _editorLeaveState.value = EditorLeaveState(claimed.token, EditorLeavePhase.Quiescing)
            EditorLeaveTestSeam.capture()?.record(EditorLeaveTestStage.OwnershipClaimed)
            startTransaction = true
            claimed
        }
        return owner to startTransaction
    }

    internal fun requestSaveAndLeave(): Long? {
        val (owner, startTransaction) = claimEditorLeaveOwner() ?: return null
        if (startTransaction) {
            startEditorLeave(owner)
        }
        return owner.token
    }

    private fun startEditorLeave(owner: EditorLeaveOwner) {
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) { runEditorLeave(owner) }
        val start = synchronized(this) {
            if (editorLeaveOwner === owner) {
                owner.job = job
                true
            } else false
        }
        if (start) job.start() else job.cancel()
    }

    internal fun acknowledgeEditorLeave(token: Long? = null) {
        val owner = synchronized(this) {
            editorLeaveOwner?.takeIf {
                token == null || it.token == token
            }
        } ?: return
        if (owner.phase != EditorLeavePhase.Failed && owner.phase != EditorLeavePhase.Completed) return
        owner.phase = EditorLeavePhase.Closed
        if (editorLeaveOwner === owner) {
            _editorLeaveState.value = EditorLeaveState(phase = EditorLeavePhase.Idle)
        }
    }

    suspend fun persistDraftSnapshotNow(): Boolean {
        val (owner, startTransaction) = claimEditorLeaveOwner() ?: return false
        if (startTransaction) {
            startEditorLeave(owner)
        }
        return owner.result.await()
    }

    private fun ownsEditorLeave(owner: EditorLeaveOwner): Boolean =
        editorLeaveOwner === owner && owner.phase != EditorLeavePhase.Closed && !shuttingDown

    private suspend fun cancelAndAwait(job: Job?) {
        if (job == null || job === currentCoroutineContext()[Job]) return
        job.cancel()
        job.join()
    }

    private suspend fun awaitOwnedJob(job: Job?) {
        if (job == null || job === currentCoroutineContext()[Job]) return
        job.join()
    }

    private suspend fun quiesceEditorForLeave(owner: EditorLeaveOwner) {
        externalActionContinuationToken += 1L
        val continuation = externalActionContinuation
        externalActionContinuation = null
        externalActionContinuationPhase = ExternalActionContinuationPhase.Closed
        cancelAndAwait(continuation)

        val recoveryJob = userMemoryRecoveryOwner?.job
        invalidateMemoryRecoveryForDocumentReplacement()
        updateUiStateAndRecycleReplaced { it.copy(memoryRecoveryRequest = null) }
        cancelAndAwait(recoveryJob)

        val openJob = openImageJob
        invalidateOpenImage()
        cancelAndAwait(openJob)
        val restoreJob = restoreDraftJob
        restoreDraftToken += 1L
        restoreDraftJob?.cancel()
        cancelAndAwait(restoreJob)

        val staleDraftJob = draftSaveJob
        draftSaveJob?.cancel()
        restoreDraftToken += 1L
        cancelAndAwait(staleDraftJob)
        EditorLeaveTestSeam.capture()?.record(EditorLeaveTestStage.MutationsInvalidated)

        val parameterSettlement = settleParameterTransaction(SettlementReason.SaveAndLeave)
        (parameterSettlement as? SettlementResult.Committed)?.historyPrerequisite?.let {
            runCatching { it.await() }
        }

        val pendingBrushJob = pendingBrushStart?.job
        when {
            pendingBrushStart != null && brushIdentity == null -> {
                cancelBrushStroke()
                cancelAndAwait(pendingBrushJob)
            }
            brushIdentity != null && brushWorkingMask == null -> {
                val preparationJob = brushSnapshotJob
                cancelBrushStroke()
                cancelAndAwait(preparationJob)
            }
            brushIdentity != null && brushWorkingMask != null -> {
                if (brushTransactionState != BrushTransactionState.Finishing) {
                    finishBrushStroke()
                }
                awaitOwnedJob(brushSettlementJob)
            }
        }

        val pendingSelection = pendingSelectionParamStart
        pendingSelection?.let { pending ->
            pending.closed = true
            val pendingJob = pending.job
            pendingJob?.cancel()
            if (pendingSelectionParamStart === pending) pendingSelectionParamStart = null
            cancelAndAwait(pendingJob)
        }
        selectionParamTransaction?.let { transaction ->
            finishSelectionParamGesture()
            transactionFinishJob?.join()
            if (selectionParamTransaction === transaction) settleSelectionParamTransaction(transaction)
        }
        selectionLivePreviewJob?.cancel()
        cancelAndAwait(selectionLivePreviewJob)
        cancelAndAwait(transactionFinishJob)

        val managedJob = managedEdits.job
        invalidateManagedEdits()
        renderJob?.cancel()
        cancelAndAwait(managedJob)
        cancelAndAwait(renderJob)

        val crop = cropJob
        invalidateCropOperation()
        cropJob?.cancel()
        cancelAndAwait(crop)

        val asyncJob = asyncBusyOwner?.job
        asyncBusyOwner?.phase = AsyncBusyPhase.Cancelling
        asyncBusyOwner = null
        asyncJob?.cancel()
        cancelAndAwait(asyncJob)
        if (exportJob?.isActive != true) {
            updateUiStateAndRecycleReplaced { if (it.isBusy) it.copy(isBusy = false) else it }
        }

        invalidateComparison()

        val activeHistoryJob = historyActivity.job
        if (activeHistoryNavigation != null) {
            activeHistoryNavigation = null
            historyNavigationCounter += 1L
            historyActivity.cancel()
            cancelAndAwait(activeHistoryJob)
        } else {
            activeHistoryJob?.join()
        }
        historyActivity.clearCompleted()
        EditorLeaveTestSeam.capture()?.record(EditorLeaveTestStage.InteractiveOwnersSettled)
        check(ownsEditorLeave(owner))
    }

    private suspend fun runEditorLeave(owner: EditorLeaveOwner) {
        try {
            quiesceEditorForLeave(owner)
            if (!ownsEditorLeave(owner)) return
            EditorLeaveTestSeam.capture()?.record(EditorLeaveTestStage.BeforeFinalDraftCapture)
            owner.phase = EditorLeavePhase.Saving
            _editorLeaveState.value = EditorLeaveState(owner.token, EditorLeavePhase.Saving)
            val (epoch, previous) = beginDraftSaveOperation()
            val testSeam = DraftSaveTestSeam.capture(this)
            previous?.join()
            val owningJob = currentCoroutineContext()[Job]
            if (owningJob != null) draftSaveJob = owningJob
            val saved =
                try {
                    persistDraftSnapshotInternal(epoch, SettlementReason.ManualDraftSave, testSeam)
                } finally {
                    if (draftSaveJob === owningJob) draftSaveJob = null
                }
            if (!ownsEditorLeave(owner)) return
            if (saved) {
                EditorLeaveTestSeam.capture()?.record(EditorLeaveTestStage.DraftCommitted)
                if (exportJob?.isActive != true) {
                    updateUiStateAndRecycleReplaced { if (it.isBusy) it.copy(isBusy = false) else it }
                }
                owner.phase = EditorLeavePhase.Completed
                _editorLeaveState.value =
                    EditorLeaveState(owner.token, EditorLeavePhase.Completed, _uiState.value.draftGenerationId)
            } else {
                owner.phase = EditorLeavePhase.Failed
                _editorLeaveState.value =
                    EditorLeaveState(
                        owner.token,
                        EditorLeavePhase.Failed,
                        message = "\uC784\uC2DC \uC800\uC7A5\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4. \uD3B8\uC9D1 \uD654\uBA74\uC744 \uC720\uC9C0\uD569\uB2C8\uB2E4.",
                    )
            }
            owner.result.complete(saved)
        } catch (_: CancellationException) {
            owner.phase = EditorLeavePhase.Closed
            if (editorLeaveOwner === owner) {
                _editorLeaveState.value = EditorLeaveState(phase = EditorLeavePhase.Closed)
            }
            owner.result.complete(false)
        } catch (failure: Throwable) {
            if (!ownsEditorLeave(owner)) {
                owner.result.complete(false)
                return
            }
            owner.phase = EditorLeavePhase.Failed
            _editorLeaveState.value =
                EditorLeaveState(
                    owner.token,
                    EditorLeavePhase.Failed,
                    message = "\uD3B8\uC9D1 \uC791\uC5C5\uC744 \uC548\uC804\uD558\uAC8C \uB9C8\uBB34\uB9AC\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4. \uD3B8\uC9D1 \uD654\uBA74\uC744 \uC720\uC9C0\uD569\uB2C8\uB2E4.",
                )
            owner.result.complete(false)
            lastEditorLeaveFailureForTest = failure
            Log.w("KeplerStudio.Leave", "editor leave failed", failure)
        }
    }

    internal fun scheduleDraftAutosave(delayMs: Long = 2000L) {
        if (shuttingDown || editorLeaveLocksActions()) return
        val (epoch, _) = beginDraftSaveOperation()
        val testSeam = DraftSaveTestSeam.capture(this)
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
        if (shuttingDown || editorLeaveLocksActions()) return
        val (epoch, _) = beginDraftSaveOperation()
        val testSeam = DraftSaveTestSeam.capture(this)
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
            } ?: run { lastDraftSaveFailureReasonForTest = "epoch-or-pointer-mismatch"; return false }
        testSeam?.beforeStorageReached?.complete(Unit)
        testSeam?.parkIfRequested(DraftSaveStage.BeforeStorageTransaction)
        settleParameterTransaction(reason)
        val draftSnapshot = acquireEditorSnapshot("draftSave")
            ?: run { lastDraftSaveFailureReasonForTest = "snapshot-acquire-failed"; return false }
        val draftState = draftSnapshot.state
        ParameterLifecycleTestHook.notifyDraftCaptureBegan(draftEpoch)
        try {
            if (testSeam != null && testSeam.parkAt == null) testSeam.awaitRelease()
        } catch (failure: Throwable) {
            draftSnapshot.close()
            throw failure
        }
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
                    // In-flight save roots: the captured previous visible Draft
                    // source stays claimed while this save can still consume
                    // or roll back over it, and the freshly created
                    // compatibility source stays claimed until settlement
                    // finishes, fails, is cancelled, or is superseded.
                    val saveLegacyOwner = LegacyDraftSourceOwnership.OwnerKey.create()
                    try {
                        legacyCompatibilitySource(context, payload.previousVisibleDraftPath)?.let {
                            LegacyDraftSourceOwnership.acquire(
                                saveLegacyOwner,
                                LegacyDraftSourceOwnership.RootKind.OPERATION,
                                it.absolutePath,
                            )
                        }
                        committed =
                            saveDraftSnapshot(context, payload, testSeam) {
                                owningJob?.isActive != false &&
                                    !shuttingDown &&
                                    isDraftPayloadCurrent(payload)
                            }
                        committed?.let { saved ->
                            legacyCompatibilitySource(
                                context,
                                saved.compatibilitySourceFile?.absolutePath,
                            )?.let {
                                LegacyDraftSourceOwnership.acquire(
                                    saveLegacyOwner,
                                    LegacyDraftSourceOwnership.RootKind.OPERATION,
                                    it.absolutePath,
                                )
                            }
                            settled = settleCommittedDraft(context, saved, payload, owningJob, testSeam)
                        }
                    } finally {
                        LegacyDraftSourceOwnership.releaseOwner(saveLegacyOwner)
                    }
                }
            }
        } catch (ce: CancellationException) {
            committed?.let { saved ->
                withContext(NonCancellable) {
                    try {
                        DraftStorageCoordinator.withWriteLock {
                            val actualPointer = currentDraftGenerationId(context)
                            if (actualPointer == saved.generationId) {
                                persistLegacyDraftCompatibility(context, payload, saved)
                            }
                            DraftStorageCoordinator.deleteAllExceptUnsafe(
                                context,
                                saved.generationDirectory,
                                saved.expectedPointerGenerationId,
                            )
                        }
                    } catch (t: Throwable) {
                        logDraftSaveFailure(t)
                    }
                }
            }
            throw CancellationException("Draft save cancelled during publication settlement")
        } catch (t: Throwable) {
            logDraftSaveFailure(t)
        } finally {
            payload.recycleOwnedBitmaps()
            draftTracker?.end()
        }
        if (committed == null) {
            lastDraftSaveFailureReasonForTest = "saveDraftSnapshot-returned-null"
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
        if (!settled) lastDraftSaveFailureReasonForTest = "settleCommittedDraft-rolled-back"
        return settled
    }

    private suspend fun settleCommittedDraft(
        context: Context,
        saved: DraftSaveResult,
        payload: DraftSavePayload,
        owningJob: Job?,
        testSeam: DraftSaveTestSeam?,
    ): Boolean {
        val current =
            owningJob?.isActive != false &&
                draftSaveJob === owningJob &&
                isDraftResultCurrent(saved)
        if (!current) {
            DraftStorageCoordinator.rollbackCommittedDraft(context, saved)
            return false
        }
        val previousBaseline = draftPointerBaseline
        draftPointerBaseline = saved.generationId
        var adopted = false
        while (!adopted) {
            val expected = _uiState.value
            if (!draftResultMatchesDocumentState(saved, expected)) {
                draftPointerBaseline = previousBaseline
                DraftStorageCoordinator.rollbackCommittedDraft(context, saved)
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
        // The adopted save is authoritative: move this ViewModel's visible
        // legacy Draft root atomically off the previous path. The new
        // generation payload path is not a legacy family member, so this only
        // releases the old claim.
        replaceLegacyDraftVisibleRoot(payload.previousVisibleDraftPath, saved.sourcePath)
        saved.expectedPointerGenerationId?.let { ThumbnailBitmapCache.invalidate("draft:$it") }
        testSeam?.parkIfRequested(DraftSaveStage.BeforePostCommitCleanup)
        withContext(Dispatchers.IO) {
            DraftStorageCoordinator.withWriteLock {
                val actualPointer = DraftStorageCoordinator.readCurrentPointerUnsafe(context)
                runCatching {
                    // Only write legacy compatibility when the saved generation is still authoritative
                    val currentPointer = currentDraftGenerationId(context)
                    if (currentPointer == saved.generationId) {
                        persistLegacyDraftCompatibility(context, payload, saved)
                    }
                }.onFailure(::logDraftSaveFailure)
                runCatching {
                    // Always preserve actual authoritative current pointer + saved directory
                    DraftStorageCoordinator.deleteAllExceptUnsafe(
                        context,
                        saved.generationDirectory,
                        saved.expectedPointerGenerationId,
                    )
                }.onFailure(::logDraftSaveFailure)
            }
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
        val startupHistoryStore =
            ExportTestSeam.capture()?.historyStore ?: savedExportHistoryStore
        val startupJob = viewModelScope.launch {
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
                // Draft restore is a replaceable child operation. OpenImage
                // and clearDraft may cancel it, but they must not cancel the
                // coordinator's remaining startup phases.
                supervisorScope {
                    val restoreChild =
                        launch(start = CoroutineStart.LAZY) {
                            restoreDraftIfAvailable(context, startupRestoreToken, startupRevision)
                        }
                    restoreDraftJob = restoreChild
                    restoreChild.invokeOnCompletion {
                        if (restoreDraftJob === restoreChild) restoreDraftJob = null
                    }
                    restoreChild.start()
                    restoreChild.join()
                }
                lastStartupStageForTest = StartupInitializationStage.HISTORY_LOAD_STARTED
                StartupInitializationTestSeam.capture()?.onStage?.invoke(
                    StartupInitializationStage.HISTORY_LOAD_STARTED,
                )
                try {
                    val historyResult =
                        withContext(Dispatchers.IO) {
                            startupHistoryStore.loadOrRebuildWithMutation()
                        }
                    invalidateRemovedHistoryThumbnails(context, historyResult, startupHistoryStore)
                    updateUiStateAndRecycleReplaced {
                        if (historyResult.revision != startupHistoryStore.revision) it
                        else
                            it.copy(
                                savedExports = historyResult.items,
                                exportHistoryRetention =
                                    historyResult.retention ?: it.exportHistoryRetention,
                            )
                    }
                    lastStartupStageForTest = StartupInitializationStage.HISTORY_LOAD_FINISHED
                    StartupInitializationTestSeam.capture()?.onStage?.invoke(
                        StartupInitializationStage.HISTORY_LOAD_FINISHED,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (failure: Throwable) {
                    lastSavedExportHistoryFailureForTest = failure
                    Log.e("KeplerStudio.History", "saved export history startup failed", failure)
                    updateUiStateAndRecycleReplaced {
                        it.copy(message = "내보낸 사진 기록을 불러오지 못했습니다.")
                    }
                }
                lastStartupStageForTest = StartupInitializationStage.RECONCILIATION_STARTED
                StartupInitializationTestSeam.capture()?.onStage?.invoke(
                    StartupInitializationStage.RECONCILIATION_STARTED,
                )
                runCatching {
                    withContext(Dispatchers.IO) {
                        draftSaveMutex.withLock {
                            reconcileStartupArtifacts(context, _uiState.value.sourcePath)
                        }
                    }
                }.onSuccess { outcome ->
                    if (outcome.failedCount > 0) {
                        Log.w(
                            FLARE_GUARD_AI_TAG,
                            "Startup storage reconcile: ${outcome.failedCount} deletion failures",
                        )
                    }
                }.onFailure { t ->
                    Log.w(FLARE_GUARD_AI_TAG, "Startup storage reconcile failed", t)
                }
                lastStartupStageForTest = StartupInitializationStage.RECONCILIATION_FINISHED
                StartupInitializationTestSeam.capture()?.onStage?.invoke(
                    StartupInitializationStage.RECONCILIATION_FINISHED,
                )
            } finally {
                lastStartupStageForTest = StartupInitializationStage.COORDINATOR_SETTLED
                runCatching {
                    StartupInitializationTestSeam.capture()?.onStage?.invoke(
                        StartupInitializationStage.COORDINATOR_SETTLED,
                    )
                }
                startupInitCompletion.complete(Unit)
            }
        }
        startupCoordinatorJob = startupJob
        startupJob.invokeOnCompletion {
            if (startupCoordinatorJob === startupJob) startupCoordinatorJob = null
        }
    }

    fun openImage(uri: Uri) {
        if (shuttingDown || editorLeaveLocksActions()) return
        // A startup Draft restore is a replaceable document operation.  A
        // user-selected image must be able to supersede it even while the
        // restore owns a decoded bitmap/session and has published isBusy.
        // Invalidate before admission so the action is not rejected merely
        // because the old restore is still preparing its off-state bundle.
        if (
            restoreDraftJob?.isActive == true ||
                userMemoryRecoveryOwner?.descriptor?.action == MemoryRetryAction.RestoreDraft
        ) {
            invalidateDraftOperations()
            clearDocumentBusyIfNoExport()
        }
        val settlement = settleParameterTransactionBeforeExternalEdit()
        if (continueAfterOwnParameterSettlement(settlement) { openImage(uri) }) return
        if (!settleForEditorAction()) return
        if (!canEnterEditorActionAfterSettlement()) return
        invalidateSelectionPreview()
        invalidateCropOperation()
        invalidateManagedEdits()
        invalidateDraftOperations()
        renderJob?.cancel()
        invalidateExport()
        invalidateSelectionPreview()
        cropJob?.cancel()
        closeParamUndoWindow()
        val openToken = ++openImageToken
        val invalidateRevision = _uiState.value.revision + 1
        val openingMessage = "\uC774\uBBF8\uC9C0\uB97C \uC5EC\uB294 \uC911\uC785\uB2C8\uB2E4"
        updateUiStateAndRecycleReplaced {
            it.copy(isBusy = true, revision = invalidateRevision, message = openingMessage)
        }
        val operationSeam = OpenImageTestSeam.capture()
        val launchedOpenJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val identity =
                OpenImageIdentity(
                    token = openToken,
                    invalidateRevision = invalidateRevision,
                    incomingUri = uri,
                    owningJob = checkNotNull(currentCoroutineContext()[Job]),
                )
            var preview: Bitmap? = null
            var createdSession = 0L
            var createdSessionRelease: ((Long) -> Unit)? = null
            var ownedSource: OwnedIncomingSource? = null
            var failureStage = OpenImageFailureStage.Source
            val opTracker = beginMemoryTracking("openImage", snapshotState = "decoding")
            fun cleanupOwnedResources(cause: Throwable?) {
                preview?.takeUnless(Bitmap::isRecycled)?.recycle()
                preview = null
                if (createdSession != 0L) {
                    releaseNativeSessionHandle(createdSession, createdSessionRelease)
                    createdSession = 0L
                    createdSessionRelease = null
                }
                ownedSource?.cleanup()?.let { cleanupFailure ->
                    lastOpenImageCleanupFailureForTest = cleanupFailure
                    cause?.addSuppressed(cleanupFailure)
                }
                ownedSource = null
            }
            try {
                val context = getApplication<Application>()
                withContext(Dispatchers.IO) {
                    ownedSource =
                        (operationSeam?.sourceTransactionFactory?.invoke(context, uri)
                            ?: IncomingSourceTransaction(context)).acquire(uri)
                    failureStage = OpenImageFailureStage.Decode
                    preview =
                        operationSeam?.decode?.invoke(ownedSource!!.file.absolutePath)
                            ?: decodeSampledMutableBitmapWithExif(
                                ownedSource!!.file.absolutePath,
                                maxSide = 2048,
                                opTracker,
                            )
                }
                if (!isCurrentOpenImage(identity)) {
                    cleanupOwnedResources(null)
                    return@launch
                }
                val decodedPreview = preview!!
                val openedSource = checkNotNull(ownedSource).file
                Log.i(
                    FLARE_GUARD_AI_TAG,
                    "Opened image with EXIF orientation: ${openedSource.name} preview=${decodedPreview.width}x${decodedPreview.height}",
                )
                failureStage = OpenImageFailureStage.NativeSession
                if (operationSeam?.nativeSessionFactory != null) {
                    createdSession = operationSeam.nativeSessionFactory.invoke(openedSource.absolutePath)
                    createdSessionRelease = operationSeam.nativeSessionReleaser
                } else {
                    val creation = nativeCreateSessionHandleOrTest(openedSource.absolutePath)
                    createdSession = creation.handle
                    createdSessionRelease = creation.releaseOverride
                }
                currentCoroutineContext().ensureActive()
                tracker.registerNativeSession(
                    handle = createdSession,
                    documentGeneration = historyCoordinator.currentGeneration(),
                    sourceIdentity = decodedPreview.hashCode().toString(),
                    state = "created",
                )
                if (!isCurrentOpenImage(identity)) {
                    cleanupOwnedResources(null)
                    return@launch
                }
                val previousSession = nativeSession
                val previousSessionRelease = nativeSessionRelease
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
                failureStage = OpenImageFailureStage.Adoption
                nativeSession = createdSession
                nativeSessionRelease = createdSessionRelease
                if (
                    !commitUiState(
                        previousState,
                        nextState,
                        replaceDocument = true,
                        adoptedNativeSession = createdSession,
                        registerIncomingDocument = false,
                    )
                ) {
                    nativeSession = previousSession
                    nativeSessionRelease = previousSessionRelease
                    error("open image adoption superseded")
                }
                checkNotNull(ownedSource).transferToDocument(previousState.sourcePath)
                ownedSource = null
                createdSession = 0L
                createdSessionRelease = null
                tracker.updateNativeSession(nativeSession, "active")
                lastSuccessfullyRenderedParams = EditParams()
                preview = null
                releaseNativeSessionHandle(previousSession, previousSessionRelease)
                // The incoming document replaced any previous legacy Draft
                // visibility/document claims; the file itself stays on disk
                // under its persistent pointer until reclamation revalidates.
                replaceLegacyDraftVisibleRoot(previousState.draftSourcePath, nextState.draftSourcePath)
                releaseLegacyDraftDocumentRoot(previousState.sourcePath)
                deleteOwnedWorkingSource(context, previousState.sourcePath)
                forceDraftSaveAsync()
            } catch (ce: CancellationException) {
                cleanupOwnedResources(ce)
                if (ownsOpenImageSettlement(identity)) {
                    updateUiStateAndRecycleReplaced { current ->
                        if (current.isBusy) current.copy(isBusy = false) else current
                    }
                }
                throw ce
            } catch (t: Throwable) {
                lastOpenImageFailureForTest = t
                cleanupOwnedResources(t)
                if (
                    isCurrentOpenImage(identity)
                ) {
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            isBusy = false,
                            message = openImageFailureMessage(failureStage),
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
                if (openImageJob === currentCoroutineContext()[Job]) openImageJob = null
            }
        }
        openImageJob = launchedOpenJob
        launchedOpenJob.start()
    }

    private fun isCurrentOpenImage(identity: OpenImageIdentity): Boolean =
        ownsOpenImageSettlement(identity) &&
            identity.owningJob.isActive

    private fun ownsOpenImageSettlement(identity: OpenImageIdentity): Boolean =
        !shuttingDown &&
            identity.token == openImageToken &&
            _uiState.value.revision == identity.invalidateRevision

    private fun openImageFailureMessage(stage: OpenImageFailureStage): String =
        when (stage) {
            OpenImageFailureStage.Source -> "선택한 이미지 파일을 읽지 못했습니다."
            OpenImageFailureStage.Decode -> "이미지를 디코딩하지 못했습니다."
            OpenImageFailureStage.NativeSession -> "이미지 처리 세션을 시작하지 못했습니다."
            OpenImageFailureStage.Adoption -> "이미지를 열지 못했습니다."
        }

    fun updateParams(transform: (EditParams) -> EditParams) {
        if (shuttingDown) return
        if (editorLeaveLocksActions()) return
        prepareForGlobalParamEdit()
        val parameterAdmission = editorActionAdmission(allowMaskSupersession = true)
        if (
            parameterAdmission != EditorActionAdmission.Ready &&
                !(parameterAdmission == EditorActionAdmission.HistoryBusy && parameterGesture != null)
        ) {
            if (parameterAdmission == EditorActionAdmission.HistoryBusy) reportHistoryBusyAdmission()
            return
        }
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
        val settlement = settleParameterTransactionBeforeExternalEdit()
        if (continueAfterOwnParameterSettlement(settlement) { applyAutoEnhance() }) return
        prepareForExternalEdit()
        if (!canEnterEditorActionAfterSettlement(allowMaskSupersession = true)) return
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
        val settlement = settleParameterTransactionBeforeExternalEdit()
        if (continueAfterOwnParameterSettlement(settlement) {
                applyEngineChange(noiseEngine, detailEngine, toneEngine, hazeEngine, message)
            }
        ) return
        val current = prepareForExternalEdit()
        if (!canEnterEditorActionAfterSettlement(allowMaskSupersession = true)) return
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
        val settlement = settleParameterTransactionBeforeExternalEdit()
        if (continueAfterOwnParameterSettlement(settlement) { resetAdjustments() }) return
        prepareForExternalEdit()
        if (!canEnterEditorActionAfterSettlement(allowMaskSupersession = true)) return
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
        val resetDecodeSeam = ResetAdjustmentsTestSeam.capture()
        val nextRevision = startRevision + 1
        renderJob?.cancel()
        invalidateExport()
        var decoded: Bitmap? = null
        updateUiStateAndRecycleReplaced {
            it.copy(isBusy = true, revision = nextRevision, message = "초기화하는 중입니다")
        }
        launchManagedRenderWithPreparedResources({ operationToken ->
            var undoSnapshotOwned: OwnedHistorySnapshot? = null
            val undoSnapshot = pendingHistory?.await()
            pendingHistory = null
            undoSnapshotOwned = undoSnapshot?.let(::OwnedHistorySnapshot)
            try {
                withContext(Dispatchers.IO) {
                    val result =
                        resetDecodeSeam?.decode?.invoke(sourcePath)
                            ?: decodeSampledMutableBitmapWithExif(
                                sourcePath,
                                maxSide = 2048,
                                resetTracker,
                            )
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
                    settleAdoptedEditHistory(undoSnapshotOwned?.take())
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
                        it.copy(isBusy = false, message = "초기화에 실패했습니다.")
                    }
                } else if (isManagedEditTokenCurrent(operationToken)) {
                    updateUiState { it.copy(isBusy = false) }
                }
            } finally {
                undoSnapshotOwned?.close()
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
        val settlement = settleParameterTransactionBeforeExternalEdit()
        if (continueAfterOwnParameterSettlement(settlement) {
                applyPresetLook(params, look, message)
            }
        ) return PresetApplyResult.Accepted
        val current = prepareForExternalEdit()
        if (!canEnterEditorActionAfterSettlement(allowMaskSupersession = true))
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
        val historyStore = ExportTestSeam.capture()?.historyStore ?: savedExportHistoryStore
        viewModelScope.launch {
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        historyStore.setRetention(retention)
                    }
                invalidateRemovedHistoryThumbnails(context, result, historyStore)
                updateUiStateAndRecycleReplaced {
                    if (result.revision != historyStore.revision) it
                    else
                        it.copy(
                            exportHistoryRetention = retention,
                            savedExports = result.items,
                            message =
                                if (it.isBusy) it.message
                                else "내보낸 사진 기록 자동 정리가 ${retention.label}으로 설정되었습니다",
                        )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (failure: Throwable) {
                lastSavedExportHistoryFailureForTest = failure
                Log.e("KeplerStudio.History", "saved export history retention failed", failure)
                updateUiStateAndRecycleReplaced {
                    it.copy(message = "내보낸 사진 기록 보관 정책을 저장하지 못했습니다.")
                }
            }
        }
    }

fun exportPreview() {
        if (shuttingDown || editorLeaveLocksActions()) return
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
        val exportRetryInput = MemoryRetryInput.Export(exportFormat, exportResolution)
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
                requestAllocationRecovery(
                    MemoryRetryAction.ExportPreview,
                    failure.requiredBytes,
                    retryInput = exportRetryInput,
                )
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
            if (
                liveBase == null ||
                    MemoryRecoveryTestSeam.capture()?.rejectExportPreparation == true ||
                    !BitmapMemoryBudget.canAllocate(dirtyPeakBytes)
            ) {
                ownedExportLayers.forEach { it.bitmap.takeUnless(Bitmap::isRecycled)?.recycle() }
                ownedExportLayers = emptyList()
                exportPrepareTracker?.end()
                exportPrepareTracker = null
                updateUiStateAndRecycleReplaced {
                    it.copy(message = "메모리가 부족하여 현재 해상도로 내보낼 수 없습니다. 다른 해상도 또는 이미지를 사용해 주세요.")
                }
                requestAllocationRecovery(
                    MemoryRetryAction.ExportPreview,
                    dirtyPeakBytes,
                    retryInput = exportRetryInput,
                )
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
                                retryInput = exportRetryInput,
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
                retryInput = exportRetryInput,
            )
            return
        }
        invalidateExport()
        val token = exportToken
        val exportSeam = ExportTestSeam.capture()
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
                    releaseExportBusyIfTokenOwned(token)
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
                                historyStore.commit(savedItem)
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
                        withContext(NonCancellable) {
                            when (outcome) {
                                is ExportPipelineResult.Published<*> -> {
                                    releaseExportBusyIfOwned(exportIdentity)
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
                                        if (isCurrentExportForUi(exportIdentity)) {
                                            current.copy(
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
                                    releaseExportBusyIfOwned(exportIdentity)
                                    if (isCurrentExportForUi(exportIdentity)) {
                                        updateUiStateAndRecycleReplaced { current ->
                                            current.copy(
                                                message =
                                                    "이미지는 갤러리에 저장되었지만 앱 내 내보낸 사진 기록을 저장하지 못했습니다.",
                                            )
                                        }
                                    }
                                }
                                is ExportPipelineResult.Failed -> {
                                    lastExportFailureForTest = outcome.failure
                                    val failure = outcome.failure
                                    releaseExportBusyIfOwned(exportIdentity)
                                    if (isCurrentExportForUi(exportIdentity)) {
                                        updateUiStateAndRecycleReplaced { current ->
                                            current.copy(
                                                message =
                                                    "이미지를 내보내지 못했습니다.",
                                            )
                                        }
                                    }
                                    if (
                                        failure is BitmapAllocationRejectedException &&
                                            isCurrentExportRecoveryRequest(exportIdentity)
                                    ) {
                                        requestAllocationRecovery(
                                            MemoryRetryAction.ExportPreview,
                                            failure.requiredBytes,
                                            retryInput = exportRetryInput,
                                        )
                                    }
                                }
                                is ExportPipelineResult.CleanupFailed -> {
                                    lastExportFailureForTest = outcome.cleanupFailure
                                    releaseExportBusyIfOwned(exportIdentity)
                                    if (isCurrentExportForUi(exportIdentity)) {
                                        updateUiStateAndRecycleReplaced { current ->
                                            current.copy(
                                                message =
                                                    "이미지를 내보내지 못했으며 임시 파일을 정리하지 못했습니다.",
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
                                    releaseExportBusyIfOwned(exportIdentity)
                                }
                            }
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        // A cancelled export that hasn't been superseded by a
                        // newer owner must clear its busy state.
                        releaseExportBusyIfJobOwned(exportCoroutine)
                        throw ce
                    } catch (t: Throwable) {
                        // A pre-publish throw from render preparation is the
                        // only non-pipeline exception path. Pipeline-render
                        // exceptions surface as ExportPipelineResult.Failed;
                        // reaching here implies a transfer/Settlement failure.
                        lastExportFailureForTest = t
                        releaseExportBusyIfJobOwned(exportCoroutine)
                        if (isCurrentExportForUi(exportIdentity)) {
                            updateUiStateAndRecycleReplaced { current ->
                                current.copy(
                                    message = "이미지를 내보내지 못했습니다.",
                                )
                            }
                        }
                        if (
                            t is BitmapAllocationRejectedException &&
                                isCurrentExportRecoveryRequest(exportIdentity)
                        ) {
                            requestAllocationRecovery(
                                MemoryRetryAction.ExportPreview,
                                t.requiredBytes,
                                retryInput = exportRetryInput,
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

    /**
     * Moves this ViewModel's visible Draft root from [previousPath] to
     * [nextPath] in one atomic registry transition. Non-legacy paths resolve
     * to null, so a generation-payload draftSourcePath simply releases the
     * previous legacy claim without registering anything.
     */
    private fun replaceLegacyDraftVisibleRoot(previousPath: String?, nextPath: String?) {
        val context = getApplication<Application>()
        val previous = legacyCompatibilitySource(context, previousPath)?.absolutePath
        val next = legacyCompatibilitySource(context, nextPath)?.absolutePath
        if (previous == null && next == null) return
        LegacyDraftSourceOwnership.replace(
            legacyDraftSourceOwner,
            LegacyDraftSourceOwnership.RootKind.VISIBLE_DRAFT,
            previous,
            next,
        )
    }

    /** Releases this ViewModel's document root when it was a legacy source. */
    private fun releaseLegacyDraftDocumentRoot(previousPath: String?) {
        val context = getApplication<Application>()
        val previous = legacyCompatibilitySource(context, previousPath)?.absolutePath ?: return
        LegacyDraftSourceOwnership.release(
            legacyDraftSourceOwner,
            LegacyDraftSourceOwnership.RootKind.DOCUMENT,
            previous,
        )
    }

    fun clearDraft() {
        if (!beginMaintenance("임시 저장을 삭제하는 중입니다")) return
        val context = getApplication<Application>()
        invalidateDraftOperations()
        val clearEpoch = draftOperationEpoch
        viewModelScope.launch {
            try {
                ClearDraftTestSeam.capture(this@EditorViewModel)?.beforeStorageClear?.invoke()
                draftSaveJob?.cancelAndJoin()
                val cleared =
                    withContext(Dispatchers.IO) {
                        draftSaveMutex.withLock draftLock@{
                            DraftStorageCoordinator.withWriteLock {
                            ClearDraftTestSeam.capture(this@EditorViewModel)?.atStorageTransaction?.invoke()
                            if (clearEpoch != draftOperationEpoch) return@withWriteLock false
                            val expectedPointer = DraftStorageCoordinator.readCurrentPointerUnsafe(context)
                            val expectedBaseline = draftPointerBaseline
                            if (expectedPointer != expectedBaseline) return@withWriteLock false
                            // Capture one stable visible state snapshot
                            val visibleBefore = _uiState.value
                            val liveSourcePath = visibleBefore.sourcePath
                            // Snapshot previous prefs for rollback
                            val prefs =
                                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                            val prevPrefs = snapshotDraftPreferences(prefs)
                            // Clear pointer and baseline (global storage mutation)
                            if (!DraftStorageCoordinator.clearPointerUnsafe(context)) return@withWriteLock false
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
                                        runCatching {
                                            DraftStorageCoordinator.publishGenerationUnsafe(context, expectedPointer)
                                        }.getOrDefault(false)
                                    } else {
                                        true
                                    }
                                if (!pointerRestored) {
                                    logDraftSaveFailure(
                                        IllegalStateException("clearDraft pointer rollback failed")
                                    )
                                }
                                val currentPointer = DraftStorageCoordinator.readCurrentPointerUnsafe(context)
                                draftPointerBaseline = currentPointer
                                return@withWriteLock false
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
                                        DraftStorageCoordinator.publishGenerationUnsafe(context, expectedPointer)
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
                                    val currentPointer = DraftStorageCoordinator.readCurrentPointerUnsafe(context)
                                    draftPointerBaseline = currentPointer
                                    return@withWriteLock false
                                }
                                state = _uiState.value
                            }
// Capture legacy Draft source from prefs for thumbnail invalidation
                            val legacyDraftSourcePath = prevPrefs[KEY_DRAFT_SOURCE] as? String
                            // Durable clear succeeded — cleanup legacy directory under global lock
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
                            // Also delete the generation directory under the same lock
                            expectedPointer?.let {
                                DraftStorageCoordinator.deleteGenerationUnsafe(context, it)
                            }
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
                             // Durable clear succeeded: this ViewModel no
                             // longer exposes a visible Draft root.
                             replaceLegacyDraftVisibleRoot(visibleBefore.draftSourcePath, null)
                             true
                            }
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
        if (editorLeaveLocksActions()) return
        if (historyActivityBusy()) {
            reportHistoryBusyAdmission()
            return
        }
        val settlement = settleEditorAction()
        if (continueAfterEditorActionSettlement(settlement) {
                navigateHistory(undo, expectedTargetId)
            }) return
        if (settlement !is EditorActionSettlement.Ready) return
        if (!canEnterEditorActionAfterSettlement()) return
        invalidateSelectionPreview()
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
        val historyNavigationSeam = HistoryNavigationTestSeam.capture()
        val navigationIdentity =
            HistoryNavigationIdentity(
                token = ++historyNavigationCounter,
                generation = currentDocumentGeneration(),
                sourcePath = _uiState.value.sourcePath,
                baseContentToken = _uiState.value.baseContentToken,
            )
        activeHistoryNavigation = navigationIdentity
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
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    historyNavigationSeam?.awaitBeforeCoordinatorNavigation()
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
                                if (historyNavigationSeam?.rejectAdoption == true) {
                                    false
                                } else {
                                    applyHistorySnapshot(
                                        snapshot,
                                        buildHistoryAppliedMessage(_uiState.value, snapshot, prefix),
                                    )
                                }
                            },
                        )
                    if (!ownsHistoryNavigation(navigationIdentity)) return@launch
                    when (result) {
                        is HistoryNavigationResult.Adopted -> {
                            scheduleDraftAutosave()
                            updateHistoryFlags()
                        }
                        is HistoryNavigationResult.Unavailable ->
                            updateUiStateAndRecycleReplaced {
                                it.copy(
                                    isBusy = false,
                                    message =
                                        if (undo) "되돌리기 편집 기록이 없습니다." else "다시 실행할 편집 기록이 없습니다.",
                                )
                            }
                        is HistoryNavigationResult.Busy,
                        is HistoryNavigationResult.NotCompleted -> {
                            val message = historyNavigationMessage(historyNavigationFeedback(result))
                            updateUiStateAndRecycleReplaced {
                                it.copy(isBusy = false, message = message ?: it.message)
                            }
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
                    }
                } catch (ce: CancellationException) {
                    if (ownsHistoryNavigation(navigationIdentity)) {
                        updateUiStateAndRecycleReplaced { it.copy(isBusy = false) }
                    }
                    throw ce
                } catch (_: Throwable) {
                    if (ownsHistoryNavigation(navigationIdentity))
                        updateUiStateAndRecycleReplaced {
                            it.copy(
                                isBusy = false,
                                message = "저장된 편집 기록을 불러오지 못했습니다. 현재 편집과 기록은 유지됩니다.",
                            )
                        }
                } finally {
                    if (activeHistoryNavigation === navigationIdentity) activeHistoryNavigation = null
                    updateHistoryFlags()
                    navTracker?.end()
                }
            }
        if (historyActivity.register(navJob)) {
            navJob.start()
        } else {
            navJob.cancel()
            navTracker?.end()
            if (activeHistoryNavigation === navigationIdentity) {
                activeHistoryNavigation = null
                updateUiState { it.copy(isBusy = false) }
            }
        }
    }

    internal fun clearRedoAfterAdoptedEdit(
        onRegistered: ((HistoryActivityRegistry.Registration) -> Unit)? = null,
    ) {
        if (shuttingDown) return
        val job =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    historyCoordinator.clearRedoAfterAdoptedEdit()
                } finally {
                    updateHistoryFlags()
                }
            }
        val registration = historyActivity.registerHandle(job)
        if (registration != null) {
            onRegistered?.invoke(registration)
            job.start()
        } else {
            job.cancel()
        }
    }

    internal fun settleAdoptedEditHistory(
        snapshot: EditorHistorySnapshot?,
        onRegistered: ((HistoryActivityRegistry.Registration) -> Unit)? = null,
    ): Boolean {
        automaticRetryAttempt = null
        strongRetryAttempt = null
        return if (snapshot != null) {
            commitUndoSnapshot(snapshot, clearRedo = true, onRegistered = onRegistered)
        }
        else {
            clearRedoAfterAdoptedEdit(onRegistered)
            updateUiStateAndRecycleReplaced {
                it.copy(message = "편집은 적용했지만 메모리가 부족하여 이번 되돌리기 기록은 저장하지 못했습니다.")
            }
            false
        }
    }

    fun rotatePreview90() {
        if (shuttingDown || editorLeaveLocksActions()) return
        val settlement = settleParameterTransactionBeforeExternalEdit()
        if (continueAfterOwnParameterSettlement(settlement) { rotatePreview90() }) return
        rotatePreview90Async()
    }

    private fun rotatePreview90Async() {
        if (!canEnterEditorActionAfterSettlement()) return
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
        var pendingHistory: PendingHistorySnapshot? = prepareHistorySnapshot("rotatePreview90", start)
        val nextRevision = state.revision + 1
        val rotationSeam = RotationTestSeam.capture()
        var operationToken: Long? = null
        var rotatedPreview: Bitmap? = null
        var rotatedOriginal: Bitmap? = null
        val rotatedMasks = ArrayList<Bitmap>(start.selectionLayers.size)
        fun releaseRotatedOwned() {
            val cleanup = Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
            rotatedPreview?.let(cleanup::add)
            rotatedOriginal?.let(cleanup::add)
            rotatedMasks.forEach(cleanup::add)
            cleanup.forEach { bitmap -> if (!bitmap.isRecycled) bitmap.recycle() }
            rotatedPreview = null
            rotatedOriginal = null
            rotatedMasks.clear()
        }
        fun transferRotatedToDocument() {
            rotatedPreview = null
            rotatedOriginal = null
            rotatedMasks.clear()
        }
        updateUiState { it.copy(isBusy = true, revision = nextRevision) }
        launchManagedEditWithPreparedResources(
            { token ->
                operationToken = token
                var before: EditorHistorySnapshot? = null
                try {
                    before = pendingHistory?.await()
                    pendingHistory = null
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
                    rotationSeam?.awaitBeforeAdoption()
                    withContext(Dispatchers.Main) {
                        val current = uiState.value
                        val currentIdentity =
                            current.sourcePath == start.identity.sourcePath &&
                                current.baseContentToken == start.identity.baseContentToken &&
                                currentDocumentGeneration() == start.identity.generation
                        if (isManagedEditCurrent(token, nextRevision) && currentIdentity && before != null) {
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
                                    isBusy = false,
                                    message = "\uBBF8\uB9AC\uBDF0\uC744 90\uB3C4 \uD68C\uC804\uD588\uC2B5\uB2C8\uB2E4.",
                                )
                            }
                            transferRotatedToDocument()
                            val retained = before
                            before = null
                            settleAdoptedEditHistory(retained)
                            forceDraftSaveAsync()
                        } else if (isManagedEditTokenCurrent(token)) {
                            updateUiState { it.copy(isBusy = false) }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    if (isManagedEditTokenCurrent(token)) {
                        updateUiState {
                            it.copy(
                                isBusy = false,
                                message = "\uBBF8\uB9AC\uBDF0 \uD68C\uC804\uC5D0 \uC2E4\uD328\uD588\uC2B5\uB2C8\uB2E4.",
                            )
                        }
                        if (failure is BitmapAllocationRejectedException) {
                            requestAllocationRecovery(
                                MemoryRetryAction.RotatePreview,
                                failure.requiredBytes,
                                retryInput = MemoryRetryInput.Rotate(state.cropState),
                            )
                        }
                    }
                } finally {
                    before?.let(::recycleHistorySnapshot)
                    pendingHistory?.close()
                    pendingHistory = null
                    releaseRotatedOwned()
                    start.close()
                }
            },
            PreparedResourceHandoff.create(
                "rotatePreview90",
                {
                    pendingHistory?.close()
                    pendingHistory = null
                    releaseRotatedOwned()
                    start.close()
                },
                { start.close() },
                {},
                {
                    val token = operationToken
                    if (token != null && isManagedEditTokenCurrent(token)) {
                        updateUiState { it.copy(isBusy = false) }
                    }
                },
            ),
        )
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
        val settlement = settleParameterTransactionBeforeExternalEdit()
        if (continueAfterOwnParameterSettlement(settlement) {
                applyNativeSpecialEffects(title, failureMessage, effect)
            }
        ) return
        prepareForExternalEdit()
        if (!canEnterEditorActionAfterSettlement(allowMaskSupersession = true)) return
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
            var undoSnapshotOwned: OwnedHistorySnapshot? = null
            val undoSnapshot = pendingHistory?.await()
            pendingHistory = null
            undoSnapshotOwned = undoSnapshot?.let(::OwnedHistorySnapshot)
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
                    settleAdoptedEditHistory(undoSnapshotOwned?.take())
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
                undoSnapshotOwned?.close()
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
        val settlement = settleParameterTransactionBeforeExternalEdit()
        if (continueAfterOwnParameterSettlement(settlement) {
                applyFlareGuardAiOrRulePreview(context, mode)
            }
        ) return
        if (!canEnterEditorActionAfterSettlement(allowMaskSupersession = true)) {
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
        expectedDraftGenerationId: String? = null,
    ): GenerationRestoreOutcome {
        val pointer =
            withContext(Dispatchers.IO) { currentDraftGenerationId(context) }
                ?: return GenerationRestoreOutcome.Absent
        if (expectedDraftGenerationId != null && pointer != expectedDraftGenerationId) {
            return GenerationRestoreOutcome.Stale
        }
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
        DraftRestoreTestSeam.capture()?.await(DraftRestoreTestStage.ValidationComplete, pointer)
        publishRestoreBusy(
            restoreToken,
            restoreStartRevision,
            "임시저장된 편집을 불러오는 중입니다",
        )
        var ownedBase: Bitmap? = null
        var ownedRendered: Bitmap? = null
        var restoreRenderSuccess: RenderResult.Success? = null
        val ownedMasks = ArrayList<Bitmap>(validated.maskFiles.size)
        var createdSession = 0L
        var createdSessionRelease: ((Long) -> Unit)? = null
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
            DraftRestoreTestSeam.capture()?.await(DraftRestoreTestStage.SourceDecoded, pointer)
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
            DraftRestoreTestSeam.capture()?.await(DraftRestoreTestStage.RenderCreated, pointer)
            withContext(Dispatchers.IO) {
                val creation =
                    nativeCreateSessionHandleOrTest(
                        checkNotNull(ownedWorkingSource).absolutePath
                    )
                createdSession = creation.handle
                createdSessionRelease = creation.releaseOverride
            }
            if (createdSession == 0L) error("draft native session creation failed")
            DraftRestoreTestSeam.capture()?.await(DraftRestoreTestStage.NativeSessionCreated, pointer)
            DraftRestoreTestSeam.capture()?.await(DraftRestoreTestStage.BeforeAdoption, pointer)
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
                    settleRestoreBusyPublication(restoreToken, restoreStartRevision)
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
            val previousSessionRelease = nativeSessionRelease
            restorePreviousBaseline = draftPointerBaseline
            draftPointerBaseline = pointer
            restoreBaselineChanged = true
            nativeSession = createdSession
            nativeSessionRelease = createdSessionRelease
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
                    adoptedRestoredWorkingSource = checkNotNull(ownedWorkingSource).absolutePath,
                )
            ) {
                nativeSession = previousSession
                nativeSessionRelease = previousSessionRelease
                draftPointerBaseline = restorePreviousBaseline
                error("draft generation adoption was not confirmed")
            }
            restoreStateAdopted = true
            // Adopted generation restore: visible Draft root moved onto the
            // generation payload; any previous legacy document claim ends.
            replaceLegacyDraftVisibleRoot(previousState.draftSourcePath, validated.sourceFile.absolutePath)
            releaseLegacyDraftDocumentRoot(previousState.sourcePath)
            restoreBusyPublication = null
            createdSession = 0L
            createdSessionRelease = null
            ownedBase = null
            ownedRendered = null
            ownedMasks.clear()
            ownedWorkingSource = null
            lastSuccessfullyRenderedParams = manifest.params
            runCatching { releaseNativeSessionHandle(previousSession, previousSessionRelease) }
                .onFailure { logDraftSaveFailure(it) }
            runCatching { deleteOwnedWorkingSource(context, previousState.sourcePath) }
                .onFailure { logDraftSaveFailure(it) }
            return GenerationRestoreOutcome.Restored
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            if (t is VirtualMachineError || t is ThreadDeath || t is LinkageError) throw t
            logDraftSaveFailure(t)
            if (restoreStateAdopted) {
                return GenerationRestoreOutcome.Restored
            }
            if (t is BitmapAllocationRejectedException) {
                val pointerStillCurrent =
                    withContext(Dispatchers.IO) { currentDraftGenerationId(context) == pointer }
                if (
                    !shuttingDown &&
                        restoreToken == restoreDraftToken &&
                        _uiState.value.revision == restoreStartRevision &&
                        pointerStillCurrent &&
                        draftPointerBaseline == pointer
                ) {
                    settleRestoreBusyPublication(restoreToken, restoreStartRevision)
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            isBusy = false,
                            message = "메모리가 부족하여 임시저장 복구를 완료하지 못했습니다. 기존 편집과 임시저장은 안전합니다.",
                        )
                    }
                    requestAllocationRecovery(
                        MemoryRetryAction.RestoreDraft,
                        t.requiredBytes,
                        payload = pointer,
                    )
                } else {
                    settleRestoreBusyPublication(restoreToken, restoreStartRevision)
                    return GenerationRestoreOutcome.Stale
                }
                return GenerationRestoreOutcome.MemoryRejected(t.requiredBytes)
            }
            if (withContext(Dispatchers.IO) { currentDraftGenerationId(context) } != pointer) {
                if (
                    !shuttingDown &&
                        restoreToken == restoreDraftToken &&
                        _uiState.value.revision == restoreStartRevision
                ) {
                    settleRestoreBusyPublication(restoreToken, restoreStartRevision)
                }
                return GenerationRestoreOutcome.Stale
            }
            if (
                !shuttingDown &&
                    restoreToken == restoreDraftToken &&
                    _uiState.value.revision == restoreStartRevision
            ) {
                settleRestoreBusyPublication(restoreToken, restoreStartRevision)
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
            ownedWorkingSource?.let(::releaseAndDeleteRestoredWorkingSource)
            releaseNativeSessionHandle(createdSession, createdSessionRelease)
            restoreTracker?.end()
            restoreMaskAdmission?.close()
        }
    }

    private suspend fun restoreDraftIfAvailable(
        context: Context,
        restoreToken: Long,
        restoreStartRevision: Int,
        target: DraftRestoreTarget = DraftRestoreTarget.CurrentStartup,
        retryCompletion: CompletableDeferred<DraftRestoreRetryOutcome>? = null,
    ) {
        fun completeRetry(outcome: DraftRestoreRetryOutcome) {
            retryCompletion?.complete(outcome)
        }
        if (
            shuttingDown ||
                restoreToken != restoreDraftToken ||
                restoreStartRevision != _uiState.value.revision
        )
        {
            completeRetry(DraftRestoreRetryOutcome.Cancelled)
            return
        }
        val generationRestore =
            when (target) {
                DraftRestoreTarget.CurrentStartup ->
                    restoreCurrentDraftGeneration(context, restoreToken, restoreStartRevision)
                is DraftRestoreTarget.ExactGeneration ->
                    restoreCurrentDraftGeneration(
                        context,
                        restoreToken,
                        restoreStartRevision,
                        target.generationId,
                    )
                is DraftRestoreTarget.ExactLegacy -> GenerationRestoreOutcome.Absent
            }
        if (generationRestore == GenerationRestoreOutcome.Restored) {
            completeRetry(DraftRestoreRetryOutcome.Restored)
            return
        }
        if (generationRestore is GenerationRestoreOutcome.MemoryRejected) {
            completeRetry(DraftRestoreRetryOutcome.MemoryRejected)
            return
        }
        if (generationRestore == GenerationRestoreOutcome.Stale) {
            completeRetry(DraftRestoreRetryOutcome.Stale)
            return
        }
        if (target is DraftRestoreTarget.ExactGeneration) {
            if (!shuttingDown && restoreToken == restoreDraftToken &&
                restoreStartRevision == _uiState.value.revision) {
                updateUiStateAndRecycleReplaced {
                    if (it.revision == restoreStartRevision) it.copy(isBusy = false) else it
                }
            }
            completeRetry(
                when (generationRestore) {
                    GenerationRestoreOutcome.Absent -> DraftRestoreRetryOutcome.ExactTargetMissing
                    is GenerationRestoreOutcome.Invalid -> DraftRestoreRetryOutcome.ExactTargetInvalid
                    else -> DraftRestoreRetryOutcome.Stale
                }
            )
            return
        }
        if (target is DraftRestoreTarget.ExactLegacy &&
            withContext(Dispatchers.IO) {
                draftSaveMutex.withLock {
                    currentDraftGenerationId(context) == null &&
                        legacyDraftIdentity(context) == target.identity
                }
            }.not()) {
            updateUiStateAndRecycleReplaced {
                if (restoreToken == restoreDraftToken && it.revision == restoreStartRevision) {
                    it.copy(isBusy = false)
                } else it
            }
            completeRetry(DraftRestoreRetryOutcome.Stale)
            return
        }
        if (target == DraftRestoreTarget.CurrentStartup && generationRestore is GenerationRestoreOutcome.Invalid) {
            DraftRestoreTestSeam.capture()?.await(
                DraftRestoreTestStage.CurrentStartupInvalidBeforeCleanup,
                generationRestore.generationId,
            )
            val cleared =
                withContext(Dispatchers.IO) {
                    draftSaveMutex.withLock {
                        if (
                            restoreToken != restoreDraftToken ||
                                restoreStartRevision != _uiState.value.revision
                        )
                            return@withLock false
                        DraftStorageCoordinator.clearInvalidGenerationIfCurrent(
                            context,
                            generationRestore.generationId,
                        ).also { cleared ->
                            if (cleared) draftPointerBaseline = null
                        }
                    }
                }
            if (!cleared) {
                updateUiStateAndRecycleReplaced {
                    if (restoreToken == restoreDraftToken && it.revision == restoreStartRevision) {
                        it.copy(isBusy = false, message = "손상된 임시저장 포인터를 정리하지 못했습니다.")
                    } else it
                }
                completeRetry(DraftRestoreRetryOutcome.ExactTargetInvalid)
                return
            }
        }
        val restoreSnapshot =
            try {
                DraftRestoreTestSeam.capture()?.beforeLegacySnapshot()
                withContext(Dispatchers.IO) {
                    draftSaveMutex.withLock {
                        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                        val storedSourcePath =
                            safeDraftPreferenceString(prefs, KEY_DRAFT_SOURCE) ?: return@withLock null
                        val draftSavedAt =
                            safeDraftPreferenceLong(prefs, KEY_DRAFT_SAVED_AT)?.takeIf { it > 0L }
                        cleanupDraftTemporaryFiles(context)
                        DraftRestoreSnapshot(
                            preferences = DraftPreferencesSnapshot(prefs.all.toMap()),
                            savedAtMillis = draftSavedAt,
                            recovery = resolveDraftRecovery(context, storedSourcePath),
                            legacyIdentity = legacyDraftIdentity(context),
                            generationPointer = currentDraftGenerationId(context),
                        )
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (t is VirtualMachineError || t is ThreadDeath || t is LinkageError) throw t
                logDraftSaveFailure(t)
                val exactTargetStillCurrent =
                    when (target) {
                        is DraftRestoreTarget.ExactLegacy ->
                            withContext(Dispatchers.IO) {
                                draftSaveMutex.withLock {
                                    currentDraftGenerationId(context) == null &&
                                        legacyDraftIdentity(context) == target.identity
                                }
                            }
                        else -> true
                    }
                if (restoreToken == restoreDraftToken && exactTargetStillCurrent) {
                    updateUiStateAndRecycleReplaced {
                        it.copy(
                            isBusy = false,
                            message =
                                "\uC784\uC2DC\uC800\uC7A5 \uBCF5\uAD6C \uC815\uBCF4\uB97C \uD655\uC778\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4. \uD3B8\uC9D1\uC740 \uACC4\uC18D\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4.",
                        )
                    }
                }
                completeRetry(
                    if (restoreToken == restoreDraftToken && exactTargetStillCurrent) {
                        DraftRestoreRetryOutcome.ExactTargetInvalid
                    } else {
                        DraftRestoreRetryOutcome.Stale
                    }
                )
                return
            }
        if (restoreSnapshot == null) {
            completeRetry(DraftRestoreRetryOutcome.ExactTargetMissing)
            return
        }
        val draftPrefs = restoreSnapshot.preferences
        val draftSavedAt = restoreSnapshot.savedAtMillis
        val recovery = restoreSnapshot.recovery
        if (restoreToken != restoreDraftToken) {
            completeRetry(DraftRestoreRetryOutcome.Cancelled)
            return
        }
        val exactLegacyIdentity = (target as? DraftRestoreTarget.ExactLegacy)?.identity
        fun legacyRestoreIdentityMatches(): Boolean =
            !shuttingDown &&
                restoreToken == restoreDraftToken &&
                restoreStartRevision == _uiState.value.revision &&
                (exactLegacyIdentity == null ||
                    (currentDraftGenerationId(context) == null &&
                        legacyDraftIdentity(context) == exactLegacyIdentity)) &&
                currentDraftGenerationId(context) == restoreSnapshot.generationPointer &&
                draftPointerBaseline == restoreSnapshot.generationPointer &&
                legacyDraftIdentity(context) == restoreSnapshot.legacyIdentity
        suspend fun legacyRestoreIsCurrent(): Boolean =
            withContext(Dispatchers.IO) {
                draftSaveMutex.withLock { legacyRestoreIdentityMatches() }
            }
        suspend fun settleStaleLegacyRestore() {
            if (!shuttingDown && restoreToken == restoreDraftToken) {
                settleRestoreBusyPublication(restoreToken, restoreStartRevision)
            }
        }
        if (!legacyRestoreIsCurrent()) {
            settleStaleLegacyRestore()
            completeRetry(DraftRestoreRetryOutcome.Stale)
            return
        }
        val sourceFile = recovery.sourceFile
        if (sourceFile == null) {
            val missingDraftMessage = "임시 저장 원본을 찾을 수 없습니다. 기존 임시 저장 파일이 삭제되어 복구할 수 없습니다."
            // Option A: update both visible message and the publication record
            // so that terminal settlement treats them as the same owner.
            val publication = restoreBusyPublication
            if (publication != null && publication.retryOwner === userMemoryRecoveryOwner) {
                restoreBusyPublication = publication.copy(message = missingDraftMessage)
            }
            updateUiStateAndRecycleReplaced { it.copy(message = missingDraftMessage) }
            completeRetry(DraftRestoreRetryOutcome.ExactTargetMissing)
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
        if (!params.isValidDraftParams()) {
            if (!shuttingDown && restoreToken == restoreDraftToken && _uiState.value.revision == restoreStartRevision) {
                updateUiStateAndRecycleReplaced {
                    it.copy(
                        isBusy = false,
                        message = "\uC784\uC2DC\uC800\uC7A5 \uD3B8\uC9D1 \uAC12\uC774 \uC720\uD6A8\uD558\uC9C0 \uC54A\uC544 \uBCF5\uAD6C\uB97C \uC911\uB2E8\uD588\uC2B5\uB2C8\uB2E4.",
                    )
                }
            }
            completeRetry(DraftRestoreRetryOutcome.ExactTargetInvalid)
            return
        }
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
        publishRestoreBusy(
            restoreToken,
            restoreStartRevision,
            "\uC784\uC2DC\uC800\uC7A5\uB41C \uD3B8\uC9D1\uC744 \uBD88\uB7EC\uC624\uB294 \uC911\uC785\uB2C8\uB2E4",
        )
        var preview: Bitmap? = null
        var rendered: Bitmap? = null
        var restoreRenderSuccess: RenderResult.Success? = null
        var createdSession = 0L
        var createdSessionRelease: ((Long) -> Unit)? = null
        var expectedRestoreRevision: Int? = null
        // In-flight legacy restore root: the exact source being decoded stays
        // claimed until adoption transfers it to this ViewModel or a terminal
        // path releases it.
        val legacyRestoreOpOwner = LegacyDraftSourceOwnership.OwnerKey.create()
        var legacyRestoreAdoptedToDocument = false
        legacyCompatibilitySource(context, sourcePath)?.let {
            LegacyDraftSourceOwnership.acquire(
                legacyRestoreOpOwner,
                LegacyDraftSourceOwnership.RootKind.OPERATION,
                it.absolutePath,
            )
        }
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
            DraftRestoreTestSeam.capture()?.await(DraftRestoreTestStage.SourceDecoded, sourcePath)
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
                completeRetry(DraftRestoreRetryOutcome.Cancelled)
                return
            }
            if (!legacyRestoreIsCurrent()) {
                recycleOwnedRestoreBitmaps()
                settleStaleLegacyRestore()
                completeRetry(DraftRestoreRetryOutcome.Stale)
                return
            }
            val creation = nativeCreateSessionHandleOrTest(sourcePath)
            createdSession = creation.handle
            createdSessionRelease = creation.releaseOverride
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
                releaseNativeSessionHandle(createdSession, createdSessionRelease)
                createdSession = 0L
                completeRetry(DraftRestoreRetryOutcome.Cancelled)
                return
            }
            if (!legacyRestoreIsCurrent()) {
                recycleOwnedRestoreBitmaps()
                releaseNativeSessionHandle(createdSession, createdSessionRelease)
                createdSession = 0L
                settleStaleLegacyRestore()
                completeRetry(DraftRestoreRetryOutcome.Stale)
                return
            }
            val previousSession = nativeSession
            val previousSessionRelease = nativeSessionRelease
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
                    recoveryDebugInfo = recovery.debugInfo,
                    showRecoveryDebugCard = true,
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
            val adopted =
                draftSaveMutex.withLock {
                    if (!legacyRestoreIdentityMatches()) {
                        false
                    } else {
                        nativeSession = createdSession
                        nativeSessionRelease = createdSessionRelease
                        commitUiState(
                            previousState,
                            nextState,
                            replaceDocument = true,
                            adoptedNativeSession = createdSession,
                        )
                    }
                }
            if (!adopted) {
                nativeSession = previousSession
                nativeSessionRelease = previousSessionRelease
                releaseNativeSessionHandle(createdSession, createdSessionRelease)
                createdSession = 0L
                settleStaleLegacyRestore()
                completeRetry(DraftRestoreRetryOutcome.Stale)
                return
            }
            // Adoption succeeded: the operation root ends exactly where this
            // ViewModel's document + visible Draft roots begin.
            legacyCompatibilitySource(context, sourcePath)?.let {
                LegacyDraftSourceOwnership.transferOperationToViewModel(
                    operationOwner = legacyRestoreOpOwner,
                    viewModelOwner = legacyDraftSourceOwner,
                    path = it.absolutePath,
                )
            }
            legacyRestoreAdoptedToDocument = true
            createdSession = 0L
            createdSessionRelease = null
            restoreBusyPublication = null
            preview = null
            rendered = null
            lastSuccessfullyRenderedParams = params
            releaseNativeSessionHandle(previousSession, previousSessionRelease)
            deleteOwnedWorkingSource(context, previousState.sourcePath)
            forceDraftSaveAsync()
            completeRetry(DraftRestoreRetryOutcome.Restored)
        } catch (ce: CancellationException) {
            recycleOwnedRestoreBitmaps()
            releaseNativeSessionHandle(createdSession, createdSessionRelease)
            throw ce
        } catch (t: Throwable) {
            if (t is VirtualMachineError || t is ThreadDeath || t is LinkageError) throw t
            recycleOwnedRestoreBitmaps()
            releaseNativeSessionHandle(createdSession, createdSessionRelease)
            val currentRevision = _uiState.value.revision
            val isRestoreStillCurrent =
                !shuttingDown &&
                    restoreToken == restoreDraftToken &&
                    (currentRevision == restoreStartRevision ||
                        currentRevision == expectedRestoreRevision) &&
                    legacyRestoreIsCurrent()
            if (isRestoreStillCurrent) {
                settleRestoreBusyPublication(restoreToken, restoreStartRevision)
                updateUiStateAndRecycleReplaced {
                    it.copy(
                        isBusy = false,
                        message =
                            "\uC784\uC2DC\uC800\uC7A5\uC744 \uBD88\uB7EC\uC624\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4: ${t.message}",
                    )
                }
                if (t is BitmapAllocationRejectedException) {
                    requestAllocationRecovery(
                        MemoryRetryAction.RestoreDraft,
                        t.requiredBytes,
                        retryInput =
                        withContext(Dispatchers.IO) {
                            MemoryRetryInput.Draft(
                                generationId = null,
                                legacyIdentity = restoreSnapshot.legacyIdentity,
                            )
                        },
                    )
                    completeRetry(DraftRestoreRetryOutcome.MemoryRejected)
                } else {
                    completeRetry(DraftRestoreRetryOutcome.ExactTargetInvalid)
                }
            } else {
                settleStaleLegacyRestore()
                completeRetry(DraftRestoreRetryOutcome.Stale)
            }
        } finally {
            if (!legacyRestoreAdoptedToDocument) {
                LegacyDraftSourceOwnership.releaseOwner(legacyRestoreOpOwner)
            }
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
                var historyPrerequisite: HistoryActivityRegistry.Registration? = null
                transaction.lifecycleInstallation?.hooks?.onTransactionCommitBegan?.invoke(transaction.id)
                if (!transaction.historyCommitted) {
                    transaction.commit()
                    val snapshot = transaction.takeOwnedSnapshot()
                    if (snapshot != null) {
                        if (reason == SettlementReason.Shutdown) {
                            recycleHistorySnapshot(snapshot)
                        } else {
                            settleAdoptedEditHistory(snapshot) { registration ->
                                historyPrerequisite = registration
                            }
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
                return SettlementResult.Committed(settledRevision, historyPrerequisite)
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
        if (historyActivityBusy()) return SettlementResult.HistoryBusy
        return settleParameterTransaction(SettlementReason.ExternalEdit)
    }

    internal fun abortPendingParameterEdit() {
        settleParameterTransactionBeforeExternalEdit()
    }

    private fun prepareForMaskInteraction(): EditorUiState {
        pendingSelectionParamStart?.job?.cancel()
        pendingSelectionParamStart = null
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
                val admission = reserveSelectionMaskCandidate(
                    owner = "history-adopt:${snapshot.coordinatorGeneration ?: "unknown"}",
                    layers = snapshot.selectionLayers,
                    bytesAlreadyReserved =
                        snapshot.maskReservations.isNotEmpty() || snapshot.candidateAdmission != null,
                )
                if (admission is SelectionMaskOwnershipLedger.MaskAdmission.Rejected) {
                    throw BitmapAllocationRejectedException(
                        selectionMaskCandidateBytes(snapshot.selectionLayers),
                    )
                }
                admission
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
            throw BitmapAllocationRejectedException(required)
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
            it.copy(
                canUndo = flags.canUndo,
                canRedo = flags.canRedo,
                historyBusy = historyActivity.isBusy(),
            )
        }
    }

    private fun clearEditHistory() {
        invalidateMemoryRecoveryForDocumentReplacement()
        activeHistoryNavigation = null
        historyNavigationCounter += 1L
        historyActivity.cancel()
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

    private fun releaseNativeSessionHandle(
        session: Long,
        releaseOverride: ((Long) -> Unit)? = null,
    ) {
        if (session != 0L) {
            tracker.unregisterNativeSession(session)
            runCatching {
                releaseOverride?.invoke(session) ?: nativeReleaseSessionOrTest(session)
            }
        }
    }

    private fun releaseNativeSession() {
        if (nativeSession != 0L) {
            val session = nativeSession
            val releaseOverride = nativeSessionRelease
            nativeSession = 0L
            nativeSessionRelease = null
            releaseNativeSessionHandle(session, releaseOverride)
        }
    }

    override fun onCleared() {
        // Android teardown is a non-suspending terminal ownership boundary. The
        // app-level leave action has already awaited Draft persistence; onCleared
        // only blocks new work, resolves visible parameter state synchronously,
        // cancels owned jobs, and releases memory/history ownership. It does not
        // claim that filesystem persistence can be completed here.
        shuttingDown = true
        editorLeaveOwner?.let { owner ->
            owner.phase = EditorLeavePhase.Closed
            owner.job?.cancel()
            owner.result.complete(false)
        }
        editorLeaveOwner = null
        _editorLeaveState.value = EditorLeaveState(phase = EditorLeavePhase.Closed)
        activeHistoryNavigation = null
        historyNavigationCounter += 1L
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
        invalidateOpenImage()
        renderJob?.cancel()
        invalidateExport()
        invalidateSelectionPreview()
        paramUndoWindowJob?.cancel()
        cropJob?.cancel()
        transactionFinishJob?.cancel()
        restoreDraftJob?.cancel()
        startupCoordinatorJob?.cancel()
        asyncBusyOwner?.let { owner ->
            owner.phase = AsyncBusyPhase.Closed
            owner.job?.cancel()
        }
        asyncBusyOwner = null
        userMemoryRecoveryOwner?.let { owner ->
            owner.close()
            lastClosedMemoryRecoveryOwner = owner
        }
        userMemoryRecoveryOwner = null
        trimMemoryCleanupJob?.cancel()
        trimMemoryCleanupJob = null
        automaticRetryAttempt = null
        strongRetryAttempt = null
        updateUiStateAndRecycleReplaced { it.copy(memoryRecoveryRequest = null) }
        releaseNativeSession()
        // The model session is process-global but editor-owned; ensure editor teardown
        // invalidates its command generation and settles the registry out of Ready.
        RemasterModelSession.unload()
        historyActivity.cancel()
        discardPendingParamUndoSnapshot()
        historyCoordinator.close()
        uiStateOwnership?.releaseAll()
        uiState.value.sourcePath?.let { IncomingSourceLiveOwnership.releaseDocument(File(it)) }
        bitmapLeaseLedger.releaseState(uiState.value)
        recycleBitmaps(bitmapLeaseLedger.shutdown())
        tracker.logSnapshot("preTrackerClose")
        tracker.close()
        uiState.value.sourcePath?.let { RestoredWorkingSourceOwnership.releaseDocument(File(it)) }
        // Teardown drops only this ViewModel's legacy-source claims; claims
        // held by other ViewModels or in-flight operations always survive.
        LegacyDraftSourceOwnership.releaseOwner(legacyDraftSourceOwner)
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

private enum class RetryFailureArbitration {
    FreshFailure,
    AutomaticRetryFailure,
    StrongRetryFailure,
    UnrelatedFailureWhileRetrying,
}

internal sealed interface SelectionRetryTarget {
    data object Irrelevant : SelectionRetryTarget
    data class Layer(val id: String) : SelectionRetryTarget
}

internal sealed interface MemoryRetryInput {
    data object Irrelevant : MemoryRetryInput
    data class Export(val format: ExportFormat, val resolution: ExportResolution) : MemoryRetryInput
    data class Crop(val state: CropState) : MemoryRetryInput
    data class Rotate(val cropState: CropState) : MemoryRetryInput
    data class Draft(
        val generationId: String?,
        val legacyIdentity: LegacyDraftIdentity? = null,
    ) : MemoryRetryInput
    data class Route(val route: String) : MemoryRetryInput
}

internal data class MemoryRetryDescriptor(
    val token: Long,
    val action: MemoryRetryAction,
    val requiredBytes: Long,
    val sourcePath: String?,
    val baseContentToken: String,
    val revision: Int,
    val payload: String?,
    val selectionTarget: SelectionRetryTarget = SelectionRetryTarget.Irrelevant,
    val input: MemoryRetryInput = MemoryRetryInput.Irrelevant,
    // Navigation-specific identity for target-aware recovery
    val navigationDirection: Boolean? = null, // true = undo, false = redo
    val targetEntryId: String? = null,
    val coordinatorGeneration: String? = null,
    val sourceBranchSize: Int = 0,
    val destinationBranchSize: Int = 0,
)

private val REVISION_ADVANCING_MEMORY_RETRY_ACTIONS =
    setOf(
        MemoryRetryAction.CreateBrushSelection,
        MemoryRetryAction.SubjectSelection,
        MemoryRetryAction.MaskAwareRemaster,
        MemoryRetryAction.FlareNight,
        MemoryRetryAction.FlareSun,
        MemoryRetryAction.DuplicateSelection,
        MemoryRetryAction.BackgroundSelection,
        MemoryRetryAction.AutoStraightenCrop,
        MemoryRetryAction.ApplySelectionNative,
        MemoryRetryAction.RotatePreview,
    )

private fun SelectionRetryTarget.matchesCurrent(activeSelectionLayerId: String?): Boolean =
    when (this) {
        SelectionRetryTarget.Irrelevant -> true
        is SelectionRetryTarget.Layer -> activeSelectionLayerId == id
    }

internal data class MemoryCleanupResult(
    /** Whether any RAM/resource was released anywhere in the cleanup path. */
    val reclaimedResources: Boolean,
    /** Whether history recovery completed (not busy/superseded) for the requested transaction. */
    val historyRecoveryCompleted: Boolean,
    /** Whether the protected disk budget was satisfied after eligible eviction. */
    val historyDiskBudgetSatisfied: Boolean,
    /** True when the history operation was busy or superseded — result is not retry-safe. */
    val historyRecoverySuperseded: Boolean,
    val ownerStillCurrent: Boolean = true,
)

private fun MemoryRetryDescriptor?.matchesRetryFailure(
    currentFailure: MemoryRetryDescriptor,
): Boolean {
    val previous = this ?: return false
    if (
        previous.action != currentFailure.action ||
            previous.sourcePath != currentFailure.sourcePath ||
            previous.baseContentToken != currentFailure.baseContentToken
    ) return false
    if (previous.selectionTarget != currentFailure.selectionTarget) return false
    if (previous.input != currentFailure.input) return false
    if (
        currentFailure.action == MemoryRetryAction.HistoryUndo ||
            currentFailure.action == MemoryRetryAction.HistoryRedo
    ) {
        return previous.coordinatorGeneration == currentFailure.coordinatorGeneration &&
            previous.navigationDirection == currentFailure.navigationDirection &&
            previous.targetEntryId == currentFailure.targetEntryId &&
            previous.payload == currentFailure.payload &&
            currentFailure.revision.toLong() in
                previous.revision.toLong()..(previous.revision.toLong() + 1L)
    }
    if (currentFailure.action == MemoryRetryAction.OpenImage) {
        return previous.payload == currentFailure.payload
    }
    return currentFailure.revision.toLong() in
        previous.revision.toLong()..(previous.revision.toLong() + 1L)
}

private fun MemoryRetryDescriptor.matchesSameRecoveryRequest(
    other: MemoryRetryDescriptor,
): Boolean =
    action == other.action &&
        sourcePath == other.sourcePath &&
        baseContentToken == other.baseContentToken &&
        revision == other.revision &&
        payload == other.payload &&
        selectionTarget == other.selectionTarget &&
        input == other.input &&
        navigationDirection == other.navigationDirection &&
        targetEntryId == other.targetEntryId &&
        coordinatorGeneration == other.coordinatorGeneration

internal fun memoryRetryAttemptMatchesFailureForTest(
    previous: MemoryRetryDescriptor?,
    currentFailure: MemoryRetryDescriptor,
): Boolean = previous.matchesRetryFailure(currentFailure)

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
    if (MemoryRecoveryTestSeam.capture()?.rejectSelectionMaskAdmission == true) {
        null
    } else {
        selectionMaskOwnership.reserve(
            owner = owner,
            bytes = BitmapMemoryBudget.bytes(source.width, source.height, config),
            documentLayerDelta = documentLayerDelta,
        )
    }

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
    if (MemoryRecoveryTestSeam.capture()?.rejectSelectionMaskAdmission == true) {
        SelectionMaskOwnershipLedger.MaskAdmission.Rejected("test seam rejected mask admission")
    } else {
        selectionMaskOwnership.reserveDocumentCandidate(
            owner = owner,
            bytes = bytes,
            documentLayerCount = documentLayerCount,
        )
    }

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
        if (BitmapCopyTestSeam.isCustomCopyEnabled()) {
            BitmapCopyTestSeam.copyOwned(this, config, mutable)
        } else {
            copy(config, mutable) ?: throw IllegalStateException("bitmap copy failed")
        }
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

private fun migrateDraftSourceIfNeeded(context: Context, storedSourcePath: String): File? {
    val storedSource = File(storedSourcePath)
    if (!storedSource.isFile) return null
    if (isOwnedDraftSource(context, storedSource)) return storedSource
    val draftSource = persistDraftSourceFile(context, storedSource.absolutePath) ?: return null
    if (draftSource.absolutePath != storedSource.absolutePath) {
        val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val previousPointer = safeDraftPreferenceString(preferences, KEY_DRAFT_SOURCE)
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

internal fun isOwnedDraftSource(context: Context, file: File): Boolean {
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
    return sameCanonicalPath(safeDraftPreferenceString(preferences, KEY_DRAFT_SOURCE), path) &&
        safeDraftPreferenceString(preferences, KEY_DRAFT_BASE_TOKEN) == state.baseContentToken
}

private fun isSupportedDraftSource(context: Context, source: File): Boolean =
    source.isFile && (isOwnedDraftSource(context, source) || source.name == DRAFT_SOURCE_FILE_NAME)

private fun sameCanonicalPath(first: String?, second: String?): Boolean =
    first != null &&
        second != null &&
        runCatching { File(first).canonicalFile == File(second).canonicalFile }.getOrDefault(false)

private fun sameOptionalCanonicalPath(first: String?, second: String?): Boolean =
    if (first == null || second == null) first == second else sameCanonicalPath(first, second)

private fun deleteObsoleteDraftSources(context: Context) {
    val directory = persistentDraftDirectory(context)
    directory.listFiles()?.forEach { file ->
        // Legacy source files can still be the input of another live
        // ViewModel save. Generation cleanup/reconciliation owns their
        // eventual reclamation; this commit may only remove its own temps.
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

/**
 * Resolves [path] to a live legacy Draft compatibility source candidate:
 * canonical containment inside drafts/current plus the owned naming contract.
 * Non-family paths (generation payloads, incoming cache finals, restored
 * working sources) resolve to null so they are never registered here; those
 * families have their own ownership registries.
 */
private fun legacyCompatibilitySource(context: Context, path: String?): File? {
    if (path == null) return null
    return runCatching {
        val file = File(path).canonicalFile
        file.takeIf {
            it.parentFile == persistentDraftDirectory(context).canonicalFile &&
                LegacyDraftSourceOwnership.isOwnedSourceName(it.name)
        }
    }.getOrNull()
}

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

internal data class DraftSaveResult(
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
    val legacyIdentity: LegacyDraftIdentity,
    val generationPointer: String?,
)

internal data class LegacyDraftIdentity(
    val generationPointer: String?,
    val payloadFingerprint: String,
)

private data class DraftPointerSnapshot(val generationId: String?)

private sealed interface DraftRestoreTarget {
    data object CurrentStartup : DraftRestoreTarget

    data class ExactGeneration(val generationId: String) : DraftRestoreTarget

    data class ExactLegacy(val identity: LegacyDraftIdentity) : DraftRestoreTarget
}

private sealed interface DraftRestoreRetryOutcome {
    data object Restored : DraftRestoreRetryOutcome

    data object MemoryRejected : DraftRestoreRetryOutcome

    data object Stale : DraftRestoreRetryOutcome

    data object ExactTargetMissing : DraftRestoreRetryOutcome

    data object ExactTargetInvalid : DraftRestoreRetryOutcome

    data object Cancelled : DraftRestoreRetryOutcome
}

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
    RestoredWorkingSourceOwnership.acquire(destination)
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
        RestoredWorkingSourceOwnership.releaseRestore(destination)
        RestoredWorkingSourceOwnership.deleteIfUnowned(destination)
        throw t
    }
}

private fun deleteOwnedWorkingSource(context: Context, sourcePath: String?) {
    if (sourcePath == null) return
    val filesDirectory =
        runCatching { File(context.filesDir, "editor_sources").canonicalFile }.getOrNull() ?: return
    val cacheDirectory = runCatching { context.cacheDir.canonicalFile }.getOrNull() ?: return
    val source = runCatching { File(sourcePath).canonicalFile }.getOrNull() ?: return
    val ownedDraftSource =
        source.parentFile == filesDirectory &&
            RestoredWorkingSourceOwnership.isOwnedName(source.name)
    val ownedIncomingSource =
        source.parentFile == cacheDirectory &&
            source.name.startsWith("source_") &&
            source.extension == "img"
    if (ownedDraftSource) {
        RestoredWorkingSourceOwnership.deleteIfUnowned(source)
    } else if (
        ownedIncomingSource &&
            IncomingSourceLiveOwnership.deleteIfUnowned(source) == IncomingSourceLiveOwnership.DeleteResult.DELETED
    ) {
        // Incoming cache sources share the same linearized ownership boundary
        // as startup and manual cache cleanup.
    }
}

private fun releaseAndDeleteRestoredWorkingSource(source: File) {
    RestoredWorkingSourceOwnership.releaseRestore(source)
    RestoredWorkingSourceOwnership.deleteIfUnowned(source)
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

private suspend fun saveDraftSnapshot(
    context: Context,
    payload: DraftSavePayload,
    testSeam: DraftSaveTestSeam?,
    isCurrent: () -> Boolean,
): DraftSaveResult? {
    return DraftStorageCoordinator.withWriteLock {
        testSeam?.parkIfRequested(DraftSaveStage.StorageTransactionAcquired)
        if (!isCurrent()) {
            DraftSaveTestSeam.Registry.lastFailureReasonForTest = "save-draft-not-current"
            return@withWriteLock null
        }
        val draftSource =
            when {
                payload.previousVisibleDraftPath?.let(::File)?.isFile == true &&
                    sameCanonicalPath(
                        safeDraftPreferenceString(
                            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE),
                            KEY_DRAFT_SOURCE,
                        ),
                        payload.previousVisibleDraftPath,
                    ) &&
                    safeDraftPreferenceString(
                        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE),
                        KEY_DRAFT_BASE_TOKEN,
                    ) == payload.baseContentToken &&
                    isSupportedDraftSource(context, File(payload.previousVisibleDraftPath)) ->
                    DraftSourceResult(File(payload.previousVisibleDraftPath), changed = false)
                !payload.baseBitmapDirty && payload.sourcePath != null ->
                    persistDraftSourceFileIfNeeded(context, payload.sourcePath)
                        ?: run { DraftSaveTestSeam.Registry.lastFailureReasonForTest = "reuse-source-null:${payload.sourcePath}"; return@withWriteLock null }
                payload.dirtyBitmapCopy != null ->
                    persistDraftBitmapFile(context, payload.dirtyBitmapCopy)?.let {
                        DraftSourceResult(it, changed = true)
                    }
                        ?: run { DraftSaveTestSeam.Registry.lastFailureReasonForTest = "dirty-bitmap-persist-null"; return@withWriteLock null }
                else -> null
            }?.also { }
                ?: run { DraftSaveTestSeam.Registry.lastFailureReasonForTest = "no-draft-source-result:${payload.sourcePath}/dirty=${payload.baseBitmapDirty}"; return@withWriteLock null }
        testSeam?.parkIfRequested(DraftSaveStage.CompatibilitySourceVisible)
        val savedAt = System.currentTimeMillis()
        val generationResult: DraftSaveResult?
        try {
            generationResult = persistDraftGenerationInternal(
                context = context,
                payload = payload,
                draftSourceFile = draftSource.file,
                savedAt = savedAt,
                dirtyBitmapCopy = payload.dirtyBitmapCopy,
                isCurrent = isCurrent,
                testSeam = testSeam,
            )
        } catch (ce: CancellationException) {
            if (draftSource.changed && isOwnedDraftSource(context, draftSource.file)) {
                runCatching { draftSource.file.delete() }
            }
            throw ce
        } catch (t: Throwable) {
            if (draftSource.changed && isOwnedDraftSource(context, draftSource.file)) {
                runCatching { draftSource.file.delete() }
            }
            throw t
        }
        if (generationResult == null) {
            if (draftSource.changed && isOwnedDraftSource(context, draftSource.file))
                draftSource.file.delete()
            DraftSaveTestSeam.Registry.lastFailureReasonForTest = "generation-persist-null"
            return@withWriteLock null
        }
        generationResult.copy(
            compatibilitySourceFile = draftSource.file,
            compatibilitySourceChanged = draftSource.changed,
        )
    }
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
        deleteObsoleteDraftSources(context)
    }
}

private suspend fun persistDraftGenerationInternal(
    context: Context,
    payload: DraftSavePayload,
    draftSourceFile: File,
    savedAt: Long,
    dirtyBitmapCopy: Bitmap?,
    isCurrent: () -> Boolean,
    testSeam: DraftSaveTestSeam?,
): DraftSaveResult? {
    val genId = UUID.randomUUID().toString()
    var genDir = newDraftGenerationDirectory(context)
    var pendingResult: DraftSaveResult? = null
    var pointerCommitted = false
    var result: DraftSaveResult? = null
    var durablePublication = false
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
            DraftSaveTestSeam.Registry.lastFailureReasonForTest = "generation-write-failed"
            return null
        }
        // Finalize, validate, and publish — all under the outer global lock from saveDraftSnapshot
        val finalized = DraftStorageCoordinator.finalizeGenerationUnsafe(context, genDir, genId)
        if (finalized == null) {
            deleteDraftDirectory(context, genDir)
            DraftSaveTestSeam.Registry.lastFailureReasonForTest = "generation-finalize-failed"
            return null
        }
        testSeam?.parkIfRequested(DraftSaveStage.GenerationFinalizedBeforePublish)
        genDir = finalized
        val validated = validateDraftGeneration(genDir, genId)
        if (validated == null) {
            deleteDraftDirectory(context, genDir)
            DraftSaveTestSeam.Registry.lastFailureReasonForTest = "generation-validation-failed"
            return null
        }
        if (!isCurrent()) {
            deleteDraftDirectory(context, genDir)
            DraftSaveTestSeam.Registry.lastFailureReasonForTest = "generation-not-current"
            return null
        }
        val pointer = DraftStorageCoordinator.readCurrentPointerUnsafe(context)
        if (pointer != payload.expectedPointerGenerationId) {
            deleteDraftDirectory(context, genDir)
            DraftSaveTestSeam.Registry.lastFailureReasonForTest = "generation-pointer-mismatch"
            return null
        }
        val previousDirectory = payload.expectedPointerGenerationId?.let {
            findDraftGenerationDirectory(context, it)?.root
        }
        result = DraftSaveResult(
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
        val published = withContext(NonCancellable) {
            DraftStorageCoordinator.publishGenerationUnsafe(context, genDir.root.name)
        }
        if (!published) {
            deleteDraftDirectory(context, genDir)
            DraftSaveTestSeam.Registry.lastFailureReasonForTest = "generation-publish-failed"
            return null
        }
        val actualPointer = DraftStorageCoordinator.readCurrentPointerUnsafe(context)
        durablePublication = actualPointer == genDir.root.name
        if (!durablePublication) {
            deleteDraftDirectory(context, genDir)
            DraftSaveTestSeam.Registry.lastFailureReasonForTest = "generation-publish-failed"
            return null
        }
        testSeam?.pointerPersistedGenerationId?.complete(genDir.root.name)
        testSeam?.parkIfRequested(DraftSaveStage.PointerPublished)
        testSeam?.parkIfRequested(DraftSaveStage.PointerPersistedBeforeSettlement)
        val completedDir = result.copy(pointerPublished = true)
        pendingResult = completedDir
        pointerCommitted = true
        return completedDir
    } catch (ce: CancellationException) {
        testSeam?.cancellationCaught?.complete(Unit)
        if (durablePublication) {
            val saved = pendingResult ?: result?.copy(pointerPublished = true)
            if (saved != null) {
                withContext(NonCancellable) {
                    try {
                        DraftStorageCoordinator.rollbackCommittedDraftUnsafe(context, saved)
                    } catch (t: Throwable) {
                        logDraftSaveFailure(t)
                    }
                }
            }
        } else {
            deleteDraftDirectory(context, genDir)
        }
        throw ce
    } catch (t: Throwable) {
        if (durablePublication) {
            val saved = pendingResult ?: result?.copy(pointerPublished = true)
            if (saved != null) {
                try {
                    DraftStorageCoordinator.rollbackCommittedDraftUnsafe(context, saved)
                } catch (t2: Throwable) {
                    logDraftSaveFailure(t2)
                }
            }
        } else {
            deleteDraftDirectory(context, genDir)
        }
        Log.w(FLARE_GUARD_AI_TAG, "Draft generation save failed", t)
        return null
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
                file.isFile && IncomingSourceArtifactNames.isFinalName(file.name)
            }
            .orEmpty()
    var removed = 0
    files.forEach { file ->
        val expired = now - file.lastModified() > maxAgeMs
        val isActive = activePath != null && file.absolutePath == activePath
        if (
            expired &&
                !isActive &&
                IncomingSourceLiveOwnership.deleteIfUnowned(file) == IncomingSourceLiveOwnership.DeleteResult.DELETED
        ) {
            removed += 1
        }
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
                    safeDraftPreferenceString(prefs, KEY_NOISE_ENGINE),
                    NoiseEngine.FastEdgeAware,
                ),
            detailEngine =
                enumValueOrDefault(
                    safeDraftPreferenceString(prefs, KEY_DETAIL_ENGINE),
                    DetailEngine.MaskedUnsharp,
                ),
            toneEngine =
                enumValueOrDefault(
                    safeDraftPreferenceString(prefs, KEY_TONE_ENGINE),
                    ToneEngine.HistogramAuto,
                ),
            hazeEngine =
                enumValueOrDefault(
                    safeDraftPreferenceString(prefs, KEY_HAZE_ENGINE),
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
internal const val KEY_DRAFT_SOURCE = "draft_source"
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
