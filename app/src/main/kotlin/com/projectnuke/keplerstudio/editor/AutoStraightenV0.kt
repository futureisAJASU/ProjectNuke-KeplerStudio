package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt

fun estimateAutoStraightenDegreesV0(bitmap: Bitmap): Float {
    val step = max(1, max(bitmap.width, bitmap.height) / 360)
    val bins = FloatArray(49)
    val prev = IntArray(bitmap.width)
    val cur = IntArray(bitmap.width)
    val next = IntArray(bitmap.width)
    var y = step
    while (y < bitmap.height - step) {
        bitmap.getPixels(prev, 0, bitmap.width, 0, y - step, bitmap.width, 1)
        bitmap.getPixels(cur, 0, bitmap.width, 0, y, bitmap.width, 1)
        bitmap.getPixels(next, 0, bitmap.width, 0, y + step, bitmap.width, 1)
        var x = step
        while (x < bitmap.width - step) {
            val gx = autoStraightenLuma(cur[x + step]) - autoStraightenLuma(cur[x - step])
            val gy = autoStraightenLuma(next[x]) - autoStraightenLuma(prev[x])
            val weight = abs(gx) + abs(gy)
            if (weight > 0.16f) {
                val edgeAngle = Math.toDegrees(atan2(gy.toDouble(), gx.toDouble())).toFloat()
                val lineAngle = normalizeAutoStraightenAngle(edgeAngle + 90f)
                if (lineAngle in -12f..12f) {
                    val index = ((lineAngle + 12f) * 2f).roundToInt().coerceIn(0, bins.lastIndex)
                    bins[index] += weight
                }
            }
            x += step
        }
        y += step
    }
    val bestIndex = bins.indices.maxByOrNull { bins[it] } ?: return 0f
    if (bins[bestIndex] <= 0.0001f) return 0f
    val lineAngle = bestIndex / 2f - 12f
    return (-lineAngle).coerceIn(-12f, 12f)
}

private fun autoStraightenLuma(pixel: Int): Float {
    val r = ((pixel ushr 16) and 0xff) / 255f
    val g = ((pixel ushr 8) and 0xff) / 255f
    val b = (pixel and 0xff) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

private fun normalizeAutoStraightenAngle(angle: Float): Float {
    var value = angle
    while (value > 90f) value -= 180f
    while (value < -90f) value += 180f
    return value
}
