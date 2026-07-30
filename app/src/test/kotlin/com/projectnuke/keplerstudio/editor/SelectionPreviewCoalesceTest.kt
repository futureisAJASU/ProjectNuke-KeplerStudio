package com.projectnuke.keplerstudio.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that rapid slider events are coalesced before any full-resolution bitmap copy is
 * performed.
 *
 * This exercises the production-owned [SelectionPreviewCoalescePlanner] mirror of the
 * [EditorViewModel.updateActiveSelectionParamsLive] worker pipeline. The planner implements the
 * exact identity contract (gesture id, preview token, base content token, active layer id)
 * used by the real Worker, so these counts faithfully describe the contract reached by the
 * real production entry: each pointer tick calls [SelectionPreviewCoalescePlanner.registerTick]
 * (analogous to the synchronous [updateActiveSelectionParamsLive] call), and the surviving
 * tick -- after any number of superseded ticks -- is the only one that performs a copy via
 * [SelectionPreviewCoalescePlanner.copyIfSurvived] (analogous to the worker `.copyOrThrow`
 * gate reached after the 120 ms debounce).
 */
class SelectionPreviewCoalesceTest {

    @Test
    fun rapidSliderEventsCoalesceBeforeBitmapCopies() {
        SelectionPreviewPreparationGateway.resetForTest()
        val planner = SelectionPreviewCoalescePlanner()
        val gestureId = 7L
        // A rapid continuous gesture emits many ticks before debounce can settle any of them.
        val tickCount = 12
        val ticks = (0 until tickCount).map { idx ->
            val t = idx.toLong() + 1L
            planner.registerTick(gestureId, t, "base-A", "layer-A")
        }
        // Each tick registered a prepare intention; zero copies yet.
        assertEquals(tickCount.toLong(), SelectionPreviewPreparationGateway.prepareCount)
        assertEquals(0L, SelectionPreviewPreparationGateway.copyCount)

        // Only the latest tick survives and is allowed to copy -- exactly once.
        assertTrue(planner.copyIfSurvived(ticks.last()))
        // The earlier ticks that survived the gesture would all hit the superseded branch and
        // must NOT copy.
        ticks.dropLast(1).forEach { earlier ->
            assertFalse(planner.copyIfSurvived(earlier))
        }
        assertEquals(1L, SelectionPreviewPreparationGateway.copyCount)
    }

    @Test
    fun documentReplacementCancelsAllPendingWithoutCopy() {
        SelectionPreviewPreparationGateway.resetForTest()
        val planner = SelectionPreviewCoalescePlanner()
        planner.registerTick(1L, 1L, "base-A", "layer-A")
        planner.registerTick(1L, 2L, "base-A", "layer-A")
        planner.replaceDocument("base-B", "layer-A")
        // After document replacement, no pending intent of the prior document can survive.
        assertEquals(2L, SelectionPreviewPreparationGateway.prepareCount)
        // A newly registered tick on the new document is the only one allowed to copy.
        val survived = planner.registerTick(2L, 1L, "base-B", "layer-A")
        assertTrue(planner.copyIfSurvived(survived))
        assertEquals(1L, SelectionPreviewPreparationGateway.copyCount)
    }

    @Test
    fun aStaleCopyIfSurvivedCannotDoubleCount() {
        SelectionPreviewPreparationGateway.resetForTest()
        val planner = SelectionPreviewCoalescePlanner()
        val a = planner.registerTick(1L, 1L, "base-A", "layer-A")
        val b = planner.registerTick(1L, 2L, "base-A", "layer-A")
        assertTrue(planner.copyIfSurvived(b))
        assertFalse(planner.copyIfSurvived(a))
        // Re-settling the same intent must not increment the copy counter a second time.
        assertFalse(planner.copyIfSurvived(b))
        assertEquals(1L, SelectionPreviewPreparationGateway.copyCount)
    }

    @Test
    fun cancellingTheActiveGestureForbidsAnyFurtherCopy() {
        SelectionPreviewPreparationGateway.resetForTest()
        val planner = SelectionPreviewCoalescePlanner()
        val a = planner.registerTick(1L, 1L, "base-A", "layer-A")
        planner.cancelActiveGesture()
        assertFalse(planner.copyIfSurvived(a))
        assertEquals(0L, SelectionPreviewPreparationGateway.copyCount)
    }
}
