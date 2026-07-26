package com.projectnuke.keplerstudio.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MaskQualityValidatorTest {
    @Test
    fun rejectsMalformedAndNonFiniteMasks() {
        assertIs<MaskQualityResult.Invalid>(
            MaskQualityValidator.evaluate(floatArrayOf(1f), width = 2, height = 1),
        )
        assertIs<MaskQualityResult.Invalid>(
            MaskQualityValidator.evaluate(floatArrayOf(Float.NaN), width = 1, height = 1),
        )
        assertIs<MaskQualityResult.Invalid>(
            MaskQualityValidator.evaluate(floatArrayOf(Float.POSITIVE_INFINITY), width = 1, height = 1),
        )
    }

    @Test
    fun largeBorderTouchingSubjectIsValidWhenOperationAllowsIt() {
        val values =
            FloatArray(7 * 5) { index ->
                val x = index % 7
                if (x <= 4) 0.9f else 0.1f
            }

        val result = assertIs<MaskQualityResult.Valid>(
            MaskQualityValidator.evaluate(values, width = 7, height = 5),
        )

        assertTrue(result.metrics.affectedAreaRatio > 0.7f)
        assertTrue(result.metrics.borderContactRatio > 0f)
        assertEquals(1, result.metrics.connectedComponentCount)
    }

    @Test
    fun multipleLegitimateSubjectsRemainSeparateComponents() {
        val values = FloatArray(9 * 5)
        for (y in 1..3) {
            for (x in 1..2) values[y * 9 + x] = 1f
            for (x in 6..7) values[y * 9 + x] = 1f
        }

        val result = assertIs<MaskQualityResult.Valid>(
            MaskQualityValidator.evaluate(values, width = 9, height = 5),
        )

        assertEquals(2, result.metrics.connectedComponentCount)
        assertEquals(0.5f, result.metrics.largestComponentRatio)
    }

    @Test
    fun isolatedNoiseCanBeClassifiedAsLowConfidence() {
        val values = FloatArray(7 * 7)
        values[8] = 1f
        values[12] = 1f
        values[36] = 1f
        values[40] = 1f

        val result =
            MaskQualityValidator.evaluate(
                values,
                width = 7,
                height = 7,
                thresholds = MaskQualityThresholds(maximumIsolatedNoiseRatio = 0.25f),
            )

        assertIs<MaskQualityResult.LowConfidence>(result)
    }

    @Test
    fun generatedAlphaEdgeReportsEntropyAndEdgeComplexity() {
        val values =
            floatArrayOf(
                0f, 0.25f, 0.75f, 1f,
                0f, 0.25f, 0.75f, 1f,
                0f, 0.25f, 0.75f, 1f,
            )

        val result = assertIs<MaskQualityResult.Valid>(
            MaskQualityValidator.evaluate(values, width = 4, height = 3),
        )

        assertTrue(result.metrics.entropy > 0f)
        assertTrue(result.metrics.edgeComplexity > 0f)
    }
}
