package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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

internal class MemoryTrackerScope private constructor(
    val tracker: TrackerSession,
    val name: String,
    val documentGeneration: String,
    val baseContentToken: String,
    val revision: Int,
    val snapshotState: String,
    private val transientReserveBytes: Long
) {
    private val operationToken: Long
    private val ended = AtomicBoolean(false)
    private val trackedEdges = ArrayDeque<Long>(8)

    init {
        operationToken = if (DebugMemoryTracker.isEnabled()) {
            tracker.beginOperation(
                name = name,
                documentGeneration = documentGeneration,
                baseContentToken = baseContentToken,
                revision = revision,
                transientReserveBytes = transientReserveBytes,
                snapshotState = snapshotState
            )
        } else {
            0L
        }
    }

    val token: Long get() = operationToken

    fun track(bitmap: Bitmap, owner: String): Long {
        if (!DebugMemoryTracker.isEnabled()) return 0L
        val handle = tracker.registerBitmap(
            bitmap = bitmap,
            owner = owner,
            operation = name,
            token = operationToken,
            documentGeneration = documentGeneration
        )
        if (handle != 0L) trackedEdges.addLast(handle)
        return handle
    }

    fun trackAll(bitmaps: List<Bitmap>, owner: String) {
        bitmaps.forEach { track(it, owner) }
    }

    fun release(handle: Long) {
        if (handle == 0L) return
        tracker.releaseEdge(handle)
    }

    fun end() {
        if (ended.compareAndSet(false, true)) {
            trackedEdges.forEach { tracker.releaseEdge(it) }
            tracker.endOperation(name, operationToken)
        }
    }

    companion object {
        fun create(
            tracker: TrackerSession,
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
    private val tracker: TrackerSession
) {
    private data class SlotKey(val owner: String)

    private val lock = ReentrantLock()
    private val prevBitmaps = ConcurrentHashMap<String, Long>()

    fun reconcile(prev: EditorUiState?, next: EditorUiState, documentGeneration: String) {
        if (!DebugMemoryTracker.isEnabled()) return
        try {
            val nextOwners = mutableSetOf<String>()

            next.previewBitmap?.let {
                val handle = tracker.registerBitmap(
                    bitmap = it,
                    owner = TrackerOwners.UI_STATE_PREVIEW,
                    operation = TrackerOwners.UI_STATE,
                    token = 0L,
                    documentGeneration = documentGeneration
                )
                nextOwners.add(TrackerOwners.UI_STATE_PREVIEW)
            }

            next.originalPreviewBitmap?.let {
                if (it !== next.previewBitmap) {
                    val handle = tracker.registerBitmap(
                        bitmap = it,
                        owner = TrackerOwners.UI_STATE_ORIGINAL,
                        operation = TrackerOwners.UI_STATE,
                        token = 0L,
                        documentGeneration = documentGeneration
                    )
                    nextOwners.add(TrackerOwners.UI_STATE_ORIGINAL)
                }
            }

            next.selectionLayers.forEach { layer ->
                val handle = tracker.registerBitmap(
                    bitmap = layer.bitmap,
                    owner = TrackerOwners.selectionLayer(layer.id),
                    operation = TrackerOwners.UI_STATE,
                    token = 0L,
                    documentGeneration = documentGeneration
                )
                nextOwners.add(TrackerOwners.selectionLayer(layer.id))
            }

            lock.lock()
            try {
                prev?.previewBitmap?.let {
                    if (!nextOwners.contains(TrackerOwners.UI_STATE_PREVIEW)) {
                        tracker.unregisterBitmap(it, TrackerOwners.UI_STATE_PREVIEW)
                    }
                }
                prev?.originalPreviewBitmap?.let {
                    if (it !== next.previewBitmap && !nextOwners.contains(TrackerOwners.UI_STATE_ORIGINAL)) {
                        tracker.unregisterBitmap(it, TrackerOwners.UI_STATE_ORIGINAL)
                    }
                }
                prev?.selectionLayers?.forEach { layer ->
                    if (!nextOwners.contains(TrackerOwners.selectionLayer(layer.id))) {
                        tracker.unregisterBitmap(layer.bitmap, TrackerOwners.selectionLayer(layer.id))
                    }
                }
            } finally {
                lock.unlock()
            }
        } catch (_: Throwable) {
        }
    }

    fun releaseAll() {
        if (!DebugMemoryTracker.isEnabled()) return
        try {
            lock.lock()
            try {
                prevBitmaps.keys.toList().forEach {
                    tracker.unregisterBitmap(
                        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
                        it
                    )
                }
            } finally {
                lock.unlock()
            }
        } catch (_: Throwable) {
        }
    }
}

internal fun EditorViewModel.createTracker(): TrackerSession =
    DebugMemoryTracker.createSession("editor-${System.identityHashCode(this)}")

internal fun EditorViewModel.beginMemoryTracking(
    name: String,
    snapshotState: String = "hot",
    transientReserveBytes: Long = 0L
): MemoryTrackerScope? {
    if (!DebugMemoryTracker.isEnabled() || isShuttingDown()) return null
    val state = uiState.value
    return MemoryTrackerScope.create(
        tracker = tracker,
        name = name,
        documentGeneration = historyCoordinator.currentGeneration(),
        baseContentToken = state.baseContentToken,
        revision = state.revision,
        snapshotState = snapshotState,
        transientReserveBytes = transientReserveBytes
    )
}

internal fun EditorViewModel.registerDocumentGeneration(generation: String?) {
    if (!DebugMemoryTracker.isEnabled() || isShuttingDown()) return
    if (generation != null) tracker.registerDocument(generation)
}

internal fun EditorViewModel.unregisterDocumentGeneration(generation: String?) {
    if (!DebugMemoryTracker.isEnabled() || isShuttingDown()) return
    if (generation != null) tracker.unregisterDocument(generation)
}

internal fun EditorViewModel.registerUiStateBitmap(bitmap: Bitmap, owner: String) {
    if (!DebugMemoryTracker.isEnabled() || isShuttingDown()) return
    if (bitmap.isRecycled) return
    val gen = historyCoordinator.currentGeneration()
    tracker.registerBitmap(
        bitmap = bitmap,
        owner = owner,
        operation = TrackerOwners.UI_STATE,
        token = 0L,
        documentGeneration = gen
    )
}

internal fun EditorViewModel.unregisterUiStateBitmap(bitmap: Bitmap, owner: String? = null) {
    if (!DebugMemoryTracker.isEnabled() || isShuttingDown()) return
    tracker.unregisterBitmap(bitmap, owner)
}
