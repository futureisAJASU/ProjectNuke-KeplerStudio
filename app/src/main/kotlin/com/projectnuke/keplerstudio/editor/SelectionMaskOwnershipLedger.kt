package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import java.util.EnumMap
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Named-owner ledger for selection-mask bitmaps.
 *
 * Tracks every outstanding reference to a layer bitmap by [MaskOwnerKind] so that
 * retirement of a mask defers recycling until every named owner has released. The ledger is
 * identity-keyed (one slot per Bitmap reference) and additive per owner kind, so multiple
 * concurrent owners of the same kind stack correctly.
 *
 * Companion to [BitmapLeaseLedger], which covers identity-level ref counts for arbitrary
 * bitmap consumers (snapshot leases, transient UI pins). This ledger adds semantic
 * ownership names for the mask-specific call sites (active preview, history snapshot,
 * brush working copy, etc.) so we can:
 *
 * - Assert that a retired mask cannot be recycled while any named owner holds it.
 * - Surface live owner sets for debug instrumentation.
 * - Force typed ownership at every future mask-reference site by requiring
 *   [acquire] to declare a [MaskOwnerKind].
 */
internal class SelectionMaskOwnershipLedger(
    private val pinBitmap: ((Bitmap) -> BitmapLease.BitmapPin?)? = null,
    private val layerBudget: () -> Int = { Int.MAX_VALUE },
    private val byteBudget: () -> Long = { Long.MAX_VALUE },
) {
    private val lock = ReentrantLock()
    private val reservationIds = AtomicLong(1L)
    private data class ReservationEntry(val owner: String, val bytes: Long, val layers: Int)
    private val reservations = LinkedHashMap<Long, ReservationEntry>()
    private var reservedBytes: Long = 0L
    private var reservedLayers: Int = 0
    private var activeBytes: Long = 0L
    private var activeLayers: Int = 0

    private data class Slot(
        val owners: EnumMap<MaskOwnerKind, Int> = EnumMap(MaskOwnerKind::class.java),
    ) {
        val totalRefs: Int get() = owners.values.sum()
        val isLive: Boolean get() = totalRefs > 0
    }

    private val slots = IdentityHashMap<Bitmap, Slot>()

    fun reserve(owner: String, bytes: Long, documentLayerDelta: Int = 0): MaskReservation? {
        if (owner.isBlank() || bytes <= 0L || documentLayerDelta < 0) return null
        return lock.withLock {
            val limit = byteBudget().coerceAtLeast(0L)
            if (bytes > limit - activeBytes - reservedBytes) return null
            if (documentLayerDelta > layerBudget().coerceAtLeast(0) - activeLayers - reservedLayers) return null
            val id = reservationIds.getAndIncrement()
            reservations[id] = ReservationEntry(owner, bytes, documentLayerDelta)
            reservedBytes += bytes
            reservedLayers += documentLayerDelta
            return MaskReservation(this, id, owner, bytes, documentLayerDelta)
        }
    }

    fun reservedBytes(): Long = lock.withLock { reservedBytes }
    fun reservedLayers(): Int = lock.withLock { reservedLayers }
    fun activeBytes(): Long = lock.withLock { activeBytes }
    fun activeLayers(): Int = lock.withLock { activeLayers }

    /** Reconciles the committed document mask ownership with the same admission ledger. */
    fun reconcileActiveState(layers: List<SelectionLayer>) {
        lock.withLock {
            val unique = java.util.Collections.newSetFromMap(IdentityHashMap<Bitmap, Boolean>())
            layers.forEach { unique.add(it.bitmap) }
            activeBytes = unique.sumOf { BitmapMemoryBudget.bytes(it) }
            activeLayers = layers.size
        }
    }

    internal fun releaseReservation(id: Long, owner: String, bytes: Long, layers: Int) {
        lock.withLock {
            val entry = reservations[id] ?: return
            if (entry.owner != owner || entry.bytes != bytes || entry.layers != layers) return
            reservations.remove(id)
            reservedBytes = (reservedBytes - bytes).coerceAtLeast(0L)
            reservedLayers = (reservedLayers - layers).coerceAtLeast(0)
        }
    }

    fun acquire(bitmap: Bitmap?, kind: MaskOwnerKind): MaskOwnerHandle? {
        val value = bitmap ?: return null
        if (value.isRecycled) return null

        // Pin before taking the semantic-ledger lock. State settlement holds the
        // bitmap-ledger lock while reconciling this ledger; reversing that order
        // here would deadlock acquisition against publication/replacement.
        val pin = pinBitmap?.invoke(value)
        if (pinBitmap != null && pin == null) return null
        val handle = lock.withLock {
            if (value.isRecycled) {
                null
            } else {
                val slot = slots.getOrElse(value) { Slot().also { slots[value] = it } }
                slot.owners[kind] = (slot.owners[kind] ?: 0) + 1
                MaskOwnerHandle(this, value, kind, pin)
            }
        }
        if (handle == null) pin?.close()
        return handle
    }

    /**
     * Attempt to retire the semantic ledger entry. Succeeds only when no named owner
     * currently holds it. Bitmap recycling is deliberately owned by [BitmapLeaseLedger],
     * which also tracks document state and transient pins; this ledger never recycles.
     */
    fun tryRetire(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return false
        lock.withLock {
            val slot = slots[bitmap]
            if (slot != null && slot.isLive) return false
            slots.remove(bitmap)
        }
        return true
    }

    /** Snapshot of current named owners for [bitmap]. Used for diagnostics / tests. */
    fun ownersFor(bitmap: Bitmap): Set<MaskOwnerKind> {
        lock.withLock {
            val slot = slots[bitmap] ?: return emptySet()
            return slot.owners.entries.filter { it.value > 0 }.map { it.key }.toSet()
        }
    }

    fun liveCount(): Int {
        lock.withLock {
            return slots.values.count { it.isLive }
        }
    }

    fun totalHandleCount(): Int {
        lock.withLock {
            return slots.values.sumOf { it.totalRefs }
        }
    }

    fun resetForTest() {
        lock.withLock {
            slots.clear()
            reservations.clear()
            reservedBytes = 0L
            reservedLayers = 0
            activeBytes = 0L
            activeLayers = 0
            reservationIds.set(1L)
        }
    }

    /**
     * Decrement the named-owner ref count for [bitmap]. Does NOT recycle the bitmap; the
     * caller is expected to invoke [tryRetire] separately once all owners are released.
     */
    internal fun release(bitmap: Bitmap?, kind: MaskOwnerKind) {
        if (bitmap == null) return
        lock.withLock {
            val slot = slots[bitmap] ?: return
            val current = slot.owners[kind] ?: 0
            if (current > 1) {
                slot.owners[kind] = current - 1
                return
            }
            slot.owners.remove(kind)
            if (!slot.isLive) {
                slots.remove(bitmap)
            }
        }
    }
}

internal class MaskReservation internal constructor(
    private val ledger: SelectionMaskOwnershipLedger,
    private val id: Long,
    private val owner: String,
    private val bytes: Long,
    private val layers: Int,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (closed.compareAndSet(false, true)) ledger.releaseReservation(id, owner, bytes, layers)
    }
}

/**
 * Typed semantic owner kinds for selection-mask bitmaps. The list is intentionally closed
 * so every mask-reference site must declare which role it is serving.
 */
internal enum class MaskOwnerKind {
    /** The active editor state owner (uiState.selectionLayers[i]). */
    ActiveState,

    /** A history-snapshot copy (EditorHistorySnapshot.selectionLayers[i]). */
    HistorySnapshot,

    /** Live-preview worker output (SelectionLivePreviewActions). */
    LivePreview,

    /** Native-bake transform input copy (SelectionNativeBakeActions). */
    BakeTransform,

    /** Crop-transform input copy (CropEditActions). */
    CropTransform,

    /** Brush working copy owned while a stroke is open (EditorViewModel.beginBrushStroke). */
    BrushWorkingCopy,

    /** Layer duplication source copy (SelectionLayerExtraActions). */
    DuplicateSource,

    /** Background-selection source copy (SelectionLayerExtraActions). */
    BackgroundSource,

    /** Brush-selection subject-extract copy (SelectionEditorActions). */
    SubjectExtract,

    /** Draft persistence write copy (DraftGeneration). */
    DraftPersistence,

    /** Transient UI consumer (preview card overlay / preview image). */
    TransientUi,
}

/** Handle acquired via [SelectionMaskOwnershipLedger.acquire]. */
internal class MaskOwnerHandle internal constructor(
    private val ledger: SelectionMaskOwnershipLedger,
    private val bitmap: Bitmap?,
    private val kind: MaskOwnerKind,
    private val bitmapPin: BitmapLease.BitmapPin?,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    val ownerKind: MaskOwnerKind get() = kind

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            ledger.release(bitmap, kind)
            bitmapPin?.close()
        }
    }
}
