package com.projectnuke.keplerstudio.editor

import kotlin.math.ln

enum class MaskConnectivity {
    Four,
    Eight,
}

data class MaskQualityThresholds(
    val activationThreshold: Float = 0.5f,
    val minimumAffectedAreaRatio: Float = 0.0005f,
    val maximumAffectedAreaRatio: Float? = null,
    val minimumConfidence: Float = 0f,
    val maximumIsolatedNoiseRatio: Float = 1f,
    val connectivity: MaskConnectivity = MaskConnectivity.Four,
)

data class MaskComponentMetrics(
    val area: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val borderContactPixels: Int,
    val confidenceSum: Double,
)

data class MaskComponentAnalysis(
    val componentCount: Int,
    val largestArea: Int,
    val isolatedArea: Int,
    val topComponents: List<MaskComponentMetrics>,
)

data class MaskQualityMetrics(
    val affectedAreaRatio: Float,
    val boundingBoxRatio: Float,
    val borderContactRatio: Float,
    val connectedComponentCount: Int,
    val largestComponentRatio: Float,
    val isolatedNoiseRatio: Float,
    val meanConfidence: Float,
    val activeRegionMeanConfidence: Float = meanConfidence,
    val activeRegionP90Confidence: Float = meanConfidence,
    val peakConfidence: Float = meanConfidence,
    val backgroundLeakage: Float = 0f,
    val uncertaintyBandRatio: Float = 0f,
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
    private const val MAX_RETAINED_COMPONENTS = 32

    fun evaluate(
        values: FloatArray,
        width: Int,
        height: Int,
        thresholds: MaskQualityThresholds = MaskQualityThresholds(),
        isCancelled: () -> Boolean = { false },
    ): MaskQualityResult {
        validateThresholds(thresholds)?.let { return MaskQualityResult.Invalid(it) }
        val pixelCount = checkedPixelCountOrNull(width, height)
            ?: return MaskQualityResult.Invalid("invalid or excessive dimensions")
        if (values.size != pixelCount) {
            return MaskQualityResult.Invalid("mask dimensions do not match the tensor")
        }

        val activationThreshold = thresholds.activationThreshold
        val confidenceHistogram = IntArray(256)
        var activeCount = 0
        var borderCount = 0
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var confidenceSum = 0.0
        var activeConfidenceSum = 0.0
        var backgroundConfidenceSum = 0.0
        var backgroundCount = 0
        var peakConfidence = 0f
        var uncertaintyCount = 0
        var entropySum = 0.0
        var edgeTransitions = 0

        values.forEachIndexed { index, value ->
            if (!value.isFinite()) return MaskQualityResult.Invalid("mask contains NaN or infinity")
            if (value !in 0f..1f) {
                return MaskQualityResult.Invalid("mask values are outside 0..1")
            }
            confidenceSum += value
            peakConfidence = maxOf(peakConfidence, value)
            if (value > 0f && value < 1f) {
                entropySum +=
                    -value * ln(value.toDouble()) -
                        (1f - value) * ln((1f - value).toDouble())
            }
            if (value in 0.25f..0.75f) uncertaintyCount++
            if (value >= activationThreshold) {
                activeCount++
                activeConfidenceSum += value
                confidenceHistogram[(value * 255f).toInt().coerceIn(0, 255)]++
                val x = index % width
                val y = index / width
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) borderCount++
            } else {
                backgroundConfidenceSum += value
                backgroundCount++
            }
        }
        if (isCancelled()) return MaskQualityResult.Invalid("mask evaluation cancelled")

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val active = values[index] >= activationThreshold
                if (x + 1 < width && active != (values[index + 1] >= activationThreshold)) {
                    edgeTransitions++
                }
                if (y + 1 < height && active != (values[index + width] >= activationThreshold)) {
                    edgeTransitions++
                }
            }
            if ((y and 63) == 0 && isCancelled()) {
                return MaskQualityResult.Invalid("mask evaluation cancelled")
            }
        }

        val components =
            analyzeComponents(
                values = values,
                width = width,
                height = height,
                activationThreshold = activationThreshold,
                connectivity = thresholds.connectivity,
                maximumRetainedComponents = MAX_RETAINED_COMPONENTS,
                isCancelled = isCancelled,
            ) ?: return MaskQualityResult.Invalid("mask evaluation cancelled")
        val count = pixelCount.coerceAtLeast(1)
        val metrics =
            MaskQualityMetrics(
                affectedAreaRatio = activeCount.toFloat() / count,
                boundingBoxRatio =
                    if (activeCount == 0) {
                        0f
                    } else {
                        val area =
                            Math.multiplyExact(
                                (maxX - minX + 1).toLong(),
                                (maxY - minY + 1).toLong(),
                            )
                        area.toFloat() / count
                    },
                borderContactRatio =
                    if (activeCount == 0) 0f else borderCount.toFloat() / activeCount,
                connectedComponentCount = components.componentCount,
                largestComponentRatio =
                    if (activeCount == 0) 0f else components.largestArea.toFloat() / activeCount,
                isolatedNoiseRatio =
                    if (activeCount == 0) 0f else components.isolatedArea.toFloat() / activeCount,
                meanConfidence = (confidenceSum / count).toFloat(),
                activeRegionMeanConfidence =
                    if (activeCount == 0) 0f else (activeConfidenceSum / activeCount).toFloat(),
                activeRegionP90Confidence =
                    percentileFromHistogram(confidenceHistogram, activeCount, 0.9f),
                peakConfidence = peakConfidence,
                backgroundLeakage =
                    if (backgroundCount == 0) 0f
                    else (backgroundConfidenceSum / backgroundCount).toFloat(),
                uncertaintyBandRatio = uncertaintyCount.toFloat() / count,
                entropy = (entropySum / count).toFloat(),
                edgeComplexity = edgeTransitions.toFloat() / count,
                isEmpty = activeCount == 0,
                isFull = activeCount == pixelCount,
            )

        val maximumArea = thresholds.maximumAffectedAreaRatio
        return when {
            metrics.isEmpty -> MaskQualityResult.Invalid("mask is empty")
            metrics.affectedAreaRatio < thresholds.minimumAffectedAreaRatio ->
                MaskQualityResult.Invalid("affected area is too small")
            maximumArea != null && metrics.affectedAreaRatio > maximumArea ->
                MaskQualityResult.LowConfidence(metrics)
            metrics.activeRegionMeanConfidence < thresholds.minimumConfidence ->
                MaskQualityResult.LowConfidence(metrics)
            metrics.isolatedNoiseRatio > thresholds.maximumIsolatedNoiseRatio ->
                MaskQualityResult.LowConfidence(metrics)
            else -> MaskQualityResult.Valid(metrics)
        }
    }

    internal fun analyzeComponents(
        values: FloatArray,
        width: Int,
        height: Int,
        activationThreshold: Float,
        connectivity: MaskConnectivity,
        maximumRetainedComponents: Int,
        isCancelled: () -> Boolean = { false },
    ): MaskComponentAnalysis? {
        val state = ByteArray(values.size) { index ->
            if (values[index] >= activationThreshold) 1 else 0
        }
        val queue = IntArray(values.size)
        val retained = ArrayList<MaskComponentMetrics>(maximumRetainedComponents.coerceAtLeast(0))
        var componentCount = 0
        var largestArea = 0
        var isolatedArea = 0
        for (start in state.indices) {
            if (state[start].toInt() != 1) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            state[start] = 2
            var area = 0
            var left = width
            var top = height
            var right = -1
            var bottom = -1
            var border = 0
            var confidenceSum = 0.0
            while (head < tail) {
                val index = queue[head++]
                area++
                val x = index % width
                val y = index / width
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) border++
                confidenceSum += values[index]
                enqueueNeighbor(state, queue, width, height, x - 1, y, tail)?.let { tail = it }
                enqueueNeighbor(state, queue, width, height, x + 1, y, tail)?.let { tail = it }
                enqueueNeighbor(state, queue, width, height, x, y - 1, tail)?.let { tail = it }
                enqueueNeighbor(state, queue, width, height, x, y + 1, tail)?.let { tail = it }
                if (connectivity == MaskConnectivity.Eight) {
                    enqueueNeighbor(state, queue, width, height, x - 1, y - 1, tail)
                        ?.let { tail = it }
                    enqueueNeighbor(state, queue, width, height, x + 1, y - 1, tail)
                        ?.let { tail = it }
                    enqueueNeighbor(state, queue, width, height, x - 1, y + 1, tail)
                        ?.let { tail = it }
                    enqueueNeighbor(state, queue, width, height, x + 1, y + 1, tail)
                        ?.let { tail = it }
                }
            }
            componentCount++
            largestArea = maxOf(largestArea, area)
            if (area <= 2) isolatedArea += area
            val component =
                MaskComponentMetrics(area, left, top, right, bottom, border, confidenceSum)
            if (maximumRetainedComponents > 0) {
                retained += component
                retained.sortByDescending(MaskComponentMetrics::area)
                if (retained.size > maximumRetainedComponents) retained.removeAt(retained.lastIndex)
            }
            if (isCancelled()) return null
        }
        return MaskComponentAnalysis(componentCount, largestArea, isolatedArea, retained)
    }

    private fun enqueueNeighbor(
        state: ByteArray,
        queue: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        tail: Int,
    ): Int? {
        if (x !in 0 until width || y !in 0 until height) return null
        val index = y * width + x
        if (state[index].toInt() != 1) return null
        state[index] = 2
        queue[tail] = index
        return tail + 1
    }

    private fun percentileFromHistogram(
        histogram: IntArray,
        count: Int,
        percentile: Float,
    ): Float {
        if (count == 0) return 0f
        val target = (count * percentile).toInt().coerceIn(1, count)
        var cumulative = 0
        histogram.indices.forEach { index ->
            cumulative += histogram[index]
            if (cumulative >= target) return index / 255f
        }
        return 1f
    }

    private fun validateThresholds(thresholds: MaskQualityThresholds): String? =
        when {
            !thresholds.activationThreshold.isFinite() ||
                thresholds.activationThreshold !in 0f..1f -> "invalid activation threshold"
            !thresholds.minimumAffectedAreaRatio.isFinite() ||
                thresholds.minimumAffectedAreaRatio !in 0f..1f -> "invalid minimum area ratio"
            thresholds.maximumAffectedAreaRatio?.let { !it.isFinite() || it !in 0f..1f } == true ->
                "invalid maximum area ratio"
            !thresholds.minimumConfidence.isFinite() ||
                thresholds.minimumConfidence !in 0f..1f -> "invalid minimum confidence"
            !thresholds.maximumIsolatedNoiseRatio.isFinite() ||
                thresholds.maximumIsolatedNoiseRatio !in 0f..1f -> "invalid isolated-noise ratio"
            else -> null
        }

    private fun checkedPixelCountOrNull(width: Int, height: Int): Int? {
        if (width <= 0 || height <= 0) return null
        val count = runCatching { Math.multiplyExact(width.toLong(), height.toLong()) }.getOrNull()
            ?: return null
        return count.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }
}
