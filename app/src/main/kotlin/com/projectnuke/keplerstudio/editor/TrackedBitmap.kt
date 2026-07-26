package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap

/**
 * Short-lived operation ownership for a Bitmap and its exact diagnostic edge.
 *
 * The wrapper drops its tracker reference as soon as ownership is released or disarmed.
 */
internal class TrackedBitmap private constructor(
    val bitmap: Bitmap,
    scope: MemoryTrackerScope?,
    edge: Long,
) {
    private val lock = Any()
    private var scope: MemoryTrackerScope? = scope
    private var edge: Long = edge
    private var settled = false

    fun release(): Boolean =
        synchronized(lock) {
            if (settled) return false
            settled = true
            val ownedScope = scope
            val ownedEdge = edge
            scope = null
            edge = 0L
            ownedScope?.release(ownedEdge)
            true
        }

    fun recycleAndRelease(): Boolean {
        synchronized(lock) {
            if (settled) return false
            settled = true
            val ownedScope = scope
            val ownedEdge = edge
            scope = null
            edge = 0L
            try {
                if (!bitmap.isRecycled) bitmap.recycle()
            } finally {
                ownedScope?.release(ownedEdge)
            }
            return true
        }
    }

    fun transferTo(destinationOwner: String): Boolean =
        synchronized(lock) {
            if (settled) return false
            val ownedScope = scope ?: return true
            val destinationEdge = ownedScope.track(bitmap, destinationOwner)
            val sourceEdge = edge
            edge = destinationEdge
            ownedScope.release(sourceEdge)
            true
        }

    fun disarmAfterAdoption(): Bitmap {
        release()
        return bitmap
    }

    internal fun exactHandle(): Long = synchronized(lock) { edge }

    companion object {
        fun acquire(
            bitmap: Bitmap,
            scope: MemoryTrackerScope?,
            owner: String,
        ): TrackedBitmap =
            TrackedBitmap(
                bitmap = bitmap,
                scope = scope,
                edge = scope?.track(bitmap, owner) ?: 0L,
            )
    }
}
