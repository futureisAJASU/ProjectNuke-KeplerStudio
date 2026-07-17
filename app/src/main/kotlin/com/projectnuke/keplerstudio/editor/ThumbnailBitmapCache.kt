package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import java.util.LinkedHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ThumbnailBitmapCache {
    private const val MAX_BYTES = 24 * 1024 * 1024
    private val mutex = Mutex()
    private var bytes = 0L
    private val entries = LinkedHashMap<String, Bitmap>(16, 0.75f, true)

    suspend fun load(key: String, decode: () -> Bitmap?): Bitmap? = mutex.withLock {
        entries[key]?.takeIf { !it.isRecycled }?.let { return it }
        val decoded = decode() ?: return null
        val size = decoded.allocationByteCount.toLong()
        if (size > MAX_BYTES) return decoded
        entries[key] = decoded
        bytes += size
        while (bytes > MAX_BYTES && entries.isNotEmpty()) {
            val eldest = entries.entries.iterator().next()
            entries.remove(eldest.key)
            bytes -= eldest.value.allocationByteCount.toLong()
            if (!eldest.value.isRecycled) eldest.value.recycle()
        }
        decoded
    }

    suspend fun invalidate(key: String) = mutex.withLock {
        entries.remove(key)?.let { bitmap ->
            bytes -= bitmap.allocationByteCount.toLong()
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
