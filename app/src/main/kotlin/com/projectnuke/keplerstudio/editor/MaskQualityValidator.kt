package com.projectnuke.keplerstudio.editor

import kotlin.math.ln

data class MaskQualityThresholds(
    val activationThreshold: Float = 0.5f,
    val minimumAffectedAreaRatio: Float = 0.0005f,
    val maximumAffectedAreaRatio: Float? = null,
    val minimumConfidence: Float = 0f,
    val maximumIsolatedNoiseRatio: Float = 1f,
)

data class MaskQualityMetrics(
    val affectedAreaRatio: Float,
    val boundingBoxRatio: Float,
    val borderContactRatio: Float,
    val connectedComponentCount: Int,
    val largestComponentRatio: Float,
    val isolatedNoiseRatio: Float,
    val meanConfidence: Float,
    val entropy: Float,
    val edgeComplexity: Float,
    val isEmpty: Boolean,
    val isFull: Boolean,
)

sealed interface MaskQualityResult {
    data class Valid(val metrics: MaskQualityMetrics) : MaskQualityResult
    data class LowConfidence(val metrics: MaskQualityMetrics) : MaskQualityResult
    data class Invalid(val reason: String) : MaskQualityResult
}

object MaskQualityValidator {
    fun evaluate(
        values: FloatArray,
        width: Int,
        height: Int,
        thresholds: MaskQualityThresholds = MaskQualityThresholds(),
    ): MaskQualityResult {
        if (width <= 0 || height <= 0) return MaskQualityResult.Invalid("non-positive dimensions")
        val expected = width.toLong() * height.toLong()
        if (expected > Int.MAX_VALUE || values.size != expected.toInt()) {
            return MaskQualityResult.Invalid("mask dimensions do not match the tensor")
        }
        if (values.any { !it.isFinite() }) return MaskQualityResult.Invalid("mask contains NaN or infinity")
        if (values.any { it < 0f || it > 1f }) return MaskQualityResult.Invalid("mask values are outside 0..1")

        val active = BooleanArray(values.size)
        var activeCount = 0
        var borderCount = 0
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var confidenceSum = 0.0
        var entropySum = 0.0
        var edgeTransitions = 0
        val threshold = thresholds.activationThreshold.coerceIn(0f, 1f)
        values.forEachIndexed { index, value ->
            confidenceSum += value
            if (value > 0f && value < 1f) {
                entropySum += -value * ln(value.toDouble()) - (1f - value) * ln((1f - value).toDouble())
            }
            if (value >= threshold) {
                active[index] = true
                activeCount++
                val x = index % width
                val y = index / width
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) borderCount++
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (x + 1 < width && active[index] != active[index + 1]) edgeTransitions++
                if (y + 1 < height && active[index] != active[index + width]) edgeTransitions++
            }
        }

        val componentSizes = componentSizes(active, width, height)
        val largest = componentSizes.maxOrNull() ?: 0
        val isolated = componentSizes.filter { it <= 2 }.sum()
        val pixelCount = values.size.coerceAtLeast(1)
        val metrics =
            MaskQualityMetrics(
                affectedAreaRatio = activeCount.toFloat() / pixelCount,
                boundingBoxRatio =
                    if (activeCount == 0) 0f
                    else ((maxX - minX + 1).toLong() * (maxY - minY + 1).toLong()).toFloat() / pixelCount,
                borderContactRatio =
                    if (activeCount == 0) 0f else borderCount.toFloat() / activeCount,
                connectedComponentCount = componentSizes.size,
                largestComponentRatio =
                    if (activeCount == 0) 0f else largest.toFloat() / activeCount,
                isolatedNoiseRatio =
                    if (activeCount == 0) 0f else isolated.toFloat() / activeCount,
                meanConfidence = (confidenceSum / pixelCount).toFloat(),
                entropy = (entropySum / pixelCount).toFloat(),
                edgeComplexity = edgeTransitions.toFloat() / pixelCount,
                isEmpty = activeCount == 0,
                isFull = activeCount == values.size,
            )

        val maximumArea = thresholds.maximumAffectedAreaRatio
        return when {
            metrics.isEmpty -> MaskQualityResult.Invalid("mask is empty")
            metrics.affectedAreaRatio < thresholds.minimumAffectedAreaRatio ->
                MaskQualityResult.Invalid("affected area is too small")
            maximumArea != null && metrics.affectedAreaRatio > maximumArea ->
                MaskQualityResult.LowConfidence(metrics)
            metrics.meanConfidence < thresholds.minimumConfidence ->
                MaskQualityResult.LowConfidence(metrics)
            metrics.isolatedNoiseRatio > thresholds.maximumIsolatedNoiseRatio ->
                MaskQualityResult.LowConfidence(metrics)
            else -> MaskQualityResult.Valid(metrics)
        }
    }

    private fun componentSizes(active: BooleanArray, width: Int, height: Int): List<Int> {
        val visited = BooleanArray(active.size)
        val queue = IntArray(active.size)
        val sizes = mutableListOf<Int>()
        active.indices.forEach { start ->
            if (!active[start] || visited[start]) return@forEach
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var size = 0
            while (head < tail) {
                val index = queue[head++]
                size++
                val x = index % width
                val y = index / width
                fun enqueue(candidate: Int) {
                    if (active[candidate] && !visited[candidate]) {
                        visited[candidate] = true
                        queue[tail++] = candidate
                    }
                }
                if (x > 0) enqueue(index - 1)
                if (x + 1 < width) enqueue(index + 1)
                if (y > 0) enqueue(index - width)
                if (y + 1 < height) enqueue(index + width)
            }
            sizes += size
        }
        return sizes
    }
}
