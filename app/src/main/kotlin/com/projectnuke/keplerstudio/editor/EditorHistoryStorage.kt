package com.projectnuke.keplerstudio.editor

import android.content.Context
import android.graphics.Bitmap
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal enum class HistorySnapshotStorage { Exact, MetadataOnly }

internal enum class HistoryPayloadState { Hot, Cold, Loading, Spilling, Adopting, Discarded }

internal data class ColdHistoryPayload(
    val directory: File,
    val bytes: Long,
    val decodedBytes: Long
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
}

internal data class HistoryFlags(val canUndo: Boolean, val canRedo: Boolean, val busy: Boolean)

internal data class HistoryAdmissionResult(
    val retained: Boolean,
    val movedToStorage: Boolean,
    val flags: HistoryFlags
)

internal sealed class HistoryNavigationResult {
    data class Adopted(val flags: HistoryFlags, val movedToStorage: Boolean) : HistoryNavigationResult()
    data class Unavailable(val flags: HistoryFlags) : HistoryNavigationResult()
    data class Failed(val flags: HistoryFlags) : HistoryNavigationResult()
    data class MemoryRejected(val requiredBytes: Long, val flags: HistoryFlags) : HistoryNavigationResult()
    data class Busy(val flags: HistoryFlags) : HistoryNavigationResult()
}

internal class EditorHistoryCoordinator(
    context: Context,
    private val scope: CoroutineScope
) {
    private val storage = EditorHistoryStorage(context.applicationContext)
    private var undo = ArrayDeque<EditorHistoryEntry>()
    private var redo = ArrayDeque<EditorHistoryEntry>()
    private var documentGeneration = UUID.randomUUID().toString()
    private var operationToken = 0L
    private val operationCompletions = HashMap<Long, CompletableDeferred<Unit>>()
    private var operationBusy = true
    private var closed = false
    @Volatile private var visibleFlags = HistoryFlags(false, false, true)
    private var idleSignal = CompletableDeferred<Unit>()

    init {
        val initialGeneration = documentGeneration
        storage.registerSession(initialGeneration)
        scope.launch {
            runCatching { storage.initializeSession(initialGeneration) }
            if (isMainOwner() && documentGeneration == initialGeneration && operationBusy) {
                operationBusy = false
                publishState()
                idleSignal.complete(Unit)
            }
        }
    }

    fun flags(): HistoryFlags = visibleFlags
    fun currentGeneration(): String = documentGeneration
    fun navigationTargetId(undoDirection: Boolean): String? = (if (undoDirection) undo else redo).lastOrNull()?.id

    fun canCapture(requiredBytes: Long): Boolean {
        return !visibleFlags.busy && requiredBytes >= 0L && BitmapMemoryBudget.canAllocate(requiredBytes)
    }

    suspend fun admitAdoptedSnapshot(
        snapshot: EditorHistorySnapshot,
        clearRedo: Boolean,
        foregroundReserveBytes: Long
    ): HistoryAdmissionResult {
        checkMainOwner()
        if (!awaitIdle()) {
            snapshot.recycleBitmaps()
            return HistoryAdmissionResult(false, false, visibleFlags)
        }
        if (snapshot.coordinatorGeneration != documentGeneration) {
            snapshot.recycleBitmaps()
            return HistoryAdmissionResult(false, false, visibleFlags)
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
        var moved = false
        try {
            val snapshotBytes = snapshot.bitmapBytes()
            moved = spillUntilFits(snapshotBytes, emptySet(), token, generation)
            if (isOperationCurrent(token, generation) && (fitsWith(snapshotBytes) || snapshotBytes > BitmapMemoryBudget.historyBudgetBytes())) {
                val admittedEntry = EditorHistoryEntry(documentGeneration = generation, hotSnapshot = snapshot)
                if (snapshotBytes > BitmapMemoryBudget.historyBudgetBytes()) {
                    val published = storage.publish(admittedEntry, snapshot)
                    if (published != null && isOperationCurrent(token, generation)) {
                        admittedEntry.coldPayload = published
                        admittedEntry.hotSnapshot = null
                        admittedEntry.payloadState = HistoryPayloadState.Cold
                        snapshot.recycleBitmaps()
                        moved = true
                    } else {
                        published?.let { storage.delete(it) }
                    }
                }
                if (admittedEntry.hotSnapshot == null || fitsWith(snapshotBytes)) {
                undo.addLast(admittedEntry)
                retained = true
                publishState()
                trimEntryCount(discarded)
                moved = rebalanceHot(foregroundReserveBytes, token, generation) || moved
                trimDiskBudget(discarded)
                retained = undo.contains(admittedEntry)
                }
            }
            deleteEntries(discarded)
            return HistoryAdmissionResult(retained, moved, visibleFlags.copy(busy = false))
        } finally {
            if (!retained && !snapshot.resourcesReleased) snapshot.recycleBitmaps()
            finishOperation(token)
        }
    }

    /**
     * Admit an oversized current-state snapshot directly to cold storage during navigation.
     * The snapshot is published to disk, its bitmaps recycled, and a Cold entry is returned.
     * Returns null if publication fails or operation becomes stale.
     */
    suspend fun admitOversizedCurrentSnapshot(
        snapshot: EditorHistorySnapshot,
        token: Long,
        generation: String
    ): EditorHistoryEntry? {
        checkMainOwner()
        if (snapshot.coordinatorGeneration != generation) {
            snapshot.recycleBitmaps()
            return null
        }
        val snapshotBytes = snapshot.bitmapBytes()
        if (snapshotBytes <= BitmapMemoryBudget.historyBudgetBytes()) {
            return null // not oversized
        }
        val entry = EditorHistoryEntry(documentGeneration = generation, hotSnapshot = snapshot)
        val published = storage.publish(entry, snapshot)
        if (published == null || !isOperationCurrent(token, generation)) {
            published?.let { storage.delete(it) }
            if (!snapshot.resourcesReleased) snapshot.recycleBitmaps()
            return null
        }
        entry.coldPayload = published
        entry.hotSnapshot = null
        entry.payloadState = HistoryPayloadState.Cold
        snapshot.recycleBitmaps()
        return entry
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
            deleteEntries(discarded)
            visibleFlags.copy(busy = false)
        } finally {
            finishOperation(token)
        }
    }

    suspend fun navigate(
        undoDirection: Boolean,
        expectedTargetId: String? = null,
        currentCaptureBytes: Long,
        captureCurrent: (HistorySnapshotStorage, String) -> EditorHistorySnapshot?,
        materialize: suspend (EditorHistorySnapshot, (EditorHistorySnapshot) -> Unit) -> EditorHistorySnapshot?,
        adopt: (EditorHistorySnapshot) -> Boolean
    ): HistoryNavigationResult {
        checkMainOwner()
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
        try {
            target.payloadState = if (loadedFromDisk) HistoryPayloadState.Loading else HistoryPayloadState.Adopting
            if (loadedFromDisk) {
                val required = storage.requiredBitmapBytes(target, generation)
                    ?: return HistoryNavigationResult.Failed(visibleFlags.copy(busy = false))
                val transientRequired = BitmapMemoryBudget.saturatingAdd(required, currentCaptureBytes)
                spillUntilFits(currentCaptureBytes, setOf(target.id), token, generation)
                if (!BitmapMemoryBudget.canAllocate(transientRequired)) {
                    return HistoryNavigationResult.MemoryRejected(transientRequired, visibleFlags.copy(busy = false))
                }
            }
            loaded = if (loadedFromDisk) storage.load(target, generation) { loaded = it } else target.hotSnapshot
            val baseTarget = loaded ?: return HistoryNavigationResult.Failed(visibleFlags.copy(busy = false))
            if (!isOperationCurrent(token, generation) || source.lastOrNull() !== target) {
                return HistoryNavigationResult.Failed(visibleFlags.copy(busy = false))
            }
            materialized = materialize(baseTarget) { materialized = it }
                ?: return HistoryNavigationResult.Failed(visibleFlags.copy(busy = false))
            if (!isOperationCurrent(token, generation) || source.lastOrNull() !== target) {
                return HistoryNavigationResult.Failed(visibleFlags.copy(busy = false))
            }
            val targetForAdoption = checkNotNull(materialized)
            currentSnapshot = captureCurrent(targetForAdoption.storage, targetForAdoption.baseContentToken)
                ?: return HistoryNavigationResult.Failed(visibleFlags.copy(busy = false))

            val targetResidentBytes = target.hotResidentBytes()
            val projectedRequired = (currentSnapshot!!.bitmapBytes() - targetResidentBytes).coerceAtLeast(0L)
            val protected = setOf(target.id)
            val moved = spillUntilFits(projectedRequired, protected, token, generation)

            // Handle oversized current snapshot: publish directly to cold storage
            val currentSnapshotBytes = currentSnapshot!!.bitmapBytes()
            if (currentSnapshotBytes > BitmapMemoryBudget.historyBudgetBytes()) {
                currentEntry = admitOversizedCurrentSnapshot(currentSnapshot, token, generation)
                if (currentEntry == null) {
                    return HistoryNavigationResult.Failed(visibleFlags.copy(busy = false))
                }
                currentSnapshot = null // ownership transferred to cold storage
            }

            // For oversized current snapshot, we only need target bytes to fit in hot (which they do, since target was hot)
            // For normal case, check fitsAfterReplacingTarget
            val fitsBudget = if (currentEntry != null) {
                // Oversized current went to cold; target was hot so hot budget fits
                true
            } else {
                fitsAfterReplacingTarget(currentSnapshot!!, target)
            }

            if (!isOperationCurrent(token, generation) || source.lastOrNull() !== target || !fitsBudget) {
                currentEntry?.let { entry ->
                    entry.coldPayload?.let { storage.delete(it) }
                }
                return HistoryNavigationResult.Failed(visibleFlags.copy(busy = false))
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

            adopted = adopt(targetForAdoption)
            if (!adopted) return HistoryNavigationResult.Failed(visibleFlags.copy(busy = false))
            currentSnapshot = null
            undo = nextUndo
            redo = nextRedo
            target.hotSnapshot = null
            target.payloadState = HistoryPayloadState.Discarded
            discarded += target
            publishState()
            trimEntryCount(discarded)
            withContext(NonCancellable) {
                runCatching { deleteEntries(discarded) }
            }
            return HistoryNavigationResult.Adopted(visibleFlags.copy(busy = false), moved)
        } catch (failure: BitmapAllocationRejectedException) {
            return HistoryNavigationResult.MemoryRejected(failure.requiredBytes, visibleFlags.copy(busy = false))
        } finally {
            if (!adopted) {
                val cleanup = Collections.newSetFromMap(IdentityHashMap<EditorHistorySnapshot, Boolean>())
                currentSnapshot?.let(cleanup::add)
                materialized?.takeIf { it !== target.hotSnapshot }?.let(cleanup::add)
                loaded?.takeIf { loadedFromDisk }?.let(cleanup::add)
                cleanup.forEach { if (!it.resourcesReleased) it.recycleBitmaps() }
                // Clean up oversized current entry if it was created but not adopted
                currentEntry?.let { entry ->
                    entry.coldPayload?.let { storage.delete(it) }
                }
                if (target.payloadState != HistoryPayloadState.Discarded) {
                    target.payloadState = if (target.hotSnapshot != null) HistoryPayloadState.Hot else HistoryPayloadState.Cold
                }
            }
            finishOperation(token)
            if (adopted) scheduleMaintenance(maintenanceReserve)
        }
    }

    internal data class RecoverResult(
        val reclaimedRamBytes: Long,
        val diskBudgetSatisfied: Boolean
    )

    suspend fun recover(strong: Boolean, protectedEntryId: String? = null): RecoverResult {
        checkMainOwner()
        if (operationBusy) return RecoverResult(0L, true)
        val token = beginOperation()
        val generation = documentGeneration
        val discarded = ArrayList<EditorHistoryEntry>()
        var reclaimed = 0L
        var diskBudgetSatisfied = true
        try {
            val protectedSet = buildSet {
                protectedEntryId?.let { add(it) }
                // Always protect the most recent entry in each stack
                undo.lastOrNull()?.id?.let { add(it) }
                redo.lastOrNull()?.id?.let { add(it) }
            }
            if (strong) {
                // Single-pass Redo rebuild with authoritative settlement
                val newRedo = ArrayDeque<EditorHistoryEntry>()
                redo.forEach { entry ->
                    if (!isOperationCurrent(token, generation)) {
                        newRedo.add(entry)
                        return@forEach
                    }
                    if (entry.id in protectedSet || entry.payloadState != HistoryPayloadState.Hot) {
                        // Protected or already Cold - keep as-is
                        newRedo.add(entry)
                    } else {
                        // Unprotected hot entry - attempt spill
                        val hotBytesBefore = entry.hotResidentBytes()
                        if (spillEntry(entry, token, generation)) {
                            // Successfully spilled - remains in stack as Cold, hot bytes released
                            newRedo.add(entry)
                            reclaimed = BitmapMemoryBudget.saturatingAdd(reclaimed, hotBytesBefore)
                        } else {
                            // Spill failed (stale operation or publish failed) - settle once and discard
                            settleDiscarded(entry, hotBytesBefore, discarded)
                            reclaimed = BitmapMemoryBudget.saturatingAdd(reclaimed, hotBytesBefore)
                        }
                    }
                }
                redo = newRedo
                publishState()
            }
            val candidates = buildList {
                addAll(redo.filter { it.hotSnapshot != null && it.id !in protectedSet })
                addAll(undo.toList().dropLast(1).filter { it.hotSnapshot != null && it.id !in protectedSet })
            }
            for (entry in candidates) {
                if (!isOperationCurrent(token, generation)) break
                val hotBytesBefore = entry.hotResidentBytes()
                if (spillEntry(entry, token, generation)) {
                    reclaimed = BitmapMemoryBudget.saturatingAdd(reclaimed, hotBytesBefore)
                }
            }
            // trimDiskBudget now returns whether budget was satisfied
            diskBudgetSatisfied = trimDiskBudget(discarded, protectedSet)
            deleteEntries(discarded)
            return RecoverResult(reclaimed, diskBudgetSatisfied)
        } finally {
            finishOperation(token)
        }
    }

    /** Settles an entry being discarded from a stack: recycles hot snapshot, clears state, adds to discarded list once. */
    private fun settleDiscarded(entry: EditorHistoryEntry, hotBytesBefore: Long, discarded: MutableList<EditorHistoryEntry>) {
        // Only recycle if hot snapshot exists and hasn't been released
        if (entry.hotSnapshot != null && !entry.hotSnapshot!!.resourcesReleased) {
            entry.hotSnapshot!!.recycleBitmaps()
            // Only count bytes if we actually released resident resources
            // hotBytesBefore > 0 implies there was something to release
        }
        entry.hotSnapshot = null
        entry.payloadState = HistoryPayloadState.Discarded
        discarded.add(entry)
    }

    fun replaceDocument() {
        checkMainOwner()
        operationToken += 1L
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
        operationBusy = true
        publishState()
        scope.launch {
            runCatching {
                storage.initializeSession(newGeneration)
                pendingOperations.forEach { it.await() }
                storage.deleteEntries(oldEntries)
                storage.unregisterSession(oldGeneration)
                storage.deleteSession(oldGeneration)
            }
            if (isMainOwner() && documentGeneration == newGeneration) {
                operationBusy = false
                publishState()
                idleSignal.complete(Unit)
            }
        }
    }

    fun close() {
        checkMainOwner()
        closed = true
        operationToken += 1L
        val generation = documentGeneration
        val entries = (undo + redo).toList()
        val pendingOperations = operationCompletions.values.toList()
        undo = ArrayDeque()
        redo = ArrayDeque()
        entries.forEach { entry ->
            if (entry.payloadState == HistoryPayloadState.Spilling) entry.payloadState = HistoryPayloadState.Discarded
            else entry.hotSnapshot?.recycleBitmaps()
            entry.hotSnapshot = null
            entry.payloadState = HistoryPayloadState.Discarded
        }
        operationBusy = true
        idleSignal.complete(Unit)
        publishState()
        scope.launch(NonCancellable) {
            pendingOperations.forEach { it.await() }
            storage.deleteEntries(entries)
            storage.unregisterSession(generation)
            storage.deleteSession(generation)
        }
    }

    private suspend fun spillUntilFits(requiredBytes: Long, protected: Set<String>, token: Long, generation: String): Boolean {
        var moved = false
        while (isOperationCurrent(token, generation) && !fitsWith(requiredBytes)) {
            val candidate = (undo + redo).firstOrNull {
                it.id !in protected && it.payloadState == HistoryPayloadState.Hot && it.hotSnapshot != null
            } ?: break
            if (!spillEntry(candidate, token, generation)) break
            moved = true
        }
        return moved
    }

    private suspend fun rebalanceHot(reserveBytes: Long, token: Long, generation: String): Boolean {
        val target = (BitmapMemoryBudget.historyBudgetBytes() - reserveBytes.coerceAtLeast(0L)).coerceAtLeast(0L)
        var moved = false
        while (isOperationCurrent(token, generation) && hotBytes() > target) {
            val recentUndo = undo.lastOrNull()?.id
            val recentRedo = redo.lastOrNull()?.id
            val candidate = (undo + redo).firstOrNull {
                it.payloadState == HistoryPayloadState.Hot && it.hotSnapshot != null && it.id != recentUndo && it.id != recentRedo
            } ?: (undo + redo).firstOrNull { it.payloadState == HistoryPayloadState.Hot && it.hotSnapshot != null }
            if (candidate == null || !spillEntry(candidate, token, generation)) break
            moved = true
        }
        return moved
    }

    private suspend fun spillEntry(entry: EditorHistoryEntry, token: Long, generation: String): Boolean {
        val snapshot = entry.hotSnapshot ?: return entry.coldPayload != null
        if (entry.payloadState != HistoryPayloadState.Hot) return false
        entry.payloadState = HistoryPayloadState.Spilling
        val published = try {
            storage.publish(entry, snapshot)
        } catch (t: Throwable) {
            if (!isOperationCurrent(token, generation) || entry.payloadState == HistoryPayloadState.Discarded) {
                if (!snapshot.resourcesReleased) snapshot.recycleBitmaps()
                entry.hotSnapshot = null
            } else {
                entry.payloadState = HistoryPayloadState.Hot
            }
            throw t
        }
        if (!isOperationCurrent(token, generation) || entry.payloadState == HistoryPayloadState.Discarded) {
            published?.let { storage.delete(it) }
            if (!snapshot.resourcesReleased) snapshot.recycleBitmaps()
            entry.hotSnapshot = null
            return false
        }
        if (published == null) {
            entry.payloadState = HistoryPayloadState.Hot
            return false
        }
        entry.coldPayload = published
        entry.hotSnapshot = null
        entry.payloadState = HistoryPayloadState.Cold
        snapshot.recycleBitmaps()
        publishState()
        return true
    }

    private fun trimEntryCount(discarded: MutableList<EditorHistoryEntry>) {
        while (undo.size > HISTORY_ENTRY_MAX) discarded += undo.removeFirst()
        while (redo.size > HISTORY_ENTRY_MAX) discarded += redo.removeFirst()
        discardRam(discarded.filter { it.payloadState != HistoryPayloadState.Discarded })
        publishState()
    }

    private suspend fun trimDiskBudget(discarded: MutableList<EditorHistoryEntry>, protectedSet: Set<String> = emptySet()): Boolean {
        var total = (undo + redo).fold(0L) { bytes, entry ->
            BitmapMemoryBudget.saturatingAdd(bytes, entry.coldPayload?.bytes ?: 0L)
        }
        val budget = BitmapMemoryBudget.historyDiskBudgetBytes()
        if (total <= budget) return true
        val coldEntries = (undo + redo).filter { it.coldPayload != null && it.id !in protectedSet }
        for (entry in coldEntries) {
            if (total <= budget) break
            total = (total - (entry.coldPayload?.bytes ?: 0L)).coerceAtLeast(0L)
            undo.remove(entry)
            redo.remove(entry)
            entry.payloadState = HistoryPayloadState.Discarded
            discarded += entry
        }
        publishState()
        return total <= budget
    }

    private fun scheduleMaintenance(foregroundReserveBytes: Long) {
        scope.launch {
            if (operationBusy) return@launch
            val token = beginOperation()
            val generation = documentGeneration
            val discarded = ArrayList<EditorHistoryEntry>()
            try {
                rebalanceHot(foregroundReserveBytes, token, generation)
                val protectedSet = buildSet {
                    undo.lastOrNull()?.id?.let { add(it) }
                    redo.lastOrNull()?.id?.let { add(it) }
                }
                trimDiskBudget(discarded, protectedSet)
                deleteEntries(discarded)
            } finally {
                finishOperation(token)
            }
        }
    }

    private suspend fun deleteEntries(entries: Collection<EditorHistoryEntry>) {
        if (entries.isEmpty()) return
        storage.deleteEntries(entries)
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
        requiredBytes <= BitmapMemoryBudget.historyBudgetBytes() &&
            BitmapMemoryBudget.saturatingAdd(hotBytes(), requiredBytes) <= BitmapMemoryBudget.historyBudgetBytes()

    private fun fitsAfterReplacingTarget(snapshot: EditorHistorySnapshot, target: EditorHistoryEntry): Boolean {
        val withoutTarget = (hotBytes() - target.hotResidentBytes()).coerceAtLeast(0L)
        return BitmapMemoryBudget.saturatingAdd(withoutTarget, snapshot.bitmapBytes()) <= BitmapMemoryBudget.historyBudgetBytes()
    }

    private fun hotBytes(): Long = BitmapMemoryBudget.saturatingAdd(
        *(undo + redo).map(EditorHistoryEntry::hotResidentBytes).toLongArray()
    )

    private fun beginOperation(): Long {
        checkMainOwner()
        operationBusy = true
        idleSignal = CompletableDeferred()
        val token = ++operationToken
        operationCompletions[token] = CompletableDeferred()
        publishState()
        return token
    }

    private fun finishOperation(token: Long) {
        operationCompletions.remove(token)?.complete(Unit)
        if (operationToken == token) {
            operationBusy = false
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
    }

    private fun checkMainOwner() = check(isMainOwner()) { "history coordinator must be called on Main" }
    private fun isMainOwner(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    private companion object {
        const val HISTORY_ENTRY_MAX = 5
    }
}

private class EditorHistoryStorage(context: Context) {
    private val root = File(context.filesDir, "editor_history_v3")

    fun registerSession(sessionId: String) {
        activeSessions += sessionId
    }

    fun unregisterSession(sessionId: String) {
        activeSessions -= sessionId
    }

    suspend fun initializeSession(sessionId: String) = withContext(Dispatchers.IO) {
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
    }

    suspend fun publish(entry: EditorHistoryEntry, snapshot: EditorHistorySnapshot): ColdHistoryPayload? = withContext(Dispatchers.IO) {
        val session = sessionDirectory(entry.documentGeneration)
        if (!isSafeId(entry.id) || !isSafeId(entry.documentGeneration)) return@withContext null
        session.mkdirs()
        val diskReserve = 8L * 1024L * 1024L
        if (root.usableSpace < BitmapMemoryBudget.saturatingAdd(snapshot.bitmapBytes(), diskReserve)) return@withContext null
        val staging = File(session, "$STAGING_PREFIX${entry.id}-${UUID.randomUUID()}")
        val published = File(session, "$ENTRY_PREFIX${entry.id}")
        try {
            check(staging.mkdirs())
            val manifest = snapshotManifest(entry, snapshot, staging)
            writeSynced(File(staging, MANIFEST), manifest.toString().toByteArray(Charsets.UTF_8))
            writeSynced(File(staging, COMPLETE), "ok".toByteArray(Charsets.US_ASCII))
            syncDirectory(staging)
            if (published.exists()) published.deleteRecursively()
            check(staging.renameTo(published))
            syncDirectory(session)
            ColdHistoryPayload(published, published.directoryBytes(), snapshot.bitmapBytes())
        } catch (ce: CancellationException) {
            staging.deleteRecursively()
            published.takeIf { isOwnedEntryDirectory(it, entry.documentGeneration, entry.id) }?.deleteRecursively()
            throw ce
        } catch (_: Throwable) {
            staging.deleteRecursively()
            published.takeIf { isOwnedEntryDirectory(it, entry.documentGeneration, entry.id) }?.deleteRecursively()
            null
        }
    }

    suspend fun load(
        entry: EditorHistoryEntry,
        expectedGeneration: String,
        register: (EditorHistorySnapshot) -> Unit
    ): EditorHistorySnapshot? = withContext(Dispatchers.IO) {
        val payload = entry.coldPayload ?: return@withContext null
        val directory = payload.directory
        if (entry.documentGeneration != expectedGeneration || !isOwnedEntryDirectory(directory, expectedGeneration, entry.id) || !directory.isCompleteHistoryDirectory()) return@withContext null
        val owned = ArrayList<Bitmap>()
        var requiredBytes = 0L
        try {
            val manifestFile = File(directory, MANIFEST)
            val completeFile = File(directory, COMPLETE)
            check(manifestFile.canonicalFile.parentFile == directory.canonicalFile)
            check(completeFile.canonicalFile.parentFile == directory.canonicalFile && completeFile.readText(Charsets.US_ASCII) == "ok")
            val json = JSONObject(manifestFile.readText(Charsets.UTF_8))
            check(json.getInt("version") == VERSION)
            check(json.getString("entryId") == entry.id)
            check(json.getString("documentGeneration") == expectedGeneration)
            check(json.getString("storage") in HistorySnapshotStorage.entries.map(Enum<*>::name))
            val bitmapSpecs = json.getJSONArray("bitmaps")
            val keys = HashSet<String>()
            val fileNames = HashSet<String>()
            for (i in 0 until bitmapSpecs.length()) {
                val spec = bitmapSpecs.getJSONObject(i)
                val key = spec.getString("key")
                val fileName = spec.getString("file")
                val width = spec.getInt("width")
                val height = spec.getInt("height")
                check(keys.add(key) && fileNames.add(fileName) && isSafePayloadName(fileName) && width > 0 && height > 0)
                check(spec.getString("config") == Bitmap.Config.ARGB_8888.name)
                val file = File(directory, fileName)
                check(file.isFile && file.canonicalFile.parentFile == directory.canonicalFile)
                requiredBytes = BitmapMemoryBudget.saturatingAdd(requiredBytes, BitmapMemoryBudget.bytes(width, height))
            }
            if (!BitmapMemoryBudget.canAllocate(requiredBytes)) throw BitmapAllocationRejectedException(requiredBytes)
            val bitmaps = HashMap<String, Bitmap>()
            for (i in 0 until bitmapSpecs.length()) {
                val spec = bitmapSpecs.getJSONObject(i)
                val bitmap = decodeMutableBitmapOrThrow(File(directory, spec.getString("file")).absolutePath)
                owned += bitmap
                check(bitmap.width == spec.getInt("width") && bitmap.height == spec.getInt("height") && bitmap.config == Bitmap.Config.ARGB_8888)
                check(bitmaps.put(spec.getString("key"), bitmap) == null)
            }
            val snapshot = parseSnapshot(json, bitmaps)
            register(snapshot)
            owned.clear()
            snapshot
        } catch (ce: CancellationException) {
            owned.forEach { if (!it.isRecycled) it.recycle() }
            throw ce
        } catch (failure: BitmapAllocationRejectedException) {
            owned.forEach { if (!it.isRecycled) it.recycle() }
            throw failure
        } catch (_: OutOfMemoryError) {
            owned.forEach { if (!it.isRecycled) it.recycle() }
            throw BitmapAllocationRejectedException(requiredBytes)
        } catch (_: Throwable) {
            owned.forEach { if (!it.isRecycled) it.recycle() }
            null
        }
    }

    suspend fun requiredBitmapBytes(entry: EditorHistoryEntry, expectedGeneration: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val payload = checkNotNull(entry.coldPayload)
            val directory = payload.directory
            check(entry.documentGeneration == expectedGeneration && isOwnedEntryDirectory(directory, expectedGeneration, entry.id))
            val json = JSONObject(File(directory, MANIFEST).readText(Charsets.UTF_8))
            check(json.getInt("version") == VERSION && json.getString("entryId") == entry.id && json.getString("documentGeneration") == expectedGeneration)
            val specs = json.getJSONArray("bitmaps")
            var total = 0L
            for (index in 0 until specs.length()) {
                val spec = specs.getJSONObject(index)
                total = BitmapMemoryBudget.saturatingAdd(total, BitmapMemoryBudget.bytes(spec.getInt("width"), spec.getInt("height")))
            }
            total
        }.getOrNull()
    }

    suspend fun deleteEntries(entries: Collection<EditorHistoryEntry>) = withContext(Dispatchers.IO) {
        entries.mapNotNull(EditorHistoryEntry::coldPayload).forEach { deleteInternal(it) }
    }

    suspend fun delete(payload: ColdHistoryPayload) = withContext(Dispatchers.IO) { deleteInternal(payload) }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        sessionDirectory(sessionId).takeIf { isOwnedSessionDirectory(it, sessionId) }?.deleteRecursively()
    }

    private fun snapshotManifest(entry: EditorHistoryEntry, snapshot: EditorHistorySnapshot, staging: File): JSONObject {
        val bitmapKeys = IdentityHashMap<Bitmap, String>()
        val bitmapSpecs = JSONArray()
        fun persist(bitmap: Bitmap?): String? {
            bitmap ?: return null
            bitmapKeys[bitmap]?.let { return it }
            check(snapshot.storage == HistorySnapshotStorage.Exact)
            val key = "bitmap-${bitmapKeys.size}"
            val fileName = "$key.png"
            FileOutputStream(File(staging, fileName)).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
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

    private fun deleteInternal(payload: ColdHistoryPayload) {
        payload.directory.takeIf { isOwnedDirectory(it) }?.deleteRecursively()
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
