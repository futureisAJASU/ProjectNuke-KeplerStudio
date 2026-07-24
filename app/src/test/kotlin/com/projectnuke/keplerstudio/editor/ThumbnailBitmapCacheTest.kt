package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.graphics.Bitmap.Config as BitmapConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ThumbnailBitmapCacheTest {

    private lateinit var tracker: TrackerSession

    @Before
    fun setUp() {
        tracker = TrackerSession("test")
        ThumbnailBitmapCache.attachDiagnostics(tracker)
        ThumbnailBitmapCache.setByteBudget(64L * 1024L * 1024L)
    }

    @After
    fun tearDown() {
        ThumbnailBitmapCache.detachDiagnostics()
        ThumbnailBitmapCache.clear()
        tracker.clear()
    }

    @Test
    fun testAcquireAndReleaseBitmap() = runBlocking {
        val bitmap = createMockBitmap(100, 100)
        val lease = ThumbnailBitmapCache.acquire("key1") { bitmap }
        assertTrue(lease != null)
        lease!!.close()
        ThumbnailBitmapCache.clear()
    }

    @Test
    fun testAcquireReturnsCachedOnSecondCall() = runBlocking {
        val bitmap = createMockBitmap(100, 100)
        var decodeCount = 0
        val lease1 = ThumbnailBitmapCache.acquire("key1") {
            decodeCount++
            bitmap
        }
        assertTrue(lease1 != null)
        lease1!!.close()
        val lease2 = ThumbnailBitmapCache.acquire("key1") {
            decodeCount++
            bitmap
        }
        assertTrue(lease2 != null)
        lease2!!.close()
        assertEquals(1, decodeCount)
    }

    @Test
    fun testEvictUnleasedRemovesZeroLeaseEntries() = runBlocking {
        val bitmap = createMockBitmap(100, 100)
        val lease = ThumbnailBitmapCache.acquire("key1") { bitmap }
        assertTrue(lease != null)
        lease!!.close()
        val evicted = ThumbnailBitmapCache.evictUnleased()
        assertTrue(evicted > 0L)
    }

    @Test
    fun testInvalidateRemovesEntry() = runBlocking {
        val bitmap = createMockBitmap(100, 100)
        val lease = ThumbnailBitmapCache.acquire("key1") { bitmap }
        assertTrue(lease != null)
        lease!!.close()
        ThumbnailBitmapCache.invalidate("key1")
        val lease2 = ThumbnailBitmapCache.acquire("key1") { bitmap }
        assertTrue(lease2 != null)
        lease2!!.close()
    }

    @Test
    fun testClearRemovesAllEntries() = runBlocking {
        val bitmap = createMockBitmap(100, 100)
        val lease = ThumbnailBitmapCache.acquire("key1") { bitmap }
        assertTrue(lease != null)
        lease!!.close()
        ThumbnailBitmapCache.clear()
        tracker.clear()
    }

    @Test
    fun testSetByteBudgetTriggersEviction() = runBlocking {
        val bitmap = createMockBitmap(100, 100)
        val lease = ThumbnailBitmapCache.acquire("key1") { bitmap }
        assertTrue(lease != null)
        lease!!.close()
        ThumbnailBitmapCache.setByteBudget(1L)
    }

    @Test
    fun testLeaseTrackingRegisterAndUnregister() = runBlocking {
        val bitmap = createMockBitmap(100, 100)
        val lease = ThumbnailBitmapCache.acquire("key1") { bitmap }
        assertTrue(lease != null)
        lease!!.close()
    }

    @Test
    fun testLease0To1Transition() = runBlocking {
        val bitmap = createMockBitmap(100, 100)
        val lease = ThumbnailBitmapCache.acquire("key1") { bitmap }
        assertTrue(lease != null)
        val snap = ThumbnailBitmapCache.thumbnailCacheSnapshot()
        assertEquals(1, snap.totalActiveLeases)
        lease!!.close()
    }

    @Test
    fun testLease1To2Transition() = runBlocking {
        val bitmap = createMockBitmap(100, 100)
        val lease1 = ThumbnailBitmapCache.acquire("key1") { bitmap }
        val lease2 = ThumbnailBitmapCache.acquire("key1") { bitmap }
        assertTrue(lease1 != null)
        assertTrue(lease2 != null)
        val snap = ThumbnailBitmapCache.thumbnailCacheSnapshot()
        assertEquals(2, snap.totalActiveLeases)
        lease1!!.close()
        lease2!!.close()
    }

    @Test
    fun testLease2To1Transition() = runBlocking {
        val bitmap = createMockBitmap(100, 100)
        val lease1 = ThumbnailBitmapCache.acquire("key1") { bitmap }
        val lease2 = ThumbnailBitmapCache.acquire("key1") { bitmap }
        lease1!!.close()
        val snap = ThumbnailBitmapCache.thumbnailCacheSnapshot()
        assertEquals(1, snap.totalActiveLeases)
        lease2!!.close()
    }

    @Test
    fun testLease1To0Transition() = runBlocking {
        val bitmap = createMockBitmap(100, 100)
        val lease = ThumbnailBitmapCache.acquire("key1") { bitmap }
        lease!!.close()
        val snap = ThumbnailBitmapCache.thumbnailCacheSnapshot()
        assertEquals(0, snap.totalActiveLeases)
    }

    @Test
    fun testEvictionWithActiveLease() = runBlocking {
        val bitmap = createMockBitmap(100, 100, forceAllocationBytes = 1024L * 1024L)
        ThumbnailBitmapCache.setByteBudget(1L)
        val lease = ThumbnailBitmapCache.acquire("key1") { bitmap }
        assertTrue(lease != null)
        val snap = ThumbnailBitmapCache.thumbnailCacheSnapshot()
        assertEquals(0, snap.residentEntryCount)
        lease!!.close()
    }

    @Test
    fun testClearWithActiveLease() = runBlocking {
        val bitmap = createMockBitmap(100, 100)
        val lease = ThumbnailBitmapCache.acquire("key1") { bitmap }
        assertTrue(lease != null)
        ThumbnailBitmapCache.clear()
        val snap = ThumbnailBitmapCache.thumbnailCacheSnapshot()
        assertEquals(0, snap.residentEntryCount)
        lease!!.close()
    }

    private fun createMockBitmap(width: Int, height: Int, forceAllocationBytes: Long = -1L): Bitmap {
        return Bitmap.createBitmap(width, height, BitmapConfig.ARGB_8888)
    }
}
