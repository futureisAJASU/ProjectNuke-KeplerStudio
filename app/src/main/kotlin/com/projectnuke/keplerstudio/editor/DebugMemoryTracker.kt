package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.util.Log
import com.projectnuke.keplerstudio.BuildConfig
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

internal object DebugMemoryTracker {
    @JvmStatic
    fun isEnabled(): Boolean = BuildConfig.DEBUG

    @JvmStatic
    fun createSession(editorInstanceId: String): TrackerSession =
        TrackerSession(editorInstanceId)

    @JvmStatic
    fun thumbnailCacheSnapshot(): TrackerSession.ThumbnailCacheSnapshot {
        var residentEntryCount = 0L
        var residentBytes = 0L
        var totalActiveLeases = 0L
        var removedButLeasedEntryCount = 0L
        TrackerSessionHolder.sessions.forEach { _, session ->
            val snap = session.thumbnailCacheSnapshot()
            residentEntryCount += snap.residentEntryCount
            residentBytes += snap.residentBytes
            totalActiveLeases += snap.totalActiveLeases
            removedButLeasedEntryCount += snap.removedButLeasedEntryCount
        }
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

internal class TrackerSession(
    val editorInstanceId: String
) : ThumbnailCacheDiagnostics {
    private val lock = ReentrantLock()
    private val tag = "KeplerDebugMem:$editorInstanceId"

    private val referenceQueue = ReferenceQueue<Bitmap>()

    private class WeakIdentityKey(
        bitmap: Bitmap,
        queue: ReferenceQueue<Bitmap>
    ) : WeakReference<Bitmap>(bitmap, queue) {
        private val identityHashCode: Int = System.identityHashCode(bitmap)

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
        val historyHotResidentBytes: Long,
        val coldLoadDecodedTransientBytes: Long,
        val historyColdCompressedBytes: Long,
        val deletionDebtBytes: Long,
        val combinedKnownEstimatedPeakBytes: Long,
        val combinedEstimatedPeakBytes: Long,
        val documentGeneration: String,
        val timestamp: Long
    )

    data class ResidentSnapshot(
        val editorInstanceId: String,
        val totalBytes: Long,
        val bitmapCount: Int,
        val byOwner: Map<String, Long>,
        val byOperation: Map<String, Long>,
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
        val nativeEstimateBytes: Long,
        val unknownNativeBytes: Long,
        val combinedKnownEstimatedBytes: Long,
        val timestamp: Long,
        val ownerByteTotalsNonAdditive: Boolean = true,
        val activeThumbnailEntryCount: Int = 0,
        val activeThumbnailLeases: Int = 0,
        val thumbnailRemovedButLeased: Int = 0,
        val thumbnailResidentBytes: Long = 0L
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
        val timestamp: Long
    )

    data class ThumbnailCacheSnapshot(
        val residentEntryCount: Int,
        val residentBytes: Long,
        val totalActiveLeases: Int,
        val removedButLeasedEntryCount: Int
    )

    private val nodes = ConcurrentHashMap<WeakIdentityKey, NodeEntry>()
    private val edgeIndex = ConcurrentHashMap<Long, WeakIdentityKey>()
    private val operations = ConcurrentHashMap<String, OperationRecord>()

    private val documentGenerations = ConcurrentHashMap<String, Long>()
    private val documentPeaks = ConcurrentHashMap<String, AtomicReference<PeakSnapshot>>()
    private val currentGeneration = AtomicReference<String>("")

    private val currentToken = AtomicLong(0L)

    private val historyHotResidentBytes = AtomicLong(0L)
    private val historyHotEntryCount = AtomicLong(0L)
    private val historyColdCompressedBytes = AtomicLong(0L)
    private val coldLoadDecodedTransientBytes = AtomicLong(0L)
    private val deletionDebtBytes = AtomicLong(0L)

    private val nativeSessions = ConcurrentHashMap<Long, NativeSessionRecord>()

    private val thumbnailResidentEntryCount = AtomicLong(0L)
    private val thumbnailResidentBytes = AtomicLong(0L)
    private val thumbnailTotalLeases = AtomicLong(0L)
    private val thumbnailRemovedButLeased = AtomicLong(0L)

    private val boundedHistorySummaries = ArrayDeque<HistoryMetricsSnapshot>(MAX_HISTORY_SUMMARIES)

    init {
        TrackerSessionHolder.sessions[editorInstanceId] = this
        val initialGen = UUID.randomUUID().toString()
        currentGeneration.set(initialGen)
        documentGenerations[initialGen] = System.currentTimeMillis()
        documentPeaks.putIfAbsent(initialGen, AtomicReference(PeakSnapshot(
            editorInstanceId = editorInstanceId,
            residentBitmapBytes = 0L,
            nativeEstimateBytes = 0L,
            activeTransientReserveBytes = 0L,
            historyHotResidentBytes = 0L,
            coldLoadDecodedTransientBytes = 0L,
            historyColdCompressedBytes = 0L,
            deletionDebtBytes = 0L,
            combinedKnownEstimatedPeakBytes = 0L,
            combinedEstimatedPeakBytes = 0L,
            documentGeneration = initialGen,
            timestamp = System.currentTimeMillis()
        )))
    }

    companion object {
        const val MAX_HISTORY_SUMMARIES = 8
        const val MAX_OPERATION_LOG = 256
    }

    fun registerDocument(generation: String) {
        try {
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
                combinedEstimatedPeakBytes = 0L,
                documentGeneration = generation,
                timestamp = System.currentTimeMillis()
            )))
            if (documentGenerations.size == 1) {
                currentGeneration.set(generation)
            }
        } catch (_: Throwable) {
        }
    }

    fun setCurrentDocumentGeneration(generation: String) {
        try {
            currentGeneration.set(generation)
        } catch (_: Throwable) {
        }
    }

    fun currentDocumentGeneration(): String = currentGeneration.get()

    fun unregisterDocument(generation: String) {
        try {
            documentGenerations.remove(generation)
            val peakRef = documentPeaks.remove(generation)
            if (peakRef != null) {
                val snap = peakRef.get()
                synchronized(boundedHistorySummaries) {
                    boundedHistorySummaries.addLast(snap.toHistorySummary())
                    while (boundedHistorySummaries.size > MAX_HISTORY_SUMMARIES) boundedHistorySummaries.removeFirst()
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun registerBitmap(
        bitmap: Bitmap,
        owner: String,
        operation: String,
        token: Long,
        documentGeneration: String,
        isOwnerCounted: Boolean = true
    ): Long {
        try {
            purgeClearedReferences()
            val key = WeakIdentityKey(bitmap, referenceQueue)
            val existing = nodes[key]
            val node = if (existing != null) {
                existing.node
            } else {
                BitmapNode(
                    identity = System.identityHashCode(bitmap),
                    width = bitmap.width,
                    height = bitmap.height,
                    config = bitmap.config,
                    bytes = BitmapMemoryBudget.bytes(bitmap)
                )
            }
            val edges = mutableListOf<EdgeRecord>()
            val edgesByOwner = mutableMapOf<String, MutableList<EdgeRecord>>()
            val entry = NodeEntry(key, node, edges, edgesByOwner)
            nodes.putIfAbsent(key, entry)
            val actualEntry = nodes[key] ?: entry
            val handle = currentToken.incrementAndGet()
            val edge = EdgeRecord(
                handle = handle,
                owner = owner,
                operation = operation,
                token = token,
                documentGeneration = documentGeneration,
                acquiredAt = System.currentTimeMillis(),
                isOwnerCounted = isOwnerCounted
            )
            lock.lock()
            try {
                actualEntry.edges.add(edge)
                actualEntry.edgesByOwner.getOrPut(owner) { mutableListOf() }.add(edge)
            } finally {
                lock.unlock()
            }
            edgeIndex[handle] = key
            considerPeak(documentGeneration)
            return handle
        } catch (_: Throwable) {
            return 0L
        }
    }

    fun releaseEdge(handle: Long): Boolean {
        if (handle == 0L) return false
        try {
            val key = edgeIndex.remove(handle) ?: return false
            val entry = nodes[key] ?: return false
            lock.lock()
            try {
                val edge = entry.edges.find { it.handle == handle && !it.released }
                if (edge == null) return false
                edge.released = true
                entry.edges.remove(edge)
                entry.edgesByOwner[edge.owner]?.remove(edge)
                if (entry.edgesByOwner[edge.owner]?.isEmpty() == true) {
                    entry.edgesByOwner.remove(edge.owner)
                }
            } finally {
                lock.unlock()
            }
            if (entry.closed) {
                maybeRemoveEntry(entry)
            }
            considerPeak(currentGeneration.get())
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    fun unregisterBitmap(bitmap: Bitmap, owner: String? = null) {
        try {
            purgeClearedReferences()
            val key = WeakIdentityKey(bitmap, referenceQueue)
            val entry = nodes[key] ?: return
            lock.lock()
            try {
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
            } finally {
                lock.unlock()
            }
            maybeRemoveEntry(entry)
        } catch (_: Throwable) {
        }
    }

    private fun maybeRemoveEntry(entry: NodeEntry) {
        if (entry.edges.isEmpty()) {
            entry.closed = true
            nodes.remove(entry.key)
        }
    }

    fun registerNativeSession(
        handle: Long,
        documentGeneration: String,
        sourceIdentity: String,
        state: String,
        knownEstimateBytes: Long = -1L
    ) {
        if (handle == 0L) return
        try {
            nativeSessions[handle] = NativeSessionRecord(
                handle = handle,
                documentGeneration = documentGeneration,
                sourceIdentity = sourceIdentity,
                state = state,
                knownEstimateBytes = knownEstimateBytes,
                createdAt = System.currentTimeMillis()
            )
            considerPeak(documentGeneration)
        } catch (_: Throwable) {
        }
    }

    fun updateNativeSession(handle: Long, state: String) {
        if (handle == 0L) return
        try {
            nativeSessions[handle]?.let { existing ->
                nativeSessions[handle] = existing.copy(state = state)
            }
        } catch (_: Throwable) {
        }
    }

    fun unregisterNativeSession(handle: Long) {
        if (handle == 0L) return
        try {
            nativeSessions.remove(handle)
        } catch (_: Throwable) {
        }
    }

    fun setHistoryHotResident(bytes: Long, count: Int) {
        try {
            historyHotResidentBytes.set(bytes)
            historyHotEntryCount.set(count.toLong())
            considerPeak(currentGeneration.get())
        } catch (_: Throwable) {
        }
    }

    fun setHistoryColdCompressed(bytes: Long) {
        try {
            historyColdCompressedBytes.set(bytes)
        } catch (_: Throwable) {
        }
    }

    fun setColdLoadDecodedTransient(bytes: Long) {
        try {
            coldLoadDecodedTransientBytes.set(bytes)
            considerPeak(currentGeneration.get())
        } catch (_: Throwable) {
        }
    }

    fun setDeletionDebt(bytes: Long) {
        try {
            deletionDebtBytes.set(bytes)
        } catch (_: Throwable) {
        }
    }

    fun setHistoryMetricsSnapshot(metrics: HistoryMetricsSnapshot) {
        try {
            synchronized(boundedHistorySummaries) {
                boundedHistorySummaries.addLast(metrics)
                while (boundedHistorySummaries.size > MAX_HISTORY_SUMMARIES) boundedHistorySummaries.removeFirst()
            }
        } catch (_: Throwable) {
        }
    }

    fun setThumbnailCacheSnapshot(snap: ThumbnailCacheSnapshot) {
        try {
            thumbnailResidentEntryCount.set(snap.residentEntryCount.toLong())
            thumbnailResidentBytes.set(snap.residentBytes)
            thumbnailTotalLeases.set(snap.totalActiveLeases.toLong())
            thumbnailRemovedButLeased.set(snap.removedButLeasedEntryCount.toLong())
        } catch (_: Throwable) {
        }
    }

    fun beginOperation(
        name: String,
        documentGeneration: String,
        baseContentToken: String,
        revision: Int,
        transientReserveBytes: Long,
        snapshotState: String
    ): Long {
        try {
            val token = currentToken.incrementAndGet()
            val record = OperationRecord(
                name = name,
                token = token,
                documentGeneration = documentGeneration,
                baseContentToken = baseContentToken,
                revision = revision,
                startedAt = System.currentTimeMillis(),
                transientReserveBytes = transientReserveBytes,
                snapshotState = snapshotState
            )
            operations["$name:$token"] = record
            considerPeak(documentGeneration)
            return token
        } catch (_: Throwable) {
            return 0L
        }
    }

    fun endOperation(name: String, token: Long) {
        if (token == 0L) return
        try {
            operations.remove("$name:$token")
        } catch (_: Throwable) {
        }
    }

    private fun getActiveTransientReserve(): Long {
        var total = 0L
        for (op in operations.values) {
            total = BitmapMemoryBudget.saturatingAdd(total, op.transientReserveBytes)
        }
        return total
    }

    private fun getNativeEstimateAndUnknown(): Pair<Long, Long> {
        var estimate = 0L
        var unknown = 0L
        for (session in nativeSessions.values) {
            if (session.isUnknown) {
                unknown++
            } else {
                estimate = BitmapMemoryBudget.saturatingAdd(estimate, session.knownEstimateBytes)
            }
        }
        return estimate to unknown
    }

    private fun computeResidentBytes(): Pair<Long, Int> {
        purgeClearedReferences()
        var total = 0L
        var count = 0
        for ((key, entry) in nodes) {
            lock.lock()
            try {
                if (entry.edges.isNotEmpty()) {
                    val bitmap = key.get()
                    if (bitmap != null && !bitmap.isRecycled) {
                        total = BitmapMemoryBudget.saturatingAdd(total, entry.node.bytes)
                        count++
                    }
                }
            } finally {
                lock.unlock()
            }
        }
        return total to count
    }

    private fun considerPeak(documentGeneration: String) {
        try {
            val snap = computeResidentBytes()
            val (nativeEstimate, unknownNative) = getNativeEstimateAndUnknown()
            val activeTransient = getActiveTransientReserve()
            val historyHot = historyHotResidentBytes.get()
            val historyCold = historyColdCompressedBytes.get()
            val coldLoad = coldLoadDecodedTransientBytes.get()
            val deletionDebt = deletionDebtBytes.get()
            val combinedKnown = BitmapMemoryBudget.saturatingAdd(
                snap.first,
                BitmapMemoryBudget.saturatingAdd(
                    nativeEstimate,
                    BitmapMemoryBudget.saturatingAdd(
                        historyHot,
                        BitmapMemoryBudget.saturatingAdd(
                            coldLoad,
                            BitmapMemoryBudget.saturatingAdd(historyCold, BitmapMemoryBudget.saturatingAdd(deletionDebt, activeTransient))
                        )
                    )
                )
            )
            val combinedWithUnknown = if (unknownNative > 0L) Long.MAX_VALUE else combinedKnown
            val peakRef = documentPeaks[documentGeneration]
            if (peakRef != null) {
                val current = peakRef.get()
                if (combinedKnown > current.combinedKnownEstimatedPeakBytes) {
                    val updated = PeakSnapshot(
                        editorInstanceId = editorInstanceId,
                        residentBitmapBytes = snap.first,
                        nativeEstimateBytes = nativeEstimate,
                        activeTransientReserveBytes = activeTransient,
                        historyHotResidentBytes = historyHot,
                        coldLoadDecodedTransientBytes = coldLoad,
                        historyColdCompressedBytes = historyCold,
                        deletionDebtBytes = deletionDebt,
                        combinedKnownEstimatedPeakBytes = combinedKnown,
                        combinedEstimatedPeakBytes = combinedWithUnknown,
                        documentGeneration = documentGeneration,
                        timestamp = System.currentTimeMillis()
                    )
                    peakRef.set(updated)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun PeakSnapshot.toHistorySummary(): HistoryMetricsSnapshot = HistoryMetricsSnapshot(
        editorInstanceId = editorInstanceId,
        coordinatorGeneration = documentGeneration,
        hotEntryCount = 0,
        hotResidentBytes = residentBitmapBytes,
        retainedColdCompressedBytes = historyColdCompressedBytes,
        pendingDeletionDebtBytes = deletionDebtBytes,
        activeColdLoadDecodedBytes = coldLoadDecodedTransientBytes,
        operationActive = false,
        loading = false,
        spilling = false,
        adopting = false,
        protectedTargetId = null,
        timestamp = timestamp
    )

    internal fun purgeClearedReferences() {
        try {
            var cleared: Reference<*>?
            while (referenceQueue.poll().also { cleared = it } != null) {
                val key = cleared as WeakIdentityKey
                val entry = nodes.remove(key)
                if (entry != null) {
                    lock.lock()
                    try {
                        for (edge in entry.edges) {
                            edge.released = true
                            edgeIndex.remove(edge.handle)
                        }
                        entry.edges.clear()
                        entry.edgesByOwner.clear()
                    } finally {
                        lock.unlock()
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun snapshot(): ResidentSnapshot {
        try {
            purgeClearedReferences()
            val byOwner = ConcurrentHashMap<String, Long>()
            val byOperation = ConcurrentHashMap<String, Long>()
            var total = 0L
            var count = 0
            for ((key, entry) in nodes) {
                lock.lock()
                try {
                    if (entry.edges.isEmpty()) {
                    } else {
                        val bitmap = key.get()
                        if (bitmap == null || bitmap.isRecycled) {
                        } else {
                            total = BitmapMemoryBudget.saturatingAdd(total, entry.node.bytes)
                            count++
                            for (edge in entry.edges) {
                                if (edge.isOwnerCounted) {
                                    byOwner[edge.owner] = BitmapMemoryBudget.saturatingAdd(
                                        byOwner.getOrDefault(edge.owner, 0L), entry.node.bytes
                                    )
                                }
                                byOperation[edge.operation] = BitmapMemoryBudget.saturatingAdd(
                                    byOperation.getOrDefault(edge.operation, 0L), entry.node.bytes
                                )
                            }
                        }
                    }
                } finally {
                    lock.unlock()
                }
            }
            val activeOps = operations.values.toList()
            val sessions = nativeSessions.values.toList()
            val activeTransient = getActiveTransientReserve()
            val (nativeEstimate, unknownNative) = getNativeEstimateAndUnknown()
            val historyHot = historyHotResidentBytes.get()
            val historyCold = historyColdCompressedBytes.get()
            val coldLoad = coldLoadDecodedTransientBytes.get()
            val deletionDebt = deletionDebtBytes.get()
            val combinedKnown = BitmapMemoryBudget.saturatingAdd(
                total,
                BitmapMemoryBudget.saturatingAdd(
                    nativeEstimate,
                    BitmapMemoryBudget.saturatingAdd(
                        historyHot,
                        BitmapMemoryBudget.saturatingAdd(
                            coldLoad,
                            BitmapMemoryBudget.saturatingAdd(historyCold, BitmapMemoryBudget.saturatingAdd(deletionDebt, activeTransient))
                        )
                    )
                )
            )
            val combinedWithUnknown = if (unknownNative > 0L) Long.MAX_VALUE else combinedKnown
            val gen = currentGeneration.get()
            val peakSnap = documentPeaks[gen]?.get()
            return ResidentSnapshot(
                editorInstanceId = editorInstanceId,
                totalBytes = total,
                bitmapCount = count,
                byOwner = byOwner.toMap(),
                byOperation = byOperation.toMap(),
                nativeSessions = sessions,
                activeOperations = activeOps,
                estimatedPeakBytes = peakSnap?.combinedEstimatedPeakBytes ?: 0L,
                peakSnapshot = peakSnap,
                historyHotResidentBytes = historyHot,
                historyHotEntryCount = historyHotEntryCount.get().toInt(),
                historyColdCompressedBytes = historyCold,
                coldLoadDecodedTransientBytes = coldLoad,
                deletionDebtBytes = deletionDebt,
                activeTransientReserveBytes = activeTransient,
                nativeEstimateBytes = nativeEstimate,
                unknownNativeBytes = unknownNative,
                combinedKnownEstimatedBytes = combinedKnown,
                timestamp = System.currentTimeMillis(),
                activeThumbnailEntryCount = thumbnailResidentEntryCount.get().toInt(),
                activeThumbnailLeases = thumbnailTotalLeases.get().toInt(),
                thumbnailRemovedButLeased = thumbnailRemovedButLeased.get().toInt(),
                thumbnailResidentBytes = thumbnailResidentBytes.get()
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
                unknownNativeBytes = 0L,
                combinedKnownEstimatedBytes = 0L,
                timestamp = System.currentTimeMillis()
            )
        }
    }

    fun logSnapshot(tag: String) {
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

    fun debugString(): String {
        try {
            val snap = snapshot()
            val sb = StringBuilder()
            sb.append("TrackerSession[$editorInstanceId] snapshot:\n")
            sb.append("  totalBytes=${snap.totalBytes} bitmapCount=${snap.bitmapCount} peak=${snap.estimatedPeakBytes}\n")
            sb.append("  residentBitmapBytes=${snap.totalBytes} nativeEstimate=${snap.nativeEstimateBytes} unknownNative=${snap.unknownNativeBytes} combinedKnown=${snap.combinedKnownEstimatedBytes}\n")
            sb.append("  activeTransientReserve=${snap.activeTransientReserveBytes}\n")
            sb.append("  combinedEstimatedPeak=${snap.peakSnapshot?.combinedEstimatedPeakBytes ?: 0L}\n")
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

    fun clear() {
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
            nativeSessions.clear()
            documentGenerations.clear()
            documentPeaks.clear()
            synchronized(boundedHistorySummaries) { boundedHistorySummaries.clear() }
            historyHotResidentBytes.set(0L)
            historyHotEntryCount.set(0L)
            historyColdCompressedBytes.set(0L)
            coldLoadDecodedTransientBytes.set(0L)
            deletionDebtBytes.set(0L)
            thumbnailResidentEntryCount.set(0L)
            thumbnailResidentBytes.set(0L)
            thumbnailTotalLeases.set(0L)
            thumbnailRemovedButLeased.set(0L)
        } catch (_: Throwable) {
        }
    }

    fun thumbnailCacheSnapshot(): ThumbnailCacheSnapshot = ThumbnailCacheSnapshot(
        residentEntryCount = thumbnailResidentEntryCount.get().toInt(),
        residentBytes = thumbnailResidentBytes.get(),
        totalActiveLeases = thumbnailTotalLeases.get().toInt(),
        removedButLeasedEntryCount = thumbnailRemovedButLeased.get().toInt()
    )

    override fun onAcquire(bytes: Long) {
        thumbnailTotalLeases.incrementAndGet()
    }

    override fun onRelease(bytes: Long) {
        thumbnailTotalLeases.decrementAndGet()
    }

    override fun onEvictResident(bytes: Long) {
        thumbnailResidentEntryCount.decrementAndGet()
        thumbnailResidentBytes.addAndGet(-bytes)
    }

    override fun onResidentAcquire(bytes: Long) {
        thumbnailResidentEntryCount.incrementAndGet()
        thumbnailResidentBytes.addAndGet(bytes)
    }

    override fun onResidentRelease(bytes: Long) {
        thumbnailResidentEntryCount.decrementAndGet()
        thumbnailResidentBytes.addAndGet(-bytes)
    }

    override fun onRemoveButLease(bytes: Long) {
        thumbnailRemovedButLeased.incrementAndGet()
    }

    fun historySnapshot(): HistoryMetricsSnapshot? {
        synchronized(boundedHistorySummaries) {
            return boundedHistorySummaries.lastOrNull()
        }
    }

    fun logFinalSnapshot() {
        try {
            logSnapshot("onCleared")
        } catch (_: Throwable) {
        }
    }
}
