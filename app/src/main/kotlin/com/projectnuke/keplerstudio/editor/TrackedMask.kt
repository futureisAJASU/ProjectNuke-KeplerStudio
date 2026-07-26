package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap

/**
 * Atomic ownership of a model-produced mask and its diagnostic edge.
 *
 * All lifecycle fields are protected by [lock].  In particular, diagnostic transfer is
 * performed while the source is still owned: a failed destination registration leaves
 * this object unchanged, and a successful registration is installed before the old edge
 * is released.  Cleanup paths deliberately swallow tracker/recycle failures.
 */
class TrackedMask private constructor(
    val bitmap: Bitmap,
    val modelId: String,
    val modelVersion: String,
    val operationToken: Long,
    val documentGeneration: String,
    confidenceMetrics: ModelConfidence,
    maskQuality: MaskQualityResult?,
    private val edgeTracker: EdgeTracker?,
    edge: Long,
    owner: String,
) {
    private sealed interface State {
        data class Owned(val edge: Long, val owner: String) : State
        data object Adopted : State
        data object Recycled : State
        data object ReleasedWithoutRecycle : State
    }

    internal interface EdgeTracker {
        fun track(bitmap: Bitmap, owner: String): Long
        fun release(edge: Long)
    }

    private val lock = Any()
    private var state: State = State.Owned(edge, owner)
    private var metadata = Metadata(confidenceMetrics, maskQuality)

    private data class Metadata(
        val confidence: ModelConfidence,
        val quality: MaskQualityResult?,
    )

    val confidenceMetrics: ModelConfidence get() = synchronized(lock) { metadata.confidence }
    val maskQuality: MaskQualityResult? get() = synchronized(lock) { metadata.quality }
    val isSettled: Boolean get() = synchronized(lock) { state !is State.Owned }
    val diagnosticOwner: String
        get() = synchronized(lock) { (state as? State.Owned)?.owner.orEmpty() }

    /** Finalizes metadata without changing bitmap/edge ownership. */
    internal fun finalizeMetadata(
        confidenceMetrics: ModelConfidence,
        maskQuality: MaskQualityResult? = null,
    ): Boolean = synchronized(lock) {
        if (state !is State.Owned) return@synchronized false
        metadata = Metadata(confidenceMetrics, maskQuality)
        true
    }

    /** Returns the bitmap to exactly one adopter, or null after any settlement. */
    fun adoptOrNull(): Bitmap? = synchronized(lock) {
        val owned = state as? State.Owned ?: return@synchronized null
        if (bitmap.isRecycled) {
            state = State.Recycled
            safeRelease(owned.edge)
            return@synchronized null
        }
        state = State.Adopted
        safeRelease(owned.edge)
        bitmap
    }

    fun requireAdopt(): Bitmap =
        adoptOrNull() ?: throw IllegalStateException("TrackedMask was already settled")

    /**
     * Transfers the diagnostic owner, retaining original ownership if registration fails.
     * The destination edge is installed before source release, so one resident bitmap is
     * continuously represented.
     */
    fun transferToOrKeep(owner: String): Boolean = synchronized(lock) {
        val owned = state as? State.Owned ?: return@synchronized false
        if (bitmap.isRecycled) {
            state = State.Recycled
            safeRelease(owned.edge)
            return@synchronized false
        }
        val tracker = edgeTracker
        if (tracker == null) {
            state = State.Owned(owned.edge, owner)
            return@synchronized true
        }
        val destination =
            try {
                tracker.track(bitmap, owner)
            } catch (_: Throwable) {
                return@synchronized false
            }
        if (destination == 0L) return@synchronized false
        state = State.Owned(destination, owner)
        safeRelease(owned.edge)
        true
    }

    /** Idempotent settlement without recycling; intended only when ownership moved externally. */
    fun releaseWithoutRecycle(): Boolean = synchronized(lock) {
        val owned = state as? State.Owned ?: return@synchronized false
        state = State.ReleasedWithoutRecycle
        safeRelease(owned.edge)
        true
    }

    /** Idempotent, production-no-throw recycle and edge release. */
    fun recycleAndRelease(): Boolean = synchronized(lock) {
        val owned = state as? State.Owned ?: return@synchronized false
        state = State.Recycled
        try {
            if (!bitmap.isRecycled) bitmap.recycle()
        } catch (_: Throwable) {
            // Finalizer cleanup must not mask the primary failure.
        } finally {
            safeRelease(owned.edge)
        }
        true
    }

    private fun safeRelease(edge: Long) {
        if (edge == 0L) return
        try {
            edgeTracker?.release(edge)
        } catch (_: Throwable) {
            // Diagnostic cleanup is best effort and must never escape finalizers.
        }
    }

    internal fun exactEdge(): Long = synchronized(lock) {
        (state as? State.Owned)?.edge ?: 0L
    }

    companion object {
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
            val tracker =
                scope?.let {
                    object : EdgeTracker {
                        override fun track(bitmap: Bitmap, owner: String): Long = it.track(bitmap, owner)
                        override fun release(edge: Long) = it.release(edge)
                    }
                }
            return acquireForTest(
                bitmap, tracker, owner, modelId, modelVersion, operationToken,
                documentGeneration, confidenceMetrics, maskQuality,
            )
        }

        internal fun acquireForTest(
            bitmap: Bitmap,
            edgeTracker: EdgeTracker?,
            owner: String,
            modelId: String,
            modelVersion: String,
            operationToken: Long,
            documentGeneration: String,
            confidenceMetrics: ModelConfidence,
            maskQuality: MaskQualityResult? = null,
        ): TrackedMask {
            val edge = edgeTracker?.track(bitmap, owner) ?: 0L
            return TrackedMask(
                bitmap, modelId, modelVersion, operationToken, documentGeneration,
                confidenceMetrics, maskQuality, edgeTracker, edge, owner,
            )
        }
    }
}
