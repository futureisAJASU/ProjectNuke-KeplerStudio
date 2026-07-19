package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/** Conservative JVM Bitmap allocation guard; native stages remain whole-image by design. */
internal object BitmapMemoryBudget {
    private const val BYTES_PER_ARGB_8888_PIXEL = 4L
    private const val HISTORY_HEAP_FRACTION = 5L
    private const val HISTORY_CAP_BYTES = 192L * 1024L * 1024L
    private const val HEADROOM_NUMERATOR = 2L
    private const val HEADROOM_DENOMINATOR = 3L

    fun bytes(width: Int, height: Int, config: Bitmap.Config? = Bitmap.Config.ARGB_8888): Long {
        if (width <= 0 || height <= 0) return 0L
        val bytesPerPixel = when (config) {
            Bitmap.Config.RGB_565 -> 2L
            Bitmap.Config.ALPHA_8 -> 1L
            Bitmap.Config.RGBA_F16 -> 8L
            else -> BYTES_PER_ARGB_8888_PIXEL
        }
        return saturatingMultiply(saturatingMultiply(width.toLong(), height.toLong()), bytesPerPixel)
    }

    fun bytes(bitmap: Bitmap?): Long = bitmap?.takeUnless(Bitmap::isRecycled)?.let {
        max(bytes(it.width, it.height, it.config), it.allocationByteCount.toLong())
    } ?: 0L

    fun availableBytes(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()).coerceAtLeast(0L)
    }

    fun canAllocate(requiredBytes: Long): Boolean {
        if (requiredBytes <= 0L) return true
        return requiredBytes <= saturatingMultiply(availableBytes(), HEADROOM_NUMERATOR) / HEADROOM_DENOMINATOR
    }

    fun historyBudgetBytes(): Long = min(
        HISTORY_CAP_BYTES,
        Runtime.getRuntime().maxMemory() / HISTORY_HEAP_FRACTION
    )

    fun saturatingAdd(vararg values: Long): Long = values.fold(0L) { total, value ->
        if (value <= 0L) total else if (Long.MAX_VALUE - total < value) Long.MAX_VALUE else total + value
    }

    fun saturatingMultiply(left: Long, right: Long): Long =
        if (left <= 0L || right <= 0L) 0L else if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
}
