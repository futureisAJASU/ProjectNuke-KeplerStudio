package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal enum class HistorySnapshotStorage { Exact, MetadataOnly }

internal enum class HistoryPayloadState { Hot, Cold, Loading, Spilling, Adopting, Discarded }

internal enum class HistoryAdmissionNotRetainedReason {
    MemoryCapacity,
    StorageUnavailable,
    StorageBudget,
    Superseded,
    Closed,
}

internal enum class HistoryStorageMovement {
    None,
    ExistingEntriesSpilled,
    AdmittedSnapshotStoredCold,
    Both;

    internal fun plus(other: HistoryStorageMovement): HistoryStorageMovement = when {
        this == None -> other
        other == None -> this
        this == other -> this
        else -> Both
    }
}

internal sealed interface SpillResult {
    data object Success : SpillResult
    data class CurrentFailure(val reason: HistoryAdmissionNotRetainedReason) : SpillResult
    data object Superseded : SpillResult
}

internal data class SpillUntilFitsResult(
    val moved: Boolean,
    val terminalReason: HistoryAdmissionNotRetainedReason? = null,
)

internal data class RebalanceResult(
    val spilledEntryIds: Set<String>,
) {
    val moved: Boolean get() = spilledEntryIds.isNotEmpty()
}

internal sealed interface HistoryPublishResult {
    data class Success(val payload: ColdHistoryPayload) : HistoryPublishResult
    data object InsufficientStorage : HistoryPublishResult
    data class Failed(val cause: Throwable) : HistoryPublishResult
}
internal enum class TrimResult { Satisfied, Superseded }

private class HistoryStorageWriteFailure(message: String) : java.io.IOException(message)

private class HistoryStorageInvariantViolation(message: String) : IllegalStateException(message)

internal data class ColdHistoryPayload(
    val directory: File,
    val bytes: Long,
    val decodedBytes: Long,
    val generation: String
)

internal data class HistorySelectionMaskPreflight(
    val uniqueMaskBytes: Long,
    val layerCount: Int,
)

internal object NoSelectionMaskAdmission : AutoCloseable {
    override fun close() = Unit
}

/** Result of a batch cold-payload deletion attempt. */
internal data class DeletionResult(
    val allConfirmedAbsent: Boolean,
    val failedPayloads: List<ColdHistoryPayload>
)

internal data class EditorHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val documentGeneration: String,
    var hotSnapshot: EditorHistorySnapshot?,
    var coldPayload: ColdHistoryPayload? = null,
    var payloadState: HistoryPayloadState = HistoryPayloadState.Hot
) {
    /** Resident bitmap bytes in RAM (hot snapshots only). */
    fun hotResidentBytes(): Long = hotSnapshot?.bitmapBytes() ?: 0L

    /** Total decoded bitmap bytes this entry would require if loaded (hot or cold). */
    fun decodedBytes(): Long = hotSnapshot?.bitmapBytes() ?: coldPayload?.decodedBytes ?: 0L

    /** Compressed disk bytes for cold entries. */
    fun coldDiskBytes(): Long = coldPayload?.bytes ?: 0L

    /** Metadata-only snapshots have no bitmap payload and intentionally stay hot. */
    fun canColdSpill(): Boolean =
        payloadState == HistoryPayloadState.Hot &&
            hotSnapshot?.storage == HistorySnapshotStorage.Exact
}

internal data class HistoryFlags(val canUndo: Boolean, val canRedo: Boolean, val busy: Boolean)

internal sealed interface HistoryCaptureAvailability {
    data object Ready : HistoryCaptureAvailability
    data object HistoryBusy : HistoryCaptureAvailability
    data class MemoryRejected(val requiredBytes: Long) : HistoryCaptureAvailability
}

internal sealed interface HistoryAdmissionOutcome {
    val flags: HistoryFlags

    data class Retained(
        override val flags: HistoryFlags,
        val storageMovement: HistoryStorageMovement,
    ) : HistoryAdmissionOutcome

    data class NotRetained(
        val reason: HistoryAdmissionNotRetainedReason,
        override val flags: HistoryFlags,
    ) : HistoryAdmissionOutcome
}

internal sealed class HistoryNavigationResult {
    data class Adopted(
        val flags: HistoryFlags,
        val storageMovement: HistoryStorageMovement,
    ) : HistoryNavigationResult()
    data class Unavailable(val flags: HistoryFlags) : HistoryNavigationResult()
    data class NotCompleted(
        val reason: HistoryNavigationNotCompletedReason,
        val flags: HistoryFlags,
    ) : HistoryNavigationResult()
    data class MemoryRejected(val requiredBytes: Long, val flags: HistoryFlags) : HistoryNavigationResult()
    data class Busy(val flags: HistoryFlags) : HistoryNavigationResult()
}

internal enum class HistoryNavigationNotCompletedReason {
    TargetUnavailable,
    TargetCorrupt,
    StorageUnavailable,
    StorageBudget,
    CurrentStateStorageUnavailable,
    CurrentStateStorageBudget,
    Superseded,
    CurrentStateCaptureFailed,
    MaterializationFailed,
    AdoptionRejected,
    Closed,
}

internal sealed interface CurrentNavigationSnapshotAdmission {
    data class Hot(val entry: EditorHistoryEntry) : CurrentNavigationSnapshotAdmission
    data class Cold(val entry: EditorHistoryEntry) : CurrentNavigationSnapshotAdmission
    data class NotRetained(
        val reason: HistoryNavigationNotCompletedReason,
    ) : CurrentNavigationSnapshotAdmission
}

/**
 * Production-used persistence boundary for the history coordinator.
 *
 * Tests can deterministically suspend or fail individual storage stages without duplicating
 * coordinator ownership logic. The filesystem implementation below is the production backend.
 */
internal interface HistoryStorageBackend {
    fun registerSession(sessionId: String)
    fun unregisterSession(sessionId: String)
    suspend fun initializeSession(sessionId: String)
    suspend fun publish(entry: EditorHistoryEntry, snapshot: EditorHistorySnapshot): HistoryPublishResult
    suspend fun load(
        entry: EditorHistoryEntry,
        expectedGeneration: String,
        register: (EditorHistorySnapshot) -> Unit,
    ): EditorHistorySnapshot?
    suspend fun loadWithSelectionMaskPreflight(
        entry: EditorHistoryEntry,
        expectedGeneration: String,
        preflight: suspend (HistorySelectionMaskPreflight) -> AutoCloseable?,
        register: (EditorHistorySnapshot) -> Unit,
    ): EditorHistorySnapshot? = load(entry, expectedGeneration, register)
    suspend fun requiredBitmapBytes(entry: EditorHistoryEntry, expectedGeneration: String): Long?
    suspend fun deleteEntries(entries: Collection<EditorHistoryEntry>): DeletionResult
    suspend fun delete(entry: EditorHistoryEntry): Boolean
    suspend fun delete(payload: ColdHistoryPayload): Boolean
    suspend fun deletePayloads(payloads: Collection<ColdHistoryPayload>): DeletionResult
    suspend fun deleteSession(sessionId: String): Boolean
}

internal class EditorHistoryCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val tracker: TrackerSession? = null,
    settlementDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val storage: HistoryStorageBackend = EditorHistoryStorage(context.applicationContext),
    private val historyRamBudgetBytes: () -> Long = BitmapMemoryBudget::historyBudgetBytes,
    private val historyDiskBudgetBytes: () -> Long = BitmapMemoryBudget::historyDiskBudgetBytes,
) {
    /** Detached settlement survives viewModelScope cancellation and owns all lifecycle IO. */
    private val settlementScope = CoroutineScope(SupervisorJob() + settlementDispatcher)
    private var undo = ArrayDeque<EditorHistoryEntry>()
    private var redo = ArrayDeque<EditorHistoryEntry>()
    @Volatile private var documentGeneration = UUID.randomUUID().toString()
    private var operationToken = 0L
    private val operationCompletions = HashMap<Long, CompletableDeferred<Unit>>()
    /** Pending generation-settlement completions (from replaceDocument). close() awaits these. */
    private val detachedGenerationSettlements = ArrayList<CompletableDeferred<Unit>>()
    /** Final close-settlement deferred, completed when the close finalizer finishes. */
    @PublishedApi internal var closeSettlement: CompletableDeferred<Unit>? = null
    /** Cold payloads whose physical deletion has not been confirmed. */
    private val pendingDeletionDebt = ArrayList<ColdHistoryPayload>()
    private var operationBusy = true
    private var diagnosticOperationKind = TrackerSession.HistoryOperationKind.Idle
    private var diagnosticNavigationDirection: String? = null
    private var diagnosticProtectedTargetId: String? = null
    private var diagnosticRecoveryMode: String? = null
    private var diagnosticOperationPhase: String? = null
    /** Diagnostic-only handoff: prevents a target from being counted as both history and UI. */
    private var diagnosticTransferredHotEntryId: String? = null
    private var activeColdLoadDecodedBytes = 0L
    private var closed = false
    @Volatile private var visibleFlags = HistoryFlags(false, false, true)
    private var idleSignal = CompletableDeferred<Unit>()
    internal var onFlagsChanged: (() -> Unit)? = null

    init {
        val initialGeneration = documentGeneration
        val initialSignal = CompletableDeferred<Unit>()
        idleSignal = initialSignal
        storage.registerSession(initialGeneration)
        settlementScope.launch {
            withContext(NonCancellable) {
                runCatching { storage.initializeSession(initialGeneration) }
            }
            if (isMainOwner() && documentGeneration == initialGeneration && !closed && operationBusy) {
                operationBusy = false
                publishState()
            }
            // Always complete: wakes waiters even if a replacement superseded us.
            initialSignal.complete(Unit)
        }
    }

    /** Combined cold-bytes still on disk: entries still in stacks + pending deletion debt. */
    private fun totalColdDiskBytes(): Long {
        var total = 0L
        undo.forEach { total = BitmapMemoryBudget.saturatingAdd(total, it.coldPayload?.bytes ?: 0L) }
        redo.forEach { total = BitmapMemoryBudget.saturatingAdd(total, it.coldPayload?.bytes ?: 0L) }
        pendingDeletionDebt.forEach { total = BitmapMemoryBudget.saturatingAdd(total, it.bytes) }
        return total
    }

    /** Records confirmed-failed deletions as debt; duplicate directories are not re-tracked. */
    private fun recordDeletionDebt(failedPayloads: Collection<ColdHistoryPayload>) {
        for (payload in failedPayloads) {
            if (pendingDeletionDebt.none { it.directory == payload.directory }) {
                pendingDeletionDebt.add(payload)
            }
        }
    }

    /**
     * TOTAL physical settlement for entries whose logical discard is already
     * irrevocably committed (removed from their stacks, payloadState ==
     * Discarded). Every cold payload MUST end in exactly one of two states:
     * confirmed physically absent, or represented by a pendingDeletionDebt
     * row. The deletion batch therefore runs inside a narrow NonCancellable
     * section — aborting between the irreversible discard and physical
     * settlement would strand untracked payload directories forever — and
     * caller cancellation still propagates as soon as that settlement
     * completes. Every caller passes entries that have already crossed the
     * discard boundary. A backend-level exceptional exit (any Throwable,
     * CancellationException included) records every payload STILL PRESENT as
     * debt before propagating: confirmed-absent payloads stay truthfully
     * absent, so a partial batch failure can never leave a debtless file nor
     * invent reclaim bytes for work already done.
     */
    private suspend fun settleColdEntries(discarded: Collection<EditorHistoryEntry>): DeletionResult {
        if (discarded.isEmpty()) return DeletionResult(true, emptyList())
        val result = withContext(NonCancellable) {
            try {
                storage.deleteEntries(discarded)
            } catch (t: Throwable) {
                val stillPresent =
                    discarded.mapNotNull { entry -> entry.coldPayload?.takeIf { it.directory.exists() } }
                recordDeletionDebt(stillPresent)
                if (t is CancellationException) throw t
                DeletionResult(false, stillPresent)
            }
        }
        recordDeletionDebt(result.failedPayloads)
        // Settlement is now total: re-arm caller cancellation explicitly — the
        // NonCancellable section may resume undispached on a same dispatcher
        // without an implicit cancellation check.
        currentCoroutineContext().ensureActive()
        return result
    }

    /**
     * TOTAL physical settlement for cold payloads no longer owned by any
     * history structure (superseded publications, rejected admissions).
     * Mirrors [settleColdEntries]: exceptional exits record every still-present
     * payload as debt first, then caller cancellation/exception propagates.
     */
    private suspend fun settleColdPayloads(payloads: Collection<ColdHistoryPayload>) {
        if (payloads.isEmpty()) return
        val result = withContext(NonCancellable) {
            try {
                storage.deletePayloads(payloads)
            } catch (t: Throwable) {
                val stillPresent = payloads.filter { it.directory.exists() }
                recordDeletionDebt(stillPresent)
                if (t is CancellationException) throw t
                DeletionResult(false, stillPresent)
            }
        }
        recordDeletionDebt(result.failedPayloads)
        // Mirrors [settleColdEntries]: total settlement first, then caller
        // cancellation propagates.
        currentCoroutineContext().ensureActive()
    }

    /** Remove all deletion debt whose generation matches [generation]. */
    private fun clearGenerationDebt(generation: String) {
        pendingDeletionDebt.removeAll { it.generation == generation }
    }

    /**
     * Retry all pending deletion debt.
     * Snapshot the debt list on Main, delete all via one IO batch, then reconcile on Main.
     * No iterator is held across a suspend boundary.
     * Debt added by a concurrent replacement/close during IO is preserved.
     * CancellationException propagates; other storage failures yield a truthful
     * zero with debt untouched. Returns the bytes of debt rows confirmed absent,
     * so every row settles exactly once.
     */
    private suspend fun retryPendingDeletions(): Long {
        if (pendingDeletionDebt.isEmpty()) return 0L
        val snapshot = pendingDeletionDebt.toList()
        val result = try {
            storage.deletePayloads(snapshot)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            return 0L
        }
        val snapshotDirs = snapshot.map { it.directory }.toSet()
        val failedDirs = result.failedPayloads.map { it.directory }.toHashSet()
        // Remove only debt that was in the snapshot AND was confirmed absent.
        var freedBytes = 0L
        pendingDeletionDebt.removeAll { debt ->
            val confirmedAbsent = debt.directory in snapshotDirs && debt.directory !in failedDirs
            if (confirmedAbsent) {
                freedBytes = BitmapMemoryBudget.saturatingAdd(freedBytes, debt.bytes)
            }
            confirmedAbsent
        }
        return freedBytes
    }

    fun flags(): HistoryFlags = visibleFlags
    fun currentGeneration(): String = documentGeneration
    fun navigationTargetId(undoDirection: Boolean): String? = (if (undoDirection) undo else redo).lastOrNull()?.id

    /** Pure read-only inspection for tests: committed undo-stack entry count. */
    internal fun undoEntryCountForTest(): Int = undo.size

    /** Pure read-only inspection for tests: committed redo-stack entry count. */
    internal fun redoEntryCountForTest(): Int = redo.size

    fun canCapture(requiredBytes: Long): Boolean {
        return captureAvailability(requiredBytes) is HistoryCaptureAvailability.Ready
    }

    fun captureAvailability(requiredBytes: Long): HistoryCaptureAvailability {
        if (visibleFlags.busy) return HistoryCaptureAvailability.HistoryBusy
        if (requiredBytes < 0L || !BitmapMemoryBudget.canAllocate(requiredBytes)) {
            return HistoryCaptureAvailability.MemoryRejected(requiredBytes)
        }
        return HistoryCaptureAvailability.Ready
    }

    suspend fun admitAdoptedSnapshot(
        snapshot: EditorHistorySnapshot,
        clearRedo: Boolean,
        foregroundReserveBytes: Long
    ): HistoryAdmissionOutcome {
        checkMainOwner()
        if (!awaitIdle()) {
            snapshot.recycleBitmaps()
            return HistoryAdmissionOutcome.NotRetained(
                HistoryAdmissionNotRetainedReason.Closed,
                visibleFlags,
            )
        }
        if (snapshot.coordinatorGeneration != documentGeneration) {
            snapshot.recycleBitmaps()
            return HistoryAdmissionOutcome.NotRetained(
                HistoryAdmissionNotRetainedReason.Superseded,
                visibleFlags,
            )
        }
        val token = beginOperation()
        val generation = documentGeneration
        val discarded = ArrayList<EditorHistoryEntry>()
        if (clearRedo) {
            discarded += redo
            redo = ArrayDeque()
            discardRam(discarded)
            publishState()
        }
        var retained = false
        var storageMovement = HistoryStorageMovement.None
        var notRetainedReason: HistoryAdmissionNotRetainedReason? = null
        try {
            val snapshotBytes = snapshot.bitmapBytes()
            val spill = spillUntilFits(snapshotBytes, emptySet(), token, generation)
            if (spill.moved) {
                storageMovement = storageMovement.plus(HistoryStorageMovement.ExistingEntriesSpilled)
            }
            notRetainedReason = spill.terminalReason
            if (notRetainedReason == null && isOperationCurrent(token, generation) &&
                (fitsWith(snapshotBytes) || snapshotBytes > historyRamBudgetBytes())
            ) {
                val admittedEntry = EditorHistoryEntry(documentGeneration = generation, hotSnapshot = snapshot)
                if (snapshotBytes > historyRamBudgetBytes()) {
                    when (val published = storage.publish(admittedEntry, snapshot)) {
                        is HistoryPublishResult.Success -> {
                            if (isOperationCurrent(token, generation)) {
                                admittedEntry.coldPayload = published.payload
                                admittedEntry.hotSnapshot = null
                                admittedEntry.payloadState = HistoryPayloadState.Cold
                                snapshot.transferDiagnosticsToCoordinator()
                                snapshot.recycleBitmaps()
                                storageMovement = storageMovement.plus(HistoryStorageMovement.AdmittedSnapshotStoredCold)
                            } else {
                                settleColdPayloads(listOf(published.payload))
                                notRetainedReason = HistoryAdmissionNotRetainedReason.Superseded
                            }
                        }
                        HistoryPublishResult.InsufficientStorage -> {
                            notRetainedReason = if (isOperationCurrent(token, generation)) {
                                HistoryAdmissionNotRetainedReason.StorageBudget
                            } else {
                                HistoryAdmissionNotRetainedReason.Superseded
                            }
                        }
                        is HistoryPublishResult.Failed -> {
                            notRetainedReason = if (isOperationCurrent(token, generation)) {
                                HistoryAdmissionNotRetainedReason.StorageUnavailable
                            } else {
                                HistoryAdmissionNotRetainedReason.Superseded
                            }
                        }
                    }
                }
                if (notRetainedReason == null && (admittedEntry.hotSnapshot == null || fitsWith(snapshotBytes))) {
                    snapshot.transferDiagnosticsToCoordinator()
                    undo.addLast(admittedEntry)
                    retained = true
                    publishState()
                    trimEntryCount(discarded)
                    val rebalance = rebalanceHot(foregroundReserveBytes, token, generation)
                    if (rebalance.moved) {
                        val rebalanceMovement = when {
                            admittedEntry.id in rebalance.spilledEntryIds &&
                                rebalance.spilledEntryIds.any { it != admittedEntry.id } ->
                                HistoryStorageMovement.Both
                            admittedEntry.id in rebalance.spilledEntryIds ->
                                HistoryStorageMovement.AdmittedSnapshotStoredCold
                            else -> HistoryStorageMovement.ExistingEntriesSpilled
                        }
                        storageMovement = storageMovement.plus(rebalanceMovement)
                    }
                    if (isOperationCurrent(token, generation)) {
                        if (trimDiskBudget(discarded, emptySet(), token, generation) == TrimResult.Superseded) {
                            notRetainedReason = HistoryAdmissionNotRetainedReason.Superseded
                            retained = false
                        }
                    } else {
                        notRetainedReason = HistoryAdmissionNotRetainedReason.Superseded
                        retained = false
                    }
                    if (retained && !undo.contains(admittedEntry)) {
                        notRetainedReason = HistoryAdmissionNotRetainedReason.StorageBudget
                        retained = false
                    }
                }
            } else if (notRetainedReason == null) {
                notRetainedReason = if (!isOperationCurrent(token, generation)) {
                    HistoryAdmissionNotRetainedReason.Superseded
                } else {
                    HistoryAdmissionNotRetainedReason.MemoryCapacity
                }
            }
            settleColdEntries(discarded)
            val flags = visibleFlags.copy(busy = false)
            return if (retained) {
                HistoryAdmissionOutcome.Retained(flags, storageMovement)
            } else {
                HistoryAdmissionOutcome.NotRetained(
                    notRetainedReason ?: HistoryAdmissionNotRetainedReason.Superseded,
                    flags,
                )
            }
        } finally {
            if (!retained && !snapshot.resourcesReleased) snapshot.recycleBitmaps()
            finishOperation(token)
        }
    }

    /**
     * Admit the current-state snapshot to the destination history branch during navigation.
     * The result is structured because a rejected publication is not a memory rejection.
     */
    suspend fun admitOversizedCurrentSnapshot(
        snapshot: EditorHistorySnapshot,
        token: Long,
        generation: String
    ): CurrentNavigationSnapshotAdmission {
        checkMainOwner()
        if (snapshot.coordinatorGeneration != generation || !isOperationCurrent(token, generation)) {
            snapshot.recycleBitmaps()
            return CurrentNavigationSnapshotAdmission.NotRetained(
                HistoryNavigationNotCompletedReason.Superseded,
            )
        }
        val snapshotBytes = snapshot.bitmapBytes()
        if (snapshotBytes <= historyRamBudgetBytes()) {
            return CurrentNavigationSnapshotAdmission.Hot(
                EditorHistoryEntry(documentGeneration = generation, hotSnapshot = snapshot),
            )
        }
        val entry = EditorHistoryEntry(documentGeneration = generation, hotSnapshot = snapshot)
        diagnosticOperationKind = TrackerSession.HistoryOperationKind.DirectToCold
        publishState()
        val published = storage.publish(entry, snapshot)
        if (!isOperationCurrent(token, generation)) {
            if (published is HistoryPublishResult.Success) settleColdPayloads(listOf(published.payload))
            if (!snapshot.resourcesReleased) snapshot.recycleBitmaps()
            return CurrentNavigationSnapshotAdmission.NotRetained(
                HistoryNavigationNotCompletedReason.Superseded,
            )
        }
        when (published) {
            HistoryPublishResult.InsufficientStorage -> {
                if (!snapshot.resourcesReleased) snapshot.recycleBitmaps()
                return CurrentNavigationSnapshotAdmission.NotRetained(
                    HistoryNavigationNotCompletedReason.CurrentStateStorageBudget,
                )
            }
            is HistoryPublishResult.Failed -> {
                if (!snapshot.resourcesReleased) snapshot.recycleBitmaps()
                return CurrentNavigationSnapshotAdmission.NotRetained(
                    HistoryNavigationNotCompletedReason.CurrentStateStorageUnavailable,
                )
            }
            is HistoryPublishResult.Success -> Unit
        }
        entry.coldPayload = (published as HistoryPublishResult.Success).payload
        entry.hotSnapshot = null
        entry.payloadState = HistoryPayloadState.Cold
        snapshot.transferDiagnosticsToCoordinator()
        snapshot.recycleBitmaps()
        return CurrentNavigationSnapshotAdmission.Cold(entry)
    }

    suspend fun clearRedoAfterAdoptedEdit(): HistoryFlags {
        checkMainOwner()
        if (!awaitIdle()) return visibleFlags
        val token = beginOperation()
        val discarded = redo.toList()
        redo = ArrayDeque()
        discardRam(discarded)
        publishState()
        return try {
            settleColdEntries(discarded)
            visibleFlags.copy(busy = false)
        } finally {
            finishOperation(token)
        }
    }

    suspend fun navigate(
        undoDirection: Boolean,
        expectedTargetId: String? = null,
        currentCaptureBytes: Long,
        captureCurrent: suspend (HistorySnapshotStorage, String) -> EditorHistorySnapshot?,
        materialize: suspend (EditorHistorySnapshot, (EditorHistorySnapshot) -> Unit) -> EditorHistorySnapshot?,
        preflightSelectionMasks: suspend (HistorySelectionMaskPreflight) -> AutoCloseable? = {
            NoSelectionMaskAdmission
        },
        adopt: (EditorHistorySnapshot) -> Boolean
    ): HistoryNavigationResult {
        checkMainOwner()
        if (closed) return HistoryNavigationResult.NotCompleted(HistoryNavigationNotCompletedReason.Closed, visibleFlags.copy(busy = false))
        if (operationBusy) return HistoryNavigationResult.Busy(visibleFlags)
        val source = if (undoDirection) undo else redo
        if (source.isEmpty()) return HistoryNavigationResult.Unavailable(visibleFlags)
        if (expectedTargetId != null && source.last().id != expectedTargetId) return HistoryNavigationResult.Unavailable(visibleFlags)
        val token = beginOperation()
        val generation = documentGeneration
        val target = source.last()
        val loadedFromDisk = target.hotSnapshot == null
        var loaded: EditorHistorySnapshot? = null
        var materialized: EditorHistorySnapshot? = null
        var currentSnapshot: EditorHistorySnapshot? = null
        var currentEntry: EditorHistoryEntry? = null
        var adopted = false
        var maintenanceReserve = 0L
        val discarded = ArrayList<EditorHistoryEntry>()
        diagnosticOperationKind = if (loadedFromDisk) TrackerSession.HistoryOperationKind.Loading else TrackerSession.HistoryOperationKind.Adopting
        diagnosticNavigationDirection = if (undoDirection) "undo" else "redo"
        diagnosticProtectedTargetId = target.id
        publishState()
        try {
            target.payloadState = if (loadedFromDisk) HistoryPayloadState.Loading else HistoryPayloadState.Adopting
            if (loadedFromDisk) {
                val required = storage.requiredBitmapBytes(target, generation)
                    ?: return HistoryNavigationResult.NotCompleted(
                        HistoryNavigationNotCompletedReason.TargetUnavailable,
                        visibleFlags.copy(busy = false),
                    )
                val transientRequired = BitmapMemoryBudget.saturatingAdd(required, currentCaptureBytes)
                val targetSpill = spillUntilFits(currentCaptureBytes, setOf(target.id), token, generation)
                targetSpill.terminalReason?.let { reason ->
                    return HistoryNavigationResult.NotCompleted(
                        navigationReason(reason, currentState = false),
                        visibleFlags.copy(busy = false),
                    )
                }
                if (!BitmapMemoryBudget.canAllocate(transientRequired)) {
                    return HistoryNavigationResult.MemoryRejected(transientRequired, visibleFlags.copy(busy = false))
                }
            }
            loaded = if (loadedFromDisk) {
                storage.loadWithSelectionMaskPreflight(target, generation, preflightSelectionMasks) { decoded ->
                    loaded = decoded
                    if (isOperationCurrent(token, generation)) {
                        activeColdLoadDecodedBytes = decoded.bitmapBytes()
                        publishState()
                    }
                }
            } else target.hotSnapshot
            val baseTarget = loaded ?: return HistoryNavigationResult.NotCompleted(
                HistoryNavigationNotCompletedReason.TargetCorrupt,
                visibleFlags.copy(busy = false),
            )
            if (!isOperationCurrent(token, generation) || source.lastOrNull() !== target) {
                return HistoryNavigationResult.NotCompleted(
                    HistoryNavigationNotCompletedReason.Superseded,
                    visibleFlags.copy(busy = false),
                )
            }
            materialized = materialize(baseTarget) { materialized = it }
                ?: return HistoryNavigationResult.NotCompleted(
                    HistoryNavigationNotCompletedReason.MaterializationFailed,
                    visibleFlags.copy(busy = false),
                )
            if (!isOperationCurrent(token, generation) || source.lastOrNull() !== target) {
                return HistoryNavigationResult.NotCompleted(
                    HistoryNavigationNotCompletedReason.Superseded,
                    visibleFlags.copy(busy = false),
                )
            }
            val targetForAdoption = checkNotNull(materialized)
            diagnosticOperationKind = TrackerSession.HistoryOperationKind.Adopting
            publishState()
            currentSnapshot = captureCurrent(targetForAdoption.storage, targetForAdoption.baseContentToken)
                ?: return HistoryNavigationResult.NotCompleted(
                    HistoryNavigationNotCompletedReason.CurrentStateCaptureFailed,
                    visibleFlags.copy(busy = false),
                )

            val targetResidentBytes = target.hotResidentBytes()
            val projectedRequired = (currentSnapshot!!.bitmapBytes() - targetResidentBytes).coerceAtLeast(0L)
            val protected = setOf(target.id)
            val spill = spillUntilFits(projectedRequired, protected, token, generation)
            spill.terminalReason?.let { reason ->
                return HistoryNavigationResult.NotCompleted(
                    navigationReason(reason, currentState = true),
                    visibleFlags.copy(busy = false),
                )
            }
            var storageMovement = if (spill.moved) {
                HistoryStorageMovement.ExistingEntriesSpilled
            } else {
                HistoryStorageMovement.None
            }

            // Handle oversized current snapshot: publish directly to cold storage
            val currentSnapshotBytes = currentSnapshot!!.bitmapBytes()
            if (currentSnapshotBytes > historyRamBudgetBytes()) {
                when (val currentAdmission = admitOversizedCurrentSnapshot(currentSnapshot, token, generation)) {
                    is CurrentNavigationSnapshotAdmission.Hot -> {
                        currentEntry = currentAdmission.entry
                    }
                    is CurrentNavigationSnapshotAdmission.Cold -> {
                        currentEntry = currentAdmission.entry
                        currentSnapshot = null // ownership transferred to cold storage
                        storageMovement = storageMovement.plus(HistoryStorageMovement.AdmittedSnapshotStoredCold)
                    }
                    is CurrentNavigationSnapshotAdmission.NotRetained -> {
                        return HistoryNavigationResult.NotCompleted(
                            currentAdmission.reason,
                            visibleFlags.copy(busy = false),
                        )
                    }
                }
            }

            // For oversized current snapshot, we only need target bytes to fit in hot (which they do, since target was hot)
            // For normal case, check fitsAfterReplacingTarget
            val fitsBudget = if (currentEntry != null) {
                // Oversized current went to cold; target was hot so hot budget fits
                true
            } else {
                fitsAfterReplacingTarget(currentSnapshot!!, target)
            }

            if (!isOperationCurrent(token, generation) || source.lastOrNull() !== target) {
                currentEntry?.let { entry ->
                    entry.coldPayload?.let { settleColdPayloads(listOf(it)) }
                }
                return HistoryNavigationResult.NotCompleted(
                    HistoryNavigationNotCompletedReason.Superseded,
                    visibleFlags.copy(busy = false),
                )
            }
            if (!fitsBudget) {
                val requiredBytes = BitmapMemoryBudget.saturatingAdd(
                    (hotBytes() - target.hotResidentBytes()).coerceAtLeast(0L),
                    currentSnapshot?.bitmapBytes() ?: 0L,
                )
                return HistoryNavigationResult.MemoryRejected(
                    requiredBytes,
                    visibleFlags.copy(busy = false),
                )
            }

            val nextUndo = ArrayDeque(undo)
            val nextRedo = ArrayDeque(redo)
            val nextSource = if (undoDirection) nextUndo else nextRedo
            val nextDestination = if (undoDirection) nextRedo else nextUndo
            check(nextSource.removeLast().id == target.id)
            val destinationEntry = currentEntry ?: EditorHistoryEntry(documentGeneration = generation, hotSnapshot = currentSnapshot!!)
            nextDestination.addLast(destinationEntry)
            val foregroundReserve = maxOf(targetForAdoption.bitmapBytes(), destinationEntry.hotResidentBytes())
            maintenanceReserve = foregroundReserve

            if (loadedFromDisk) {
                // The decoded target is about to become UI-owned; do not retain it as cold-load RAM.
                activeColdLoadDecodedBytes = 0L
            } else {
                diagnosticTransferredHotEntryId = target.id
            }
            publishState()
            adopted = adopt(targetForAdoption)
            if (!adopted) {
                diagnosticTransferredHotEntryId = null
                if (loadedFromDisk) activeColdLoadDecodedBytes = targetForAdoption.bitmapBytes()
                publishState()
                return HistoryNavigationResult.NotCompleted(
                    HistoryNavigationNotCompletedReason.AdoptionRejected,
                    visibleFlags.copy(busy = false),
                )
            }
            // UI reconciliation has acquired the destination edges; the materialized local
            // snapshot must no longer contribute a second ledger owner.
            targetForAdoption.releaseLocalDiagnostics()
            // The current snapshot now moves into the coordinator's hot/cold entry.  Drop its
            // local handles before publishing the destination aggregate.
            if (currentEntry == null) currentSnapshot?.transferDiagnosticsToCoordinator()
            currentSnapshot = null
            undo = nextUndo
            redo = nextRedo
            target.hotSnapshot = null
            target.payloadState = HistoryPayloadState.Discarded
            diagnosticTransferredHotEntryId = null
            discarded += target
            publishState()
            trimEntryCount(discarded)
            settleColdEntries(discarded)
            return HistoryNavigationResult.Adopted(visibleFlags.copy(busy = false), storageMovement)
        } catch (failure: BitmapAllocationRejectedException) {
            return HistoryNavigationResult.MemoryRejected(failure.requiredBytes, visibleFlags.copy(busy = false))
        } finally {
            // This value represents only a decoded object still owned by this navigation.
            if (loadedFromDisk && isOperationCurrent(token, generation)) {
                activeColdLoadDecodedBytes = 0L
                publishState()
            }
            if (!adopted) {
                val cleanup = Collections.newSetFromMap(IdentityHashMap<EditorHistorySnapshot, Boolean>())
                currentSnapshot?.let(cleanup::add)
                materialized?.takeIf { it !== target.hotSnapshot }?.let(cleanup::add)
                loaded?.takeIf { loadedFromDisk }?.let(cleanup::add)
                cleanup.forEach { if (!it.resourcesReleased) it.recycleBitmaps() }
                // Clean up oversized current entry if it was created but not adopted
                currentEntry?.let { entry ->
                    entry.coldPayload?.let { settleColdPayloads(listOf(it)) }
                }
                if (target.payloadState != HistoryPayloadState.Discarded) {
                    target.payloadState = if (target.hotSnapshot != null) HistoryPayloadState.Hot else HistoryPayloadState.Cold
                }
            }
            finishOperation(token)
            if (adopted) scheduleMaintenance(maintenanceReserve)
        }
    }

    private fun navigationReason(
        reason: HistoryAdmissionNotRetainedReason,
        currentState: Boolean,
    ): HistoryNavigationNotCompletedReason = when (reason) {
        HistoryAdmissionNotRetainedReason.StorageUnavailable ->
            if (currentState) HistoryNavigationNotCompletedReason.CurrentStateStorageUnavailable
            else HistoryNavigationNotCompletedReason.StorageUnavailable
        HistoryAdmissionNotRetainedReason.StorageBudget ->
            if (currentState) HistoryNavigationNotCompletedReason.CurrentStateStorageBudget
            else HistoryNavigationNotCompletedReason.StorageBudget
        HistoryAdmissionNotRetainedReason.Superseded -> HistoryNavigationNotCompletedReason.Superseded
        HistoryAdmissionNotRetainedReason.Closed -> HistoryNavigationNotCompletedReason.Closed
        HistoryAdmissionNotRetainedReason.MemoryCapacity ->
            error("navigation spill cannot report MemoryCapacity")
    }

    internal data class RecoverResult(
        val reclaimedRamBytes: Long,
        val diskBudgetSatisfied: Boolean,
        val superseded: Boolean
    )

    suspend fun recover(strong: Boolean, protectedEntryId: String? = null): RecoverResult {
        checkMainOwner()
        if (operationBusy) return RecoverResult(0L, true, superseded = true)
        val token = beginOperation()
        val generation = documentGeneration
        diagnosticOperationKind = TrackerSession.HistoryOperationKind.Recovery
        diagnosticRecoveryMode = if (strong) "strong" else "automatic"
        diagnosticOperationPhase = "idle"
        diagnosticProtectedTargetId = protectedEntryId
        publishState()
        val discarded = ArrayList<EditorHistoryEntry>()
        var reclaimed = 0L
        var diskBudgetSatisfied = true
        var superseded = false
        try {
            val protectedSet = buildSet {
                protectedEntryId?.let { add(it) }
                undo.lastOrNull()?.id?.let { add(it) }
                redo.lastOrNull()?.id?.let { add(it) }
            }
            if (strong && !superseded) {
                val newRedo = ArrayDeque<EditorHistoryEntry>()
                for (entry in redo) {
                    if (!isOperationCurrent(token, generation)) {
                        superseded = true
                        break
                    }
                    if (entry.id in protectedSet || entry.payloadState != HistoryPayloadState.Hot || !entry.canColdSpill()) {
                        newRedo.add(entry)
                        continue
                    }
                    // Unprotected hot entry — attempt spill
                    val hotBefore = entry.hotResidentBytes()
                    val result = spillEntry(entry, token, generation)
                    when (result) {
                        SpillResult.Success -> {
                            newRedo.add(entry)
                            reclaimed = BitmapMemoryBudget.saturatingAdd(reclaimed, hotBefore)
                        }
                        is SpillResult.CurrentFailure -> {
                            // Current-operation publish failure: intentionally discard.
                            // spillEntry already restored payloadState to Hot on publish-null,
                            // or threw. Settle from this operation.
                            if (!isOperationCurrent(token, generation)) {
                                superseded = true
                                break
                            }
                            val actuallyReleased = settleSingleDiscarded(entry, discarded)
                            reclaimed = BitmapMemoryBudget.saturatingAdd(reclaimed, actuallyReleased)
                        }
                        SpillResult.Superseded -> {
                            // Stale or closed during spill — replacement/close owns settlement.
                            superseded = true
                            break
                        }
                    }
                }
                // Commit rebuilt redo only while current
                if (!superseded && isOperationCurrent(token, generation)) {
                    redo = newRedo
                    publishState()
                }
            }
            // Spill remaining candidates (undo except tip, remaining redo hot)
            // Only strong recovery may discard failed spills; non-strong preserves them as Hot.
            if (!superseded) {
                val candidates = buildList {
                    addAll(redo.filter { it.canColdSpill() && it.id !in protectedSet })
                    addAll(undo.toList().dropLast(1).filter { it.canColdSpill() && it.id !in protectedSet })
                }
                for (entry in candidates) {
                    if (!isOperationCurrent(token, generation)) {
                        superseded = true
                        break
                    }
                    val hotBefore = entry.hotResidentBytes()
                    val result = spillEntry(entry, token, generation)
                    when (result) {
                        SpillResult.Success -> {
                            reclaimed = BitmapMemoryBudget.saturatingAdd(reclaimed, hotBefore)
                        }
                        is SpillResult.CurrentFailure -> {
                            // Non-strong or candidate spill: entry already restored to Hot by spillEntry.
                            // Do NOT discard — preserve as Hot. Stop spilling on transient failure.
                            if (strong) {
                                // Strong recovery: current-operation publish failure intentionally evicts.
                                if (!isOperationCurrent(token, generation)) {
                                    superseded = true
                                    break
                                }
                                // Remove from owning deque first
                                if (!undo.remove(entry)) redo.remove(entry)
                                val actuallyReleased = settleSingleDiscarded(entry, discarded)
                                reclaimed = BitmapMemoryBudget.saturatingAdd(reclaimed, actuallyReleased)
                            } else {
                                // Automatic recovery: transient disk failure preserves entry as Hot; stop.
                                break
                            }
                        }
                        SpillResult.Superseded -> {
                            superseded = true
                            break
                        }
                    }
                }
            }
            // Disk trim and delete only if still current
            if (!superseded && isOperationCurrent(token, generation)) {
                val trimResult = trimDiskBudget(discarded, protectedSet, token, generation)
                if (trimResult == TrimResult.Superseded) {
                    superseded = true
                } else {
                    // Delete evicted entries on IO; track any failures as new debt.
                    settleColdEntries(discarded)
                    // Recheck after suspending deletion — replacement/close may supersede during IO
                    if (!isOperationCurrent(token, generation) || closed) {
                        superseded = true
                    } else {
                        // Physical disk budget includes retained stack cold bytes + remaining debt.
                        diskBudgetSatisfied = totalColdDiskBytes() <= historyDiskBudgetBytes()
                    }
                }
            }
            return RecoverResult(reclaimed, diskBudgetSatisfied, superseded)
        } finally {
            finishOperation(token)
        }
    }

    /** Recycles resident hot snapshot if unreleased, clears state, adds to discarded list, returns actually-released bytes. */
    private fun settleSingleDiscarded(entry: EditorHistoryEntry, discarded: MutableList<EditorHistoryEntry>): Long {
        val released = if (entry.hotSnapshot != null && !entry.hotSnapshot!!.resourcesReleased) {
            val bytes = entry.hotSnapshot!!.bitmapBytes()
            entry.hotSnapshot!!.recycleBitmaps()
            bytes
        } else 0L
        entry.hotSnapshot = null
        entry.payloadState = HistoryPayloadState.Discarded
        discarded.add(entry)
        return released
    }

    /**
     * Storage-pressure request boundary. The process-global pressure controller
     * ASKS history to free disk space through this method; it receives neither
     * paths nor payloads and never touches editor_history_v3 itself.
     *
     * Authority rules mirror [recover]:
     *  - exclusive coordinator operation (never overlaps navigation/admission),
     *    so entries captured by an in-flight navigation are structurally
     *    unreachable;
     *  - the current Undo/Redo tips stay protected; only non-tip cold payloads
     *    and pending deletion debt are eligible;
     *  - every mutation after a suspension revalidates operationToken and
     *    documentGeneration; superseded work settles stale truthfully and never
     *    mutates the replacing document's state;
     *  - physically-failed deletes remain represented as deletion debt and
     *    their bytes are NEVER reported as reclaimed.
     *
     * Returns best-effort CONFIRMED-freed disk bytes (>= 0), or null when the
     * coordinator cannot attempt reclamation right now (busy/closed). The value
     * is reporting only; the caller's capacity re-read stays authoritative.
     */
    suspend fun reclaimHistoryForStoragePressure(): Long? {
        checkMainOwner()
        if (closed || operationBusy) return null
        val token = beginOperation()
        val generation = documentGeneration
        diagnosticOperationKind = TrackerSession.HistoryOperationKind.Recovery
        diagnosticRecoveryMode = "storage-pressure"
        diagnosticOperationPhase = "trimming"
        publishState()
        try {
            val protectedSet = buildSet {
                undo.lastOrNull()?.id?.let { add(it) }
                redo.lastOrNull()?.id?.let { add(it) }
            }
            // First settle previously-failed deletions; confirmed successes
            // report their actual bytes exactly once as debt rows are removed.
            var reclaimed = retryPendingDeletions()
            if (!isOperationCurrent(token, generation)) return reclaimed
            // Eligible disk consumers: non-tip COLD payloads. Hot entries own no
            // disk bytes; spilling them under disk pressure would only grow the
            // footprint. Metadata-only snapshots intentionally stay hot.
            val candidates = (undo + redo).filter {
                it.payloadState == HistoryPayloadState.Cold &&
                    it.coldPayload != null &&
                    it.id !in protectedSet
            }
            if (candidates.isEmpty()) return reclaimed
            // Deque mutation runs only while still current: there is no
            // suspension between the revalidation above and this block.
            val discarded = ArrayList<EditorHistoryEntry>(candidates.size)
            candidates.forEach { entry ->
                undo.remove(entry)
                redo.remove(entry)
                entry.payloadState = HistoryPayloadState.Discarded
                discarded.add(entry)
            }
            publishState()
            val result = settleColdEntries(discarded)
            // Revalidate after the IO suspension: a replacement/close owns any
            // further settlement; we only report what was actually confirmed.
            val failedDirectories = HashSet<File>().also { dirs ->
                result.failedPayloads.forEach { dirs.add(it.directory) }
            }
            candidates.forEach { entry ->
                val payload = entry.coldPayload ?: return@forEach
                if (payload.directory !in failedDirectories) {
                    reclaimed = BitmapMemoryBudget.saturatingAdd(reclaimed, payload.bytes)
                }
            }
            return reclaimed
        } finally {
            finishOperation(token)
        }
    }

    /** Pure read-only inspection for tests: pending physical-deletion debt bytes. */
    internal fun pendingDeletionDebtBytesForTest(): Long =
        pendingDeletionDebt.fold(0L) { sum, payload -> BitmapMemoryBudget.saturatingAdd(sum, payload.bytes) }

    /** Pure read-only inspection for tests: directories owned by pending deletion debt. */
    internal fun pendingDeletionDebtDirectoriesForTest(): Set<File> =
        pendingDeletionDebt.mapTo(HashSet()) { it.directory }

    fun replaceDocument() {
        checkMainOwner()
        operationToken += 1L
        activeColdLoadDecodedBytes = 0L
        diagnosticOperationKind = TrackerSession.HistoryOperationKind.Maintenance
        diagnosticNavigationDirection = null
        diagnosticProtectedTargetId = null
        diagnosticRecoveryMode = null
        diagnosticOperationPhase = null
        diagnosticTransferredHotEntryId = null
        val oldGeneration = documentGeneration
        val oldEntries = (undo + redo).toList()
        val pendingOperations = operationCompletions.values.toList()
        undo = ArrayDeque()
        redo = ArrayDeque()
        documentGeneration = UUID.randomUUID().toString()
        val newGeneration = documentGeneration
        storage.registerSession(newGeneration)
        oldEntries.forEach { entry ->
            if (entry.payloadState == HistoryPayloadState.Spilling) {
                entry.payloadState = HistoryPayloadState.Discarded
            } else {
                entry.hotSnapshot?.recycleBitmaps()
                entry.hotSnapshot = null
                entry.payloadState = HistoryPayloadState.Discarded
            }
        }
        // Wake any waiter still on the previous signal, then install a fresh incomplete signal.
        val prevSignal = idleSignal
        val handoffSignal = CompletableDeferred<Unit>()
        idleSignal = handoffSignal
        prevSignal.complete(Unit)
        operationBusy = true
        publishState()
        val settlementDeferred = CompletableDeferred<Unit>()
        detachedGenerationSettlements.add(settlementDeferred)
        settlementScope.launch {
            try {
                withContext(NonCancellable) {
                    runCatching { storage.initializeSession(newGeneration) }
                    pendingOperations.forEach { it.await() }
                    // Delete old entries on IO; failures tracked as debt after session check.
                    val entryResult = runCatching { storage.deleteEntries(oldEntries) }.getOrNull()
                    // Continue holding for session-level check in finally.
                    runCatching { storage.unregisterSession(oldGeneration) }
                    val sessionOk = runCatching { storage.deleteSession(oldGeneration) }.getOrDefault(false)
                    when {
                        sessionOk ->
                            // Whole session confirmed absent — clear all debt for this generation.
                            clearGenerationDebt(oldGeneration)
                        entryResult != null ->
                            // Session deletion failed — record individual failures as debt.
                            recordDeletionDebt(entryResult.failedPayloads)
                        else -> {
                            // Backend-level batch exception: every old payload still
                            // on disk becomes debt, so supersession can never leave
                            // a debtless orphan of the superseded generation.
                            val stillPresent = oldEntries.mapNotNull { entry ->
                                entry.coldPayload?.takeIf { it.directory.exists() }
                            }
                            recordDeletionDebt(stillPresent)
                        }
                    }
                }
                // Busy release: only when this replacement still owns the handoff.
                if (isMainOwner() && !closed && documentGeneration == newGeneration) {
                    operationBusy = false
                    publishState()
                }
            } finally {
                // Always wake waiters, even if we were superseded.
                handoffSignal.complete(Unit)
                // Remove our record immediately; do not wait for next replacement.
                detachedGenerationSettlements.remove(settlementDeferred)
                settlementDeferred.complete(Unit)
            }
        }
    }

    fun close() {
        checkMainOwner()
        if (closed) return
        closed = true
        operationToken += 1L
        val generation = documentGeneration
        val entries = (undo + redo).toList()
        val pendingOperations = operationCompletions.values.toList()
        val pendingDetached = detachedGenerationSettlements.toList()
        detachedGenerationSettlements.clear()
        undo = ArrayDeque()
        redo = ArrayDeque()
        entries.forEach { entry ->
            if (entry.payloadState == HistoryPayloadState.Spilling) entry.payloadState = HistoryPayloadState.Discarded
            else entry.hotSnapshot?.recycleBitmaps()
            entry.hotSnapshot = null
            entry.payloadState = HistoryPayloadState.Discarded
        }
        // Wake the current signal's waiters, then install a pre-completed signal.
        // Waiters will loop, detect closed == true, and return false from awaitIdle().
        operationBusy = true
        val prevSignal = idleSignal
        val finalSignal = CompletableDeferred<Unit>()
        finalSignal.complete(Unit)
        idleSignal = finalSignal
        prevSignal.complete(Unit)
        publishState()
        val deferred = CompletableDeferred<Unit>()
        closeSettlement = deferred
        settlementScope.launch(NonCancellable) {
            try {
                pendingOperations.forEach { it.await() }
                pendingDetached.forEach { it.await() }
            } finally {
                // Every step wrapped individually: failure must not skip remaining steps.
                // Entry deletion uses exception-safe storage methods; all per-payload failures
                // are caught and returned in DeletionResult.
                runCatching { settleColdEntries(entries) }
                runCatching { retryPendingDeletions() }
                // Current-generation unregister always attempted.
                runCatching { storage.unregisterSession(generation) }
                runCatching {
                    if (storage.deleteSession(generation)) {
                        clearGenerationDebt(generation)
                    }
                }
                // closeSettlement MUST complete in every case.
                deferred.complete(Unit)
            }
        }
    }

    private suspend fun spillUntilFits(
        requiredBytes: Long,
        protected: Set<String>,
        token: Long,
        generation: String,
    ): SpillUntilFitsResult {
        var moved = false
        while (isOperationCurrent(token, generation) && !fitsWith(requiredBytes)) {
            val candidate = (undo + redo).firstOrNull {
                it.id !in protected && it.canColdSpill()
            } ?: return SpillUntilFitsResult(moved)
            when (val result = spillEntry(candidate, token, generation)) {
                SpillResult.Success -> Unit
                is SpillResult.CurrentFailure -> return SpillUntilFitsResult(moved, result.reason)
                SpillResult.Superseded ->
                    return SpillUntilFitsResult(moved, HistoryAdmissionNotRetainedReason.Superseded)
            }
            moved = true
        }
        return SpillUntilFitsResult(
            moved = moved,
            terminalReason = if (isOperationCurrent(token, generation)) null
            else HistoryAdmissionNotRetainedReason.Superseded,
        )
    }

    private suspend fun rebalanceHot(reserveBytes: Long, token: Long, generation: String): RebalanceResult {
        val target = (historyRamBudgetBytes() - reserveBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
        val spilledEntryIds = LinkedHashSet<String>()
        while (isOperationCurrent(token, generation) && hotBytes() > target) {
            val recentUndo = undo.lastOrNull()?.id
            val recentRedo = redo.lastOrNull()?.id
            val candidate = (undo + redo).firstOrNull {
                it.canColdSpill() && it.id != recentUndo && it.id != recentRedo
            } ?: (undo + redo).firstOrNull { it.canColdSpill() }
            if (candidate == null) break
            when (spillEntry(candidate, token, generation)) {
                SpillResult.Success -> spilledEntryIds += candidate.id
                is SpillResult.CurrentFailure, SpillResult.Superseded -> break
            }
        }
        return RebalanceResult(spilledEntryIds)
    }

    /** Returns SpillResult.Success when entry is now Cold with confirmed publication.
     *  Returns SpillResult.CurrentFailure when the current operation's publish failed;
     *  caller may discard the entry. Returns SpillResult.Superseded when the operation
     *  is stale/closed; caller must NOT discard or reclaim — replacement/close owns
     *  settlement through its own NonCancellable finalizer, which deletes the whole
     *  superseded session directory, so a caller cancellation at this boundary can
     *  never strand the just-published payload as an untracked orphan. */
    private suspend fun spillEntry(entry: EditorHistoryEntry, token: Long, generation: String): SpillResult {
        val snapshot = entry.hotSnapshot ?: return if (entry.coldPayload != null) {
            SpillResult.Success
        } else {
            SpillResult.CurrentFailure(HistoryAdmissionNotRetainedReason.StorageUnavailable)
        }
        // Metadata-only entries own no bitmap payload. Keep their negligible
        // state hot; otherwise we would publish a manifest the cold loader
        // correctly rejects for containing zero bitmap payloads.
        if (snapshot.storage == HistorySnapshotStorage.MetadataOnly) {
            return SpillResult.CurrentFailure(HistoryAdmissionNotRetainedReason.StorageUnavailable)
        }
        if (entry.payloadState != HistoryPayloadState.Hot) {
            return SpillResult.CurrentFailure(HistoryAdmissionNotRetainedReason.StorageUnavailable)
        }
        entry.payloadState = HistoryPayloadState.Spilling
        if (diagnosticRecoveryMode != null) {
            diagnosticOperationKind = TrackerSession.HistoryOperationKind.Recovery
            diagnosticOperationPhase = "spilling"
        } else {
            diagnosticOperationKind = TrackerSession.HistoryOperationKind.Spilling
        }
        publishState()
        val published = try {
            storage.publish(entry, snapshot)
        } catch (t: Throwable) {
            if (!isOperationCurrent(token, generation) || entry.payloadState == HistoryPayloadState.Discarded) {
                // Superseded: replacement/close already recycled & settled in its own finalizer.
                // Only release if they didn't (unreleased snapshot).
                if (!snapshot.resourcesReleased) snapshot.recycleBitmaps()
                entry.hotSnapshot = null
                return SpillResult.Superseded
            } else {
                entry.payloadState = HistoryPayloadState.Hot
                throw t
            }
        }
        if (!isOperationCurrent(token, generation) || entry.payloadState == HistoryPayloadState.Discarded) {
            // Stale or closed during publication — replacement/close owns settlement.
            if (published is HistoryPublishResult.Success) storage.delete(published.payload)
            if (!snapshot.resourcesReleased) snapshot.recycleBitmaps()
            entry.hotSnapshot = null
            return SpillResult.Superseded
        }
        when (published) {
            HistoryPublishResult.InsufficientStorage -> {
                entry.payloadState = HistoryPayloadState.Hot
                return SpillResult.CurrentFailure(HistoryAdmissionNotRetainedReason.StorageBudget)
            }
            is HistoryPublishResult.Failed -> {
                entry.payloadState = HistoryPayloadState.Hot
                return SpillResult.CurrentFailure(HistoryAdmissionNotRetainedReason.StorageUnavailable)
            }
            is HistoryPublishResult.Success -> Unit
        }
        val successfulPublication = published as HistoryPublishResult.Success
        entry.coldPayload = successfulPublication.payload
        entry.hotSnapshot = null
        entry.payloadState = HistoryPayloadState.Cold
        snapshot.recycleBitmaps()
        publishState()
        return SpillResult.Success
    }

    private fun trimEntryCount(discarded: MutableList<EditorHistoryEntry>) {
        while (undo.size > HISTORY_ENTRY_MAX) discarded += undo.removeFirst()
        while (redo.size > HISTORY_ENTRY_MAX) discarded += redo.removeFirst()
        discardRam(discarded.filter { it.payloadState != HistoryPayloadState.Discarded })
        publishState()
    }

    /**
     * Evict unprotected cold entries from stacks until the physical disk budget is satisfied.
     * Starting total includes both retained stack cold bytes AND pending deletion debt.
     * Rechecks token/generation after every suspend boundary.
     * Never mutates a superseding document's deques.
     */
    private suspend fun trimDiskBudget(
        discarded: MutableList<EditorHistoryEntry>,
        protectedSet: Set<String> = emptySet(),
        token: Long,
        generation: String
    ): TrimResult {
        var total = totalColdDiskBytes()
        val budget = historyDiskBudgetBytes()
        if (total <= budget) return TrimResult.Satisfied
        if (diagnosticRecoveryMode != null) {
            diagnosticOperationKind = TrackerSession.HistoryOperationKind.Recovery
            diagnosticOperationPhase = "trimming"
        } else {
            diagnosticOperationKind = TrackerSession.HistoryOperationKind.Trimming
        }
        publishState()
        retryPendingDeletions()
        // Recheck after suspension — replacement may have superseded.
        if (!isOperationCurrent(token, generation)) return TrimResult.Superseded
        total = totalColdDiskBytes()
        if (total <= budget) return TrimResult.Satisfied
        val coldEntries = (undo + redo).filter { it.coldPayload != null && it.id !in protectedSet }
        for (entry in coldEntries) {
            if (total <= budget) break
            // Recheck immediately before deque mutation.
            if (!isOperationCurrent(token, generation)) return TrimResult.Superseded
            total = (total - (entry.coldPayload?.bytes ?: 0L)).coerceAtLeast(0L)
            undo.remove(entry)
            redo.remove(entry)
            entry.payloadState = HistoryPayloadState.Discarded
            discarded += entry
        }
        if (!isOperationCurrent(token, generation)) return TrimResult.Superseded
        publishState()
        return TrimResult.Satisfied
    }

    private fun scheduleMaintenance(foregroundReserveBytes: Long) {
        scope.launch {
            if (operationBusy) return@launch
            val token = beginOperation()
            diagnosticOperationKind = TrackerSession.HistoryOperationKind.Maintenance
            publishState()
            val generation = documentGeneration
            val discarded = ArrayList<EditorHistoryEntry>()
            try {
                rebalanceHot(foregroundReserveBytes, token, generation)
                if (!isOperationCurrent(token, generation)) return@launch
                val protectedSet = buildSet {
                    undo.lastOrNull()?.id?.let { add(it) }
                    redo.lastOrNull()?.id?.let { add(it) }
                }
                val trimResult = trimDiskBudget(discarded, protectedSet, token, generation)
                if (trimResult == TrimResult.Superseded) return@launch
                // Delete evicted entries on IO; track any failures as new debt.
                settleColdEntries(discarded)
                // Recheck after suspending deletion — old operation must not act on replacement generation
                if (isOperationCurrent(token, generation)) publishState()
            } finally {
                finishOperation(token)
            }
        }
    }

    private fun discardRam(entries: Collection<EditorHistoryEntry>) {
        entries.forEach { entry ->
            if (entry.payloadState != HistoryPayloadState.Spilling) {
                entry.hotSnapshot?.recycleBitmaps()
                entry.hotSnapshot = null
            }
            entry.payloadState = HistoryPayloadState.Discarded
        }
    }

    private fun fitsWith(requiredBytes: Long): Boolean =
        requiredBytes <= historyRamBudgetBytes() &&
            BitmapMemoryBudget.saturatingAdd(hotBytes(), requiredBytes) <= historyRamBudgetBytes()

    private fun fitsAfterReplacingTarget(snapshot: EditorHistorySnapshot, target: EditorHistoryEntry): Boolean {
        val withoutTarget = (hotBytes() - target.hotResidentBytes()).coerceAtLeast(0L)
        return BitmapMemoryBudget.saturatingAdd(withoutTarget, snapshot.bitmapBytes()) <= historyRamBudgetBytes()
    }

    private fun hotBytes(): Long {
        var total = 0L
        undo.forEach { total = BitmapMemoryBudget.saturatingAdd(total, it.hotResidentBytes()) }
        redo.forEach { total = BitmapMemoryBudget.saturatingAdd(total, it.hotResidentBytes()) }
        return total
    }

    private fun beginOperation(): Long {
        checkMainOwner()
        operationBusy = true
        // Complete the previous signal so its waiters loop to the new one.
        val prevSignal = idleSignal
        idleSignal = CompletableDeferred()
        prevSignal.complete(Unit)
        val token = ++operationToken
        operationCompletions[token] = CompletableDeferred()
        publishState()
        return token
    }

    private fun finishOperation(token: Long) {
        operationCompletions.remove(token)?.complete(Unit)
        if (operationToken == token) {
            operationBusy = false
            diagnosticOperationKind = TrackerSession.HistoryOperationKind.Idle
            diagnosticNavigationDirection = null
            diagnosticProtectedTargetId = null
            diagnosticRecoveryMode = null
            diagnosticOperationPhase = null
            diagnosticTransferredHotEntryId = null
            activeColdLoadDecodedBytes = 0L
            publishState()
            idleSignal.complete(Unit)
        }
    }

    private suspend fun awaitIdle(): Boolean {
        while (operationBusy && !closed) idleSignal.await()
        return !closed
    }

    private fun isOperationCurrent(token: Long, generation: String): Boolean =
        operationToken == token && documentGeneration == generation

    private fun publishState() {
        visibleFlags = HistoryFlags(undo.isNotEmpty(), redo.isNotEmpty(), operationBusy)
        onFlagsChanged?.invoke()
        reportHistoryMetrics()
    }

    fun refreshDiagnostics() {
        checkMainOwner()
        publishState()
    }

    private fun reportHistoryMetrics() {
        tracker ?: return
        try {
            val excludedHot = diagnosticTransferredHotEntryId?.let { id ->
                (undo + redo).firstOrNull { it.id == id }?.hotResidentBytes() ?: 0L
            } ?: 0L
            val hot = (hotBytes() - excludedHot).coerceAtLeast(0L)
            var entryCount = 0
            var coldBytes = 0L
            undo.forEach {
                if (it.hotSnapshot != null) entryCount++
                coldBytes = BitmapMemoryBudget.saturatingAdd(coldBytes, it.coldPayload?.bytes ?: 0L)
            }
            redo.forEach {
                if (it.hotSnapshot != null) entryCount++
                coldBytes = BitmapMemoryBudget.saturatingAdd(coldBytes, it.coldPayload?.bytes ?: 0L)
            }
            val debtBytes = pendingDeletionDebt.fold(0L) { sum, p -> BitmapMemoryBudget.saturatingAdd(sum, p.bytes) }
            val coldDecoded = activeColdLoadDecodedBytes
            val isSpilling = undo.any { it.payloadState == HistoryPayloadState.Spilling } ||
                redo.any { it.payloadState == HistoryPayloadState.Spilling }
            val isAdopting = undo.any { it.payloadState == HistoryPayloadState.Adopting } ||
                redo.any { it.payloadState == HistoryPayloadState.Adopting }
            val isLoading = undo.any { it.payloadState == HistoryPayloadState.Loading } ||
                redo.any { it.payloadState == HistoryPayloadState.Loading }
            tracker.publishHistoryMetrics(TrackerSession.HistoryMetricsSnapshot(
                editorInstanceId = tracker.editorInstanceId,
                coordinatorGeneration = documentGeneration,
                hotEntryCount = entryCount,
                hotResidentBytes = hot,
                retainedColdCompressedBytes = coldBytes,
                pendingDeletionDebtBytes = debtBytes,
                activeColdLoadDecodedBytes = coldDecoded,
                operationActive = operationBusy,
                loading = isLoading,
                spilling = isSpilling,
                adopting = isAdopting,
                protectedTargetId = diagnosticProtectedTargetId,
                timestamp = System.currentTimeMillis(),
                operationKind = diagnosticOperationKind,
                navigationDirection = diagnosticNavigationDirection,
                operationToken = operationToken,
                recoveryMode = diagnosticRecoveryMode,
                operationPhase = diagnosticOperationPhase
            ))
        } catch (_: Throwable) {
        }
    }

    private fun checkMainOwner() = check(isMainOwner()) { "history coordinator must be called on Main" }
    private fun isMainOwner(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private companion object {
        const val HISTORY_ENTRY_MAX = 5
    }
}

internal class EditorHistoryStorage(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    internal val fullDecodeObserverForTest: ((File) -> Unit)? = null,
    private val syncDirectories: Boolean = true,
    internal val publishFailureObserverForTest: ((Throwable) -> Unit)? = null,
    private val enforceDiskSpace: Boolean = true,
) : HistoryStorageBackend {
    private val root = File(context.filesDir, "editor_history_v3")

    override fun registerSession(sessionId: String) {
        activeSessions += sessionId
    }

    override fun unregisterSession(sessionId: String) {
        activeSessions -= sessionId
    }

    override suspend fun initializeSession(sessionId: String): Unit = withContext(ioDispatcher) {
        root.mkdirs()
        sessionDirectory(sessionId).mkdirs()
        root.listFiles()?.filter(File::isDirectory)?.forEach { directory ->
            val id = directory.name.removePrefix(SESSION_PREFIX)
            when {
                !directory.name.startsWith(SESSION_PREFIX) || id !in activeSessions -> directory.deleteRecursively()
                id == sessionId -> directory.listFiles()?.filter {
                    it.name.startsWith(STAGING_PREFIX) || it.isDirectory && !it.isCompleteHistoryDirectory()
                }?.forEach(File::deleteRecursively)
            }
        }
        Unit
    }

    override suspend fun publish(
        entry: EditorHistoryEntry,
        snapshot: EditorHistorySnapshot,
    ): HistoryPublishResult = withContext(ioDispatcher) {
        val session = sessionDirectory(entry.documentGeneration)
        if (!isSafeId(entry.id) || !isSafeId(entry.documentGeneration)) {
            return@withContext HistoryPublishResult.Failed(
                IllegalArgumentException("invalid history publication identity"),
            )
        }
        session.mkdirs()
        val diskReserve = 8L * 1024L * 1024L
        if (enforceDiskSpace && root.usableSpace < BitmapMemoryBudget.saturatingAdd(snapshot.bitmapBytes(), diskReserve)) {
            return@withContext HistoryPublishResult.InsufficientStorage
        }
        val staging = File(session, "$STAGING_PREFIX${entry.id}-${UUID.randomUUID()}")
        val published = File(session, "$ENTRY_PREFIX${entry.id}")
        try {
            if (!staging.mkdirs()) throw HistoryStorageWriteFailure("history staging directory creation failed")
            val manifest = snapshotManifest(entry, snapshot, staging)
            writeSynced(File(staging, MANIFEST), manifest.toString().toByteArray(Charsets.UTF_8))
            writeSynced(File(staging, COMPLETE), "ok".toByteArray(Charsets.US_ASCII))
            if (syncDirectories) syncDirectory(staging)
            if (published.exists()) published.deleteRecursively()
            if (!staging.renameTo(published)) throw HistoryStorageWriteFailure("history publication rename failed")
            if (syncDirectories) syncDirectory(session)
            HistoryPublishResult.Success(
                ColdHistoryPayload(published, published.directoryBytes(), snapshot.bitmapBytes(), entry.documentGeneration),
            )
        } catch (ce: CancellationException) {
            staging.deleteRecursively()
            published.takeIf { isOwnedEntryDirectory(it, entry.documentGeneration, entry.id) }?.deleteRecursively()
            throw ce
        } catch (failure: Throwable) {
            if (failure is HistoryStorageInvariantViolation) throw failure
            publishFailureObserverForTest?.invoke(failure)
            staging.deleteRecursively()
            published.takeIf { isOwnedEntryDirectory(it, entry.documentGeneration, entry.id) }?.deleteRecursively()
            HistoryPublishResult.Failed(failure)
        }
    }

    override suspend fun load(
        entry: EditorHistoryEntry,
        expectedGeneration: String,
        register: (EditorHistorySnapshot) -> Unit
    ): EditorHistorySnapshot? =
        loadWithSelectionMaskPreflight(
            entry,
            expectedGeneration,
            { NoSelectionMaskAdmission },
            register,
        )

    private data class ColdManifestValidation(
        val json: JSONObject,
        val requiredBytes: Long,
        val maskBytes: Long,
        val layerCount: Int,
    )

    /** Schema and payload validation shared by sizing and full-load paths. */
    private fun validateColdManifest(
        entry: EditorHistoryEntry,
        expectedGeneration: String,
    ): ColdManifestValidation {
        val payload = checkNotNull(entry.coldPayload)
        val directory = payload.directory
        check(entry.documentGeneration == expectedGeneration)
        check(isOwnedEntryDirectory(directory, expectedGeneration, entry.id))
        check(directory.isCompleteHistoryDirectory())
        val manifestFile = File(directory, MANIFEST)
        val completeFile = File(directory, COMPLETE)
        check(manifestFile.isFile && manifestFile.canonicalFile.parentFile == directory.canonicalFile)
        check(completeFile.isFile && completeFile.canonicalFile.parentFile == directory.canonicalFile)
        check(completeFile.readText(Charsets.US_ASCII) == "ok")
        val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
        check(json.getInt("version") == VERSION)
        check(json.getString("entryId") == entry.id)
        check(json.getString("documentGeneration") == expectedGeneration)
        check(json.getString("storage") == HistorySnapshotStorage.Exact.name)

        val specs = json.getJSONArray("bitmaps")
        val maxPayloads = 2 + BitmapMemoryBudget.maxSelectionMaskLayers()
        check(specs.length() in 1..maxPayloads)
        val keys = HashSet<String>()
        val names = HashSet<String>()
        val dimensions = HashMap<String, Pair<Int, Int>>()
        var requiredBytes = 0L
        for (index in 0 until specs.length()) {
            val spec = specs.getJSONObject(index)
            val key = spec.getString("key")
            val name = spec.getString("file")
            val width = spec.getInt("width")
            val height = spec.getInt("height")
            check(key.isNotBlank() && keys.add(key))
            check(names.add(name) && isSafePayloadName(name))
            check(width > 0 && height > 0)
            check(spec.getString("config") == Bitmap.Config.ARGB_8888.name)
            val file = File(directory, name)
            check(file.isFile && file.canonicalFile.parentFile == directory.canonicalFile)
            val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            check(bounds.outWidth == width && bounds.outHeight == height)
            dimensions[key] = width to height
            requiredBytes = BitmapMemoryBudget.saturatingAdd(requiredBytes, BitmapMemoryBudget.bytes(width, height))
        }
        val actualPayloadNames = directory.listFiles().orEmpty()
            .filter { it.name != MANIFEST && it.name != COMPLETE }
            .mapTo(HashSet()) { it.name }
        check(actualPayloadNames == names)

        val metadata = json.getJSONObject("metadata")
        val referenced = HashSet<String>()
        fun addOptionalKey(name: String) {
            if (!metadata.isNull(name)) metadata.optString(name).takeIf(String::isNotBlank)?.let { referenced.add(it) }
        }
        addOptionalKey("previewKey")
        addOptionalKey("originalKey")
        val previewSize = metadata.optString("previewKey").takeIf(String::isNotBlank)?.let { checkNotNull(dimensions[it]) }
        val originalSize = metadata.optString("originalKey").takeIf(String::isNotBlank)?.let { checkNotNull(dimensions[it]) }
        val referenceSize = originalSize ?: previewSize
        check(referenceSize != null)
        if (previewSize != null && originalSize != null) check(previewSize == originalSize)
        val layers = metadata.getJSONArray("layers")
        check(layers.length() <= BitmapMemoryBudget.maxSelectionMaskLayers())
        val layerIds = HashSet<String>()
        val maskKeys = HashSet<String>()
        var maskBytes = 0L
        for (index in 0 until layers.length()) {
            val layer = layers.getJSONObject(index)
            check(layerIds.add(layer.getString("id")))
            val key = layer.getString("bitmapKey")
            check(maskKeys.add(key) && referenced.add(key))
            val size = checkNotNull(dimensions[key])
            check(size == referenceSize)
            maskBytes = BitmapMemoryBudget.saturatingAdd(maskBytes, BitmapMemoryBudget.bytes(size.first, size.second))
        }
        val activeId = if (metadata.isNull("activeSelectionLayerId")) null else metadata.getString("activeSelectionLayerId").takeIf(String::isNotBlank)
        check(activeId == null || activeId in layerIds)
        check(referenced == keys)
        return ColdManifestValidation(json, requiredBytes, maskBytes, layers.length())
    }

    override suspend fun loadWithSelectionMaskPreflight(
        entry: EditorHistoryEntry,
        expectedGeneration: String,
        preflight: suspend (HistorySelectionMaskPreflight) -> AutoCloseable?,
        register: (EditorHistorySnapshot) -> Unit,
    ): EditorHistorySnapshot? = withContext(ioDispatcher) {
        val payload = entry.coldPayload ?: return@withContext null
        val directory = payload.directory
        if (entry.documentGeneration != expectedGeneration || !isOwnedEntryDirectory(directory, expectedGeneration, entry.id) || !directory.isCompleteHistoryDirectory()) return@withContext null
        val owned = ArrayList<Bitmap>()
        var candidateAdmission: AutoCloseable? = null
        var requiredBytes = 0L
        try {
            val sharedValidation = validateColdManifest(entry, expectedGeneration)
            requiredBytes = sharedValidation.requiredBytes
            val json = sharedValidation.json
            val bitmapSpecs = json.getJSONArray("bitmaps")
            val validatedFiles = HashMap<String, File>()
            for (i in 0 until bitmapSpecs.length()) {
                val spec = bitmapSpecs.getJSONObject(i)
                val key = spec.getString("key")
                val fileName = spec.getString("file")
                validatedFiles[key] = File(directory, fileName)
            }
            candidateAdmission =
                preflight(
                    HistorySelectionMaskPreflight(
                        uniqueMaskBytes = sharedValidation.maskBytes,
                        layerCount = sharedValidation.layerCount,
                    )
                ) ?: throw BitmapAllocationRejectedException(sharedValidation.maskBytes)
            if (!BitmapMemoryBudget.canAllocate(requiredBytes)) throw BitmapAllocationRejectedException(requiredBytes)
            val bitmaps = HashMap<String, Bitmap>()
            for (i in 0 until bitmapSpecs.length()) {
                val spec = bitmapSpecs.getJSONObject(i)
                val file = checkNotNull(validatedFiles[spec.getString("key")])
                fullDecodeObserverForTest?.invoke(file)
                val bitmap = decodeMutableBitmapOrThrow(file.absolutePath)
                owned += bitmap
                check(bitmap.width == spec.getInt("width") && bitmap.height == spec.getInt("height") && bitmap.config == Bitmap.Config.ARGB_8888)
                check(bitmaps.put(spec.getString("key"), bitmap) == null)
            }
            val snapshot = parseSnapshot(json, bitmaps)
            snapshot.candidateAdmission = candidateAdmission
            try {
                register(snapshot)
            } catch (failure: Throwable) {
                snapshot.candidateAdmission?.close()
                snapshot.candidateAdmission = null
                throw failure
            }
            candidateAdmission = null
            owned.clear()
            snapshot
        } catch (ce: CancellationException) {
            candidateAdmission?.close()
            owned.forEach { if (!it.isRecycled) it.recycle() }
            throw ce
        } catch (failure: BitmapAllocationRejectedException) {
            candidateAdmission?.close()
            owned.forEach { if (!it.isRecycled) it.recycle() }
            throw failure
        } catch (_: OutOfMemoryError) {
            candidateAdmission?.close()
            owned.forEach { if (!it.isRecycled) it.recycle() }
            throw BitmapAllocationRejectedException(requiredBytes)
        } catch (_: Throwable) {
            candidateAdmission?.close()
            owned.forEach { if (!it.isRecycled) it.recycle() }
            null
        }
    }

    override suspend fun requiredBitmapBytes(entry: EditorHistoryEntry, expectedGeneration: String): Long? = withContext(ioDispatcher) {
        runCatching { validateColdManifest(entry, expectedGeneration).requiredBytes }.getOrNull()
    }

    /** Returns the result of batch cold-payload deletion.
     *  Every entry is attempted independently — a single failure does not skip remaining entries.
     *  Ownership-validation failure returns the payload as failed without deleting the path.
     *  Per-payload exceptions are caught and returned as deletion failures; all entries are attempted. */
    override suspend fun deleteEntries(entries: Collection<EditorHistoryEntry>): DeletionResult = withContext(ioDispatcher) {
        val failed = ArrayList<ColdHistoryPayload>()
        for (entry in entries) {
            val payload = entry.coldPayload ?: continue
            try {
                if (!deleteInternal(payload)) failed.add(payload)
            } catch (_: Throwable) {
                failed.add(payload)
            }
        }
        DeletionResult(failed.isEmpty(), failed)
    }

    override suspend fun delete(entry: EditorHistoryEntry): Boolean = withContext(ioDispatcher) {
        val payload = entry.coldPayload ?: return@withContext true
        try { deleteInternal(payload) } catch (_: Throwable) { false }
    }

    override suspend fun delete(payload: ColdHistoryPayload): Boolean = withContext(ioDispatcher) {
        try { deleteInternal(payload) } catch (_: Throwable) { false }
    }

    /** Returns the result of batch cold-payload deletion by payload reference.
     *  Per-payload exceptions are caught and returned as deletion failures; all payloads are attempted. */
    override suspend fun deletePayloads(payloads: Collection<ColdHistoryPayload>): DeletionResult = withContext(ioDispatcher) {
        val failed = ArrayList<ColdHistoryPayload>()
        for (payload in payloads) {
            try {
                if (!deleteInternal(payload)) failed.add(payload)
            } catch (_: Throwable) {
                failed.add(payload)
            }
        }
        DeletionResult(failed.isEmpty(), failed)
    }

    /** Returns true when the session directory is confirmed deleted or absent. */
    override suspend fun deleteSession(sessionId: String): Boolean = withContext(ioDispatcher) {
        val dir = sessionDirectory(sessionId).takeIf { isOwnedSessionDirectory(it, sessionId) } ?: return@withContext true
        dir.deleteRecursively() || !dir.exists()
    }

    private fun snapshotManifest(entry: EditorHistoryEntry, snapshot: EditorHistorySnapshot, staging: File): JSONObject {
        val bitmapKeys = IdentityHashMap<Bitmap, String>()
        val bitmapSpecs = JSONArray()
        fun persist(bitmap: Bitmap?): String? {
            bitmap ?: return null
            bitmapKeys[bitmap]?.let { return it }
            if (snapshot.storage != HistorySnapshotStorage.Exact) {
                throw HistoryStorageInvariantViolation("exact history publication requires bitmap storage")
            }
            val key = "bitmap-${bitmapKeys.size}"
            val fileName = "$key.png"
            FileOutputStream(File(staging, fileName)).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw HistoryStorageWriteFailure("history bitmap compression failed")
                }
                output.fd.sync()
            }
            bitmapKeys[bitmap] = key
            bitmapSpecs.put(JSONObject().apply {
                put("key", key)
                put("file", fileName)
                put("width", bitmap.width)
                put("height", bitmap.height)
                put("config", Bitmap.Config.ARGB_8888.name)
            })
            return key
        }
        val metadata = JSONObject().apply {
            put("params", snapshot.params.toJsonObject())
            put("correctionEngine", snapshot.correctionEngine.name)
            put("requestedRoute", snapshot.requestedRoute?.name ?: JSONObject.NULL)
            put("previewEngine", snapshot.previewEngine?.name ?: JSONObject.NULL)
            put("previewRoute", snapshot.previewRoute?.name ?: JSONObject.NULL)
            put("previewResultClass", snapshot.previewResultClass?.name ?: JSONObject.NULL)
            put("fallbackReason", snapshot.fallbackReason?.name ?: JSONObject.NULL)
            put("renderDecision", snapshot.renderDecision?.name ?: JSONObject.NULL)
            put("algorithmVersion", snapshot.algorithmVersion ?: JSONObject.NULL)
            put("algorithmContracts", snapshot.algorithmContracts.toJson())
            put("baseProvenance", snapshot.baseProvenance.toJson())
            put(
                "renderParticipation",
                JSONObject().apply {
                    put("model", snapshot.renderParticipation.model)
                    put("rule", snapshot.renderParticipation.rule)
                    put("manual", snapshot.renderParticipation.manual)
                },
            )
            put("noiseEngine", snapshot.noiseEngine.name)
            put("detailEngine", snapshot.detailEngine.name)
            put("toneEngine", snapshot.toneEngine.name)
            put("hazeEngine", snapshot.hazeEngine.name)
            put("baseBitmapDirty", snapshot.baseBitmapDirty)
            put("baseContentToken", snapshot.baseContentToken)
            put("previewKey", persist(snapshot.previewBitmap))
            put("originalKey", persist(snapshot.originalPreviewBitmap))
            put("presetLook", snapshot.presetLook?.let(::presetToJson) ?: JSONObject.NULL)
            put("cropState", snapshot.cropState.toJsonObject())
            put("activeSelectionLayerId", snapshot.activeSelectionLayerId ?: JSONObject.NULL)
            put("paintMode", snapshot.selectionPaintSettings.mode.name)
            put("paintSize", snapshot.selectionPaintSettings.sizePx)
            put("paintFeather", snapshot.selectionPaintSettings.feather)
            put("paintStrength", snapshot.selectionPaintSettings.strength)
            put("showSelectionOverlay", snapshot.showSelectionOverlay)
            put("flareGuardRuntimeStatus", snapshot.flareGuardRuntimeStatus ?: JSONObject.NULL)
            put("quickEffects", JSONArray(snapshot.activeQuickEffects.map { "${it.kind.name}:${it.strength.name}" }))
            put("layers", JSONArray().apply {
                snapshot.selectionLayers.forEach { layer ->
                    put(JSONObject().apply {
                        put("id", layer.id)
                        put("name", layer.name)
                        put("kind", layer.kind.name)
                        put("bitmapKey", persist(layer.bitmap))
                        put("enabled", layer.enabled)
                        put("inverted", layer.inverted)
                        put("opacity", layer.opacity)
                        put("localParams", layer.localParams.toJsonObject())
                    })
                }
            })
        }
        return JSONObject().apply {
            put("version", VERSION)
            put("entryId", entry.id)
            put("documentGeneration", entry.documentGeneration)
            put("storage", snapshot.storage.name)
            put("bitmaps", bitmapSpecs)
            put("metadata", metadata)
        }
    }

    private fun parseSnapshot(rootJson: JSONObject, bitmaps: Map<String, Bitmap>): EditorHistorySnapshot {
        val storage = HistorySnapshotStorage.valueOf(rootJson.getString("storage"))
        val json = rootJson.getJSONObject("metadata")
        fun nullableString(key: String): String? = if (json.isNull(key)) null else json.getString(key).takeIf(String::isNotBlank)
        fun bitmap(key: String): Bitmap? = nullableString(key)?.let { bitmapKey -> checkNotNull(bitmaps[bitmapKey]) }
        val referencedBitmapKeys = HashSet<String>()
        nullableString("previewKey")?.let(referencedBitmapKeys::add)
        nullableString("originalKey")?.let(referencedBitmapKeys::add)
        val layerIds = HashSet<String>()
        val layers = json.getJSONArray("layers").let { array ->
            List(array.length()) { index ->
                val layer = array.getJSONObject(index)
                val id = layer.getString("id")
                check(id.isNotBlank() && layerIds.add(id))
                val bitmapKey = layer.getString("bitmapKey")
                referencedBitmapKeys.add(bitmapKey)
                SelectionLayer(
                    id = id,
                    name = layer.getString("name"),
                    kind = enumValueStrict<SelectionLayerKind>(layer.getString("kind")),
                    bitmap = checkNotNull(bitmaps[bitmapKey]),
                    enabled = layer.getBoolean("enabled"),
                    inverted = layer.getBoolean("inverted"),
                    opacity = layer.getDouble("opacity").toFloat().also { check(it in 0f..1f) },
                    localParams = checkNotNull(parseEditParamsFromJson(layer.getJSONObject("localParams")))
                )
            }
        }
        val activeLayerId = nullableString("activeSelectionLayerId")
        check(activeLayerId == null || activeLayerId in layerIds)
        val quickEffects = json.getJSONArray("quickEffects").let { array ->
            List(array.length()) { index ->
                val parts = array.getString(index).split(':', limit = 2)
                check(parts.size == 2)
                ActiveQuickEffect(enumValueStrict(parts[0]), enumValueStrict(parts[1]))
            }
        }
        val previewBitmap = bitmap("previewKey")
        val originalBitmap = bitmap("originalKey")
        if (storage == HistorySnapshotStorage.MetadataOnly) {
            check(bitmaps.isEmpty() && layers.isEmpty() && activeLayerId == null)
        } else {
            val reference = originalBitmap ?: previewBitmap
            check(reference != null)
            if (previewBitmap != null && originalBitmap != null) {
                check(previewBitmap.width == originalBitmap.width && previewBitmap.height == originalBitmap.height)
            }
            check(layers.all { it.bitmap.width == reference.width && it.bitmap.height == reference.height })
            check(referencedBitmapKeys == bitmaps.keys)
        }
        return EditorHistorySnapshot(
            params = checkNotNull(parseEditParamsFromJson(json.getJSONObject("params"))),
            correctionEngine =
                json.optString("correctionEngine", CorrectionEngine.Engine1.name)
                    .let { value ->
                        CorrectionEngine.entries.firstOrNull { it.name == value }
                            ?: CorrectionEngine.Engine1
                    },
            requestedRoute =
                nullableString("requestedRoute")
                    ?.let { runCatching { NativeRenderRoute.valueOf(it) }.getOrNull() },
            previewEngine =
                nullableString("previewEngine")
                    ?.let { runCatching { CorrectionEngine.valueOf(it) }.getOrNull() },
            previewRoute =
                nullableString("previewRoute")
                    ?.let { runCatching { NativeRenderRoute.valueOf(it) }.getOrNull() },
            previewResultClass =
                nullableString("previewResultClass")
                    ?.let { runCatching { PreviewResultClass.valueOf(it) }.getOrNull() },
            fallbackReason =
                nullableString("fallbackReason")
                    ?.let { runCatching { RenderFallbackReason.valueOf(it) }.getOrNull() },
            renderDecision =
                nullableString("renderDecision")
                    ?.let { runCatching { RenderRouteDecision.valueOf(it) }.getOrNull() },
            renderParticipation =
                json.optJSONObject("renderParticipation")?.let {
                    RenderParticipation(
                        model = it.optBoolean("model", false),
                        rule = it.optBoolean("rule", false),
                        manual = it.optBoolean("manual", false),
                    )
                } ?: RenderParticipation(),
            algorithmVersion = nullableString("algorithmVersion"),
            algorithmContracts =
                parseAlgorithmContractSet(
                    json.optJSONObject("algorithmContracts"),
                    nullableString("algorithmVersion"),
                ),
            baseProvenance = parseBaseProvenance(json.optJSONArray("baseProvenance")),
            noiseEngine = enumValueStrict(json.getString("noiseEngine")),
            detailEngine = enumValueStrict(json.getString("detailEngine")),
            toneEngine = enumValueStrict(json.getString("toneEngine")),
            hazeEngine = enumValueStrict(json.getString("hazeEngine")),
            baseBitmapDirty = json.getBoolean("baseBitmapDirty"),
            baseContentToken = json.getString("baseContentToken").also { check(it.isNotBlank()) },
            previewBitmap = previewBitmap,
            originalPreviewBitmap = originalBitmap,
            presetLook = json.optJSONObject("presetLook")?.let(::presetFromJson),
            cropState = checkNotNull(parseCropStateFromJson(json.getJSONObject("cropState"))),
            selectionLayers = layers,
            activeSelectionLayerId = activeLayerId,
            selectionPaintSettings = SelectionPaintSettings(
                mode = enumValueStrict(json.getString("paintMode")),
                sizePx = json.getDouble("paintSize").toFloat().also { check(it.isFinite() && it > 0f) },
                feather = json.getDouble("paintFeather").toFloat().also { check(it in 0f..1f) },
                strength = json.getDouble("paintStrength").toFloat().also { check(it in 0f..1f) }
            ),
            showSelectionOverlay = json.getBoolean("showSelectionOverlay"),
            activeQuickEffects = quickEffects,
            flareGuardRuntimeStatus = nullableString("flareGuardRuntimeStatus"),
            storage = storage
        )
    }

    private fun presetToJson(look: PresetColorLook): JSONObject = JSONObject().apply {
        put("size", look.size)
        put("strength", look.strength)
        put("values", JSONArray(look.values.toList()))
    }

    private fun presetFromJson(json: JSONObject): PresetColorLook {
        val size = json.getInt("size")
        val values = json.getJSONArray("values")
        check(size > 1 && values.length() == size * size * size * 3)
        val strength = json.getDouble("strength").toFloat().also { check(it.isFinite()) }
        return PresetColorLook(size, strength, FloatArray(values.length()) {
            values.getDouble(it).toFloat().also { value -> check(value.isFinite()) }
        })
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }

    /** Returns true when the payload directory is confirmed absent after deletion.
     *  Ownership/canonical-path validation failure returns false — never delete an unowned path. */
    private fun deleteInternal(payload: ColdHistoryPayload): Boolean {
        val dir = payload.directory
        if (!isOwnedDirectory(dir)) return false
        return dir.deleteRecursively() || !dir.exists()
    }

    private fun sessionDirectory(sessionId: String): File = File(root, "$SESSION_PREFIX$sessionId")
    private fun isOwnedDirectory(file: File): Boolean = runCatching { file.canonicalPath.startsWith(root.canonicalPath + File.separator) }.getOrDefault(false)
    private fun isOwnedSessionDirectory(file: File, sessionId: String): Boolean = isSafeId(sessionId) && file.canonicalFile == sessionDirectory(sessionId).canonicalFile
    private fun isOwnedEntryDirectory(file: File, sessionId: String, entryId: String): Boolean =
        isSafeId(sessionId) && isSafeId(entryId) && file.canonicalFile == File(sessionDirectory(sessionId), "$ENTRY_PREFIX$entryId").canonicalFile
    private fun isSafeId(value: String): Boolean = SAFE_ID.matches(value)
    private fun isSafePayloadName(value: String): Boolean = SAFE_PAYLOAD.matches(value)
    private fun File.isCompleteHistoryDirectory(): Boolean = isDirectory && File(this, COMPLETE).isFile && File(this, MANIFEST).isFile
    private fun File.directoryBytes(): Long = walkTopDown().filter(File::isFile).fold(0L) { total, file -> BitmapMemoryBudget.saturatingAdd(total, file.length()) }

    private inline fun <reified T : Enum<T>> enumValueStrict(value: String): T = enumValueOf(value)

private companion object {
        const val VERSION = 3
        const val SESSION_PREFIX = "session-"
        const val ENTRY_PREFIX = "entry-"
        const val STAGING_PREFIX = ".staging-"
        const val MANIFEST = "manifest.json"
        const val COMPLETE = "complete"
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,80}")
        val SAFE_PAYLOAD = Regex("bitmap-[0-9]+\\.png")
        val activeSessions: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    }
}
