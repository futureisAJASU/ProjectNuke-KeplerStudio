package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SelectionMaskOwnershipLedgerTest {

    private fun bitmap(w: Int = 8, h: Int = 8): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun `acquire returns null for null or recycled bitmaps`() {
        val ledger = SelectionMaskOwnershipLedger()
        assertNull(ledger.acquire(null, MaskOwnerKind.ActiveState))
        val recycled = bitmap()
        recycled.recycle()
        assertNull(ledger.acquire(recycled, MaskOwnerKind.ActiveState))
    }

    @Test
    fun `single owner defers recycle across handle close`() {
        val ledger = SelectionMaskOwnershipLedger()
        val b = bitmap()
        val handle = ledger.acquire(b, MaskOwnerKind.BrushWorkingCopy)!!
        assertEquals(setOf(MaskOwnerKind.BrushWorkingCopy), ledger.ownersFor(b))

        // tryRetire returns false while owner is live
        assertFalse(ledger.tryRetire(b), "live owner must block retirement")
        assertFalse(b.isRecycled)

        handle.close()
        assertTrue(ledger.tryRetire(b), "no owner now allows retirement")
        assertTrue(b.isRecycled, "tryRetire should recycle atomically when allowed")
    }

    @Test
    fun `multiple owners of same kind stack`() {
        val ledger = SelectionMaskOwnershipLedger()
        val b = bitmap()
        val h1 = ledger.acquire(b, MaskOwnerKind.HistorySnapshot)!!
        val h2 = ledger.acquire(b, MaskOwnerKind.HistorySnapshot)!!
        assertFalse(ledger.tryRetire(b))

        h1.close()
        assertFalse(ledger.tryRetire(b), "second owner still holds")

        h2.close()
        assertTrue(ledger.tryRetire(b))
        assertTrue(b.isRecycled)
    }

    @Test
    fun `multiple distinct owners are additive but tracked separately`() {
        val ledger = SelectionMaskOwnershipLedger()
        val b = bitmap()
        val active = ledger.acquire(b, MaskOwnerKind.ActiveState)!!
        val snapshot = ledger.acquire(b, MaskOwnerKind.HistorySnapshot)!!
        assertEquals(
            setOf(MaskOwnerKind.ActiveState, MaskOwnerKind.HistorySnapshot),
            ledger.ownersFor(b)
        )
        assertEquals(2, ledger.totalHandleCount())
        assertEquals(1, ledger.liveCount())

        // Release one of two: bitmap stays live
        active.close()
        assertEquals(setOf(MaskOwnerKind.HistorySnapshot), ledger.ownersFor(b))
        assertFalse(ledger.tryRetire(b))

        // Release the last: bitmap can retire
        snapshot.close()
        assertTrue(ledger.tryRetire(b))
        assertTrue(b.isRecycled)
    }

    @Test
    fun `handle double close is idempotent`() {
        val ledger = SelectionMaskOwnershipLedger()
        val b = bitmap()
        val h = ledger.acquire(b, MaskOwnerKind.LivePreview)!!
        h.close()
        h.close()
        // No crash; one owner was counted, decrement only once
        assertEquals(0, ledger.totalHandleCount())
    }

    @Test
    fun `tryRetire on recycled bitmap returns false`() {
        val ledger = SelectionMaskOwnershipLedger()
        val b = bitmap()
        ledger.acquire(b, MaskOwnerKind.TransientUi)!!.close()
        b.recycle()
        assertFalse(ledger.tryRetire(b))
    }

    @Test
    fun `acquire after recycle is null`() {
        val ledger = SelectionMaskOwnershipLedger()
        val b = bitmap()
        b.recycle()
        assertNull(ledger.acquire(b, MaskOwnerKind.BrushWorkingCopy))
    }

    @Test
    fun `liveCount and totalHandleCount track the ledger state`() {
        val ledger = SelectionMaskOwnershipLedger()
        val b1 = bitmap()
        val b2 = bitmap()
        assertEquals(0, ledger.liveCount())
        assertEquals(0, ledger.totalHandleCount())

        val h1a = ledger.acquire(b1, MaskOwnerKind.ActiveState)!!
        val h1b = ledger.acquire(b1, MaskOwnerKind.HistorySnapshot)!!
        val h2 = ledger.acquire(b2, MaskOwnerKind.LivePreview)!!
        assertEquals(2, ledger.liveCount())
        assertEquals(3, ledger.totalHandleCount())

        h1a.close()
        assertEquals(2, ledger.liveCount())
        assertEquals(2, ledger.totalHandleCount())

        h1b.close()
        assertEquals(1, ledger.liveCount())
        assertEquals(1, ledger.totalHandleCount())

        h2.close()
        assertEquals(0, ledger.liveCount())
        assertEquals(0, ledger.totalHandleCount())
    }

    @Test
    fun `resetForTest clears all state`() {
        val ledger = SelectionMaskOwnershipLedger()
        val b = bitmap()
        ledger.acquire(b, MaskOwnerKind.SubjectExtract)!!.close()
        assertNotNull(b.takeIf { !it.isRecycled })
        ledger.resetForTest()
        assertEquals(0, ledger.liveCount())
        assertEquals(emptySet(), ledger.ownersFor(b))
    }

    @Test
    fun `ownersFor returns empty for unknown bitmap`() {
        val ledger = SelectionMaskOwnershipLedger()
        assertEquals(emptySet(), ledger.ownersFor(bitmap()))
    }

    @Test
    fun `acquire after full close re-establishes ownership`() {
        val ledger = SelectionMaskOwnershipLedger()
        val b = bitmap()
        val first = ledger.acquire(b, MaskOwnerKind.BrushWorkingCopy)!!
        first.close()
        assertTrue(ledger.tryRetire(b))
        assertTrue(b.isRecycled)
    }

    @Test
    fun `mask reservation is atomic additive and owner scoped`() {
        val ledger = SelectionMaskOwnershipLedger { 100L }
        val first = ledger.reserve("first", 60L)!!
        assertNull(ledger.reserve("second", 50L))
        assertEquals(60L, ledger.reservedBytes())
        assertEquals(1, ledger.reservedLayers())
        first.close()
        first.close()
        assertEquals(0L, ledger.reservedBytes())
        assertEquals(0, ledger.reservedLayers())
        assertNotNull(ledger.reserve("second", 100L))
    }

    @Test
    fun `active mask ownership consumes additive byte and layer admission`() {
        val ledger =
            SelectionMaskOwnershipLedger(
                byteBudget = { 100L },
                layerBudget = { 2 },
            )
        val bitmap = bitmap(4, 4)
        val layer = SelectionLayer("active", "active", SelectionLayerKind.Brush, bitmap)

        ledger.reconcileActiveState(listOf(layer))

        assertEquals(BitmapMemoryBudget.bytes(bitmap), ledger.activeBytes())
        assertEquals(1, ledger.activeLayers())
        assertNull(ledger.reserve("pending", 40L))

        ledger.reconcileActiveState(emptyList())
        ledger.reserve("pending", 40L)!!.close()
        bitmap.recycle()
    }
}
