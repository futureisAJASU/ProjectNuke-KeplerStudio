package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean

internal class MemoryTrackerScope(
    val name: String,
    val documentGeneration: String,
    val baseContentToken: String,
    val revision: Int,
    val snapshotState: String,
    private val transientReserveBytes: Long
) {
    private val operationToken: Long
    private val ended = AtomicBoolean(false)
    private val trackedBitmaps = ArrayDeque<Pair<Bitmap, String>>(8)

    init {
        operationToken = DebugMemoryTracker.beginOperation(
            name = name,
            documentGeneration = documentGeneration,
            baseContentToken = baseContentToken,
            revision = revision,
            transientReserveBytes = transientReserveBytes,
            snapshotState = snapshotState
        )
    }

    val token: Long get() = operationToken

    fun track(bitmap: Bitmap, owner: String) {
        if (bitmap.isRecycled) return
        DebugMemoryTracker.registerBitmap(
            bitmap = bitmap,
            owner = owner,
            operation = name,
            token = operationToken,
            documentGeneration = documentGeneration
        )
        trackedBitmaps.addLast(bitmap to owner)
    }

    fun trackAll(bitmaps: List<Bitmap>, owner: String) {
        bitmaps.forEach { track(it, owner) }
    }

    fun release(bitmap: Bitmap, owner: String? = null) {
        DebugMemoryTracker.unregisterBitmap(bitmap, owner)
        trackedBitmaps.removeIf { it.first === bitmap }
    }

    fun releaseAll() {
        trackedBitmaps.forEach { (bitmap, owner) ->
            DebugMemoryTracker.unregisterBitmap(bitmap, owner)
        }
        trackedBitmaps.clear()
    }

    fun end() {
        if (ended.compareAndSet(false, true)) {
            releaseAll()
            DebugMemoryTracker.endOperation(name, operationToken)
        }
    }
}

internal fun EditorViewModel.beginMemoryTracking(
    name: String,
    snapshotState: String = "hot",
    transientReserveBytes: Long = 0L
): MemoryTrackerScope? {
    if (!DebugMemoryTracker.isEnabled()) return null
    val state = uiState.value
    return MemoryTrackerScope(
        name = name,
        documentGeneration = historyCoordinator.currentGeneration(),
        baseContentToken = state.baseContentToken,
        revision = state.revision,
        snapshotState = snapshotState,
        transientReserveBytes = transientReserveBytes
    )
}

internal data class BitmapOwnershipEntry(
    val owner: String,
    val operation: String,
    val width: Int,
    val height: Int,
    val config: Bitmap.Config?,
    val bytes: Long,
    val isUiStateBitmap: Boolean
)

internal object BitmapOwnershipInspector {
    fun inspectCurrentOwnership(viewModel: EditorViewModel): List<BitmapOwnershipEntry> {
        if (!DebugMemoryTracker.isEnabled()) return emptyList()
        val state = viewModel.uiState.value
        val uiBitmaps = IdentityHashMap<Bitmap, Boolean>()
        state.previewBitmap?.let { uiBitmaps[it] = true }
        state.originalPreviewBitmap?.let { uiBitmaps[it] = true }
        state.selectionLayers.forEach { uiBitmaps[it.bitmap] = true }

        val entries = ArrayList<BitmapOwnershipEntry>()
        uiBitmaps.keys.forEach { bitmap ->
            if (!bitmap.isRecycled) {
                entries += BitmapOwnershipEntry(
                    owner = "EditorUiState",
                    operation = "live",
                    width = bitmap.width,
                    height = bitmap.height,
                    config = bitmap.config,
                    bytes = BitmapMemoryBudget.bytes(bitmap),
                    isUiStateBitmap = true
                )
            }
        }
        return entries
    }

    fun residentSummary(): String {
        if (!DebugMemoryTracker.isEnabled()) return "release build - no tracking"
        return DebugMemoryTracker.debugString()
    }
}
