package com.projectnuke.keplerstudio.editor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class ImageQualityMetricsV2(
    val changedPixelRatio: Float,
    val maximumChannelDelta: Int,
    val p95ChannelDelta: Int,
    val lumaMeanAbsoluteError: Float,
    val chromaMeanAbsoluteError: Float,
    val highlightClippingIncrease: Float,
    val shadowClippingIncrease: Float,
    val localEdgeOvershoot: Float,
    val flatRegionVariationIncrease: Float,
    val colorNeutralDrift: Float,
)

data class MaskBoundaryMetrics(
    val intersectionOverUnion: Float,
    val boundaryPrecision: Float,
    val boundaryRecall: Float,
    val boundaryFScore: Float,
    val toleranceBoundaryFScore: Float,
    val expectedComponentCount: Int,
    val actualComponentCount: Int,
    val affectedAreaDrift: Float,
)

data class DebugComparisonArtifact(
    val fixtureVersion: String,
    val width: Int,
    val height: Int,
    val resolutionLevel: DebugComparisonResolution = DebugComparisonResolution.BoundedPreview,
    val evaluatedWidth: Int = width,
    val evaluatedHeight: Int = height,
    val baselineArgb: IntArray,
    val experimentalArgb: IntArray,
    val maskArgb: IntArray?,
    val differenceHeatmapArgb: IntArray,
    val metrics: ImageQualityMetricsV2,
    val algorithmDecision: String? = null,
    val knownTransientBytes: Long? = null,
    val durationMillis: Long? = null,
) {
    val retainedBytes: Long
        get() =
            (baselineArgb.size.toLong() +
                experimentalArgb.size +
                differenceHeatmapArgb.size +
                (maskArgb?.size ?: 0)) * 4L

    fun compactMetricJson(): String =
        buildString {
            append('{')
            append("\"fixtureVersion\":\"").append(fixtureVersion).append("\",")
            append("\"changedPixelRatio\":").append(metrics.changedPixelRatio).append(',')
            append("\"maximumChannelDelta\":").append(metrics.maximumChannelDelta).append(',')
            append("\"p95ChannelDelta\":").append(metrics.p95ChannelDelta).append(',')
            append("\"lumaMae\":").append(metrics.lumaMeanAbsoluteError).append(',')
            append("\"chromaMae\":").append(metrics.chromaMeanAbsoluteError)
            append('}')
        }
}

enum class DebugComparisonResolution(val label: String) {
    BoundedPreview("미리보기 해상도"),
    EditorWorking("편집 해상도"),
}

object QualityRegressionMetricsV2 {
    fun compareArgb(
        baseline: IntArray,
        candidate: IntArray,
        width: Int,
        height: Int,
    ): ImageQualityMetricsV2 {
        requirePixelDimensions(width, height, baseline.size)
        require(candidate.size == baseline.size)
        if (baseline.isEmpty()) {
            return ImageQualityMetricsV2(0f, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
        val deltaHistogram = IntArray(256)
        var changed = 0
        var maximumDelta = 0
        var lumaError = 0.0
        var chromaError = 0.0
        var baselineHighlights = 0
        var candidateHighlights = 0
        var baselineShadows = 0
        var candidateShadows = 0
        var neutralDrift = 0.0
        var neutralCount = 0
        var flatBaselineVariation = 0.0
        var flatCandidateVariation = 0.0
        var flatEdges = 0
        baseline.indices.forEach { index ->
            val before = baseline[index]
            val after = candidate[index]
            if (before != after) changed++
            for (channel in 0..2) {
                val delta = abs(channel(after, channel) - channel(before, channel))
                deltaHistogram[delta]++
                maximumDelta = max(maximumDelta, delta)
            }
            val beforeLuma = luma(before)
            val afterLuma = luma(after)
            lumaError += abs(afterLuma - beforeLuma)
            val beforeChroma = chroma(before)
            val afterChroma = chroma(after)
            chromaError += chromaDelta(before, after)
            if (beforeLuma >= 250f) baselineHighlights++
            if (afterLuma >= 250f) candidateHighlights++
            if (beforeLuma <= 5f) baselineShadows++
            if (afterLuma <= 5f) candidateShadows++
            if (beforeChroma <= 8) {
                neutralDrift += afterChroma
                neutralCount++
            }
            if (index % width != 0) {
                val previousBefore = luma(baseline[index - 1])
                val previousAfter = luma(candidate[index - 1])
                val baselineVariation = abs(beforeLuma - previousBefore)
                if (baselineVariation <= 3f) {
                    flatBaselineVariation += baselineVariation
                    flatCandidateVariation += abs(afterLuma - previousAfter)
                    flatEdges++
                }
            }
        }
        val edgeOvershoot = localEdgeOvershoot(baseline, candidate, width, height)
        val pixels = baseline.size.toFloat()
        return ImageQualityMetricsV2(
            changedPixelRatio = changed / pixels,
            maximumChannelDelta = maximumDelta,
            p95ChannelDelta = percentile(deltaHistogram, baseline.size * 3, 0.95f),
            lumaMeanAbsoluteError = (lumaError / pixels).toFloat(),
            chromaMeanAbsoluteError = (chromaError / pixels).toFloat(),
            highlightClippingIncrease = (candidateHighlights - baselineHighlights) / pixels,
            shadowClippingIncrease = (candidateShadows - baselineShadows) / pixels,
            localEdgeOvershoot = edgeOvershoot,
            flatRegionVariationIncrease =
                if (flatEdges == 0) 0f
                else ((flatCandidateVariation - flatBaselineVariation) / flatEdges).toFloat(),
            colorNeutralDrift =
                if (neutralCount == 0) 0f else (neutralDrift / neutralCount).toFloat(),
        )
    }

    fun compareMasks(
        expected: BooleanArray,
        actual: BooleanArray,
        width: Int,
        height: Int,
        tolerancePixels: Int = 1,
    ): MaskBoundaryMetrics {
        requirePixelDimensions(width, height, expected.size)
        require(actual.size == expected.size)
        var intersection = 0
        var union = 0
        var expectedArea = 0
        var actualArea = 0
        expected.indices.forEach { index ->
            if (expected[index]) expectedArea++
            if (actual[index]) actualArea++
            if (expected[index] || actual[index]) union++
            if (expected[index] && actual[index]) intersection++
        }
        val expectedBoundary = boundary(expected, width, height)
        val actualBoundary = boundary(actual, width, height)
        require(tolerancePixels >= 0)
        var boundaryIntersection = 0
        var expectedBoundaryCount = 0
        var actualBoundaryCount = 0
        expectedBoundary.indices.forEach { index ->
            if (expectedBoundary[index]) expectedBoundaryCount++
            if (actualBoundary[index]) actualBoundaryCount++
            if (expectedBoundary[index] && actualBoundary[index]) boundaryIntersection++
        }
        val precision =
            if (actualBoundaryCount == 0) {
                if (expectedBoundaryCount == 0) 1f else 0f
            } else {
                boundaryIntersection.toFloat() / actualBoundaryCount
            }
        val recall =
            if (expectedBoundaryCount == 0) {
                if (actualBoundaryCount == 0) 1f else 0f
            } else {
                boundaryIntersection.toFloat() / expectedBoundaryCount
            }
        val tolerantPrecision = boundaryMatchRatio(actualBoundary, expectedBoundary, width, height, tolerancePixels)
        val tolerantRecall = boundaryMatchRatio(expectedBoundary, actualBoundary, width, height, tolerancePixels)
        return MaskBoundaryMetrics(
            intersectionOverUnion = if (union == 0) 1f else intersection.toFloat() / union,
            boundaryPrecision = precision,
            boundaryRecall = recall,
            boundaryFScore =
                if (precision + recall == 0f) 0f
                else 2f * precision * recall / (precision + recall),
            toleranceBoundaryFScore =
                if (tolerantPrecision + tolerantRecall == 0f) 0f
                else 2f * tolerantPrecision * tolerantRecall / (tolerantPrecision + tolerantRecall),
            expectedComponentCount = componentCount(expected, width, height),
            actualComponentCount = componentCount(actual, width, height),
            affectedAreaDrift = (actualArea - expectedArea).toFloat() / expected.size.coerceAtLeast(1),
        )
    }

    fun debugArtifact(
        fixtureVersion: String,
        baseline: IntArray,
        experimental: IntArray,
        width: Int,
        height: Int,
        maskArgb: IntArray? = null,
    ): DebugComparisonArtifact {
        val metrics = compareArgb(baseline, experimental, width, height)
        maskArgb?.let { require(it.size == baseline.size) }
        val heatmap =
            IntArray(baseline.size) { index ->
                val before = baseline[index]
                val after = experimental[index]
                val delta =
                    max(
                        abs(channel(after, 0) - channel(before, 0)),
                        max(
                            abs(channel(after, 1) - channel(before, 1)),
                            abs(channel(after, 2) - channel(before, 2)),
                        ),
                    )
                -0x1000000 or (delta shl 16)
            }
        return DebugComparisonArtifact(
            fixtureVersion = fixtureVersion,
            width = width,
            height = height,
            baselineArgb = baseline.copyOf(),
            experimentalArgb = experimental.copyOf(),
            maskArgb = maskArgb?.copyOf(),
            differenceHeatmapArgb = heatmap,
            metrics = metrics,
        )
    }

    private fun localEdgeOvershoot(
        baseline: IntArray,
        candidate: IntArray,
        width: Int,
        height: Int,
    ): Float {
        if (width < 3 || height < 3) return 0f
        var maximum = 0f
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var low = 255f
                var high = 0f
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val value = luma(baseline[(y + dy) * width + x + dx])
                        low = min(low, value)
                        high = max(high, value)
                    }
                }
                val candidateLuma = luma(candidate[y * width + x])
                maximum = max(maximum, max(low - candidateLuma, candidateLuma - high))
            }
        }
        return maximum.coerceAtLeast(0f)
    }

    private fun boundary(mask: BooleanArray, width: Int, height: Int): BooleanArray =
        BooleanArray(mask.size) { index ->
            if (!mask[index]) {
                false
            } else {
                val x = index % width
                val y = index / width
                x == 0 ||
                    y == 0 ||
                    x == width - 1 ||
                    y == height - 1 ||
                    !mask[index - 1] ||
                    !mask[index + 1] ||
                    !mask[index - width] ||
                    !mask[index + width]
            }
        }

    private fun componentCount(mask: BooleanArray, width: Int, height: Int): Int {
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        var components = 0
        mask.indices.forEach { start ->
            if (!mask[start] || visited[start]) return@forEach
            components++
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                fun enqueue(candidate: Int) {
                    if (mask[candidate] && !visited[candidate]) {
                        visited[candidate] = true
                        queue[tail++] = candidate
                    }
                }
                if (x > 0) enqueue(index - 1)
                if (x + 1 < width) enqueue(index + 1)
                if (y > 0) enqueue(index - width)
                if (y + 1 < height) enqueue(index + width)
            }
        }
        return components
    }

    private fun percentile(histogram: IntArray, count: Int, percentile: Float): Int {
        val target = (count * percentile).toInt().coerceIn(1, count)
        var cumulative = 0
        histogram.indices.forEach { index ->
            cumulative += histogram[index]
            if (cumulative >= target) return index
        }
        return histogram.lastIndex
    }

    private fun requirePixelDimensions(width: Int, height: Int, size: Int) {
        require(width > 0 && height > 0)
        val expected =
            runCatching { Math.multiplyExact(width.toLong(), height.toLong()) }.getOrNull()
        require(expected != null && expected <= Int.MAX_VALUE && expected.toInt() == size)
    }

    private fun channel(argb: Int, channel: Int): Int =
        when (channel) {
            0 -> (argb ushr 16) and 0xff
            1 -> (argb ushr 8) and 0xff
            else -> argb and 0xff
        }

    private fun chroma(argb: Int): Float {
        val red = channel(argb, 0)
        val green = channel(argb, 1)
        val blue = channel(argb, 2)
        val cb = (blue - luma(argb)) * 0.564f
        val cr = (red - luma(argb)) * 0.713f
        return sqrt(cb * cb + cr * cr)
    }

    private fun chromaDelta(first: Int, second: Int): Float {
        val firstLuma = luma(first)
        val secondLuma = luma(second)
        val cbDelta = ((channel(second, 2) - secondLuma) - (channel(first, 2) - firstLuma)) * 0.564f
        val crDelta = ((channel(second, 0) - secondLuma) - (channel(first, 0) - firstLuma)) * 0.713f
        return sqrt(cbDelta * cbDelta + crDelta * crDelta)
    }

    private fun boundaryMatchRatio(
        source: BooleanArray,
        target: BooleanArray,
        width: Int,
        height: Int,
        tolerance: Int,
    ): Float {
        var sourceCount = 0
        var matched = 0
        source.indices.forEach { index ->
            if (!source[index]) return@forEach
            sourceCount++
            val x = index % width
            val y = index / width
            var found = false
            for (dy in -tolerance..tolerance) {
                val candidateY = y + dy
                if (candidateY !in 0 until height) continue
                for (dx in -tolerance..tolerance) {
                    val candidateX = x + dx
                    if (candidateX in 0 until width && target[candidateY * width + candidateX]) {
                        found = true
                        break
                    }
                }
                if (found) break
            }
            if (found) matched++
        }
        return if (sourceCount == 0) 1f else matched.toFloat() / sourceCount
    }

    private fun luma(argb: Int): Float =
        0.2126f * channel(argb, 0) +
            0.7152f * channel(argb, 1) +
            0.0722f * channel(argb, 2)
}
