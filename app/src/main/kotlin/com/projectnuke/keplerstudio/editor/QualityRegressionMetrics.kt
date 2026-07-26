package com.projectnuke.keplerstudio.editor

import kotlin.math.abs
import kotlin.math.max

data class ExactPixelDeltaMetrics(
    val changedPixelRatio: Float,
    val maximumChannelDelta: Int,
    val meanAbsoluteChannelError: Float,
    val highlightClippingIncrease: Float,
    val meanChromaShift: Float,
)

object QualityRegressionMetrics {
    fun compareArgb(baseline: IntArray, candidate: IntArray): ExactPixelDeltaMetrics {
        require(baseline.size == candidate.size)
        if (baseline.isEmpty()) return ExactPixelDeltaMetrics(0f, 0, 0f, 0f, 0f)
        var changed = 0
        var maxDelta = 0
        var absoluteDelta = 0L
        var baselineClipped = 0
        var candidateClipped = 0
        var chromaShift = 0.0
        baseline.indices.forEach { index ->
            val before = baseline[index]
            val after = candidate[index]
            if (before != after) changed++
            val beforeR = (before ushr 16) and 0xff
            val beforeG = (before ushr 8) and 0xff
            val beforeB = before and 0xff
            val afterR = (after ushr 16) and 0xff
            val afterG = (after ushr 8) and 0xff
            val afterB = after and 0xff
            val deltas =
                intArrayOf(
                    abs(afterR - beforeR),
                    abs(afterG - beforeG),
                    abs(afterB - beforeB),
                )
            maxDelta = max(maxDelta, deltas.max())
            absoluteDelta += deltas.sum()
            if (max(beforeR, max(beforeG, beforeB)) == 255) baselineClipped++
            if (max(afterR, max(afterG, afterB)) == 255) candidateClipped++
            val beforeChroma = max(beforeR, max(beforeG, beforeB)) - minOf(beforeR, beforeG, beforeB)
            val afterChroma = max(afterR, max(afterG, afterB)) - minOf(afterR, afterG, afterB)
            chromaShift += abs(afterChroma - beforeChroma)
        }
        val pixelCount = baseline.size.toFloat()
        return ExactPixelDeltaMetrics(
            changedPixelRatio = changed / pixelCount,
            maximumChannelDelta = maxDelta,
            meanAbsoluteChannelError = absoluteDelta / (pixelCount * 3f),
            highlightClippingIncrease = (candidateClipped - baselineClipped) / pixelCount,
            meanChromaShift = (chromaShift / pixelCount).toFloat(),
        )
    }

    fun maskIoU(expected: BooleanArray, actual: BooleanArray): Float {
        require(expected.size == actual.size)
        var intersection = 0
        var union = 0
        expected.indices.forEach { index ->
            if (expected[index] || actual[index]) union++
            if (expected[index] && actual[index]) intersection++
        }
        return if (union == 0) 1f else intersection.toFloat() / union
    }
}
