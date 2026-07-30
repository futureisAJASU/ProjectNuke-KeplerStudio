package com.projectnuke.keplerstudio.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QualityRegressionMetricsV2Test {
    @Test
    fun retainedMemoryLedgerIsAdditiveAndDoesNotDoubleCountJavaHeap() {
        RetainedMemoryLedger.resetForTest()
        val comparison = RetainedMemoryLedger.reserve("comparison-test")
        val history = RetainedMemoryLedger.reserve("history-test")
        comparison.replace(RetainedMemoryCategory.JavaHeapObservable, 4_096L)
        comparison.replace(RetainedMemoryCategory.NativeBitmap, 8_192L)
        history.replace(RetainedMemoryCategory.NativeOrOpaqueRuntime, 16_384L)

        assertEquals(24_576L, BitmapMemoryBudget.retainedMemorySnapshot().admissionBytes)

        comparison.close()
        assertEquals(16_384L, BitmapMemoryBudget.retainedMemorySnapshot().admissionBytes)
        history.close()
        assertEquals(0L, BitmapMemoryBudget.retainedMemorySnapshot().admissionBytes)
    }

    @Test
    fun identicalFixtureHasZeroImageRegressionMetrics() {
        val fixture =
            intArrayOf(
                0xff000000.toInt(), 0xff202020.toInt(), 0xff808080.toInt(),
                0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(),
                0xffffffff.toInt(), 0xfffefefe.toInt(), 0xff010101.toInt(),
            )

        val metrics = QualityRegressionMetricsV2.compareArgb(fixture, fixture.copyOf(), 3, 3)

        assertEquals(0f, metrics.changedPixelRatio)
        assertEquals(0, metrics.maximumChannelDelta)
        assertEquals(0f, metrics.lumaMeanAbsoluteError)
        assertEquals(0f, metrics.localEdgeOvershoot)
    }

    @Test
    fun boundaryMetricsReportAreaComponentsAndExactBoundary() {
        val expected = BooleanArray(7 * 5)
        for (y in 1..3) {
            for (x in 1..2) expected[y * 7 + x] = true
            for (x in 4..5) expected[y * 7 + x] = true
        }

        val metrics = QualityRegressionMetricsV2.compareMasks(expected, expected.copyOf(), 7, 5)

        assertEquals(1f, metrics.intersectionOverUnion)
        assertEquals(1f, metrics.boundaryFScore)
        assertEquals(2, metrics.expectedComponentCount)
        assertEquals(2, metrics.actualComponentCount)
        assertEquals(0f, metrics.affectedAreaDrift)
    }

    @Test
    fun chromaAndBoundaryToleranceSeparateHueAndOnePixelShift() {
        val lumaOnly =
            QualityRegressionMetricsV2.compareArgb(
                intArrayOf(0xff404040.toInt()),
                intArrayOf(0xff808080.toInt()),
                1,
                1,
            )
        val hueShift =
            QualityRegressionMetricsV2.compareArgb(
                intArrayOf(0xff804040.toInt()),
                intArrayOf(0xff408040.toInt()),
                1,
                1,
            )
        assertEquals(0f, lumaOnly.chromaMeanAbsoluteError)
        assertTrue(hueShift.chromaMeanAbsoluteError > 0f)

        val expected = BooleanArray(5 * 5)
        val shifted = BooleanArray(5 * 5)
        for (y in 1..3) {
            expected[y * 5 + 1] = true
            shifted[y * 5 + 2] = true
        }
        val boundary = QualityRegressionMetricsV2.compareMasks(expected, shifted, 5, 5, tolerancePixels = 1)
        assertTrue(boundary.boundaryFScore < 1f)
        assertEquals(1f, boundary.toleranceBoundaryFScore)
    }

    @Test
    fun debugArtifactIsExplicitAndContainsStableHeatmapAndJson() {
        val baseline = intArrayOf(0xff101010.toInt(), 0xff808080.toInt())
        val candidate = intArrayOf(0xff201010.toInt(), 0xff808080.toInt())
        val mask = intArrayOf(0xffffffff.toInt(), 0xff000000.toInt())

        val artifact =
            QualityRegressionMetricsV2.debugArtifact(
                "generated-fixtures-v2",
                baseline,
                candidate,
                2,
                1,
                mask,
            )

        assertEquals(2, artifact.differenceHeatmapArgb.size)
        assertTrue(artifact.maskArgb?.contentEquals(mask) == true)
        assertTrue(artifact.compactMetricJson().contains("\"fixtureVersion\":\"generated-fixtures-v2\""))
        assertEquals(0.5f, artifact.metrics.changedPixelRatio)
    }

    @Test
    fun clippingFlatNoiseAndNeutralDriftAreSeparated() {
        val baseline = IntArray(16) { 0xff808080.toInt() }
        val clipped = baseline.copyOf().also {
            it[0] = 0xffffffff.toInt()
            it[1] = 0xff000000.toInt()
        }
        val chromaNoise =
            IntArray(16) { index ->
                if (index % 2 == 0) 0xff887888.toInt() else 0xff788878.toInt()
            }

        val clipping = QualityRegressionMetricsV2.compareArgb(baseline, clipped, 4, 4)
        val noise = QualityRegressionMetricsV2.compareArgb(baseline, chromaNoise, 4, 4)

        assertTrue(clipping.highlightClippingIncrease > 0f)
        assertTrue(clipping.shadowClippingIncrease > 0f)
        assertTrue(noise.flatRegionVariationIncrease > 0f)
        assertTrue(noise.colorNeutralDrift > 0f)
        assertTrue(noise.chromaMeanAbsoluteError > 0f)
    }

    @Test
    fun overshootAndComponentLossAreReported() {
        val baseline =
            IntArray(25) { index ->
                if (index % 5 < 2) 0xff303030.toInt() else 0xffc0c0c0.toInt()
            }
        val halo = baseline.copyOf().also {
            for (y in 0 until 5) {
                it[y * 5 + 1] = 0xff101010.toInt()
                it[y * 5 + 2] = 0xfff0f0f0.toInt()
            }
        }
        assertTrue(QualityRegressionMetricsV2.compareArgb(baseline, halo, 5, 5).localEdgeOvershoot > 0f)

        val expected = BooleanArray(35)
        for (y in 1..3) {
            expected[y * 7 + 1] = true
            expected[y * 7 + 5] = true
        }
        val lost = expected.copyOf()
        for (y in 1..3) lost[y * 7 + 5] = false
        val masks = QualityRegressionMetricsV2.compareMasks(expected, lost, 7, 5)
        assertEquals(2, masks.expectedComponentCount)
        assertEquals(1, masks.actualComponentCount)
        assertTrue(masks.intersectionOverUnion < 1f)
        assertTrue(masks.affectedAreaDrift < 0f)
    }
}
