package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class DocumentIdentity(
    val sourcePath: String?,
    val baseContentToken: String,
    val generation: String,
)

/**
 * A lifetime lease pinning state-owned Bitmaps so they are not recycled while a worker thread
 * copies or reads them.
 *
 * - [BitmapLease.acquire] captures the current document identity and all non-recycled Bitmaps.
 * - State updates that replace or remove leased Bitmaps register a retirement but defer
 *   recycling until [close] is called on this lease.
 * - Cancellation, failure and supersession must close in `finally`.
 * - No global lock is held during Bitmap copy, scale, render or native operations.
 */
internal class BitmapLease internal constructor(
    val tag: String,
    val identity: DocumentIdentity,
    private val ledger: BitmapLeaseLedger,
    private val bitmaps: Set<Bitmap>,
    val leaseId: Long,
) : AutoCloseable {

    /**
     * A minimal single-Bitmap pin: blocks retirement/recycle of [bitmap] until [close].
     * Used by transient UI consumers (e.g. histogram sampler) that hold one source Bitmap
     * across a worker hop without claiming the broader state identity.
     */
    internal class BitmapPin internal constructor(
        private val ledger: BitmapLeaseLedger,
        private val bitmap: Bitmap?,
    ) : AutoCloseable {
        @Volatile private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            ledger.releasePin(bitmap)
        }
    }
    @Volatile private var closed = false

    fun matchesIdentity(
        sourcePath: String?,
        baseContentToken: String,
        revision: Int,
        generation: String? = null,
    ): Boolean {
        val g = generation ?: identity.generation
        return identity.sourcePath == sourcePath &&
            identity.baseContentToken == baseContentToken &&
            identity.generation == g
    }

    override fun close() {
        if (closed) return
        closed = true
        ledger.release(leaseId, bitmaps)
    }

    companion object {
        fun identityBitmapSet(): MutableSet<Bitmap> =
            Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())

        fun acquire(
            tag: String,
            state: EditorUiState,
            ledger: BitmapLeaseLedger,
        ): BitmapLease? {
            val captured = identityBitmapSet()
            state.previewBitmap?.takeUnless(Bitmap::isRecycled)?.let(captured::add)
            state.originalPreviewBitmap?.takeUnless(Bitmap::isRecycled)?.let(captured::add)
            state.selectionLayers.forEach { layer ->
                layer.bitmap.takeUnless(Bitmap::isRecycled)?.let(captured::add)
            }
            if (captured.isEmpty()) return null

            val docId = DocumentIdentity(
                sourcePath = state.sourcePath,
                baseContentToken = state.baseContentToken,
                generation = ledger.nextGeneration(),
            )
            val leaseId = ledger.nextLeaseId()
            ledger.registerLease(leaseId, captured, docId)
            return BitmapLease(tag, docId, ledger, captured, leaseId)
        }
    }
}

/**
 * Ledger that defers Bitmap recycling until all state-owner refs and all leases are released.
 */
internal class BitmapLeaseLedger {
    private val lock = ReentrantLock()
    private val leaseIdCounter = AtomicLong(1L)
    private val generationCounter = AtomicInteger(0)

    private data class Slot(
        var stateRemovedCount: Int = 0,
        var leaseRef: Int = 0,
    )
    private data class LeaseEntry(
        val identity: DocumentIdentity,
        val bitmaps: MutableSet<Bitmap>,
    )

    private val slots = IdentityHashMap<Bitmap, Slot>()
    private val leases = LinkedHashMap<Long, LeaseEntry>()

    fun nextGeneration(): String = generationCounter.incrementAndGet().toString()
    fun nextLeaseId(): Long = leaseIdCounter.getAndIncrement()

    fun registerLease(leaseId: Long, bitmaps: Set<Bitmap>, identity: DocumentIdentity) {
        lock.withLock {
            val entry = LeaseEntry(identity, BitmapLease.identityBitmapSet())
            for (bitmap in bitmaps) {
                entry.bitmaps.add(bitmap)
                val slot = slots.getOrElse(bitmap) { Slot().also { slots[bitmap] = it } }
                slot.leaseRef++
            }
            leases[leaseId] = entry
        }
    }

    fun retireStateBitmap(bitmap: Bitmap): Bitmap? {
        if (bitmap.isRecycled) return null
        lock.withLock {
            val slot = slots[bitmap] ?: return bitmap
            slot.stateRemovedCount++
            return if (slot.leaseRef == 0) {
                slots.remove(bitmap)
                bitmap
            } else {
                null
            }
        }
    }

    fun release(leaseId: Long, bitmaps: Set<Bitmap>) {
        val toRecycle = ArrayList<Bitmap>(bitmaps.size)
        lock.withLock {
            leases.remove(leaseId) ?: return
            for (bitmap in bitmaps) {
                val slot = slots[bitmap] ?: continue
                if (slot.leaseRef > 0) slot.leaseRef--
                if (slot.stateRemovedCount > 0 && slot.leaseRef == 0) {
                    slots.remove(bitmap)
                    if (!bitmap.isRecycled) toRecycle.add(bitmap)
                }
            }
        }
        for (b in toRecycle) {
            try { b.recycle() } catch (_: Throwable) {}
        }
    }

    fun releaseAll() {
        val toRecycle = ArrayList<Bitmap>()
        lock.withLock {
            leases.clear()
            for (entry in slots.entries.toList()) {
                val bitmap = entry.key
                val slot = entry.value
                slot.leaseRef = 0
                slot.stateRemovedCount = 0
                slots.remove(bitmap)
                if (!bitmap.isRecycled) toRecycle.add(bitmap)
            }
        }
        for (b in toRecycle) {
            try { b.recycle() } catch (_: Throwable) {}
        }
    }

    /**
     * Pin a single Bitmap against retirement/recycle until [BitmapLease.BitmapPin.close].
     * Returns null if [bitmap] is recycled or null. The pin is identity-keyed: equal
     * Bitmap references share the same slot, so concurrent pin callers are ref-counted.
     */
    fun pinBitmap(bitmap: Bitmap?): BitmapLease.BitmapPin? {
        if (bitmap == null || bitmap.isRecycled) return null
        lock.withLock {
            val slot = slots.getOrElse(bitmap) { Slot().also { slots[bitmap] = it } }
            slot.leaseRef++
            return BitmapLease.BitmapPin(this, bitmap)
        }
    }

    internal fun releasePin(bitmap: Bitmap?) {
        if (bitmap == null) return
        val toRecycle = ArrayList<Bitmap>(1)
        lock.withLock {
            val slot = slots[bitmap] ?: return
            if (slot.leaseRef > 0) slot.leaseRef--
            if (slot.stateRemovedCount > 0 && slot.leaseRef == 0) {
                slots.remove(bitmap)
                if (!bitmap.isRecycled) toRecycle.add(bitmap)
            }
        }
        for (b in toRecycle) {
            try { b.recycle() } catch (_: Throwable) {}
        }
    }

    fun resetForTest() {
        lock.withLock {
            slots.clear()
            leases.clear()
            leaseIdCounter.set(1L)
            generationCounter.set(0)
        }
    }
}