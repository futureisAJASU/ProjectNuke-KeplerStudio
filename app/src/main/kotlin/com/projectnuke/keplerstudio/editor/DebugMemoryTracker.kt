package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.util.Log
import com.projectnuke.keplerstudio.BuildConfig
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object DebugMemoryTracker {
    @JvmStatic
    fun isEnabled(): Boolean = BuildConfig.DEBUG

    @JvmStatic
    /** The only release branch.  No session, registry entry, lock or ledger is allocated there. */
    fun createEditorSession(editor: Any): TrackerSession? =
        if (isEnabled()) TrackerSession("editor-${System.identityHashCode(editor)}") else null

    fun diagnostics(session: TrackerSession?): TrackerDiagnostics = session ?: NoopTrackerDiagnostics

    fun onGlobalThumbnailMemoryChanged() {
        if (!isEnabled()) return
        TrackerSessionHolder.sessions.values.toList().forEach { it.considerCurrentPeak() }
    }

    @JvmStatic
    fun thumbnailCacheSnapshot(): TrackerSession.ThumbnailCacheSnapshot {
        var residentEntryCount = 0L
        var residentBytes = 0L
        var totalActiveLeases = 0L
        var removedButLeasedEntryCount = 0L
        val snap = ThumbnailBitmapCache.globalDiagnosticsSnapshot()
        residentEntryCount = snap.residentEntryCount.toLong()
        residentBytes = snap.residentBytes
        totalActiveLeases = snap.totalActiveLeases.toLong()
        removedButLeasedEntryCount = snap.removedButLeasedEntryCount.toLong()
        return TrackerSession.ThumbnailCacheSnapshot(
            residentEntryCount = residentEntryCount.toInt(),
            residentBytes = residentBytes,
            totalActiveLeases = totalActiveLeases.toInt(),
            removedButLeasedEntryCount = removedButLeasedEntryCount.toInt()
        )
    }
}

internal object TrackerSessionHolder {
    internal val sessions = ConcurrentHashMap<String, TrackerSession>()
}

internal data class GlobalModelContributor(
    val contributorId: String,
    val category: String,
    val state: String,
    val knownEstimateBytes: Long?
)

/** Process-global model state; it stores numeric/state data and never tracker sessions. */
internal object GlobalModelDiagnostics {
    private val contributors = AtomicReference<Map<String, GlobalModelContributor>>(emptyMap())
    private val nextContributorId = AtomicLong(0L)
    @Volatile private var enabledForTest: Boolean? = null

    fun newContributorId(category: String): String =
        if (enabled()) "$category-${nextContributorId.incrementAndGet()}" else ""

    fun publish(contributorId: String, category: String, state: String, knownEstimateBytes: Long? = null) {
        if (!enabled() || contributorId.isEmpty()) return
        try {
            while (true) {
                val current = contributors.get()
                val next = if (state == "unloaded") {
                    current - contributorId
                } else {
                    current + (contributorId to GlobalModelContributor(
                        contributorId,
                        category,
                        state,
                        knownEstimateBytes?.takeIf { it >= 0L }
                    ))
                }
                if (contributors.compareAndSet(current, next)) break
            }
            DebugMemoryTracker.onGlobalThumbnailMemoryChanged()
        } catch (_: Throwable) {
        }
    }

    fun publish(contributorId: String, state: String, knownEstimateBytes: Long? = null) =
        publish(contributorId, contributorId, state, knownEstimateBytes)

    fun snapshot(): List<GlobalModelContributor> = contributors.get().values.toList()

    internal fun resetForTest(enabled: Boolean? = null) {
        contributors.set(emptyMap())
        nextContributorId.set(0L)
        enabledForTest = enabled
    }

    private fun enabled(): Boolean = enabledForTest ?: DebugMemoryTracker.isEnabled()
}

/** Narrow diagnostics seam.  The release implementation is a stateless singleton. */
internal interface TrackerDiagnostics {
    fun registerDocument(generation: String)
    fun activateDocument(newGeneration: String, previousGeneration: String? = null)
    fun unregisterDocument(generation: String)
    fun currentDocumentGeneration(): String
    fun registerBitmap(bitmap: Bitmap, owner: String, operation: String, token: Long, documentGeneration: String, isOwnerCounted: Boolean = true): Long
    fun releaseEdge(handle: Long): Boolean
    fun unregisterBitmap(bitmap: Bitmap, owner: String? = null)
    fun registerNativeSession(handle: Long, documentGeneration: String, sourceIdentity: String, state: String, knownEstimateBytes: Long = -1L)
    fun updateNativeSession(handle: Long, state: String)
    fun rebindNativeSessionGeneration(handle: Long, generation: String)
    fun unregisterNativeSession(handle: Long)
    fun beginOperation(name: String, documentGeneration: String, baseContentToken: String, revision: Int, transientReserveBytes: Long, snapshotState: String): Long
    fun registerTransientContributor(operationToken: Long, documentGeneration: String, label: String, knownBytes: Long?): Long
    fun releaseTransientContributor(handle: Long): Boolean
    fun endOperation(name: String, token: Long)
    fun logSnapshot(tag: String)
    fun debugString(): String
    fun close()
}

private object NoopTrackerDiagnostics : TrackerDiagnostics {
    override fun registerDocument(generation: String) = Unit
    override fun activateDocument(newGeneration: String, previousGeneration: String?) = Unit
    override fun unregisterDocument(generation: String) = Unit
    override fun currentDocumentGeneration() = ""
    override fun registerBitmap(bitmap: Bitmap, owner: String, operation: String, token: Long, documentGeneration: String, isOwnerCounted: Boolean) = 0L
    override fun releaseEdge(handle: Long) = false
    override fun unregisterBitmap(bitmap: Bitmap, owner: String?) = Unit
    override fun registerNativeSession(handle: Long, documentGeneration: String, sourceIdentity: String, state: String, knownEstimateBytes: Long) = Unit
    override fun updateNativeSession(handle: Long, state: String) = Unit
    override fun rebindNativeSessionGeneration(handle: Long, generation: String) = Unit
    override fun unregisterNativeSession(handle: Long) = Unit
    override fun beginOperation(name: String, documentGeneration: String, baseContentToken: String, revision: Int, transientReserveBytes: Long, snapshotState: String) = 0L
    override fun registerTransientContributor(operationToken: Long, documentGeneration: String, label: String, knownBytes: Long?) = 0L
    override fun releaseTransientContributor(handle: Long) = false
    override fun endOperation(name: String, token: Long) = Unit
    override fun logSnapshot(tag: String) = Unit
    override fun debugString() = "release build - tracking disabled (stateless no-op)"
    override fun close() = Unit
}

internal class TrackerSession(
    val editorInstanceId: String,
    private val identityHashProvider: (Bitmap) -> Int = { System.identityHashCode(it) }
) : TrackerDiagnostics {
    private val lock = ReentrantLock()
    private val tag = "KeplerDebugMem:$editorInstanceId"

    private val referenceQueue = ReferenceQueue<Bitmap>()

    private class WeakIdentityKey(
        bitmap: Bitmap,
        queue: ReferenceQueue<Bitmap>,
        identityHashCode: Int
    ) : WeakReference<Bitmap>(bitmap, queue) {
        private val identityHashCode: Int = identityHashCode

        override fun hashCode(): Int = identityHashCode

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WeakIdentityKey) return false
            val thisBitmap = this.get()
            val otherBitmap = other.get()
            if (thisBitmap == null || otherBitmap == null) {
                return this === other
            }
            return thisBitmap === otherBitmap
        }
    }

    private class BitmapNode(
        val identity: Int,
        val width: Int,
        val height: Int,
        val config: Bitmap.Config?,
        val bytes: Long
    )

    private class NodeEntry(
        val key: WeakIdentityKey,
        val node: BitmapNode,
        val edges: MutableList<EdgeRecord>,
        val edgesByOwner: MutableMap<String, MutableList<EdgeRecord>>,
        @Volatile var closed: Boolean = false
    )

    data class EdgeRecord(
        val handle: Long,
        val owner: String,
        val operation: String,
        val token: Long,
        val documentGeneration: String,
        val acquiredAt: Long,
        val isOwnerCounted: Boolean,
        @Volatile var released: Boolean = false
    )

    data class OperationRecord(
        val name: String,
        val token: Long,
        val documentGeneration: String,
        val baseContentToken: String,
        val revision: Int,
        val startedAt: Long,
        val transientReserveBytes: Long,
        val snapshotState: String
    )

    data class NativeSessionRecord(
        val handle: Long,
        val documentGeneration: String,
        val sourceIdentity: String,
        val state: String,
        val knownEstimateBytes: Long = -1L,
        val createdAt: Long = 0L
    ) {
        val isUnknown: Boolean get() = knownEstimateBytes < 0L
    }

    data class PeakSnapshot(
        val editorInstanceId: String,
        val residentBitmapBytes: Long,
        val nativeEstimateBytes: Long,
        val activeTransientReserveBytes: Long,
        val knownLiveTransientBytes: Long = 0L,
        val unknownTransientContributorCount: Long = 0L,
        val historyHotResidentBytes: Long,
        val coldLoadDecodedTransientBytes: Long,
        val historyColdCompressedBytes: Long,
        val deletionDebtBytes: Long,
        val combinedKnownEstimatedPeakBytes: Long,
        val combinedCompleteEstimatedPeakBytes: Long?,
        val combinedHasUnknownContributors: Boolean = false,
        val documentGeneration: String,
        val timestamp: Long
    )

    data class ResidentSnapshot(
        val editorInstanceId: String,
        val totalBytes: Long,
        val bitmapCount: Int,
        val byOwner: Map<String, Long>,
        val byOperation: Map<String, Long>,
        val acquisitionCount: Int = 0,
        val byOwnerAcquisitionCount: Map<String, Int> = emptyMap(),
        val byOperationAcquisitionCount: Map<String, Int> = emptyMap(),
        val nativeSessions: List<NativeSessionRecord>,
        val activeOperations: List<OperationRecord>,
        val estimatedPeakBytes: Long,
        val peakSnapshot: PeakSnapshot?,
        val historyHotResidentBytes: Long,
        val historyHotEntryCount: Int,
        val historyColdCompressedBytes: Long,
        val coldLoadDecodedTransientBytes: Long,
        val deletionDebtBytes: Long,
        val activeTransientReserveBytes: Long,
        val knownLiveTransientBytes: Long = 0L,
        val unknownTransientContributorCount: Long = 0L,
        val nativeEstimateBytes: Long,
        val unknownNativeContributorCount: Long,
        val combinedKnownEstimatedBytes: Long,
        val combinedHasUnknownContributors: Boolean = false,
        val combinedCompleteEstimatedBytes: Long? = null,
        val timestamp: Long,
        val ownerByteTotalsNonAdditive: Boolean = true,
        val activeThumbnailEntryCount: Int = 0,
        val activeThumbnailLeases: Int = 0,
        val thumbnailRemovedButLeased: Int = 0,
        val thumbnailResidentBytes: Long = 0L,
        val thumbnailRemovedButLeasedBytes: Long = 0L,
        val globalModelContributors: List<GlobalModelContributor> = emptyList()
    )

    data class TransientContributorRecord(
        val handle: Long,
        val operationToken: Long,
        val documentGeneration: String,
        val label: String,
        val knownBytes: Long?
    )

    data class HistoryMetricsSnapshot(
        val editorInstanceId: String,
        val coordinatorGeneration: String,
        val hotEntryCount: Int,
        val hotResidentBytes: Long,
        val retainedColdCompressedBytes: Long,
        val pendingDeletionDebtBytes: Long,
        val activeColdLoadDecodedBytes: Long,
        val operationActive: Boolean,
        val loading: Boolean,
        val spilling: Boolean,
        val adopting: Boolean,
        val protectedTargetId: String?,
        val timestamp: Long,
        val operationKind: HistoryOperationKind = HistoryOperationKind.Idle,
        val navigationDirection: String? = null,
        val operationToken: Long = 0L,
        val recoveryMode: String? = null,
        val operationPhase: String? = null
    )

    enum class HistoryOperationKind { Idle, Loading, Spilling, Adopting, Trimming, Maintenance, DirectToCold, Recovery }

    data class CompletedGenerationPeakSummary(
        val documentGeneration: String,
        val combinedKnownPeakBytes: Long,
        val hadUnknownContributors: Boolean,
        val timestamp: Long
    )

    data class ThumbnailCacheSnapshot(
        val residentEntryCount: Int,
        val residentBytes: Long,
        val totalActiveLeases: Int,
        val removedButLeasedEntryCount: Int
    )

    private val nodes = ConcurrentHashMap<WeakIdentityKey, NodeEntry>()
    private val edgeIndex = ConcurrentHashMap<Long, NodeEntry>()
    private val operations = ConcurrentHashMap<String, OperationRecord>()
    private val transientContributors = ConcurrentHashMap<Long, TransientContributorRecord>()

    private val documentGenerations = ConcurrentHashMap<String, Long>()
    private val documentPeaks = ConcurrentHashMap<String, AtomicReference<PeakSnapshot>>()
    private val currentGeneration = AtomicReference<String>("")
    private enum class Lifecycle { Active, Closing, Closed }
    private val lifecycle = AtomicReference(Lifecycle.Active)

    private val currentToken = AtomicLong(0L)

    private val historyHotResidentBytes = AtomicLong(0L)
    private val historyHotEntryCount = AtomicLong(0L)
    private val historyColdCompressedBytes = AtomicLong(0L)
    private val coldLoadDecodedTransientBytes = AtomicLong(0L)
    private val deletionDebtBytes = AtomicLong(0L)

    private val nativeSessions = ConcurrentHashMap<Long, NativeSessionRecord>()

    private val completedGenerationPeaks = ArrayDeque<CompletedGenerationPeakSummary>(MAX_HISTORY_SUMMARIES)
    private val latestHistoryMetrics = AtomicReference<HistoryMetricsSnapshot?>(null)

    init {
        TrackerSessionHolder.sessions[editorInstanceId] = this
    }

    companion object {
        const val MAX_HISTORY_SUMMARIES = 8
        const val MAX_OPERATION_LOG = 256
    }

    private fun acceptingEvents(): Boolean = lifecycle.get() == Lifecycle.Active

    override fun registerDocument(generation: String) {
        try {
            lock.withLock {
                if (lifecycle.get() != Lifecycle.Active) return
                documentGenerations[generation] = System.currentTimeMillis()
                documentPeaks.putIfAbsent(generation, AtomicReference(PeakSnapshot(
                    editorInstanceId = editorInstanceId,
                    residentBitmapBytes = 0L,
                    nativeEstimateBytes = 0L,
                    activeTransientReserveBytes = 0L,
                    historyHotResidentBytes = 0L,
                    coldLoadDecodedTransientBytes = 0L,
                    historyColdCompressedBytes = 0L,
                    deletionDebtBytes = 0L,
                    combinedKnownEstimatedPeakBytes = 0L,
                    combinedCompleteEstimatedPeakBytes = 0L,
                    documentGeneration = generation,
                    timestamp = System.currentTimeMillis()
                )))
                if (documentGenerations.size == 1) {
                    currentGeneration.set(generation)
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun setCurrentDocumentGeneration(generation: String) {
        try {
            lock.withLock {
                if (lifecycle.get() != Lifecycle.Active) return
                currentGeneration.set(generation)
            }
        } catch (_: Throwable) {
        }
    }

    override fun currentDocumentGeneration(): String = currentGeneration.get()

    override fun unregisterDocument(generation: String) {
        try {
            lock.withLock {
                if (lifecycle.get() != Lifecycle.Active) return
                documentGenerations.remove(generation)
                val peakRef = documentPeaks.remove(generation)
                if (peakRef != null) {
                    val snap = peakRef.get()
                    synchronized(completedGenerationPeaks) {
                        completedGenerationPeaks.addLast(CompletedGenerationPeakSummary(
                            generation,
                            snap.combinedKnownEstimatedPeakBytes,
                            snap.combinedHasUnknownContributors,
                            snap.timestamp
                        ))
                        while (completedGenerationPeaks.size > MAX_HISTORY_SUMMARIES) completedGenerationPeaks.removeFirst()
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** One ordered document handoff prevents a tracker generation from drifting from history. */
    override fun activateDocument(newGeneration: String, previousGeneration: String?) {
        try { lock.withLock {
            if (lifecycle.get() != Lifecycle.Active) return
            registerDocument(newGeneration)
            historyHotResidentBytes.set(0L)
            historyHotEntryCount.set(0L)
            historyColdCompressedBytes.set(0L)
            coldLoadDecodedTransientBytes.set(0L)
            deletionDebtBytes.set(0L)
            latestHistoryMetrics.set(null)
            currentGeneration.set(newGeneration)
            previousGeneration?.takeIf { it != newGeneration }?.let(::unregisterDocument)
        } } catch (_: Throwable) { }
    }

    override fun registerBitmap(
        bitmap: Bitmap,
        owner: String,
        operation: String,
        token: Long,
        documentGeneration: String,
        isOwnerCounted: Boolean
    ): Long {
        if (bitmap.isRecycled) return 0L
        return try {
            val handle = lock.withLock {
                if (lifecycle.get() != Lifecycle.Active || bitmap.isRecycled) return@withLock 0L
                purgeClearedReferencesLocked()
                val identity = identityHashProvider(bitmap)
                val lookup = WeakIdentityKey(bitmap, referenceQueue, identity)
                val entry = nodes[lookup] ?: NodeEntry(
                    key = lookup,
                    node = BitmapNode(
                        identity = identity,
                        width = bitmap.width,
                        height = bitmap.height,
                        config = bitmap.config,
                        bytes = BitmapMemoryBudget.bytes(bitmap)
                    ),
                    edges = mutableListOf(),
                    edgesByOwner = mutableMapOf()
                ).also { nodes[it.key] = it }
                val nextHandle = currentToken.incrementAndGet()
                val edge = EdgeRecord(nextHandle, owner, operation, token, documentGeneration,
                    System.currentTimeMillis(), isOwnerCounted)
                entry.edges.add(edge)
                entry.edgesByOwner.getOrPut(owner) { mutableListOf() }.add(edge)
                edgeIndex[nextHandle] = entry
                nextHandle
            }
            if (handle != 0L) considerPeak(documentGeneration)
            handle
        } catch (_: Throwable) { 0L }
    }

    fun registerBitmap(bitmap: Bitmap, owner: String, operation: String, token: Long, documentGeneration: String): Long =
        registerBitmap(bitmap, owner, operation, token, documentGeneration, true)

    override fun releaseEdge(handle: Long): Boolean {
        if (handle == 0L) return false
        return try {
            val released = lock.withLock {
                val entry = edgeIndex.remove(handle) ?: return@withLock false
                val edge = entry.edges.firstOrNull { it.handle == handle && !it.released }
                    ?: return@withLock false
                edge.released = true
                entry.edges.remove(edge)
                entry.edgesByOwner[edge.owner]?.remove(edge)
                if (entry.edgesByOwner[edge.owner].isNullOrEmpty()) entry.edgesByOwner.remove(edge.owner)
                if (entry.edges.isEmpty()) {
                    entry.closed = true
                    nodes.remove(entry.key, entry)
                }
                true
            }
            released
        } catch (_: Throwable) { false }
    }

    override fun unregisterBitmap(bitmap: Bitmap, owner: String?) {
        try {
            lock.withLock {
                if (lifecycle.get() != Lifecycle.Active) return@withLock
                purgeClearedReferencesLocked()
                val key = WeakIdentityKey(bitmap, referenceQueue, identityHashProvider(bitmap))
                val entry = nodes[key] ?: return@withLock
                if (owner != null) {
                    val ownerEdges = entry.edgesByOwner[owner]
                    if (ownerEdges != null) {
                        for (edge in ownerEdges) {
                            edge.released = true
                            edgeIndex.remove(edge.handle)
                        }
                        ownerEdges.clear()
                        entry.edgesByOwner.remove(owner)
                        entry.edges.removeAll { it.released }
                    }
                } else {
                    for (edge in entry.edges) {
                        edge.released = true
                        edgeIndex.remove(edge.handle)
                    }
                    entry.edges.clear()
                    entry.edgesByOwner.clear()
                }
                if (entry.edges.isEmpty()) {
                    entry.closed = true
                    nodes.remove(entry.key, entry)
                }
            }
        } catch (_: Throwable) { }
    }

    fun unregisterBitmap(bitmap: Bitmap) = unregisterBitmap(bitmap, null)

    override fun registerNativeSession(
        handle: Long,
        documentGeneration: String,
        sourceIdentity: String,
        state: String,
        knownEstimateBytes: Long
    ) {
        if (handle == 0L) return
        try {
            lock.withLock {
                if (lifecycle.get() != Lifecycle.Active) return
                nativeSessions[handle] = NativeSessionRecord(handle, documentGeneration, sourceIdentity, state,
                    knownEstimateBytes, System.currentTimeMillis())
            }
            considerPeak(documentGeneration)
        } catch (_: Throwable) {
        }
    }

    fun registerNativeSession(handle: Long, documentGeneration: String, sourceIdentity: String, state: String) =
        registerNativeSession(handle, documentGeneration, sourceIdentity, state, -1L)

    override fun updateNativeSession(handle: Long, state: String) {
        if (handle == 0L) return
        try {
            lock.withLock {
                if (lifecycle.get() != Lifecycle.Active) return
                nativeSessions[handle]?.let { nativeSessions[handle] = it.copy(state = state) }
            }
        } catch (_: Throwable) {
        }
    }

    override fun rebindNativeSessionGeneration(handle: Long, generation: String) {
        if (handle == 0L) return
        try { lock.withLock {
            if (lifecycle.get() != Lifecycle.Active || currentGeneration.get() != generation) return
            nativeSessions[handle]?.let { nativeSessions[handle] = it.copy(documentGeneration = generation) }
        } } catch (_: Throwable) { }
    }

    override fun unregisterNativeSession(handle: Long) {
        if (handle == 0L) return
        try {
            lock.withLock {
                if (lifecycle.get() != Lifecycle.Active) return
                nativeSessions.remove(handle)
            }
        } catch (_: Throwable) {
        }
    }

    fun publishHistoryMetrics(metrics: HistoryMetricsSnapshot) {
        try {
            lock.withLock {
                if (lifecycle.get() != Lifecycle.Active || metrics.coordinatorGeneration != currentGeneration.get()) return
                historyHotResidentBytes.set(metrics.hotResidentBytes.coerceAtLeast(0L))
                historyHotEntryCount.set(metrics.hotEntryCount.coerceAtLeast(0).toLong())
                historyColdCompressedBytes.set(metrics.retainedColdCompressedBytes.coerceAtLeast(0L))
                coldLoadDecodedTransientBytes.set(metrics.activeColdLoadDecodedBytes.coerceAtLeast(0L))
                deletionDebtBytes.set(metrics.pendingDeletionDebtBytes.coerceAtLeast(0L))
                latestHistoryMetrics.set(metrics)
            }
            considerPeak(metrics.coordinatorGeneration)
        } catch (_: Throwable) { }
    }

    override fun beginOperation(
        name: String,
        documentGeneration: String,
        baseContentToken: String,
        revision: Int,
        transientReserveBytes: Long,
        snapshotState: String
    ): Long {
        return try {
            val token = lock.withLock {
                if (lifecycle.get() != Lifecycle.Active) return@withLock 0L
                val next = currentToken.incrementAndGet()
                val record = OperationRecord(
                name = name,
                token = next,
                documentGeneration = documentGeneration,
                baseContentToken = baseContentToken,
                revision = revision,
                startedAt = System.currentTimeMillis(),
                transientReserveBytes = transientReserveBytes,
                snapshotState = snapshotState
            )
                operations["$name:$next"] = record
                next
            }
            if (token != 0L) considerPeak(documentGeneration)
            token
        } catch (_: Throwable) { 0L }
    }

    override fun endOperation(name: String, token: Long) {
        if (token == 0L) return
        try {
            lock.withLock {
                if (lifecycle.get() == Lifecycle.Closed) return
                operations.remove("$name:$token")
                transientContributors.entries.removeAll { it.value.operationToken == token }
            }
        } catch (_: Throwable) {
        }
    }

    private fun getActiveTransientReserve(documentGeneration: String = currentGeneration.get()): Long {
        var total = 0L
        for (op in operations.values) {
            if (op.documentGeneration != documentGeneration) continue
            total = BitmapMemoryBudget.saturatingAdd(total, op.transientReserveBytes)
        }
        return total
    }

    override fun registerTransientContributor(
        operationToken: Long,
        documentGeneration: String,
        label: String,
        knownBytes: Long?
    ): Long = try {
        val handle = lock.withLock {
            if (lifecycle.get() != Lifecycle.Active ||
                operations.values.none { it.token == operationToken && it.documentGeneration == documentGeneration }
            ) return@withLock 0L
            val next = currentToken.incrementAndGet()
            transientContributors[next] = TransientContributorRecord(
                next,
                operationToken,
                documentGeneration,
                label,
                knownBytes?.coerceAtLeast(0L)
            )
            next
        }
        if (handle != 0L) considerPeak(documentGeneration)
        handle
    } catch (_: Throwable) { 0L }

    override fun releaseTransientContributor(handle: Long): Boolean = try {
        lock.withLock { transientContributors.remove(handle) != null }
    } catch (_: Throwable) { false }

    private fun getLiveTransientAndUnknown(documentGeneration: String): Pair<Long, Long> = lock.withLock {
        var bytes = 0L
        var unknown = 0L
        transientContributors.values.forEach {
            if (it.documentGeneration != documentGeneration) return@forEach
            if (it.knownBytes == null) unknown++ else bytes = BitmapMemoryBudget.saturatingAdd(bytes, it.knownBytes)
        }
        bytes to unknown
    }

    private fun getNativeEstimateAndUnknown(documentGeneration: String = currentGeneration.get()): Pair<Long, Long> {
        var estimate = 0L
        var unknown = 0L
        for (session in nativeSessions.values) {
            if (session.documentGeneration != documentGeneration) continue
            if (session.isUnknown) {
                unknown++
            } else {
                estimate = BitmapMemoryBudget.saturatingAdd(estimate, session.knownEstimateBytes)
            }
        }
        return estimate to unknown
    }

    private data class LedgerSummary(
        val bytes: Long,
        val bitmapCount: Int,
        val acquisitionCount: Int,
        val byOwner: Map<String, Long>,
        val byOperation: Map<String, Long>,
        val byOwnerAcquisitions: Map<String, Int>,
        val byOperationAcquisitions: Map<String, Int>
    )

    private data class HistoryAggregateSummary(
        val hotBytes: Long,
        val hotEntryCount: Int,
        val coldBytes: Long,
        val coldLoadBytes: Long,
        val deletionDebtBytes: Long
    )

    private fun captureHistoryAggregates(documentGeneration: String): HistoryAggregateSummary = lock.withLock {
        if (currentGeneration.get() != documentGeneration) {
            HistoryAggregateSummary(0L, 0, 0L, 0L, 0L)
        } else {
            HistoryAggregateSummary(
                historyHotResidentBytes.get(),
                historyHotEntryCount.get().toInt(),
                historyColdCompressedBytes.get(),
                coldLoadDecodedTransientBytes.get(),
                deletionDebtBytes.get()
            )
        }
    }

    private fun captureLedgerSummary(documentGeneration: String = currentGeneration.get()): LedgerSummary = lock.withLock {
        purgeClearedReferencesLocked()
        var bytes = 0L
        var count = 0
        var acquisitions = 0
        val byOwner = HashMap<String, Long>()
        val byOperation = HashMap<String, Long>()
        val ownerCounts = HashMap<String, Int>()
        val operationCounts = HashMap<String, Int>()
        nodes.values.forEach { entry ->
            val bitmap = entry.key.get()
            val generationEdges = entry.edges.filter { it.documentGeneration == documentGeneration }
            if (generationEdges.isEmpty() || bitmap == null || bitmap.isRecycled) return@forEach
            bytes = BitmapMemoryBudget.saturatingAdd(bytes, entry.node.bytes)
            count++
            acquisitions += generationEdges.size
            generationEdges.groupingBy { it.owner }.eachCount().forEach { (label, n) ->
                if (generationEdges.any { it.owner == label && it.isOwnerCounted })
                    byOwner[label] = BitmapMemoryBudget.saturatingAdd(byOwner[label] ?: 0L, entry.node.bytes)
                ownerCounts[label] = (ownerCounts[label] ?: 0) + n
            }
            generationEdges.groupingBy { it.operation }.eachCount().forEach { (label, n) ->
                byOperation[label] = BitmapMemoryBudget.saturatingAdd(byOperation[label] ?: 0L, entry.node.bytes)
                operationCounts[label] = (operationCounts[label] ?: 0) + n
            }
        }
        LedgerSummary(bytes, count, acquisitions, byOwner, byOperation, ownerCounts, operationCounts)
    }

    internal fun considerCurrentPeak() = considerPeak(currentGeneration.get())

    private fun considerPeak(documentGeneration: String) {
        try {
            val snap = captureLedgerSummary(documentGeneration)
            val (sessionNativeEstimate, sessionUnknownNative) = getNativeEstimateAndUnknown(documentGeneration)
            val globalModels = GlobalModelDiagnostics.snapshot()
            val modelEstimate = globalModels.fold(0L) { total, model ->
                BitmapMemoryBudget.saturatingAdd(total, model.knownEstimateBytes ?: 0L)
            }
            val nativeEstimate = BitmapMemoryBudget.saturatingAdd(sessionNativeEstimate, modelEstimate)
            val unknownNative = sessionUnknownNative + globalModels.count { it.knownEstimateBytes == null }
            val activeTransient = getActiveTransientReserve(documentGeneration)
            val (liveTransient, unknownTransient) = getLiveTransientAndUnknown(documentGeneration)
            val history = captureHistoryAggregates(documentGeneration)
            val historyHot = history.hotBytes
            val coldLoad = history.coldLoadBytes
            val historyCold = history.coldBytes
            val deletionDebt = history.deletionDebtBytes
            val thumbnails = ThumbnailBitmapCache.globalDiagnosticsSnapshot()
            val combinedKnown = BitmapMemoryBudget.saturatingAdd(
                snap.bytes,
                BitmapMemoryBudget.saturatingAdd(
                    nativeEstimate,
                    BitmapMemoryBudget.saturatingAdd(
                        historyHot,
                        BitmapMemoryBudget.saturatingAdd(coldLoad, BitmapMemoryBudget.saturatingAdd(
                            BitmapMemoryBudget.saturatingAdd(activeTransient, liveTransient),
                            BitmapMemoryBudget.saturatingAdd(
                                thumbnails.residentBytes,
                                thumbnails.removedButLeasedBytes)))
                    )
                )
            )
            // Disk retention/deletion debt are intentionally not RAM contributors.
            val combinedWithUnknown = combinedKnown
            val peakRef = documentPeaks[documentGeneration]
            if (peakRef != null) {
                while (true) {
                    val current = peakRef.get()
                    if (combinedKnown <= current.combinedKnownEstimatedPeakBytes) break
                    val updated = PeakSnapshot(
                        editorInstanceId = editorInstanceId,
                        residentBitmapBytes = snap.bytes,
                        nativeEstimateBytes = nativeEstimate,
                        activeTransientReserveBytes = activeTransient,
                        knownLiveTransientBytes = liveTransient,
                        unknownTransientContributorCount = unknownTransient,
                        historyHotResidentBytes = historyHot,
                        coldLoadDecodedTransientBytes = coldLoad,
                        historyColdCompressedBytes = historyCold,
                        deletionDebtBytes = deletionDebt,
                        combinedKnownEstimatedPeakBytes = combinedKnown,
                        combinedCompleteEstimatedPeakBytes = if (unknownNative + unknownTransient == 0L) combinedWithUnknown else null,
                        combinedHasUnknownContributors = unknownNative + unknownTransient > 0L,
                        documentGeneration = documentGeneration,
                        timestamp = System.currentTimeMillis()
                    )
                    if (currentGeneration.get() != documentGeneration || !documentGenerations.containsKey(documentGeneration)) break
                    if (peakRef.compareAndSet(current, updated)) break
                }
            }
        } catch (_: Throwable) {
        }
    }

    internal fun purgeClearedReferences() {
        try { lock.withLock { purgeClearedReferencesLocked() } } catch (_: Throwable) { }
    }

    private fun purgeClearedReferencesLocked() {
        while (true) {
            val key = referenceQueue.poll() as? WeakIdentityKey ?: break
            val entry = nodes[key] ?: continue
            if (entry.key !== key) continue
            entry.edges.forEach { edge ->
                edge.released = true
                edgeIndex.remove(edge.handle, entry)
            }
            entry.edges.clear()
            entry.edgesByOwner.clear()
            entry.closed = true
            nodes.remove(key, entry)
        }
    }

    fun snapshot(): ResidentSnapshot {
        try {
            val generation = currentGeneration.get()
            val ledger = captureLedgerSummary(generation)
            val (activeOps, sessions) = lock.withLock { operations.values.toList() to nativeSessions.values.toList() }
            val activeTransient = getActiveTransientReserve()
            val (liveTransient, unknownTransient) = getLiveTransientAndUnknown(generation)
            val (sessionNativeEstimate, sessionUnknownNative) = getNativeEstimateAndUnknown()
            val globalModels = GlobalModelDiagnostics.snapshot()
            val nativeEstimate = globalModels.fold(sessionNativeEstimate) { total, model ->
                BitmapMemoryBudget.saturatingAdd(total, model.knownEstimateBytes ?: 0L)
            }
            val unknownNative = sessionUnknownNative + globalModels.count { it.knownEstimateBytes == null }
            val history = captureHistoryAggregates(generation)
            val historyHot = history.hotBytes
            val historyCold = history.coldBytes
            val coldLoad = history.coldLoadBytes
            val deletionDebt = history.deletionDebtBytes
            val thumbnails = ThumbnailBitmapCache.globalDiagnosticsSnapshot()
            val combinedKnown = BitmapMemoryBudget.saturatingAdd(
                ledger.bytes,
                BitmapMemoryBudget.saturatingAdd(
                    nativeEstimate,
                    BitmapMemoryBudget.saturatingAdd(
                        historyHot,
                        BitmapMemoryBudget.saturatingAdd(coldLoad, BitmapMemoryBudget.saturatingAdd(
                            BitmapMemoryBudget.saturatingAdd(activeTransient, liveTransient),
                            BitmapMemoryBudget.saturatingAdd(thumbnails.residentBytes, thumbnails.removedButLeasedBytes)))
                    )
                )
            )
            val gen = generation
            val peakSnap = documentPeaks[gen]?.get()
            return ResidentSnapshot(
                editorInstanceId = editorInstanceId,
                totalBytes = ledger.bytes,
                bitmapCount = ledger.bitmapCount,
                byOwner = ledger.byOwner,
                byOperation = ledger.byOperation,
                acquisitionCount = ledger.acquisitionCount,
                byOwnerAcquisitionCount = ledger.byOwnerAcquisitions,
                byOperationAcquisitionCount = ledger.byOperationAcquisitions,
                nativeSessions = sessions,
                activeOperations = activeOps,
                estimatedPeakBytes = peakSnap?.combinedKnownEstimatedPeakBytes ?: 0L,
                peakSnapshot = peakSnap,
                historyHotResidentBytes = historyHot,
                historyHotEntryCount = history.hotEntryCount,
                historyColdCompressedBytes = historyCold,
                coldLoadDecodedTransientBytes = coldLoad,
                deletionDebtBytes = deletionDebt,
                activeTransientReserveBytes = activeTransient,
                knownLiveTransientBytes = liveTransient,
                unknownTransientContributorCount = unknownTransient,
                nativeEstimateBytes = nativeEstimate,
                unknownNativeContributorCount = unknownNative,
                combinedKnownEstimatedBytes = combinedKnown,
                combinedHasUnknownContributors = unknownNative + unknownTransient > 0L,
                combinedCompleteEstimatedBytes = if (unknownNative + unknownTransient == 0L) combinedKnown else null,
                timestamp = System.currentTimeMillis(),
                activeThumbnailEntryCount = thumbnails.residentEntryCount,
                activeThumbnailLeases = thumbnails.totalActiveLeases,
                thumbnailRemovedButLeased = thumbnails.removedButLeasedEntryCount,
                thumbnailResidentBytes = thumbnails.residentBytes,
                thumbnailRemovedButLeasedBytes = thumbnails.removedButLeasedBytes,
                globalModelContributors = globalModels
            )
        } catch (_: Throwable) {
            return ResidentSnapshot(
                editorInstanceId = editorInstanceId,
                totalBytes = 0L,
                bitmapCount = 0,
                byOwner = emptyMap(),
                byOperation = emptyMap(),
                nativeSessions = emptyList(),
                activeOperations = emptyList(),
                estimatedPeakBytes = 0L,
                peakSnapshot = null,
                historyHotResidentBytes = 0L,
                historyHotEntryCount = 0,
                historyColdCompressedBytes = 0L,
                coldLoadDecodedTransientBytes = 0L,
                deletionDebtBytes = 0L,
                activeTransientReserveBytes = 0L,
                nativeEstimateBytes = 0L,
                unknownNativeContributorCount = 0L,
                combinedKnownEstimatedBytes = 0L,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    override fun logSnapshot(tag: String) {
        try {
            val snap = snapshot()
            Log.d(tag, "${this.tag}: bitmaps=${snap.bitmapCount} bytes=${snap.totalBytes} peak=${snap.estimatedPeakBytes} ops=${snap.activeOperations.size} sessions=${snap.nativeSessions.size}")
            if (snap.bitmapCount > 0) {
                val top = snap.byOwner.entries.sortedByDescending { it.value }.take(5)
                Log.d(tag, "  top owners: ${top.joinToString { "${it.key}=${it.value}" }}")
            }
        } catch (_: Throwable) {
        }
    }

    override fun debugString(): String {
        try {
            val snap = snapshot()
            val sb = StringBuilder()
            sb.append("TrackerSession[$editorInstanceId] snapshot:\n")
            sb.append("  totalBytes=${snap.totalBytes} bitmapCount=${snap.bitmapCount} peak=${snap.estimatedPeakBytes}\n")
            sb.append("  residentBitmapBytes=${snap.totalBytes} nativeEstimate=${snap.nativeEstimateBytes} unknownNativeContributors=${snap.unknownNativeContributorCount} combinedKnown=${snap.combinedKnownEstimatedBytes}\n")
            sb.append("  activeTransientReserve=${snap.activeTransientReserveBytes}\n")
            sb.append("  combinedKnownPeak=${snap.peakSnapshot?.combinedKnownEstimatedPeakBytes ?: 0L}\n")
            sb.append("  byOwner:\n")
            snap.byOwner.entries.sortedByDescending { it.value }.forEach { (owner, bytes) ->
                sb.append("    $owner: $bytes\n")
            }
            sb.append("  byOperation:\n")
            snap.byOperation.entries.sortedByDescending { it.value }.forEach { (op, bytes) ->
                sb.append("    $op: $bytes\n")
            }
            sb.append("  activeOperations: ${snap.activeOperations.size}\n")
            snap.activeOperations.forEach { op ->
                sb.append("    ${op.name} token=${op.token} reserve=${op.transientReserveBytes} state=${op.snapshotState}\n")
            }
            sb.append("  nativeSessions: ${snap.nativeSessions.size}\n")
            snap.nativeSessions.forEach { s ->
                val est = if (s.isUnknown) "unknown" else "${s.knownEstimateBytes}B"
                sb.append("    handle=${s.handle} state=${s.state} gen=${s.documentGeneration} est=$est\n")
            }
            sb.append("  historyHotResident=${snap.historyHotResidentBytes} hotEntries=${snap.historyHotEntryCount}\n")
            sb.append("  historyColdCompressed=${snap.historyColdCompressedBytes}\n")
            sb.append("  coldLoadDecodedTransient=${snap.coldLoadDecodedTransientBytes}\n")
            sb.append("  deletionDebt=${snap.deletionDebtBytes}\n")
            sb.append("  thumbnail: entries=${snap.activeThumbnailEntryCount} bytes=${snap.thumbnailResidentBytes} leases=${snap.activeThumbnailLeases} removedButLeased=${snap.thumbnailRemovedButLeased}\n")
            return sb.toString()
        } catch (_: Throwable) {
            return "TrackerSession[$editorInstanceId]: snapshot failed"
        }
    }

    /** Test-only reset.  Unlike [close], the session stays active and registered. */
    fun clearForTest() {
        if (!acceptingEvents()) return
        try {
            lock.lock()
            try {
                for (entry in nodes.values) {
                    for (edge in entry.edges) {
                        edge.released = true
                        edgeIndex.remove(edge.handle)
                    }
                    entry.edges.clear()
                    entry.edgesByOwner.clear()
                }
            } finally {
                lock.unlock()
            }
            nodes.clear()
            edgeIndex.clear()
            operations.clear()
            transientContributors.clear()
            nativeSessions.clear()
            documentGenerations.clear()
            documentPeaks.clear()
            synchronized(completedGenerationPeaks) { completedGenerationPeaks.clear() }
            latestHistoryMetrics.set(null)
            historyHotResidentBytes.set(0L)
            historyHotEntryCount.set(0L)
            historyColdCompressedBytes.set(0L)
            coldLoadDecodedTransientBytes.set(0L)
            deletionDebtBytes.set(0L)
        } catch (_: Throwable) {
        }
    }

    /**
     * Idempotent terminal transition.  Late finalizers deliberately become no-ops and the
     * conditional remove protects a newer session reusing the same test/editor identifier.
     */
    override fun close() {
        var shouldRemove = false
        try {
            lock.withLock {
                if (lifecycle.get() != Lifecycle.Active) return
                lifecycle.set(Lifecycle.Closing)
                shouldRemove = true
                nodes.values.forEach { entry -> entry.edges.forEach { it.released = true } }
                nodes.clear()
                edgeIndex.clear()
                operations.clear()
                transientContributors.clear()
                nativeSessions.clear()
                documentGenerations.clear()
                documentPeaks.clear()
                synchronized(completedGenerationPeaks) { completedGenerationPeaks.clear() }
                latestHistoryMetrics.set(null)
                while (referenceQueue.poll() != null) Unit
                historyHotResidentBytes.set(0L)
                historyHotEntryCount.set(0L)
                historyColdCompressedBytes.set(0L)
                coldLoadDecodedTransientBytes.set(0L)
                deletionDebtBytes.set(0L)
                currentGeneration.set("")
                lifecycle.set(Lifecycle.Closed)
            }
        } catch (_: Throwable) {
        } finally {
            if (shouldRemove) TrackerSessionHolder.sessions.remove(editorInstanceId, this)
        }
    }

    @Deprecated("Use clearForTest() or close()")
    fun clear() = clearForTest()

    fun thumbnailCacheSnapshot(): ThumbnailCacheSnapshot = ThumbnailBitmapCache.globalDiagnosticsSnapshot().let {
        ThumbnailCacheSnapshot(it.residentEntryCount, it.residentBytes, it.totalActiveLeases, it.removedButLeasedEntryCount)
    }

    fun historySnapshot(): HistoryMetricsSnapshot? = latestHistoryMetrics.get()

    fun completedGenerationPeakSummaries(): List<CompletedGenerationPeakSummary> =
        synchronized(completedGenerationPeaks) { completedGenerationPeaks.toList() }

    internal fun ledgerInvariantViolations(): List<String> = lock.withLock {
        buildList {
            edgeIndex.forEach { (handle, entry) ->
                if (nodes[entry.key] !== entry) add("edge $handle references absent node")
                if (entry.edges.none { it.handle == handle && !it.released }) add("edge $handle is not owned by node")
            }
            nodes.values.forEach { entry ->
                entry.edges.filterNot { it.released }.forEach { edge ->
                    if (edgeIndex[edge.handle] !== entry) add("node edge ${edge.handle} is not indexed")
                }
            }
        }
    }

    fun logFinalSnapshot() {
        try {
            logSnapshot("onCleared")
        } catch (_: Throwable) {
        }
    }
}
