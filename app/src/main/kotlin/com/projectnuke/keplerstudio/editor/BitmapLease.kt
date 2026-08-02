package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import java.util.Collections
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class DocumentIdentity(
    val sourcePath: String?,
    val baseContentToken: String,
    val generation: String,
    val revision: Int,
)

/**
 * Immutable capture of one editor state and the lease which keeps every referenced Bitmap
 * alive.  Callers must use these fields rather than reading uiState again after acquisition.
 */
internal class LeasedEditorSnapshot internal constructor(
    val state: EditorUiState,
    val identity: DocumentIdentity,
    val previewBitmap: Bitmap?,
    val originalPreviewBitmap: Bitmap?,
    val selectionLayers: List<SelectionLayer>,
    private val lease: BitmapLease,
) : AutoCloseable {
    val leaseId: Long get() = lease.leaseId
    /** Retain the exact captured references for an independent worker owner. */
    internal fun retain(tag: String): LeasedEditorSnapshot? = lease.retain(tag, this)
    override fun close() = lease.close()
}

/** A lifetime lease pinning a precisely captured set of state-owned Bitmaps. */
internal class BitmapLease internal constructor(
    val tag: String,
    val identity: DocumentIdentity,
    private val ledger: BitmapLeaseLedger,
    private val bitmaps: Set<Bitmap>,
    val leaseId: Long,
) : AutoCloseable {
    internal class BitmapPin internal constructor(
        private val ledger: BitmapLeaseLedger,
        private val bitmap: Bitmap,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) ledger.releasePin(bitmap)
        }
    }

    private val closed = AtomicBoolean(false)

    fun matchesIdentity(
        sourcePath: String?,
        baseContentToken: String,
        revision: Int,
        generation: String? = null,
    ): Boolean =
        identity.sourcePath == sourcePath &&
            identity.baseContentToken == baseContentToken &&
            identity.revision == revision &&
            (generation == null || identity.generation == generation)

    override fun close() {
        if (closed.compareAndSet(false, true)) ledger.release(leaseId)
    }

    internal fun retain(tag: String, snapshot: LeasedEditorSnapshot): LeasedEditorSnapshot? {
        if (closed.get()) return null
        return ledger.retain(tag, snapshot)
    }

    companion object {
        fun identityBitmapSet(): MutableSet<Bitmap> =
            Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())

        internal fun capture(
            tag: String,
            state: EditorUiState,
            ledger: BitmapLeaseLedger,
            documentGeneration: String,
        ): LeasedEditorSnapshot? = ledger.capture(tag, state, documentGeneration)

        /** Compatibility only for old unit tests; production callers use LeasedEditorSnapshot. */
        fun acquire(tag: String, state: EditorUiState, ledger: BitmapLeaseLedger): BitmapLease? =
            ledger.legacyAcquire(tag, state)
    }
}

/**
 * The single managed-Bitmap lifetime authority. State transitions and snapshot acquisition
 * share this lock; no lock is held while a caller copies, scales, renders, or calls JNI.
 */
internal class BitmapLeaseLedger {
    private val lock = ReentrantLock()
    private val leaseIdCounter = AtomicLong(1L)

    private data class Slot(
        var stateRefs: Int = 0,
        var leaseRefs: Int = 0,
        var retired: Boolean = false,
    )
    private data class LeaseEntry(val bitmaps: MutableSet<Bitmap>)
    private val slots = IdentityHashMap<Bitmap, Slot>()
    private val leases = LinkedHashMap<Long, LeaseEntry>()

    fun capture(
        tag: String,
        state: EditorUiState,
        documentGeneration: String,
    ): LeasedEditorSnapshot? = lock.withLock {
        val captured = BitmapLease.identityBitmapSet()
        state.previewBitmap?.let(captured::add)
        state.originalPreviewBitmap?.let(captured::add)
        state.selectionLayers.forEach { captured.add(it.bitmap) }
        if (captured.isEmpty() || captured.any { it.isRecycled }) return@withLock null
        val identity =
            DocumentIdentity(
                state.sourcePath,
                state.baseContentToken,
                documentGeneration,
                state.revision,
            )
        val leaseId = leaseIdCounter.getAndIncrement()
        val entry = LeaseEntry(BitmapLease.identityBitmapSet())
        captured.forEach { bitmap ->
            entry.bitmaps.add(bitmap)
            slots.getOrPut(bitmap) { Slot() }.leaseRefs++
        }
        leases[leaseId] = entry
        LeasedEditorSnapshot(
            state = state,
            identity = identity,
            previewBitmap = state.previewBitmap,
            originalPreviewBitmap = state.originalPreviewBitmap,
            selectionLayers = state.selectionLayers,
            lease = BitmapLease(tag, identity, this, entry.bitmaps, leaseId),
        )
    }

    internal fun retain(tag: String, snapshot: LeasedEditorSnapshot): LeasedEditorSnapshot? =
        lock.withLock {
            val captured = BitmapLease.identityBitmapSet()
            snapshot.previewBitmap?.let(captured::add)
            snapshot.originalPreviewBitmap?.let(captured::add)
            snapshot.selectionLayers.forEach { captured.add(it.bitmap) }
            if (captured.isEmpty() || captured.any { it.isRecycled }) return@withLock null
            val leaseId = leaseIdCounter.getAndIncrement()
            val entry = LeaseEntry(BitmapLease.identityBitmapSet())
            captured.forEach { bitmap ->
                entry.bitmaps.add(bitmap)
                slots.getOrPut(bitmap) { Slot() }.leaseRefs++
            }
            leases[leaseId] = entry
            LeasedEditorSnapshot(
                state = snapshot.state,
                identity = snapshot.identity,
                previewBitmap = snapshot.previewBitmap,
                originalPreviewBitmap = snapshot.originalPreviewBitmap,
                selectionLayers = snapshot.selectionLayers,
                lease = BitmapLease(tag, snapshot.identity, this, entry.bitmaps, leaseId),
            )
        }

    internal fun legacyAcquire(tag: String, state: EditorUiState): BitmapLease? = lock.withLock {
        val captured = BitmapLease.identityBitmapSet()
        state.previewBitmap?.let(captured::add)
        state.originalPreviewBitmap?.let(captured::add)
        state.selectionLayers.forEach { captured.add(it.bitmap) }
        if (captured.isEmpty() || captured.any { it.isRecycled }) return@withLock null
        val id = DocumentIdentity(state.sourcePath, state.baseContentToken, "", state.revision)
        val leaseId = leaseIdCounter.getAndIncrement()
        val entry = LeaseEntry(captured)
        captured.forEach { slots.getOrPut(it) { Slot() }.leaseRefs++ }
        leases[leaseId] = entry
        BitmapLease(tag, id, this, captured, leaseId)
    }

    /** Must be called while the caller's state publication is still inside this lock. */
    fun <T> withStateTransition(block: () -> T): T = lock.withLock(block)

    /** Replace the one state-owner set and return only Bitmaps safe to recycle. */
    fun replaceState(previous: EditorUiState, next: EditorUiState): List<Bitmap> = lock.withLock {
        val before = stateBitmapSet(previous)
        val after = stateBitmapSet(next)
        before.forEach { bitmap ->
            if (bitmap !in after) {
                val slot = slots.getOrPut(bitmap) { Slot() }
                slot.stateRefs = (slot.stateRefs - 1).coerceAtLeast(0)
                slot.retired = true
            }
        }
        after.forEach { bitmap ->
            if (bitmap !in before) {
                slots.getOrPut(bitmap) { Slot() }.stateRefs++
                slots[bitmap]?.retired = false
            }
        }
        collectRetiredLocked(before + after)
    }

    /** Compatibility hook for narrow callers; state transitions should use [replaceState]. */
    fun retireStateBitmap(bitmap: Bitmap): Bitmap? = lock.withLock {
        val slot = slots[bitmap] ?: return@withLock if (!bitmap.isRecycled) bitmap else null
        slot.stateRefs = (slot.stateRefs - 1).coerceAtLeast(0)
        slot.retired = true
        collectRetiredLocked(setOf(bitmap)).firstOrNull()
    }

    fun release(leaseId: Long) {
        val toRecycle = lock.withLock {
            val entry = leases.remove(leaseId) ?: return@withLock emptyList()
            entry.bitmaps.forEach { bitmap ->
                slots[bitmap]?.let { it.leaseRefs = (it.leaseRefs - 1).coerceAtLeast(0) }
            }
            collectRetiredLocked(entry.bitmaps)
        }
        recycle(toRecycle)
    }

    fun pinBitmap(bitmap: Bitmap?): BitmapLease.BitmapPin? = lock.withLock {
        val value = bitmap ?: return@withLock null
        if (value.isRecycled) return@withLock null
        slots.getOrPut(value) { Slot() }.leaseRefs++
        BitmapLease.BitmapPin(this, value)
    }

    internal fun releasePin(bitmap: Bitmap) {
        val toRecycle = lock.withLock {
            slots[bitmap]?.let { it.leaseRefs = (it.leaseRefs - 1).coerceAtLeast(0) }
            collectRetiredLocked(setOf(bitmap))
        }
        recycle(toRecycle)
    }

    fun releaseState(state: EditorUiState) {
        val toRecycle = lock.withLock {
            val set = stateBitmapSet(state)
            set.forEach { bitmap ->
            slots[bitmap]?.let { it.stateRefs = (it.stateRefs - 1).coerceAtLeast(0) }
            slots[bitmap]?.retired = true
            }
            collectRetiredLocked(set)
        }
        recycle(toRecycle)
    }

    fun releaseAll() {
        val toRecycle = lock.withLock {
            val all = slots.keys.toList()
            leases.clear()
            slots.clear()
            all
        }
        recycle(toRecycle)
    }

    /** Retire every state owner without invalidating outstanding worker leases. */
    fun shutdown(): List<Bitmap> = lock.withLock {
        slots.values.forEach {
            it.stateRefs = 0
            it.retired = true
        }
        collectRetiredLocked(slots.keys.toSet())
    }

    fun resetForTest() = lock.withLock {
        slots.clear()
        leases.clear()
        leaseIdCounter.set(1L)
    }

    private fun stateBitmapSet(state: EditorUiState): Set<Bitmap> = BitmapLease.identityBitmapSet().apply {
        state.previewBitmap?.let(::add)
        state.originalPreviewBitmap?.let(::add)
        state.selectionLayers.forEach { add(it.bitmap) }
    }

    private fun collectRetiredLocked(candidates: Set<Bitmap>): List<Bitmap> {
        val result = ArrayList<Bitmap>()
        candidates.forEach { bitmap ->
            val slot = slots[bitmap] ?: return@forEach
            if (slot.retired && slot.stateRefs == 0 && slot.leaseRefs == 0) {
                slots.remove(bitmap)
                if (!bitmap.isRecycled) result += bitmap
            }
        }
        return result
    }

    private fun recycle(bitmaps: List<Bitmap>) {
        bitmaps.forEach { bitmap -> runCatching { if (!bitmap.isRecycled) bitmap.recycle() } }
    }
}
