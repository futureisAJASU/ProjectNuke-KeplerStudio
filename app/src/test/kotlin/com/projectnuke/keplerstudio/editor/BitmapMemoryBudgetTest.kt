package com.projectnuke.keplerstudio.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BitmapMemoryBudgetTest {

    @Test
    fun testBytesForArgb8888() {
        val bytes = BitmapMemoryBudget.bytes(100, 200, android.graphics.Bitmap.Config.ARGB_8888)
        assertEquals(100L * 200L * 4L, bytes)
    }

    @Test
    fun testBytesForRgb565() {
        val bytes = BitmapMemoryBudget.bytes(100, 200, android.graphics.Bitmap.Config.RGB_565)
        assertEquals(100L * 200L * 2L, bytes)
    }

    @Test
    fun testBytesForAlpha8() {
        val bytes = BitmapMemoryBudget.bytes(100, 200, android.graphics.Bitmap.Config.ALPHA_8)
        assertEquals(100L * 200L * 1L, bytes)
    }

    @Test
    fun testBytesForRgbaF16() {
        val bytes = BitmapMemoryBudget.bytes(100, 200, android.graphics.Bitmap.Config.RGBA_F16)
        assertEquals(100L * 200L * 8L, bytes)
    }

    @Test
    fun testBytesZeroDimensions() {
        assertEquals(0L, BitmapMemoryBudget.bytes(0, 0))
        assertEquals(0L, BitmapMemoryBudget.bytes(-1, 100))
        assertEquals(0L, BitmapMemoryBudget.bytes(100, -1))
    }

    @Test
    fun testCanAllocateZeroOrNegative() {
        assertTrue(BitmapMemoryBudget.canAllocate(0L))
        assertTrue(BitmapMemoryBudget.canAllocate(-1L))
    }

    @Test
    fun testCanAllocateLargeRequest() {
        val requiredBytes = Long.MAX_VALUE / 2
        assertFalse(BitmapMemoryBudget.canAllocate(requiredBytes))
    }

    @Test
    fun testSaturatingAdd() {
        assertEquals(3L, BitmapMemoryBudget.saturatingAdd(1L, 2L))
        assertEquals(3L, BitmapMemoryBudget.saturatingAdd(1L, 2L, 0L))
        assertEquals(Long.MAX_VALUE, BitmapMemoryBudget.saturatingAdd(Long.MAX_VALUE, 1L))
        assertEquals(Long.MAX_VALUE, BitmapMemoryBudget.saturatingAdd(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(0L, BitmapMemoryBudget.saturatingAdd(0L, 0L))
        assertEquals(1L, BitmapMemoryBudget.saturatingAdd(-1L, 1L))
    }

    @Test
    fun testSaturatingMultiply() {
        assertEquals(6L, BitmapMemoryBudget.saturatingMultiply(2L, 3L))
        assertEquals(0L, BitmapMemoryBudget.saturatingMultiply(0L, 100L))
        assertEquals(0L, BitmapMemoryBudget.saturatingMultiply(-1L, 100L))
        assertEquals(Long.MAX_VALUE, BitmapMemoryBudget.saturatingMultiply(Long.MAX_VALUE, 2L))
    }

    @Test
    fun testHistoryBudgetBytesLowerBound() {
        val budget = BitmapMemoryBudget.historyBudgetBytes()
        assertTrue(budget >= 16L * 1024L * 1024L)
    }

    @Test
    fun testThumbnailBudgetBytesLowerBound() {
        val budget = BitmapMemoryBudget.thumbnailBudgetBytes()
        assertTrue(budget >= 4L * 1024L * 1024L)
    }

    @Test
    fun testHistoryDiskBudgetBytesLowerBound() {
        val budget = BitmapMemoryBudget.historyDiskBudgetBytes()
        assertTrue(budget >= 128L * 1024L * 1024L)
    }

    @Test
    fun testOperationReserveBytes() {
        val reserve = BitmapMemoryBudget.operationReserveBytes()
        assertTrue(reserve >= 96L * 1024L * 1024L)
    }

    @Test
    fun testModelReserveBytes() {
        val reserve = BitmapMemoryBudget.modelReserveBytes()
        assertTrue(reserve >= 0L)
    }
}
