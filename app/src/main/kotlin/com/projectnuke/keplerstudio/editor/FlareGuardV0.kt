package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.roundToInt

fun createFlareMaskV0(bitmap: Bitmap, threshold: Float = 0.90f): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val row = IntArray(width)
    val out = IntArray(width)
    val safeThreshold = threshold.coerceIn(0.70f, 0.98f)

    for (y in 0 until height) {
        bitmap.getPixels(row, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            val luma = flareLuma(row[x])
            val a = (((luma - safeThreshold) / (1f - safeThreshold)).coerceIn(0f, 1f) * 255f).roundToInt()
            out[x] = -0x1000000 or (a shl 16) or (a shl 8) or a
        }
        mask.setPixels(out, 0, width, 0, y, width, 1)
    }
    return blurMaskBox(mask, radius = max(6, max(width, height) / 96), passes = 2)
}

fun applyFlareGuardV0(source: Bitmap, strength: Float = 0.35f): Bitmap {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val mask = createFlareMaskV0(source)
    val safeStrength = strength.coerceIn(0f, 1f)
    val width = output.width
    val row = IntArray(width)
    val maskRow = IntArray(width)

    for (y in 0 until output.height) {
        output.getPixels(row, 0, width, 0, y, width, 1)
        mask.getPixels(maskRow, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            val pixel = row[x]
            val luma = flareLuma(pixel)
            val rawMask = ((maskRow[x] ushr 16) and 0xff) / 255f
            val coreProtect = smoothstep(0.94f, 1.0f, luma)
            val amount = rawMask * safeStrength * (1f - 0.72f * coreProtect)
            if (amount <= 0.001f) continue
            row[x] = reduceFlarePixel(pixel, amount)
        }
        output.setPixels(row, 0, width, 0, y, width, 1)
    }
    mask.recycle()
    return output
}

private fun reduceFlarePixel(pixel: Int, amount: Float): Int {
    val a = pixel and -0x1000000
    val r = (pixel ushr 16) and 0xff
    val g = (pixel ushr 8) and 0xff
    val b = pixel and 0xff
    val luma = flareLuma(pixel)
    val desat = amount * 0.28f
    val darken = amount * 0.18f

    fun channel(c: Int): Int {
        val normalized = c / 255f
        val towardLuma = normalized + (luma - normalized) * desat
        val corrected = towardLuma * (1f - darken)
        return (corrected * 255f).roundToInt().coerceIn(0, 255)
    }

    return a or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

private fun blurMaskBox(mask: Bitmap, radius: Int, passes: Int): Bitmap {
    val width = mask.width
    val height = mask.height
    if (width <= 1 || height <= 1 || radius <= 0 || passes <= 0) return mask

    var current = mask
    repeat(passes) {
        val horizontal = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val srcRow = IntArray(width)
        val dstRow = IntArray(width)
        for (y in 0 until height) {
            current.getPixels(srcRow, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                var sum = 0
                var count = 0
                val left = (x - radius).coerceAtLeast(0)
                val right = (x + radius).coerceAtMost(width - 1)
                for (xx in left..right) {
                    sum += (srcRow[xx] ushr 16) and 0xff
                    count += 1
                }
                val v = sum / max(1, count)
                dstRow[x] = -0x1000000 or (v shl 16) or (v shl 8) or v
            }
            horizontal.setPixels(dstRow, 0, width, 0, y, width, 1)
        }

        val vertical = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val column = IntArray(height)
        val outColumn = IntArray(height)
        for (x in 0 until width) {
            horizontal.getPixels(column, 0, 1, x, 0, 1, height)
            for (y in 0 until height) {
                var sum = 0
                var count = 0
                val top = (y - radius).coerceAtLeast(0)
                val bottom = (y + radius).coerceAtMost(height - 1)
                for (yy in top..bottom) {
                    sum += (column[yy] ushr 16) and 0xff
                    count += 1
                }
                val v = sum / max(1, count)
                outColumn[y] = -0x1000000 or (v shl 16) or (v shl 8) or v
            }
            vertical.setPixels(outColumn, 0, 1, x, 0, 1, height)
        }

        if (current !== mask) current.recycle()
        horizontal.recycle()
        current = vertical
    }
    if (current !== mask) mask.recycle()
    return current
}

private fun flareLuma(pixel: Int): Float {
    val r = ((pixel ushr 16) and 0xff) / 255f
    val g = ((pixel ushr 8) and 0xff) / 255f
    val b = (pixel and 0xff) / 255f
    return (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
}

private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
