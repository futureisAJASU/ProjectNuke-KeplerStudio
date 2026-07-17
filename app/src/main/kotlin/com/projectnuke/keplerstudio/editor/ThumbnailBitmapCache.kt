package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import java.util.LinkedHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class ThumbnailBitmapLease internal constructor(
    val bitmap: Bitmap,
    private val releaseAction: () -> Unit
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        releaseAction()
    }
}

internal object ThumbnailBitmapCache {
    private const val MAX_BYTES = 24L * 1024L * 1024L
    private val lock = Any()
    private var residentBytes = 0L
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val inFlight = HashMap<String, Flight>()

    private class Entry(val key: String, val bitmap: Bitmap, val bytes: Long) {
        var leases = 0
        var removed = false
    }

    private class Flight {
        val result = CompletableDeferred<Entry?>()
        var waiters = 0
        var invalidated = false
    }

    suspend fun acquire(key: String, decode: () -> Bitmap?): ThumbnailBitmapLease? {
        var owner = false
        val flight: Flight
        synchronized(lock) {
            entries[key]?.takeIf { !it.removed && !it.bitmap.isRecycled }?.let { entry ->
                entry.leases += 1
                return lease(entry)
            }
            flight = inFlight[key] ?: Flight().also {
                inFlight[key] = it
                owner = true
            }
            flight.waiters += 1
        }

        if (owner) {
            var decoded: Bitmap? = null
            try {
                decoded = decode()
                currentCoroutineContext().ensureActive()
                val entry = decoded?.let { bitmap -> Entry(key, bitmap, bitmap.allocationByteCount.toLong()) }
                synchronized(lock) {
                    inFlight.remove(key, flight)
                    if (entry != null) {
                        entry.leases = flight.waiters
                        if (!flight.invalidated && entry.bytes <= MAX_BYTES) {
                            entries[key] = entry
                            residentBytes += entry.bytes
                            evictLocked()
                        } else {
                            entry.removed = true
                        }
                    }
                    flight.result.complete(entry)
                }
                decoded = null
            } catch (t: Throwable) {
                synchronized(lock) {
                    inFlight.remove(key, flight)
                    flight.result.complete(null)
                }
                throw t
            } finally {
                decoded?.takeIf { !it.isRecycled }?.recycle()
            }
        }

        return try {
            flight.result.await()?.let(::lease)
        } catch (t: Throwable) {
            val reserved = withContext(NonCancellable) { flight.result.await() }
            if (reserved != null) release(reserved)
            throw t
        }
    }

    fun invalidate(key: String) {
        synchronized(lock) {
            inFlight.remove(key)?.invalidated = true
            entries.remove(key)?.let { entry ->
                residentBytes -= entry.bytes
                entry.removed = true
                recycleIfUnusedLocked(entry)
            }
        }
    }

    private fun lease(entry: Entry): ThumbnailBitmapLease =
        ThumbnailBitmapLease(entry.bitmap) { release(entry) }

    private fun release(entry: Entry) {
        synchronized(lock) {
            if (entry.leases > 0) entry.leases -= 1
            recycleIfUnusedLocked(entry)
        }
    }

    private fun evictLocked() {
        while (residentBytes > MAX_BYTES && entries.isNotEmpty()) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
            residentBytes -= eldest.value.bytes
            eldest.value.removed = true
            recycleIfUnusedLocked(eldest.value)
        }
    }

    private fun recycleIfUnusedLocked(entry: Entry) {
        if (entry.removed && entry.leases == 0 && !entry.bitmap.isRecycled) entry.bitmap.recycle()
    }
}
