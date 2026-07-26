package com.projectnuke.keplerstudio.editor

import kotlin.test.Test
import kotlin.test.assertEquals

class QualityRegressionMetricsTest {
    @Test
    fun identityHasZeroPixelRegression() {
        val fixture =
            intArrayOf(
                0xff000000.toInt(),
                0xffffffff.toInt(),
                0xff123456.toInt(),
                0x0080ff20,
            )

        assertEquals(
            ExactPixelDeltaMetrics(0f, 0, 0f, 0f, 0f),
            QualityRegressionMetrics.compareArgb(fixture, fixture.copyOf()),
        )
    }

    @Test
    fun changedPixelAndChannelBoundsAreExact() {
        val baseline = intArrayOf(0xff000000.toInt(), 0xff102030.toInt())
        val candidate = intArrayOf(0xff010203.toInt(), 0xff102030.toInt())

        val metrics = QualityRegressionMetrics.compareArgb(baseline, candidate)

        assertEquals(0.5f, metrics.changedPixelRatio)
        assertEquals(3, metrics.maximumChannelDelta)
        assertEquals(1f, metrics.meanAbsoluteChannelError)
    }

    @Test
    fun syntheticMaskIoUIsDeterministic() {
        val expected = booleanArrayOf(true, true, false, false)
        val actual = booleanArrayOf(true, false, true, false)

        assertEquals(1f / 3f, QualityRegressionMetrics.maskIoU(expected, actual))
    }
}
