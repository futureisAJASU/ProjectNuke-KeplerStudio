package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

internal object TrackerOwners {
    const val UI_STATE = "UiState"
    const val UI_STATE_PREVIEW = "EditorUiState:previewBitmap"
    const val UI_STATE_ORIGINAL = "EditorUiState:originalPreviewBitmap"
    fun selectionLayer(layerId: String): String = "EditorUiState:selectionLayer:$layerId"

    const val THUMBNAIL_RESIDENT = "ThumbnailBitmapCache:resident"
    const val THUMBNAIL_LEASED = "ThumbnailBitmapCache:leased"

    const val DOCUMENT_GENERATION = "DocumentGeneration"
    const val NATIVE_SESSION = "NativeSession"
}
class MemoryTrackerScope private constructor(
    private val tracker: TrackerDiagnostics,
    val name: String,
    val documentGeneration: String,
    val baseContentToken: String,
    val revision: Int,
    val snapshotState: String,
    private val transientReserveBytes: Long
) {
    private val operationToken: Long
    private val ended = AtomicBoolean(false)
    private val ownershipLock = ReentrantLock()
    private val trackedEdges = ArrayDeque<Long>(8)
    private val transientContributors = ArrayDeque<Long>(4)
    private val knownTransientBytes = HashMap<Long, Long>(4)

    init {
        operationToken = tracker.beginOperation(
                name = name,
                documentGeneration = documentGeneration,
                baseContentToken = baseContentToken,
                revision = revision,
                transientReserveBytes = transientReserveBytes,
                snapshotState = snapshotState
            )
    }

    val token: Long get() = operationToken

    fun track(bitmap: Bitmap, owner: String): Long {
        return try {
            ownershipLock.lock()
            try {
                if (ended.get()) return 0L
                tracker.registerBitmap(bitmap, owner, name, operationToken, documentGeneration).also {
                    if (it != 0L && !ended.get()) trackedEdges.addLast(it)
                    else if (it != 0L) tracker.releaseEdge(it)
                }
            } finally { ownershipLock.unlock() }
        } catch (_: Throwable) { 0L }
    }

    fun trackAll(bitmaps: List<Bitmap>, owner: String) {
        bitmaps.forEach { track(it, owner) }
    }

    fun release(handle: Long) {
        if (handle == 0L) return
        ownershipLock.lock()
        try {
            tracker.releaseEdge(handle)
            trackedEdges.remove(handle)
        } finally { ownershipLock.unlock() }
    }

    fun trackTransientBytes(label: String, bytes: Long?): Long {
        return try {
            ownershipLock.lock()
            try {
                if (ended.get()) return 0L
                tracker.registerTransientContributor(operationToken, documentGeneration, label, bytes).also {
                    if (it != 0L && !ended.get()) {
                        transientContributors.addLast(it)
                        if (bytes != null) knownTransientBytes[it] = bytes.coerceAtLeast(0L)
                    }
                    else if (it != 0L) tracker.releaseTransientContributor(it)
                }
            } finally { ownershipLock.unlock() }
        } catch (_: Throwable) { 0L }
    }

    fun releaseTransient(handle: Long) {
        if (handle == 0L) return
        ownershipLock.lock()
        try {
            tracker.releaseTransientContributor(handle)
            transientContributors.remove(handle)
            knownTransientBytes.remove(handle)
        } finally { ownershipLock.unlock() }
    }

    fun availableNativeScratchBytes(): Long {
        ownershipLock.lock()
        return try {
            if (ended.get()) return 0L
            val operationBudget =
                transientReserveBytes
                    .takeIf { it > 0L }
                    ?: BitmapMemoryBudget.operationReserveBytes()
            val alreadyKnown =
                knownTransientBytes.values.fold(0L) { total, bytes ->
                    BitmapMemoryBudget.saturatingAdd(total, bytes)
                }
            (operationBudget - alreadyKnown).coerceAtLeast(0L)
        } finally {
            ownershipLock.unlock()
        }
    }

    fun end() {
        ownershipLock.lock()
        try {
            if (!ended.compareAndSet(false, true)) return
            while (trackedEdges.isNotEmpty()) tracker.releaseEdge(trackedEdges.removeFirst())
            while (transientContributors.isNotEmpty()) tracker.releaseTransientContributor(transientContributors.removeFirst())
            knownTransientBytes.clear()
            tracker.endOperation(name, operationToken)
        } finally { ownershipLock.unlock() }
    }

    companion object {
        internal fun create(
            tracker: TrackerDiagnostics,
            name: String,
            documentGeneration: String,
            baseContentToken: String,
            revision: Int,
            snapshotState: String,
            transientReserveBytes: Long
        ): MemoryTrackerScope = MemoryTrackerScope(
            tracker = tracker,
            name = name,
            documentGeneration = documentGeneration,
            baseContentToken = baseContentToken,
            revision = revision,
            snapshotState = snapshotState,
            transientReserveBytes = transientReserveBytes
        )
    }
}

internal class UiStateOwnershipReconciler(
    private val tracker: TrackerDiagnostics
) {
    private val lock = ReentrantLock()
    /** Only edge handles are retained: UI ownership never pins a Bitmap. */
    private data class OwnedSlot(val handle: Long, val generation: String)
    private val handles = HashMap<String, OwnedSlot>()

    fun reconcile(prev: EditorUiState?, next: EditorUiState, documentGeneration: String) {
        try {
            lock.lock()
            try {
                val before = slots(prev)
                val after = slots(next)
                (before.keys + after.keys).forEach { slot ->
                    val old = before[slot]
                    val replacement = after[slot]
                    val existing = handles[slot]
                    if (old === replacement && replacement != null && existing?.generation == documentGeneration) return@forEach
                    handles.remove(slot)?.let { tracker.releaseEdge(it.handle) }
                    replacement?.takeIf { !it.isRecycled }?.let { bitmap ->
                        val handle = tracker.registerBitmap(bitmap, slot, TrackerOwners.UI_STATE, 0L, documentGeneration)
                        if (handle != 0L) handles[slot] = OwnedSlot(handle, documentGeneration)
                    }
                }
            } finally {
                lock.unlock()
            }
        } catch (_: Throwable) {
        }
    }

    fun releaseAll() {
        try {
            lock.lock()
            try {
                handles.values.forEach { tracker.releaseEdge(it.handle) }
                handles.clear()
            } finally {
                lock.unlock()
            }
        } catch (_: Throwable) {
        }
    }

    private fun slots(state: EditorUiState?): Map<String, Bitmap> = buildMap {
        state?.previewBitmap?.let { put(TrackerOwners.UI_STATE_PREVIEW, it) }
        state?.originalPreviewBitmap?.let { put(TrackerOwners.UI_STATE_ORIGINAL, it) }
        state?.selectionLayers?.forEach { put(TrackerOwners.selectionLayer(it.id), it.bitmap) }
    }
}

internal fun EditorViewModel.createTracker(): TrackerDiagnostics = tracker

internal fun EditorViewModel.beginMemoryTracking(
    name: String,
    snapshotState: String = "hot",
    transientReserveBytes: Long = 0L
): MemoryTrackerScope? {
    val realTracker = trackerSession ?: return null
    if (isShuttingDown()) return null
    val state = uiState.value
    return MemoryTrackerScope.create(
        tracker = realTracker,
        name = name,
        documentGeneration = historyCoordinator.currentGeneration(),
        baseContentToken = state.baseContentToken,
        revision = state.revision,
        snapshotState = snapshotState,
        transientReserveBytes = transientReserveBytes
    )
}
