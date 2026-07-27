package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap

/**
 * Exact short-lived ownership of a Bitmap and its diagnostic edge.
 *
 * Bitmap ownership, edge ownership, and diagnostic owner are one locked state. A failed
 * destination registration never releases the source edge, and every settlement is exact-once.
 */
internal class TrackedBitmap private constructor(
    val bitmap: Bitmap,
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
        adoptOrNull() ?: throw IllegalStateException("TrackedBitmap was already settled")

    /**
     * Registers the destination diagnostic owner before settling local ownership.
     *
     * The destination edge remains owned by the surrounding tracker scope. If registration
     * fails, this object remains the bitmap and source-edge owner.
     */
    fun adoptToOrNull(destinationOwner: String): Bitmap? = synchronized(lock) {
        val owned = state as? State.Owned ?: return@synchronized null
        if (bitmap.isRecycled) {
            state = State.Recycled
            safeRelease(owned.edge)
            return@synchronized null
        }
        val tracker = edgeTracker
        if (tracker != null) {
            val destination =
                try {
                    tracker.track(bitmap, destinationOwner)
                } catch (_: Throwable) {
                    return@synchronized null
                }
            if (destination == 0L) return@synchronized null
        }
        state = State.Adopted
        safeRelease(owned.edge)
        bitmap
    }

    fun transferToOrKeep(destinationOwner: String): Boolean = synchronized(lock) {
        val owned = state as? State.Owned ?: return@synchronized false
        if (bitmap.isRecycled) {
            state = State.Recycled
            safeRelease(owned.edge)
            return@synchronized false
        }
        val tracker = edgeTracker
        if (tracker == null) {
            state = State.Owned(owned.edge, destinationOwner)
            return@synchronized true
        }
        val destination =
            try {
                tracker.track(bitmap, destinationOwner)
            } catch (_: Throwable) {
                return@synchronized false
            }
        if (destination == 0L) return@synchronized false
        state = State.Owned(destination, destinationOwner)
        safeRelease(owned.edge)
        true
    }

    fun releaseWithoutRecycle(): Boolean = synchronized(lock) {
        val owned = state as? State.Owned ?: return@synchronized false
        state = State.ReleasedWithoutRecycle
        safeRelease(owned.edge)
        true
    }

    fun recycleAndRelease(): Boolean = synchronized(lock) {
        val owned = state as? State.Owned ?: return@synchronized false
        state = State.Recycled
        try {
            if (!bitmap.isRecycled) bitmap.recycle()
        } catch (_: Throwable) {
            // Cleanup must not mask the operation failure.
        } finally {
            safeRelease(owned.edge)
        }
        true
    }

    internal fun exactHandle(): Long = synchronized(lock) {
        (state as? State.Owned)?.edge ?: 0L
    }

    private fun safeRelease(edge: Long) {
        if (edge == 0L) return
        try {
            edgeTracker?.release(edge)
        } catch (_: Throwable) {
            // Diagnostic cleanup is best effort.
        }
    }

    companion object {
        fun acquire(
            bitmap: Bitmap,
            scope: MemoryTrackerScope?,
            owner: String,
        ): TrackedBitmap {
            val tracker =
                scope?.let {
                    object : EdgeTracker {
                        override fun track(bitmap: Bitmap, owner: String): Long =
                            it.track(bitmap, owner)

                        override fun release(edge: Long) = it.release(edge)
                    }
                }
            return acquireForTest(bitmap, tracker, owner)
        }

        internal fun acquireForTest(
            bitmap: Bitmap,
            edgeTracker: EdgeTracker?,
            owner: String,
        ): TrackedBitmap {
            val edge = edgeTracker?.track(bitmap, owner) ?: 0L
            return TrackedBitmap(bitmap, edgeTracker, edge, owner)
        }
    }
}
