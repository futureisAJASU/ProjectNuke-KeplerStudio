package com.projectnuke.keplerstudio.editor

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max
import kotlin.math.min

internal enum class RetainedMemoryCategory(val subtractFromRuntimeHeap: Boolean) {
    /** Runtime.freeMemory already reflects these objects. */
    JavaHeapObservable(false),
    /** Bitmap pixels and other allocations not reliably represented by Runtime heap. */
    NativeBitmap(true),
    NativeOrOpaqueRuntime(true),
    FileBacked(false),
}

internal data class RetainedMemorySnapshot(
    val byOwner: Map<String, Map<RetainedMemoryCategory, Long>>,
) {
    val admissionBytes: Long
        get() =
            byOwner.values.sumOf { categories ->
                categories.entries
                    .filter { it.key.subtractFromRuntimeHeap }
                    .sumOf(Map.Entry<RetainedMemoryCategory, Long>::value)
            }
}

internal class RetainedMemoryReservation internal constructor(
    private val owner: String,
) : AutoCloseable {
    @Volatile
    private var closed = false

    fun replace(category: RetainedMemoryCategory, bytes: Long) {
        check(!closed) { "retained-memory reservation is closed" }
        RetainedMemoryLedger.replace(owner, category, bytes)
    }

    override fun close() {
        if (closed) return
        closed = true
        RetainedMemoryLedger.release(owner)
    }
}

internal object RetainedMemoryLedger {
    private val lock = Any()
    private val retained = LinkedHashMap<String, MutableMap<RetainedMemoryCategory, Long>>()

    fun reserve(owner: String): RetainedMemoryReservation {
        require(owner.isNotBlank())
        synchronized(lock) {
            check(owner !in retained) { "retained-memory owner already exists: $owner" }
            retained[owner] = linkedMapOf()
        }
        return RetainedMemoryReservation(owner)
    }

    internal fun replace(owner: String, category: RetainedMemoryCategory, bytes: Long) {
        synchronized(lock) {
            val categories = checkNotNull(retained[owner]) {
                "unknown retained-memory owner: $owner"
            }
            if (bytes <= 0L) categories.remove(category) else categories[category] = bytes
        }
    }

    internal fun release(owner: String) {
        synchronized(lock) { retained.remove(owner) }
    }

    fun snapshot(): RetainedMemorySnapshot =
        synchronized(lock) {
            RetainedMemorySnapshot(
                retained.mapValues { (_, categories) -> categories.toMap() }
            )
        }

    internal fun resetForTest() {
        synchronized(lock) { retained.clear() }
    }
}

/** Conservative JVM Bitmap allocation guard; native stages remain whole-image by design. */
internal object BitmapMemoryBudget {
    private const val BYTES_PER_ARGB_8888_PIXEL = 4L
    private const val HEADROOM_NUMERATOR = 2L
    private const val HEADROOM_DENOMINATOR = 3L
    private const val MIB = 1024L * 1024L

    @Volatile private var profile = HeapProfile(
        maxHeapBytes = Runtime.getRuntime().maxMemory(),
        normalMemoryClassBytes = 128L * MIB,
        largeMemoryClassBytes = Runtime.getRuntime().maxMemory(),
        lowRamDevice = false
    )

    data class HeapProfile(
        val maxHeapBytes: Long,
        val normalMemoryClassBytes: Long,
        val largeMemoryClassBytes: Long,
        val lowRamDevice: Boolean
    )

    fun initialize(context: Context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        profile = HeapProfile(
            maxHeapBytes = Runtime.getRuntime().maxMemory(),
            normalMemoryClassBytes = saturatingMultiply(manager?.memoryClass?.toLong() ?: 128L, MIB),
            largeMemoryClassBytes = saturatingMultiply(manager?.largeMemoryClass?.toLong() ?: 128L, MIB),
            lowRamDevice = manager?.isLowRamDevice == true
        )
    }

    fun heapProfile(): HeapProfile = profile

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
        val availableAfterRetained =
            (availableBytes() - RetainedMemoryLedger.snapshot().admissionBytes)
                .coerceAtLeast(0L)
        return requiredBytes <=
            saturatingMultiply(availableAfterRetained, HEADROOM_NUMERATOR) /
                HEADROOM_DENOMINATOR
    }

    fun retainedMemorySnapshot(): RetainedMemorySnapshot = RetainedMemoryLedger.snapshot()

    fun operationReserveBytes(): Long = min(profile.maxHeapBytes / 2L, max(96L * MIB, profile.maxHeapBytes / 3L))

    fun modelReserveBytes(): Long = if (profile.lowRamDevice) 24L * MIB else min(96L * MIB, max(profile.normalMemoryClassBytes / 8L, profile.maxHeapBytes / 10L))

    fun historyBudgetBytes(): Long {
        val afterReserves = (profile.maxHeapBytes - operationReserveBytes() - modelReserveBytes()).coerceAtLeast(16L * MIB)
        val fraction = if (profile.lowRamDevice) profile.maxHeapBytes / 12L else profile.maxHeapBytes / 6L
        val classBound = if (profile.lowRamDevice) profile.normalMemoryClassBytes / 10L else max(profile.normalMemoryClassBytes / 6L, profile.largeMemoryClassBytes / 10L)
        return min(afterReserves / 2L, min(classBound, min(fraction, 192L * MIB))).coerceAtLeast(16L * MIB)
    }

    fun maxSelectionMaskLayers(): Int = if (profile.lowRamDevice) 4 else 16

    fun selectionMaskBudgetBytes(): Long {
        val heapFraction = if (profile.lowRamDevice) profile.maxHeapBytes / 16L else profile.maxHeapBytes / 8L
        val classBound = if (profile.lowRamDevice) profile.normalMemoryClassBytes / 10L else max(
            profile.normalMemoryClassBytes / 6L, profile.largeMemoryClassBytes / 10L,
        )
        val internalBound = profile.maxHeapBytes / 12L
        return min(heapFraction, min(classBound, internalBound)).coerceIn(8L * MIB, 512L * MIB)
    }

    fun canAdmitSelectionLayer(
        existingMaskBytes: Long,
        candidateBytes: Long,
        existingLayerCount: Int,
    ): Boolean {
        if (candidateBytes <= 0L) return false
        if (existingLayerCount >= maxSelectionMaskLayers()) return false
        val hardCeiling = selectionMaskBudgetBytes() * 2L
        val total = saturatingAdd(existingMaskBytes, candidateBytes)
        return total <= hardCeiling && canAllocate(candidateBytes)
    }

    fun thumbnailBudgetBytes(): Long = min(if (profile.lowRamDevice) 8L * MIB else 32L * MIB, min(profile.normalMemoryClassBytes / 16L, profile.maxHeapBytes / 24L)).coerceAtLeast(4L * MIB)

    fun historyDiskBudgetBytes(): Long = min(768L * MIB, max(128L * MIB, saturatingMultiply(profile.largeMemoryClassBytes, 2L)))

    fun saturatingAdd(vararg values: Long): Long = values.fold(0L) { total, value ->
        if (value <= 0L) total else if (Long.MAX_VALUE - total < value) Long.MAX_VALUE else total + value
    }

    fun saturatingMultiply(left: Long, right: Long): Long =
        if (left <= 0L || right <= 0L) 0L else if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
}

internal class BitmapAllocationRejectedException(val requiredBytes: Long) :
    IllegalStateException("insufficient bitmap memory: $requiredBytes bytes")

internal fun decodeMutableBitmapOrThrow(path: String): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "bitmap bounds decode failed" }
    val required = BitmapMemoryBudget.bytes(bounds.outWidth, bounds.outHeight)
    if (!BitmapMemoryBudget.canAllocate(required)) throw BitmapAllocationRejectedException(required)
    var decoded: Bitmap? = null
    try {
        decoded = requireNotNull(BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }))
        if (decoded!!.config == Bitmap.Config.ARGB_8888 && decoded!!.isMutable) return decoded!!
        val mutable = decoded!!.copyOrThrow()
        decoded!!.recycle()
        decoded = null
        return mutable
    } catch (t: Throwable) {
        decoded?.takeUnless(Bitmap::isRecycled)?.recycle()
        if (t is OutOfMemoryError) throw BitmapAllocationRejectedException(required)
        throw t
    }
}
