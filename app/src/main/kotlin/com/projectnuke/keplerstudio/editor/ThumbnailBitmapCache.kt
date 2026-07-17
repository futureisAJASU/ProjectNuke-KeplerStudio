package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal class ThumbnailBitmapLease internal constructor(
    val bitmap: Bitmap,
    private val releaseAction: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) releaseAction()
    }
}

internal object ThumbnailBitmapCache {
    private const val MAX_BYTES = 24L * 1024L * 1024L
    private val lock = Any()
    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var residentBytes = 0L
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private val inFlight = HashMap<String, Flight>()

    private class Entry(val bitmap: Bitmap, val bytes: Long) {
        var leases = 0
        var removed = false
    }

    private class Flight(val key: String, val decode: () -> Bitmap?) {
        val result = CompletableDeferred<Entry?>()
        var waiters = 0
        var invalidated = false
        var completedEntry: Entry? = null
        var job: Job? = null
    }

    suspend fun acquire(key: String, decode: () -> Bitmap?): ThumbnailBitmapLease? {
        var startFlight = false
        val flight: Flight
        synchronized(lock) {
            entries[key]?.takeIf { !it.removed && !it.bitmap.isRecycled }?.let { entry ->
                entry.leases += 1
                return lease(entry)
            }
            flight = inFlight[key] ?: Flight(key, decode).also {
                inFlight[key] = it
                startFlight = true
            }
            flight.waiters += 1
        }
        if (startFlight) startDecode(flight)
        return try {
            flight.result.await()?.let(::lease)
        } catch (t: Throwable) {
            cancelWaiter(flight)
            throw t
        }
    }

    fun invalidate(key: String) {
        val job: Job?
        synchronized(lock) {
            val flight = inFlight.remove(key)
            flight?.invalidated = true
            job = flight?.job
            entries.remove(key)?.let { entry ->
                residentBytes -= entry.bytes
                entry.removed = true
                recycleIfUnusedLocked(entry)
            }
        }
        job?.cancel()
    }

    fun clear() {
        val jobs = ArrayList<Job>()
        synchronized(lock) {
            inFlight.values.forEach { flight ->
                flight.invalidated = true
                flight.job?.let(jobs::add)
            }
            inFlight.clear()
            entries.values.forEach { entry ->
                entry.removed = true
                recycleIfUnusedLocked(entry)
            }
            entries.clear()
            residentBytes = 0L
        }
        jobs.forEach(Job::cancel)
    }

    private fun startDecode(flight: Flight) {
        val job = decodeScope.launch {
            var decoded: Bitmap? = null
            try {
                decoded = flight.decode()
                val entry = decoded?.let { Entry(it, it.allocationByteCount.toLong()) }
                synchronized(lock) {
                    inFlight.remove(flight.key, flight)
                    if (entry != null && !flight.invalidated && flight.waiters > 0) {
                        entry.leases = flight.waiters
                        flight.completedEntry = entry
                        if (entry.bytes <= MAX_BYTES) {
                            entries[flight.key] = entry
                            residentBytes += entry.bytes
                            evictLocked()
                        } else {
                            entry.removed = true
                        }
                        decoded = null
                        flight.result.complete(entry)
                    } else {
                        flight.result.complete(null)
                    }
                }
            } catch (_: Throwable) {
                synchronized(lock) {
                    inFlight.remove(flight.key, flight)
                    flight.result.complete(null)
                }
            } finally {
                decoded?.takeIf { !it.isRecycled }?.recycle()
            }
        }
        synchronized(lock) {
            flight.job = job
            if (flight.invalidated || flight.waiters == 0) job.cancel()
        }
    }

    private fun cancelWaiter(flight: Flight) {
        var cancelJob: Job? = null
        synchronized(lock) {
            val completed = flight.completedEntry
            if (completed != null) {
                releaseLocked(completed)
            } else if (flight.waiters > 0) {
                flight.waiters -= 1
                if (flight.waiters == 0 && !flight.result.isCompleted) {
                    flight.invalidated = true
                    inFlight.remove(flight.key, flight)
                    cancelJob = flight.job
                }
            }
        }
        cancelJob?.cancel()
    }

    private fun lease(entry: Entry): ThumbnailBitmapLease =
        ThumbnailBitmapLease(entry.bitmap) { release(entry) }

    private fun release(entry: Entry) {
        synchronized(lock) { releaseLocked(entry) }
    }

    private fun releaseLocked(entry: Entry) {
        if (entry.leases > 0) entry.leases -= 1
        recycleIfUnusedLocked(entry)
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
