package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.util.Log
import com.projectnuke.keplerstudio.BuildConfig
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal object DebugMemoryTracker {
    private const val TAG = "KeplerDebugMem"
    private const val MAX_ENTRIES = 256
    private const val MAX_OPERATION_LOG = 64

    @Volatile
    private var enabled: Boolean = BuildConfig.DEBUG

    data class BitmapRecord(
        val identity: Int,
        val owner: String,
        val operation: String,
        val token: Long,
        val width: Int,
        val height: Int,
        val config: Bitmap.Config?,
        val bytes: Long,
        val acquiredAt: Long
    ) {
        val isAlive: Boolean get() = true
    }

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

    data class ResidentSnapshot(
        val totalBytes: Long,
        val bitmapCount: Int,
        val byOwner: Map<String, Long>,
        val byOperation: Map<String, Long>,
        val nativeSessions: List<NativeSessionRecord>,
        val activeOperations: List<OperationRecord>,
        val estimatedPeakBytes: Long,
        val timestamp: Long
    )

    private val bitmaps = Collections.newMapFromMap(
        IdentityHashMap<Bitmap, BitmapRecord>()
    )
    private val operations = ConcurrentHashMap<String, OperationRecord>()
    private val operationLog = ArrayDeque<OperationRecord>(MAX_OPERATION_LOG)
    private val nativeSessions = ConcurrentHashMap<Long, NativeSessionRecord>()
    private val documentGenerations = ConcurrentHashMap<String, Long>()

    private val currentToken = AtomicLong(0L)
    private val currentPeakBytes = AtomicLong(0L)
    private val documentToken = AtomicLong(0L)

    private val snapshotRef = AtomicReference<ResidentSnapshot?>(null)

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

    fun registerBitmap(
        bitmap: Bitmap,
        owner: String,
        operation: String,
        token: Long,
        documentGeneration: String
    ) {
        if (!enabled || bitmap.isRecycled) return
        val record = BitmapRecord(
            identity = System.identityHashCode(bitmap),
            owner = owner,
            operation = operation,
            token = token,
            width = bitmap.width,
            height = bitmap.height,
            config = bitmap.config,
            bytes = BitmapMemoryBudget.bytes(bitmap),
            acquiredAt = System.currentTimeMillis()
        )
        synchronized(bitmaps) {
            if (bitmaps.size >= MAX_ENTRIES) {
                val iterator = bitmaps.entries.iterator()
                if (iterator.hasNext()) iterator.remove()
            }
            bitmaps[bitmap] = record
        }
        val total = currentTotalBytes()
        if (total > currentPeakBytes.get()) currentPeakBytes.set(total)
    }

    fun unregisterBitmap(bitmap: Bitmap) {
        if (!enabled) return
        synchronized(bitmaps) {
            bitmaps.remove(bitmap)
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
    }

    fun unregisterDocument(generation: String) {
        if (!enabled) return
        documentGenerations.remove(generation)
    }

    fun clear() {
        if (!enabled) return
        synchronized(bitmaps) { bitmaps.clear() }
        operations.clear()
        synchronized(operationLog) { operationLog.clear() }
        nativeSessions.clear()
        documentGenerations.clear()
        currentPeakBytes.set(0L)
    }

    private fun currentTotalBytes(): Long {
        synchronized(bitmaps) {
            return BitmapMemoryBudget.saturatingAdd(
                *bitmaps.values.map(BitmapRecord::bytes).toLongArray()
            )
        }
    }

    fun snapshot(): ResidentSnapshot {
        if (!enabled) return ResidentSnapshot(0L, 0, emptyMap(), emptyMap(), emptyList(), emptyList(), 0L, 0L)
        val byOwner = ConcurrentHashMap<String, Long>()
        val byOperation = ConcurrentHashMap<String, Long>()
        var total = 0L
        var count = 0
        synchronized(bitmaps) {
            for (record in bitmaps.values) {
                total = BitmapMemoryBudget.saturatingAdd(total, record.bytes)
                count++
                byOwner[record.owner] = BitmapMemoryBudget.saturatingAdd(
                    byOwner.getOrDefault(record.owner, 0L), record.bytes
                )
                byOperation[record.operation] = BitmapMemoryBudget.saturatingAdd(
                    byOperation.getOrDefault(record.operation, 0L), record.bytes
                )
            }
        }
        val activeOps = operations.values.toList()
        val sessions = nativeSessions.values.toList()
        val peak = currentPeakBytes.get()
        val snap = ResidentSnapshot(
            totalBytes = total,
            bitmapCount = count,
            byOwner = byOwner.toMap(),
            byOperation = byOperation.toMap(),
            nativeSessions = sessions,
            activeOperations = activeOps,
            estimatedPeakBytes = peak,
            timestamp = System.currentTimeMillis()
        )
        snapshotRef.set(snap)
        return snap
    }

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
        return sb.toString()
    }
}
