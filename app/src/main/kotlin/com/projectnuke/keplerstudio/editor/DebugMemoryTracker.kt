package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.util.Log
import com.projectnuke.keplerstudio.BuildConfig
import java.lang.ref.Reference
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal object DebugMemoryTracker {
    private const val TAG = "KeplerDebugMem"
    private const val MAX_OPERATION_LOG = 256
    private const val MAX_BITMAP_RECORDS = 512

    @Volatile
    private var enabled: Boolean = BuildConfig.DEBUG

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
            return thisBitmap != null && thisBitmap === otherBitmap
        }
    }

    data class BitmapNode(
        val identity: Int,
        val width: Int,
        val height: Int,
        val config: Bitmap.Config?,
        val bytes: Long
    )

    data class OwnerEdge(
        val owner: String,
        val operation: String,
        val token: Long,
        val documentGeneration: String,
        val acquiredAt: Long
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
        val createdAt: Long
    )

    data class PeakSnapshot(
        val residentBitmapBytes: Long,
        val nativeEstimateBytes: Long,
        val activeTransientReserveBytes: Long,
        val combinedEstimatedPeakBytes: Long,
        val documentGeneration: String,
        val timestamp: Long
    )

    data class ResidentSnapshot(
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
        val timestamp: Long
    )

    private val bitmapNodes = ConcurrentHashMap<WeakIdentityKey, BitmapNode>()
    private val ownerEdges = ConcurrentHashMap<WeakIdentityKey, MutableList<OwnerEdge>>()
    private val operations = ConcurrentHashMap<String, OperationRecord>()
    private val operationLog = ArrayDeque<OperationRecord>(MAX_OPERATION_LOG)
    private val nativeSessions = ConcurrentHashMap<Long, NativeSessionRecord>()
    private val documentGenerations = ConcurrentHashMap<String, Long>()
    private val documentPeaks = ConcurrentHashMap<String, AtomicReference<PeakSnapshot>>()

    private val currentToken = AtomicLong(0L)
    private val documentToken = AtomicLong(0L)

    private val residentSnapshotRef = AtomicReference<ResidentSnapshot?>(null)

    private val bitmapRecordCount = AtomicLong(0L)

    private val historyHotResidentBytes = AtomicLong(0L)
    private val historyHotEntryCount = AtomicLong(0L)
    private val historyColdCompressedBytes = AtomicLong(0L)
    private val coldLoadDecodedTransientBytes = AtomicLong(0L)
    private val deletionDebtBytes = AtomicLong(0L)

    fun enable(enabled: Boolean) {
        DebugMemoryTracker.enabled = enabled
    }

    fun isEnabled(): Boolean = enabled

    fun newDocumentToken(): Long = documentToken.incrementAndGet()

    fun beginOperation(
        name: String,
        documentGeneration: String,
        baseContentToken: String,
        revision: Int,
        transientReserveBytes: Long,
        snapshotState: String
    ): Long {
        if (!enabled) return 0L
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
        synchronized(operationLog) {
            operationLog.addLast(record)
            while (operationLog.size > MAX_OPERATION_LOG) operationLog.removeFirst()
        }
        return token
    }

    fun endOperation(name: String, token: Long) {
        if (!enabled || token == 0L) return
        operations.remove("$name:$token")
    }

    private fun purgeClearedReferences() {
        var cleared: Reference<*>?
        while (referenceQueue.poll().also { cleared = it } != null) {
            val key = cleared as WeakIdentityKey
            bitmapNodes.remove(key)
            ownerEdges.remove(key)
            bitmapRecordCount.updateAndGet { if (it > 0) it - 1 else 0 }
        }
    }

    fun registerBitmap(
        bitmap: Bitmap,
        owner: String,
        operation: String,
        token: Long,
        documentGeneration: String
    ) {
        if (!enabled || bitmap.isRecycled) return
        purgeClearedReferences()
        val key = WeakIdentityKey(bitmap, referenceQueue)
        val node = BitmapNode(
            identity = System.identityHashCode(bitmap),
            width = bitmap.width,
            height = bitmap.height,
            config = bitmap.config,
            bytes = BitmapMemoryBudget.bytes(bitmap)
        )
        bitmapNodes.putIfAbsent(key, node)
        val edges = ownerEdges.getOrPut(key) { Collections.synchronizedList(ArrayList(4)) }
        synchronized(edges) {
            edges.add(OwnerEdge(
                owner = owner,
                operation = operation,
                token = token,
                documentGeneration = documentGeneration,
                acquiredAt = System.currentTimeMillis()
            ))
        }
        updatePeak(documentGeneration)
    }

    fun unregisterBitmap(bitmap: Bitmap, owner: String? = null) {
        if (!enabled || bitmap.isRecycled) return
        purgeClearedReferences()
        val searchKey = WeakIdentityKey(bitmap, referenceQueue)
        val edges = ownerEdges[searchKey]
        if (edges != null) {
            synchronized(edges) {
                if (owner != null) {
                    edges.removeIf { it.owner == owner }
                } else {
                    edges.clear()
                }
                if (edges.isEmpty()) {
                    ownerEdges.remove(searchKey)
                    bitmapNodes.remove(searchKey)
                    bitmapRecordCount.updateAndGet { if (it > 0) it - 1 else 0 }
                }
            }
        }
    }

    fun registerNativeSession(
        handle: Long,
        documentGeneration: String,
        sourceIdentity: String,
        state: String
    ) {
        if (!enabled || handle == 0L) return
        nativeSessions[handle] = NativeSessionRecord(
            handle = handle,
            documentGeneration = documentGeneration,
            sourceIdentity = sourceIdentity,
            state = state,
            createdAt = System.currentTimeMillis()
        )
    }

    fun updateNativeSession(handle: Long, state: String) {
        if (!enabled || handle == 0L) return
        nativeSessions[handle]?.let { existing ->
            nativeSessions[handle] = existing.copy(state = state)
        }
    }

    fun unregisterNativeSession(handle: Long) {
        if (!enabled || handle == 0L) return
        nativeSessions.remove(handle)
    }

    fun registerDocument(generation: String) {
        if (!enabled) return
        documentGenerations[generation] = System.currentTimeMillis()
        documentPeaks.putIfAbsent(generation, AtomicReference(PeakSnapshot(
            residentBitmapBytes = 0L,
            nativeEstimateBytes = 0L,
            activeTransientReserveBytes = 0L,
            combinedEstimatedPeakBytes = 0L,
            documentGeneration = generation,
            timestamp = System.currentTimeMillis()
        )))
    }

    fun unregisterDocument(generation: String) {
        if (!enabled) return
        documentGenerations.remove(generation)
        documentPeaks.remove(generation)
    }

    fun setHistoryHotResident(bytes: Long, count: Int) {
        if (!enabled) return
        historyHotResidentBytes.set(bytes)
        historyHotEntryCount.set(count.toLong())
    }

    fun setHistoryColdCompressed(bytes: Long) {
        if (!enabled) return
        historyColdCompressedBytes.set(bytes)
    }

    fun setColdLoadDecodedTransient(bytes: Long) {
        if (!enabled) return
        coldLoadDecodedTransientBytes.set(bytes)
    }

    fun setDeletionDebt(bytes: Long) {
        if (!enabled) return
        deletionDebtBytes.set(bytes)
    }

    private fun updatePeak(documentGeneration: String) {
        val snap = computeResidentBytes()
        val nativeEstimate = nativeSessions.values.sumOf { 0L }
        val activeTransient = operations.values.sumOf { it.transientReserveBytes }
        val combined = snap.first + nativeEstimate + activeTransient
        val peakRef = documentPeaks[documentGeneration]
        if (peakRef != null) {
            val current = peakRef.get()
            if (combined > current.combinedEstimatedPeakBytes) {
                peakRef.set(PeakSnapshot(
                    residentBitmapBytes = snap.first,
                    nativeEstimateBytes = nativeEstimate,
                    activeTransientReserveBytes = activeTransient,
                    combinedEstimatedPeakBytes = combined,
                    documentGeneration = documentGeneration,
                    timestamp = System.currentTimeMillis()
                ))
            }
        }
    }

    private fun computeResidentBytes(): Pair<Long, Int> {
        purgeClearedReferences()
        var total = 0L
        var count = 0
        for ((key, node) in bitmapNodes) {
            if (key.get() != null) {
                total = BitmapMemoryBudget.saturatingAdd(total, node.bytes)
                count++
            }
        }
        return total to count
    }

    fun clear() {
        if (!enabled) return
        bitmapNodes.clear()
        ownerEdges.clear()
        operations.clear()
        synchronized(operationLog) { operationLog.clear() }
        nativeSessions.clear()
        documentGenerations.clear()
        documentPeaks.clear()
        historyHotResidentBytes.set(0L)
        historyHotEntryCount.set(0L)
        historyColdCompressedBytes.set(0L)
        coldLoadDecodedTransientBytes.set(0L)
        deletionDebtBytes.set(0L)
        residentSnapshotRef.set(null)
    }

    fun snapshot(): ResidentSnapshot {
        if (!enabled) return ResidentSnapshot(
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
            timestamp = 0L
        )
        purgeClearedReferences()
        val byOwner = ConcurrentHashMap<String, Long>()
        val byOperation = ConcurrentHashMap<String, Long>()
        var total = 0L
        var count = 0
        for ((key, node) in bitmapNodes) {
            if (key.get() == null) continue
            total = BitmapMemoryBudget.saturatingAdd(total, node.bytes)
            count++
            val edges = ownerEdges[key]
            if (edges != null) {
                synchronized(edges) {
                    for (edge in edges) {
                        byOwner[edge.owner] = BitmapMemoryBudget.saturatingAdd(
                            byOwner.getOrDefault(edge.owner, 0L), node.bytes
                        )
                        byOperation[edge.operation] = BitmapMemoryBudget.saturatingAdd(
                            byOperation.getOrDefault(edge.operation, 0L), node.bytes
                        )
                    }
                }
            }
        }
        val activeOps = operations.values.toList()
        val sessions = nativeSessions.values.toList()
        val activeTransient = activeOps.sumOf { it.transientReserveBytes }
        val nativeEstimate = sessions.size.toLong() * 0L
        val peakSnap = documentPeaks[currentDocumentGeneration()]?.get()
        val snap = ResidentSnapshot(
            totalBytes = total,
            bitmapCount = count,
            byOwner = byOwner.toMap(),
            byOperation = byOperation.toMap(),
            nativeSessions = sessions,
            activeOperations = activeOps,
            estimatedPeakBytes = peakSnap?.combinedEstimatedPeakBytes ?: 0L,
            peakSnapshot = peakSnap,
            historyHotResidentBytes = historyHotResidentBytes.get(),
            historyHotEntryCount = historyHotEntryCount.get().toInt(),
            historyColdCompressedBytes = historyColdCompressedBytes.get(),
            coldLoadDecodedTransientBytes = coldLoadDecodedTransientBytes.get(),
            deletionDebtBytes = deletionDebtBytes.get(),
            activeTransientReserveBytes = activeTransient,
            nativeEstimateBytes = nativeEstimate,
            timestamp = System.currentTimeMillis()
        )
        residentSnapshotRef.set(snap)
        return snap
    }

    private fun currentDocumentGeneration(): String =
        documentGenerations.keys.firstOrNull() ?: ""

    fun logSnapshot(tag: String) {
        if (!enabled) return
        val snap = snapshot()
        Log.d(TAG, "$tag: bitmaps=${snap.bitmapCount} bytes=${snap.totalBytes} peak=${snap.estimatedPeakBytes} ops=${snap.activeOperations.size} sessions=${snap.nativeSessions.size}")
        if (snap.bitmapCount > 0) {
            val top = snap.byOwner.entries.sortedByDescending { it.value }.take(5)
            Log.d(TAG, "  top owners: ${top.joinToString { "${it.key}=${it.value}" }}")
        }
    }

    fun debugString(): String {
        if (!enabled) return "DebugMemoryTracker: disabled (release build)"
        val snap = snapshot()
        val sb = StringBuilder()
        sb.append("DebugMemoryTracker snapshot:\n")
        sb.append("  totalBytes=${snap.totalBytes} bitmapCount=${snap.bitmapCount} peak=${snap.estimatedPeakBytes}\n")
        sb.append("  residentBitmapBytes=${snap.totalBytes} nativeEstimate=${snap.nativeEstimateBytes} activeTransientReserve=${snap.activeTransientReserveBytes}\n")
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
            sb.append("    handle=${s.handle} state=${s.state} gen=${s.documentGeneration}\n")
        }
        sb.append("  historyHotResident=${snap.historyHotResidentBytes} hotEntries=${snap.historyHotEntryCount}\n")
        sb.append("  historyColdCompressed=${snap.historyColdCompressedBytes}\n")
        sb.append("  coldLoadDecodedTransient=${snap.coldLoadDecodedTransientBytes}\n")
        sb.append("  deletionDebt=${snap.deletionDebtBytes}\n")
        return sb.toString()
    }
}
