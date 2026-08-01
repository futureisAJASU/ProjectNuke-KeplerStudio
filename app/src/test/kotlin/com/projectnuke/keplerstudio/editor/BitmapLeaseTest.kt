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
}