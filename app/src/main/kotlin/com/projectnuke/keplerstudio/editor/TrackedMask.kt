package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Structured ownership for a model-produced mask bitmap and its exact diagnostic edge.
 *
 * This replaces the raw "Bitmap + separately mutable Long diagnosticEdge" pattern that
 * was easy to leak or double-release. TrackedMask owns the bitmap AND the diagnostic
 * edge together, registers the edge immediately when the final bitmap is allocated, and
 * exposes only idempotent release/recycle/transfer operations:
 *
 * - [release] / [recycleAndRelease] idempotent; second call is a no-op
 * - [transferTo] re-keys the edge to a new owner exactly once
 * - [adopt] hands the bitmap to the caller and releases the diagnostic edge exactly once
 * - if an exception is thrown during caller transfer, the caller has not adopted yet, so
 *   [recycleAndRelease] in finally remains exact-once
 *
 * Helper failures (during construction of the mask) MUST call [recycleAndRelease] so
 * the partial mask is recycled and its edge released. Stale/cancelled results settle
 * the [TrackedMask] WITHOUT publishing — only successful transfer publishes anything.
 *
 * The final mask Bitmap remains visible to the caller while internal tensor/row/output
 * buffers stay alive in the model runner — TrackedMask does not lifetime-link the mask
 * to those internal buffers; the runner releases them separately through
 * [MemoryTrackerScope.releaseTransient].
 */
class TrackedMask private constructor(
    val bitmap: Bitmap,
    val modelId: String,
    val modelVersion: String,
    val operationToken: Long,
    val documentGeneration: String,
    val confidenceMetrics: ModelConfidence,
    val maskQuality: MaskQualityResult?,
    private val scope: MemoryTrackerScope?,
    private var edge: Long,
) {
    private val settled = AtomicBoolean(false)
    private val transferred = AtomicReference("")

    val isSettled: Boolean get() = settled.get()

    /** Current diagnostic owner label (empty if released). */
    val diagnosticOwner: String get() = transferred.get()

    /** Idempotent release of the diagnostic edge without recycling the bitmap. */
    fun release(): Boolean {
        if (!settled.compareAndSet(false, true)) return false
        scope?.release(edge)
        edge = 0L
        transferred.set("")
        return true
    }

    /** Idempotent recycle + release. Returns true if this call settled the mask. */
    fun recycleAndRelease(): Boolean {
        if (!settled.compareAndSet(false, true)) return false
        val ownedScope = scope
        val ownedEdge = edge
        edge = 0L
        transferred.set("")
        try {
            if (!bitmap.isRecycled) bitmap.recycle()
        } finally {
            ownedScope?.release(ownedEdge)
        }
        return true
    }

    /**
     * Idempotent transfer to a new diagnostic owner. Use when the same Bitmap is
     * adopted by a different owner mid-lifecycle (e.g. UI-state reconciliation).
     * Returns true if this call captured the transfer.
     */
    fun transferTo(owner: String): Boolean {
        if (settled.get()) return false
        val ownedScope = scope
        if (ownedScope == null) {
            transferred.set(owner)
            return true
        }
        val destinationEdge = ownedScope.track(bitmap, owner)
        if (destinationEdge == 0L) return false
        val sourceEdge = edge
        edge = destinationEdge
        transferred.set(owner)
        ownedScope.release(sourceEdge)
        return true
    }

    /**
     * Caller adopts the bitmap exactly once; the diagnostic edge is released. After
     * adoption the caller owns the bitmap's lifecycle and TrackedMask is settled.
     */
    fun adopt(): Bitmap {
        release()
        return bitmap
    }

    internal fun exactEdge(): Long = edge

    companion object {
        /**
         * Allocate a tracked mask with immediate edge registration. The edge is
         * registered against [owner] synchronously; if the tracker is null or the
         * operation is already ended, the edge is 0L (no-op on release).
         */
        internal fun acquire(
            bitmap: Bitmap,
            scope: MemoryTrackerScope?,
            owner: String,
            modelId: String,
            modelVersion: String,
            operationToken: Long,
            documentGeneration: String,
            confidenceMetrics: ModelConfidence,
            maskQuality: MaskQualityResult? = null,
        ): TrackedMask {
            val edge = scope?.track(bitmap, owner) ?: 0L
            return TrackedMask(
                bitmap = bitmap,
                modelId = modelId,
                modelVersion = modelVersion,
                operationToken = operationToken,
                documentGeneration = documentGeneration,
                confidenceMetrics = confidenceMetrics,
                maskQuality = maskQuality,
                scope = scope,
                edge = edge,
            )
        }
    }
}
