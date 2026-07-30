package com.projectnuke.keplerstudio.editor

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExperimentalComparisonStoreTest {
    @AfterTest
    fun cleanup() {
        ExperimentalComparisonStore.clear()
        RetainedMemoryLedger.resetForTest()
    }

    @Test
    fun replacementDefersRecycleUntilViewerLeaseCloses() {
        ExperimentalComparisonStore.clear()
        RetainedMemoryLedger.resetForTest()
        val first = ownedArtifact("first", 0xff101010.toInt())
        ExperimentalComparisonStore.publishDebug(first)
        val viewerLease = first.retain()
        val firstBytes = first.retainedBitmapBytes

        val second = ownedArtifact("second", 0xff202020.toInt())
        val secondBytes = second.retainedBitmapBytes
        assertEquals(
            firstBytes + secondBytes,
            BitmapMemoryBudget.retainedMemorySnapshot().admissionBytes,
        )

        ExperimentalComparisonStore.publishDebug(second)
        assertFalse(first.isClosed)
        assertFalse(first.baseline.isRecycled)

        viewerLease.close()
        assertTrue(first.isClosed)
        assertTrue(first.baseline.isRecycled)
        assertEquals(
            secondBytes,
            BitmapMemoryBudget.retainedMemorySnapshot().admissionBytes,
        )

        ExperimentalComparisonStore.clear()
        assertTrue(second.isClosed)
        assertTrue(second.baseline.isRecycled)
        assertEquals(0L, BitmapMemoryBudget.retainedMemorySnapshot().admissionBytes)
    }

    private fun ownedArtifact(name: String, color: Int): OwnedDebugComparisonArtifact =
        OwnedDebugComparisonArtifact.create(
            QualityRegressionMetricsV2.debugArtifact(
                fixtureVersion = name,
                baseline = IntArray(16) { color },
                experimental = IntArray(16) { color xor 0x00010101 },
                width = 4,
                height = 4,
            )
        )
}
