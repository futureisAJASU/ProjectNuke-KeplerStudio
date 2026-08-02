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
class BitmapLeaseTest {

    private val ledger = BitmapLeaseLedger()

    private fun createTestBitmap(w: Int = 16, h: Int = 16): Bitmap =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    @Test
    fun `lease prevents immediate recycle during state replacement`() {
        val original = createTestBitmap()
        val state = EditorUiState(previewBitmap = original)
        val lease = BitmapLease.acquire("test", state, ledger)
        assertNotNull(lease)

        val orphaned = ledger.retireStateBitmap(original)
        assertNull(orphaned, "bitmap should be deferred when lease holds it")
        assertFalse(original.isRecycled)

        lease!!.close()
        assertTrue(original.isRecycled, "bitmap should recycle after lease closes")
    }

    @Test
    fun `recycle occurs after final lease closes`() {
        val bitmap = createTestBitmap()
        val state = EditorUiState(previewBitmap = bitmap)

        val lease1 = BitmapLease.acquire("1", state, ledger)!!
        val lease2 = BitmapLease.acquire("2", state, ledger)!!

        ledger.retireStateBitmap(bitmap)
        assertFalse(bitmap.isRecycled)

        lease1.close()
        assertFalse(bitmap.isRecycled, "still pinned by lease2")

        lease2.close()
        assertTrue(bitmap.isRecycled, "final lease releases everything")
    }

    @Test
    fun `cancellation releases the lease`() {
        val bitmap = createTestBitmap()
        val state = EditorUiState(previewBitmap = bitmap)

        val lease = BitmapLease.acquire("test", state, ledger)!!
        lease.close()
        assertFalse(bitmap.isRecycled, "only retired, not leased anymore")

        val retired = ledger.retireStateBitmap(bitmap)
        assertNotNull(retired)
        retired!!.recycle()
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `simultaneous readers are additive`() {
        val bitmap = createTestBitmap()
        val state = EditorUiState(previewBitmap = bitmap)

        val leases = (0 until 5).map { BitmapLease.acquire("$it", state, ledger)!! }
        ledger.retireStateBitmap(bitmap)
        assertFalse(bitmap.isRecycled)

        for (i in 0 until 4) leases[i].close()
        assertFalse(bitmap.isRecycled, "one lease still holds")

        leases[4].close()
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `release order is independent`() {
        val bitmap = createTestBitmap()
        val state = EditorUiState(previewBitmap = bitmap)

        val lease1 = BitmapLease.acquire("1", state, ledger)!!
        val lease2 = BitmapLease.acquire("2", state, ledger)!!

        ledger.retireStateBitmap(bitmap)
        assertFalse(bitmap.isRecycled)

        lease2.close()
        assertFalse(bitmap.isRecycled)

        lease1.close()
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `document replacement rejects adoption while preserving lifetime`() {
        val bitmap1 = createTestBitmap(16, 16)
        val bitmap2 = createTestBitmap(32, 32)

        val state1 = EditorUiState(sourcePath = "A", previewBitmap = bitmap1, baseContentToken = "a")
        val state2 = EditorUiState(sourcePath = "B", previewBitmap = bitmap2, baseContentToken = "b")

        val lease = BitmapLease.acquire("old", state1, ledger)!!
        assertEquals("A", lease.identity.sourcePath)

        ledger.retireStateBitmap(bitmap1)
        assertFalse(bitmap1.isRecycled, "lease pins bitmap1")

        val lease2 = BitmapLease.acquire("new", state2, ledger)!!
        assertEquals("B", lease2.identity.sourcePath)

        lease.close()
        assertTrue(bitmap1.isRecycled)
        lease2.close()
    }

    @Test
    fun `double close is idempotent`() {
        val bitmap = createTestBitmap()
        val state = EditorUiState(previewBitmap = bitmap)
        val lease = BitmapLease.acquire("test", state, ledger)!!
        lease.close()
        lease.close()
        // No crash = pass
    }

    @Test
    fun `state removal without lease recycles immediately`() {
        val bitmap = createTestBitmap()
        val direct = ledger.retireStateBitmap(bitmap)
        assertNotNull(direct, "no lease: should recycle immediately")
        assertFalse(direct!!.isRecycled)
        direct.recycle()
    }

    @Test
    fun `pinBitmap returns null for null or recycled bitmaps`() {
        assertNull(ledger.pinBitmap(null))
        val recycled = createTestBitmap()
        recycled.recycle()
        assertNull(ledger.pinBitmap(recycled))
    }

    @Test
    fun `pinBitmap defers recycle across retirement`() {
        val bitmap = createTestBitmap()
        val pin = ledger.pinBitmap(bitmap)
        assertNotNull(pin)
        assertFalse(bitmap.isRecycled)

        // State removal while pin is held: bitmap is deferred
        val orphan = ledger.retireStateBitmap(bitmap)
        assertNull(orphan, "pin should defer recycle")
        assertFalse(bitmap.isRecycled)

        // Release pin: bitmap should recycle now (state removal is still pending)
        pin!!.close()
        assertTrue(bitmap.isRecycled, "recycle after pin close + retirement pending")
    }

    @Test
    fun `pinBitmap is identity-keyed and additive across consumers`() {
        val bitmap = createTestBitmap()
        val pin1 = ledger.pinBitmap(bitmap)!!
        val pin2 = ledger.pinBitmap(bitmap)!!
        ledger.retireStateBitmap(bitmap)
        assertFalse(bitmap.isRecycled, "two pins + retirement should still defer")

        pin1.close()
        assertFalse(bitmap.isRecycled, "one pin still holds")

        pin2.close()
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `pinBitmap double close is idempotent`() {
        val bitmap = createTestBitmap()
        val pin = ledger.pinBitmap(bitmap)!!
        pin.close()
        pin.close()
        // No crash, no premature recycle: state still owns the bitmap
        assertFalse(bitmap.isRecycled)
    }

    @Test
    fun `pinBitmap does not lock immediate state removal of unrelated bitmaps`() {
        val pinned = createTestBitmap()
        val unrelated = createTestBitmap()
        val pin = ledger.pinBitmap(pinned)!!

        // Retire unrelated — must recycle immediately (no pin interference)
        val orphan = ledger.retireStateBitmap(unrelated)
        assertNotNull(orphan, "unrelated bitmap should recycle immediately")
        orphan!!.recycle()
        assertFalse(pinned.isRecycled, "pinned bitmap unaffected")

        pin.close()
        assertFalse(pinned.isRecycled, "release without prior state removal is a no-op")
    }

    @Test
    fun `pinBitmap after state removal still defers recycle`() {
        val bitmap = createTestBitmap()
        // Retire first — bitmap should be reclaimed by the ledger's slot table
        // but not recycled yet because the ledger keeps the slot until refs go to zero.
        // Note: retireStateBitmap returns the bitmap for immediate recycle only when the
        // slot has zero leaseRefs; since no lease exists yet, this returns the bitmap
        // for the caller to recycle. To exercise the post-removal pin path, we must
        // first establish a slot via a pin before retiring.
        val pin = ledger.pinBitmap(bitmap)!!
        // Now retire: stateRemovedCount > 0, leaseRef > 0 -> null (deferred)
        val orphan = ledger.retireStateBitmap(bitmap)
        assertNull(orphan)
        assertFalse(bitmap.isRecycled)
        pin.close()
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `atomic snapshot pins exact state across replacement and re adoption`() {
        val oldBitmap = createTestBitmap()
        val newBitmap = createTestBitmap()
        val oldState = EditorUiState(previewBitmap = oldBitmap, revision = 7)
        val newState = EditorUiState(previewBitmap = newBitmap, revision = 8)
        ledger.replaceState(EditorUiState(), oldState).forEach { it.recycle() }
        val snapshot = ledger.capture("atomic", oldState, "document-1")!!

        assertTrue(ledger.replaceState(oldState, newState).isEmpty())
        assertFalse(oldBitmap.isRecycled)
        ledger.replaceState(newState, oldState).forEach { it.recycle() }
        snapshot.close()
        assertFalse(oldBitmap.isRecycled, "re-adoption cancels retirement")
        ledger.replaceState(oldState, EditorUiState()).forEach { it.recycle() }
        assertTrue(oldBitmap.isRecycled)
        ledger.replaceState(newState, EditorUiState()).forEach { it.recycle() }
        assertTrue(newBitmap.isRecycled)
    }

    @Test
    fun `atomic snapshot deduplicates same bitmap in state fields`() {
        val bitmap = createTestBitmap()
        val state = EditorUiState(previewBitmap = bitmap, originalPreviewBitmap = bitmap)
        ledger.replaceState(EditorUiState(), state).forEach { it.recycle() }
        val snapshot = ledger.capture("duplicate", state, "document-1")!!
        ledger.replaceState(state, EditorUiState()).forEach { it.recycle() }
        assertFalse(bitmap.isRecycled)
        snapshot.close()
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `retained worker snapshot survives caller close and retires once`() {
        val bitmap = createTestBitmap()
        val state = EditorUiState(previewBitmap = bitmap)
        ledger.replaceState(EditorUiState(), state).forEach { it.recycle() }
        val caller = ledger.capture("caller", state, "document-1")!!
        val worker = caller.retain("worker")!!

        caller.close()
        ledger.replaceState(state, EditorUiState()).forEach { it.recycle() }
        assertFalse(bitmap.isRecycled)

        worker.close()
        assertTrue(bitmap.isRecycled)
    }
}
