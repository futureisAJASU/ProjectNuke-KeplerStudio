package com.projectnuke.keplerstudio.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.projectnuke.keplerstudio.editor.EditParams
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private data class CurveStats(
    val p05: Float,
    val p25: Float,
    val p50: Float,
    val p75: Float,
    val p95: Float,
    val r50: Float,
    val g50: Float,
    val b50: Float,
    val meanLuma: Float,
    val chromaMean: Float,
    val edgeMean: Float,
    val noiseMean: Float,
    val warmShadow: Float,
    val warmMid: Float,
    val warmHighlight: Float,
    val tintShadow: Float,
    val tintMid: Float,
    val tintHighlight: Float
)

fun estimateCurveMatchedPresetFromPair(
    context: Context,
    beforeUri: Uri,
    afterUri: Uri
): EditParams {
    val before = decodeCurveBitmap(context, beforeUri)
    val after = decodeCurveBitmap(context, afterUri)
    try {
        val b = analyzeCurveStats(before)
        val a = analyzeCurveStats(after)
        val bRange = (b.p95 - b.p05).coerceAtLeast(0.03f)
        val aRange = (a.p95 - a.p05).coerceAtLeast(0.03f)
        val rangeRatio = aRange / bRange - 1f
        val exposureCurve = log2Safe(a.p50.coerceAtLeast(0.01f), b.p50.coerceAtLeast(0.01f))
        val shadowCurve = ((a.p25 - b.p25) - (a.p50 - b.p50)) * 2.8f
        val highlightCurve = ((b.p75 - a.p75) + (a.p50 - b.p50)) * 2.8f
        val warmCurve = weightedLayerDelta(
            low = a.warmShadow - b.warmShadow,
            mid = a.warmMid - b.warmMid,
            high = a.warmHighlight - b.warmHighlight
        )
        val tintCurve = weightedLayerDelta(
            low = a.tintShadow - b.tintShadow,
            mid = a.tintMid - b.tintMid,
            high = a.tintHighlight - b.tintHighlight
        )
        val chromaRatio = a.chromaMean / b.chromaMean.coerceAtLeast(0.02f) - 1f
        val edgeRatio = a.edgeMean / b.edgeMean.coerceAtLeast(0.008f) - 1f

        return EditParams(
            exposure = exposureCurve.coerceIn(-1f, 1f),
            contrast = (rangeRatio * 0.55f).coerceIn(-1f, 1f),
            shadows = ((a.p05 - b.p05) * 1.8f + shadowCurve).coerceIn(-1f, 1f),
            highlights = ((b.p95 - a.p95) * 1.8f + highlightCurve).coerceIn(-1f, 1f),
            whites = ((a.p95 - b.p95) * 1.2f).coerceIn(-1f, 1f),
            blacks = ((a.p05 - b.p05) * 1.4f).coerceIn(-1f, 1f),
            temperature = (((a.r50 - a.b50) - (b.r50 - b.b50) + warmCurve) * 2.2f).coerceIn(-1f, 1f),
            tint = ((((a.r50 + a.b50) * 0.5f - a.g50) - ((b.r50 + b.b50) * 0.5f - b.g50) + tintCurve) * 2.8f).coerceIn(-1f, 1f),
            saturation = (chromaRatio * 0.45f).coerceIn(-1f, 1f),
            vibrance = (chromaRatio * 0.34f + (0.22f - b.chromaMean) * 0.25f).coerceIn(-1f, 1f),
            clarity = (edgeRatio * 0.20f).coerceIn(-1f, 1f),
            dehaze = (rangeRatio * 0.22f + (a.p05 - b.p05) * 0.75f).coerceIn(-1f, 1f),
            sharpness = (edgeRatio * 0.24f).coerceIn(0f, 1f),
            noiseReduction = ((b.noiseMean - a.noiseMean) * 8.0f).coerceIn(0f, 1f)
        )
    } finally {
        before.recycle()
        after.recycle()
    }
}

fun estimateCurveMatchedPresetFromReference(
    context: Context,
    referenceUri: Uri
): EditParams {
    val bitmap = decodeCurveBitmap(context, referenceUri)
    try {
        val s = analyzeCurveStats(bitmap)
        val tonalRange = (s.p95 - s.p05).coerceAtLeast(0.03f)
        val warmCurve = weightedLayerDelta(s.warmShadow, s.warmMid, s.warmHighlight)
        val tintCurve = weightedLayerDelta(s.tintShadow, s.tintMid, s.tintHighlight)
        val medianWarmth = s.r50 - s.b50
        val medianTint = (s.r50 + s.b50) * 0.5f - s.g50

        return EditParams(
            exposure = log2Safe(0.46f, s.p50.coerceAtLeast(0.01f)).coerceIn(-0.8f, 0.8f),
            contrast = ((tonalRange - 0.56f) * 1.05f).coerceIn(-1f, 1f),
            shadows = ((s.p25 - 0.30f) * 1.2f + (s.p05 - 0.14f) * 0.9f).coerceIn(-1f, 1f),
            highlights = ((0.78f - s.p75) * 0.9f + (0.88f - s.p95) * 0.8f).coerceIn(-1f, 1f),
            whites = ((s.p95 - 0.86f) * 1.2f).coerceIn(-1f, 1f),
            blacks = ((s.p05 - 0.10f) * 1.4f).coerceIn(-1f, 1f),
            temperature = ((medianWarmth + warmCurve * 0.8f) * 2.6f).coerceIn(-1f, 1f),
            tint = ((medianTint + tintCurve * 0.8f) * 3.8f).coerceIn(-1f, 1f),
            saturation = ((s.chromaMean - 0.22f) * 2.1f).coerceIn(-1f, 1f),
            vibrance = ((s.chromaMean - 0.17f) * 1.8f).coerceIn(-1f, 1f),
            clarity = ((s.edgeMean - 0.035f) * 5.4f).coerceIn(-1f, 1f),
            dehaze = ((tonalRange - 0.50f) * 0.9f + (0.07f - s.p05) * 1.1f).coerceIn(-1f, 1f),
            sharpness = ((s.edgeMean - 0.020f) * 9.0f).coerceIn(0f, 1f),
            noiseReduction = ((0.035f - s.noiseMean) * 9.0f).coerceIn(0f, 1f)
        )
    } finally {
        bitmap.recycle()
    }
}

private fun decodeCurveBitmap(context: Context, uri: Uri, maxSide: Int = 1024): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "이미지 입력 스트림을 열 수 없습니다" }
        BitmapFactory.decodeStream(input, null, bounds)
    }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "지원하지 않는 이미지 형식입니다" }
    var sample = 1
    val longest = max(bounds.outWidth, bounds.outHeight)
    while (longest / sample > maxSide) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "이미지 입력 스트림을 다시 열 수 없습니다" }
        BitmapFactory.decodeStream(input, null, options)
    }
    return requireNotNull(bitmap) { "이미지를 디코딩하지 못했습니다" }
}

private fun analyzeCurveStats(bitmap: Bitmap): CurveStats {
    val width = bitmap.width
    val height = bitmap.height
    val step = max(1, max(width, height) / 512)
    val row = IntArray(width)
    val lumaHistogram = IntArray(256)
    val rHistogram = IntArray(256)
    val gHistogram = IntArray(256)
    val bHistogram = IntArray(256)
    var count = 0
    var lumaSum = 0f
    var chromaSum = 0f
    var edgeSum = 0f
    var noiseSum = 0f
    var warmShadow = 0f
    var warmMid = 0f
    var warmHighlight = 0f
    var tintShadow = 0f
    var tintMid = 0f
    var tintHighlight = 0f
    var shadowCount = 0
    var midCount = 0
    var highlightCount = 0

    var y = 0
    while (y < height) {
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        var x = 0
        while (x < width) {
            val pixel = row[x]
            val r8 = (pixel shr 16) and 0xff
            val g8 = (pixel shr 8) and 0xff
            val b8 = pixel and 0xff
            val r = r8 / 255f
            val g = g8 / 255f
            val b = b8 / 255f
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
            lumaHistogram[(luma * 255f).roundToInt().coerceIn(0, 255)] += 1
            rHistogram[r8] += 1
            gHistogram[g8] += 1
            bHistogram[b8] += 1
            val warm = r - b
            val tint = (r + b) * 0.5f - g
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            chromaSum += maxC - minC
            lumaSum += luma

            if (luma < 0.33f) {
                warmShadow += warm
                tintShadow += tint
                shadowCount += 1
            } else if (luma < 0.66f) {
                warmMid += warm
                tintMid += tint
                midCount += 1
            } else {
                warmHighlight += warm
                tintHighlight += tint
                highlightCount += 1
            }

            if (x + step < width) {
                val next = row[x + step]
                val nr = ((next shr 16) and 0xff) / 255f
                val ng = ((next shr 8) and 0xff) / 255f
                val nb = (next and 0xff) / 255f
                val nl = 0.2126f * nr + 0.7152f * ng + 0.0722f * nb
                edgeSum += abs(nl - luma)
            }
            if (x > 0 && x + step < width) {
                val prev = row[max(0, x - step)]
                val next = row[min(width - 1, x + step)]
                val pl = pixelLuma(prev)
                val nl = pixelLuma(next)
                noiseSum += abs(luma - (pl + nl) * 0.5f)
            }
            count += 1
            x += step
        }
        y += step
    }

    if (count <= 0) {
        return CurveStats(0f, 0.25f, 0.5f, 0.75f, 1f, 0.5f, 0.5f, 0.5f, 0.5f, 0.1f, 0.03f, 0.03f, 0f, 0f, 0f, 0f, 0f, 0f)
    }

    return CurveStats(
        p05 = percentileFromHistogram(lumaHistogram, count, 0.05f),
        p25 = percentileFromHistogram(lumaHistogram, count, 0.25f),
        p50 = percentileFromHistogram(lumaHistogram, count, 0.50f),
        p75 = percentileFromHistogram(lumaHistogram, count, 0.75f),
        p95 = percentileFromHistogram(lumaHistogram, count, 0.95f),
        r50 = percentileFromHistogram(rHistogram, count, 0.50f),
        g50 = percentileFromHistogram(gHistogram, count, 0.50f),
        b50 = percentileFromHistogram(bHistogram, count, 0.50f),
        meanLuma = lumaSum / count,
        chromaMean = chromaSum / count,
        edgeMean = edgeSum / count,
        noiseMean = noiseSum / count,
        warmShadow = warmShadow / max(1, shadowCount),
        warmMid = warmMid / max(1, midCount),
        warmHighlight = warmHighlight / max(1, highlightCount),
        tintShadow = tintShadow / max(1, shadowCount),
        tintMid = tintMid / max(1, midCount),
        tintHighlight = tintHighlight / max(1, highlightCount)
    )
}

private fun pixelLuma(pixel: Int): Float {
    val r = ((pixel shr 16) and 0xff) / 255f
    val g = ((pixel shr 8) and 0xff) / 255f
    val b = (pixel and 0xff) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

private fun percentileFromHistogram(histogram: IntArray, count: Int, percentile: Float): Float {
    val target = (count * percentile).roundToInt().coerceIn(1, count)
    var accum = 0
    for (i in histogram.indices) {
        accum += histogram[i]
        if (accum >= target) return i / 255f
    }
    return 1f
}

private fun weightedLayerDelta(low: Float, mid: Float, high: Float): Float =
    low * 0.22f + mid * 0.56f + high * 0.22f

private fun log2Safe(numerator: Float, denominator: Float): Float {
    val n = numerator.coerceAtLeast(0.0001f)
    val d = denominator.coerceAtLeast(0.0001f)
    return ln(n / d) / ln(2f)
}
