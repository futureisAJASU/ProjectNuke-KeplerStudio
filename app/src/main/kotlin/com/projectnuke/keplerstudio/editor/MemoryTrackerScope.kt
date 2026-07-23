package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap

internal class MemoryTrackerScope(
    val name: String,
    val token: Long,
    val documentGeneration: String,
    val baseContentToken: String,
    val revision: Int,
    val snapshotState: String,
    private val transientReserveBytes: Long
) {
    private val trackedBitmaps = ArrayDeque<Pair<Bitmap, String>>(8)

    init {
        DebugMemoryTracker.beginOperation(
            name = name,
            documentGeneration = documentGeneration,
            baseContentToken = baseContentToken,
            revision = revision,
            transientReserveBytes = transientReserveBytes,
            snapshotState = snapshotState
        )
    }

    fun track(bitmap: Bitmap, owner: String) {
        if (bitmap.isRecycled) return
        DebugMemoryTracker.registerBitmap(
            bitmap = bitmap,
            owner = owner,
            operation = name,
            token = token,
            documentGeneration = documentGeneration
        )
        trackedBitmaps.addLast(bitmap to owner)
    }

    fun trackAll(bitmaps: List<Bitmap>, owner: String) {
        bitmaps.forEach { track(it, owner) }
    }

    fun release(bitmap: Bitmap) {
        DebugMemoryTracker.unregisterBitmap(bitmap)
        trackedBitmaps.removeIf { it.first === bitmap }
    }

    fun releaseAll() {
        trackedBitmaps.forEach { DebugMemoryTracker.unregisterBitmap(it.first) }
        trackedBitmaps.clear()
    }

    fun end() {
        releaseAll()
        DebugMemoryTracker.endOperation(name, token)
    }
}

internal fun EditorViewModel.beginMemoryTracking(
    name: String,
    snapshotState: String = "hot",
    transientReserveBytes: Long = 0L
): MemoryTrackerScope? {
    if (!DebugMemoryTracker.isEnabled()) return null
    val state = uiState.value
    val token = DebugMemoryTracker.newDocumentToken()
    return MemoryTrackerScope(
        name = name,
        token = token,
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
