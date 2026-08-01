package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import java.util.EnumMap
import java.util.IdentityHashMap
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
internal class SelectionMaskOwnershipLedger {
    private val lock = ReentrantLock()

    private data class Slot(
        val owners: EnumMap<MaskOwnerKind, Int> = EnumMap(MaskOwnerKind::class.java),
    ) {
        val totalRefs: Int get() = owners.values.sum()
        val isLive: Boolean get() = totalRefs > 0
    }

    private val slots = IdentityHashMap<Bitmap, Slot>()

    fun acquire(bitmap: Bitmap?, kind: MaskOwnerKind): MaskOwnerHandle? {
        if (bitmap == null || bitmap.isRecycled) return null
        lock.withLock {
            val slot = slots.getOrElse(bitmap) { Slot().also { slots[bitmap] = it } }
            slot.owners[kind] = (slot.owners[kind] ?: 0) + 1
            return MaskOwnerHandle(this, bitmap, kind)
        }
    }

    /**
     * Attempt to retire the bitmap. Succeeds (returns true and recycles) only when no
     * named owner currently holds it. Returns false if the bitmap is already recycled or
     * still has a live owner.
     */
    fun tryRetire(bitmap: Bitmap): Boolean {
        if (bitmap.isRecycled) return false
        val toRecycle: Bitmap?
        lock.withLock {
            val slot = slots[bitmap]
            if (slot != null && slot.isLive) return false
            slots.remove(bitmap)
            toRecycle = bitmap
        }
        if (toRecycle != null && !toRecycle.isRecycled) {
            try { toRecycle.recycle() } catch (_: Throwable) {}
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
        lock.withLock { slots.clear() }
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
) : AutoCloseable {
    @Volatile private var closed = false
    val ownerKind: MaskOwnerKind get() = kind

    override fun close() {
        if (closed) return
        closed = true
        ledger.release(bitmap, kind)
    }
}
