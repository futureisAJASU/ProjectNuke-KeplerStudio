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

data class PresetAnalysisStats(
    val p01: Float,
    val p05: Float,
    val p50: Float,
    val p95: Float,
    val p99: Float,
    val meanLuma: Float,
    val meanR: Float,
    val meanG: Float,
    val meanB: Float,
    val chromaMean: Float,
    val edgeMean: Float,
    val noiseMean: Float
)

fun estimatePresetFromBeforeAfter(
    context: Context,
    beforeUri: Uri,
    afterUri: Uri
): EditParams {
    val before = decodeSampledPresetBitmap(context, beforeUri)
    val after = decodeSampledPresetBitmap(context, afterUri)
    try {
        val b = analyzePresetBitmap(before)
        val a = analyzePresetBitmap(after)
        return EditParams(
            exposure = log2Safe(a.meanLuma, b.meanLuma).coerceIn(-1f, 1f),
            contrast = (((a.p95 - a.p05) - (b.p95 - b.p05)) * 1.6f).coerceIn(-1f, 1f),
            shadows = ((a.p05 - b.p05) * 2.4f).coerceIn(-1f, 1f),
            highlights = ((b.p95 - a.p95) * 2.4f).coerceIn(-1f, 1f),
            whites = ((a.p99 - b.p99) * 2.0f).coerceIn(-1f, 1f),
            blacks = ((a.p01 - b.p01) * 2.3f).coerceIn(-1f, 1f),
            temperature = (((a.meanR - a.meanB) - (b.meanR - b.meanB)) * 3.2f).coerceIn(-1f, 1f),
            tint = ((((a.meanR + a.meanB) * 0.5f - a.meanG) - ((b.meanR + b.meanB) * 0.5f - b.meanG)) * 4.0f).coerceIn(-1f, 1f),
            saturation = ((a.chromaMean - b.chromaMean) * 2.2f).coerceIn(-1f, 1f),
            vibrance = ((a.chromaMean - b.chromaMean) * 1.6f).coerceIn(-1f, 1f),
            clarity = ((a.edgeMean - b.edgeMean) * 5.0f).coerceIn(-1f, 1f),
            dehaze = ((((a.p95 - a.p05) - (b.p95 - b.p05)) + (a.p01 - b.p01)) * 1.4f).coerceIn(-1f, 1f),
            sharpness = ((a.edgeMean - b.edgeMean) * 7.0f).coerceIn(0f, 1f),
            noiseReduction = ((b.noiseMean - a.noiseMean) * 8.0f).coerceIn(0f, 1f)
        )
    } finally {
        before.recycle()
        after.recycle()
    }
}

fun estimatePresetFromReference(
    context: Context,
    referenceUri: Uri
): EditParams {
    val bitmap = decodeSampledPresetBitmap(context, referenceUri)
    try {
        val s = analyzePresetBitmap(bitmap)
        val tonalRange = s.p95 - s.p05
        val warmth = s.meanR - s.meanB
        val greenBalance = (s.meanR + s.meanB) * 0.5f - s.meanG
        return EditParams(
            exposure = log2Safe(0.46f, s.meanLuma.coerceAtLeast(0.01f)).coerceIn(-0.8f, 0.8f),
            contrast = ((tonalRange - 0.58f) * 1.2f).coerceIn(-1f, 1f),
            shadows = ((s.p05 - 0.20f) * 2.0f).coerceIn(-1f, 1f),
            highlights = ((0.82f - s.p95) * 2.0f).coerceIn(-1f, 1f),
            whites = ((s.p99 - 0.95f) * 1.8f).coerceIn(-1f, 1f),
            blacks = ((s.p01 - 0.03f) * 2.2f).coerceIn(-1f, 1f),
            temperature = (warmth * 2.8f).coerceIn(-1f, 1f),
            tint = (greenBalance * 4.2f).coerceIn(-1f, 1f),
            saturation = ((s.chromaMean - 0.22f) * 2.4f).coerceIn(-1f, 1f),
            vibrance = ((s.chromaMean - 0.18f) * 2.0f).coerceIn(-1f, 1f),
            clarity = ((s.edgeMean - 0.035f) * 6.0f).coerceIn(-1f, 1f),
            dehaze = (((tonalRange - 0.50f) + (0.07f - s.p01)) * 1.5f).coerceIn(-1f, 1f),
            sharpness = ((s.edgeMean - 0.020f) * 10.0f).coerceIn(0f, 1f),
            noiseReduction = ((0.035f - s.noiseMean) * 10.0f).coerceIn(0f, 1f)
        )
    } finally {
        bitmap.recycle()
    }
}

private fun decodeSampledPresetBitmap(
    context: Context,
    uri: Uri,
    maxSide: Int = 1024
): Bitmap {
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

private fun analyzePresetBitmap(bitmap: Bitmap): PresetAnalysisStats {
    val width = bitmap.width
    val height = bitmap.height
    val step = max(1, max(width, height) / 512)
    val row = IntArray(width)
    val histogram = IntArray(256)
    var count = 0
    var lumaSum = 0f
    var rSum = 0f
    var gSum = 0f
    var bSum = 0f
    var chromaSum = 0f
    var edgeSum = 0f
    var noiseSum = 0f

    var y = 0
    while (y < height) {
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        var x = 0
        while (x < width) {
            val pixel = row[x]
            val r = ((pixel shr 16) and 0xff) / 255f
            val g = ((pixel shr 8) and 0xff) / 255f
            val b = (pixel and 0xff) / 255f
            val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
            histogram[(luma * 255f).roundToInt().coerceIn(0, 255)] += 1

            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            chromaSum += maxC - minC
            lumaSum += luma
            rSum += r
            gSum += g
            bSum += b

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
                val pl = (((prev shr 16) and 0xff) / 255f * 0.2126f) +
                    (((prev shr 8) and 0xff) / 255f * 0.7152f) +
                    ((prev and 0xff) / 255f * 0.0722f)
                val nl = (((next shr 16) and 0xff) / 255f * 0.2126f) +
                    (((next shr 8) and 0xff) / 255f * 0.7152f) +
                    ((next and 0xff) / 255f * 0.0722f)
                noiseSum += abs(luma - (pl + nl) * 0.5f)
            }

            count += 1
            x += step
        }
        y += step
    }

    if (count <= 0) {
        return PresetAnalysisStats(
            p01 = 0f,
            p05 = 0f,
            p50 = 0.5f,
            p95 = 1f,
            p99 = 1f,
            meanLuma = 0.5f,
            meanR = 0.5f,
            meanG = 0.5f,
            meanB = 0.5f,
            chromaMean = 0.1f,
            edgeMean = 0.03f,
            noiseMean = 0.03f
        )
    }

    return PresetAnalysisStats(
        p01 = percentileFromHistogram(histogram, count, 0.01f),
        p05 = percentileFromHistogram(histogram, count, 0.05f),
        p50 = percentileFromHistogram(histogram, count, 0.50f),
        p95 = percentileFromHistogram(histogram, count, 0.95f),
        p99 = percentileFromHistogram(histogram, count, 0.99f),
        meanLuma = lumaSum / count,
        meanR = rSum / count,
        meanG = gSum / count,
        meanB = bSum / count,
        chromaMean = chromaSum / count,
        edgeMean = edgeSum / count,
        noiseMean = noiseSum / count
    )
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

private fun log2Safe(numerator: Float, denominator: Float): Float {
    val n = numerator.coerceAtLeast(0.0001f)
    val d = denominator.coerceAtLeast(0.0001f)
    return ln(n / d) / ln(2f)
}
