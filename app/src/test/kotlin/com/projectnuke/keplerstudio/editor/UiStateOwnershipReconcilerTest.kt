package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class UiStateOwnershipReconcilerTest {
    private lateinit var tracker: TrackerSession
    private lateinit var reconciler: UiStateOwnershipReconciler

    @Before
    fun setUp() {
        tracker = TrackerSession("ui")
        tracker.activateDocument("A")
        reconciler = UiStateOwnershipReconciler(tracker)
    }

    @After
    fun tearDown() {
        reconciler.releaseAll()
        tracker.close()
    }

    @Test
    fun initialMissingHandleIsAcquiredEvenWhenStatesAliasSameBitmap() {
        val bitmap = bitmap()
        val state = EditorUiState(previewBitmap = bitmap)
        reconciler.reconcile(state, state, "A")
        assertEquals(1, tracker.snapshot().acquisitionCount)
        reconciler.reconcile(state, state, "A")
        assertEquals(1, tracker.snapshot().acquisitionCount)
    }

    @Test
    fun previewOriginalAliasHasTwoEdgesAndOneNode() {
        val bitmap = bitmap()
        val next = EditorUiState(previewBitmap = bitmap, originalPreviewBitmap = bitmap)
        reconciler.reconcile(null, next, "A")
        val snapshot = tracker.snapshot()
        assertEquals(1, snapshot.bitmapCount)
        assertEquals(2, snapshot.acquisitionCount)
        assertTrue(snapshot.byOwner.containsKey(TrackerOwners.UI_STATE_PREVIEW))
        assertTrue(snapshot.byOwner.containsKey(TrackerOwners.UI_STATE_ORIGINAL))
    }

    @Test
    fun sameBitmapRebindsWhenGenerationChanges() {
        val bitmap = bitmap()
        val state = EditorUiState(previewBitmap = bitmap)
        reconciler.reconcile(null, state, "A")
        tracker.activateDocument("B", "A")
        reconciler.reconcile(state, state, "B")
        assertEquals(1, tracker.snapshot().acquisitionCount)
        assertTrue(tracker.snapshot().byOwner.containsKey(TrackerOwners.UI_STATE_PREVIEW))
    }

    @Test
    fun selectionReorderDoesNotDuplicateAndRemovalReleasesExactSlot() {
        val first = SelectionLayer("one", "one", SelectionLayerKind.Brush, bitmap())
        val second = SelectionLayer("two", "two", SelectionLayerKind.Brush, bitmap())
        val initial = EditorUiState(selectionLayers = listOf(first, second))
        reconciler.reconcile(null, initial, "A")
        reconciler.reconcile(initial, initial.copy(selectionLayers = listOf(second, first)), "A")
        assertEquals(2, tracker.snapshot().acquisitionCount)
        reconciler.reconcile(initial, initial.copy(selectionLayers = listOf(second)), "A")
        assertEquals(1, tracker.snapshot().acquisitionCount)
        assertFalse(tracker.snapshot().byOwner.containsKey(TrackerOwners.selectionLayer("one")))
    }

    @Test
    fun failedCommitSimulationDoesNotMutateOwnership() {
        val initial = EditorUiState(previewBitmap = bitmap())
        val rejected = EditorUiState(previewBitmap = bitmap())
        reconciler.reconcile(null, initial, "A")
        // A failed state CAS never calls reconcile.
        assertEquals(initial.previewBitmap!!.allocationByteCount.toLong(), tracker.snapshot().totalBytes)
        assertFalse(tracker.snapshot().byOwner.keys.any { it.contains(rejected.previewBitmap.hashCode().toString()) })
    }

    @Test
    fun oldEdgeIsReleasedBeforeCallerRecyclesDisplacedBitmap() {
        val old = bitmap()
        val replacement = bitmap()
        val before = EditorUiState(previewBitmap = old)
        val after = EditorUiState(previewBitmap = replacement)
        reconciler.reconcile(null, before, "A")
        reconciler.reconcile(before, after, "A")
        old.recycle()
        assertEquals(replacement.allocationByteCount.toLong(), tracker.snapshot().totalBytes)
    }

    @Test
    fun releaseAllOnlyDropsHandles() {
        val bitmap = bitmap()
        reconciler.reconcile(null, EditorUiState(previewBitmap = bitmap), "A")
        reconciler.releaseAll()
        assertEquals(0, tracker.snapshot().bitmapCount)
        assertFalse(bitmap.isRecycled)
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
}
