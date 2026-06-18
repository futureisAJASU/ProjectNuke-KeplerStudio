package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

data class AutoRouterScores(
    val normal: Float,
    val lowLight: Float,
    val backlight: Float,
    val overexposed: Float,
    val underexposed: Float,
    val portrait: Float,
    val landscape: Float,
    val sky: Float,
    val food: Float,
    val document: Float,
    val flare: Float,
    val sunFlare: Float,
    val veilingGlare: Float,
    val ghostBlob: Float,
    val reflection: Float,
    val jpegArtifact: Float,
    val blur: Float,
    val noise: Float,
    val highDynamicRange: Float
) {
    fun topLabels(threshold: Float = 0.45f): List<String> = buildList {
        if (lowLight >= threshold) add("low_light")
        if (backlight >= threshold) add("backlight")
        if (overexposed >= threshold) add("overexposed")
        if (underexposed >= threshold) add("underexposed")
        if (portrait >= threshold) add("portrait")
        if (landscape >= threshold) add("landscape")
        if (sky >= threshold) add("sky")
        if (food >= threshold) add("food")
        if (document >= threshold) add("document")
        if (flare >= threshold) add("flare")
        if (sunFlare >= threshold) add("sun_flare")
        if (veilingGlare >= threshold) add("veiling_glare")
        if (ghostBlob >= threshold) add("ghost_blob")
        if (reflection >= threshold) add("reflection")
        if (jpegArtifact >= threshold) add("jpeg_artifact")
        if (blur >= threshold) add("blur")
        if (noise >= threshold) add("noise")
        if (highDynamicRange >= threshold) add("high_dynamic_range")
        if (isEmpty() && normal >= threshold) add("normal")
    }
}

fun analyzeAutoRouterV0(bitmap: Bitmap): AutoRouterScores {
    val stats = analyzeRouterStats(bitmap)
    val lowLight = smoothScore(0.42f, 0.18f, 1f - stats.mean)
    val underexposed = smoothScore(0.48f, 0.22f, 1f - stats.p50)
    val overexposed = smoothScore(0.08f, 0.28f, stats.clipHighRatio)
    val highDynamicRange = smoothScore(0.52f, 0.82f, stats.p95 - stats.p05)
    val flare = (smoothScore(0.01f, 0.08f, stats.clipHighRatio) * smoothScore(0.72f, 0.92f, stats.p99)).coerceIn(0f, 1f)
    val sunFlare = (smoothScore(0.008f, 0.055f, stats.clipHighRatio) * smoothScore(0.48f, 0.76f, stats.mean) * smoothScore(0.05f, 0.16f, stats.warmDominance + stats.blueDominance)).coerceIn(0f, 1f)
    val veilingGlare = (smoothScore(0.50f, 0.74f, stats.mean) * smoothScore(0.18f, 0.46f, stats.p05) * smoothScore(0.38f, 0.72f, stats.p95 - stats.p05)).coerceIn(0f, 1f)
    val ghostBlob = (smoothScore(0.012f, 0.065f, stats.clipHighRatio) * smoothScore(0.08f, 0.22f, stats.chromaMean)).coerceIn(0f, 1f)
    val noise = if (stats.mean < 0.34f) 0.58f else 0.18f
    val document = if (stats.chromaMean < 0.05f && stats.p95 - stats.p05 > 0.55f) 0.55f else 0.08f
    val landscape = if (stats.chromaMean > 0.12f && stats.mean in 0.35f..0.72f) 0.38f else 0.12f
    val sky = if (stats.blueDominance > 0.06f && stats.mean > 0.42f) 0.45f else 0.10f
    val food = if (stats.warmDominance > 0.08f && stats.chromaMean > 0.12f) 0.32f else 0.08f
    val biggestIssue = max(max(lowLight, overexposed), max(max(underexposed, flare), max(sunFlare, veilingGlare)))
    val normal = (1f - biggestIssue).coerceIn(0f, 1f)

    return AutoRouterScores(
        normal = normal,
        lowLight = lowLight,
        backlight = if (highDynamicRange > 0.55f && stats.p05 < 0.12f) 0.52f else 0.12f,
        overexposed = overexposed,
        underexposed = underexposed,
        portrait = 0.0f,
        landscape = landscape,
        sky = sky,
        food = food,
        document = document,
        flare = flare,
        sunFlare = sunFlare,
        veilingGlare = veilingGlare,
        ghostBlob = ghostBlob,
        reflection = 0.0f,
        jpegArtifact = 0.0f,
        blur = 0.0f,
        noise = noise,
        highDynamicRange = highDynamicRange
    )
}

private data class RouterStats(
    val mean: Float,
    val p05: Float,
    val p50: Float,
    val p95: Float,
    val p99: Float,
    val chromaMean: Float,
    val clipHighRatio: Float,
    val blueDominance: Float,
    val warmDominance: Float
)

private fun analyzeRouterStats(bitmap: Bitmap): RouterStats {
    val histogram = IntArray(256)
    val step = max(1, max(bitmap.width, bitmap.height) / 384)
    val row = IntArray(bitmap.width)
    var count = 0
    var sum = 0f
    var chromaSum = 0f
    var clipHigh = 0
    var blueDominance = 0f
    var warmDominance = 0f

    var y = 0
    while (y < bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        var x = 0
        while (x < bitmap.width) {
            val pixel = row[x]
            val r = ((pixel ushr 16) and 0xff) / 255f
            val g = ((pixel ushr 8) and 0xff) / 255f
            val b = (pixel and 0xff) / 255f
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
            val maxC = max(r, max(g, b))
            val minC = kotlin.math.min(r, kotlin.math.min(g, b))
            histogram[(luma * 255f).roundToInt().coerceIn(0, 255)] += 1
            sum += luma
            chromaSum += (maxC - minC).coerceIn(0f, 1f)
            if (luma > 0.94f) clipHigh += 1
            blueDominance += (b - max(r, g)).coerceAtLeast(0f)
            warmDominance += (r - b).coerceAtLeast(0f)
            count += 1
            x += step
        }
        y += step
    }

    if (count <= 0) return RouterStats(0.5f, 0.05f, 0.5f, 0.95f, 0.99f, 0.1f, 0f, 0f, 0f)
    return RouterStats(
        mean = sum / count,
        p05 = routerPercentile(histogram, count, 0.05f),
        p50 = routerPercentile(histogram, count, 0.50f),
        p95 = routerPercentile(histogram, count, 0.95f),
        p99 = routerPercentile(histogram, count, 0.99f),
        chromaMean = chromaSum / count,
        clipHighRatio = clipHigh / count.toFloat(),
        blueDominance = blueDominance / count,
        warmDominance = warmDominance / count
    )
}

private fun routerPercentile(histogram: IntArray, count: Int, percentile: Float): Float {
    val target = (count * percentile).roundToInt().coerceIn(1, count)
    var sum = 0
    for (i in histogram.indices) {
        sum += histogram[i]
        if (sum >= target) return i / 255f
    }
    return 1f
}

private fun smoothScore(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
