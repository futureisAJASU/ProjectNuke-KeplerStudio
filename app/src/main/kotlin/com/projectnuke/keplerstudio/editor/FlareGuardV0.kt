package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import kotlin.math.max
import kotlin.math.roundToInt

enum class FlareGuardMode {
    NightLight,
    DaySun
}

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
            val luma = flareMaskLuma(row[x])
            val alpha = (((luma - safeThreshold) / (1f - safeThreshold)).coerceIn(0f, 1f) * 255f).roundToInt()
            out[x] = -0x1000000 or (alpha shl 16) or (alpha shl 8) or alpha
        }
        mask.setPixels(out, 0, width, 0, y, width, 1)
    }
    return blurFlareMaskCompat(mask, radius = max(6, max(width, height) / 96), passes = 2)
}

fun applyFlareGuardV0(source: Bitmap, strength: Float = 0.35f): Bitmap {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    NativePhotoCore.nativeApplyFlareGuardInPlace(
        bitmap = output,
        mode = FlareGuardMode.NightLight.ordinal,
        strength = strength.coerceIn(0f, 1f),
        revision = 0
    )
    return output
}

fun applyDaySunFlareGuardV0(source: Bitmap, strength: Float = 0.32f): Bitmap {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    NativePhotoCore.nativeApplyFlareGuardInPlace(
        bitmap = output,
        mode = FlareGuardMode.DaySun.ordinal,
        strength = strength.coerceIn(0f, 1f),
        revision = 0
    )
    return output
}

private fun flareMaskLuma(pixel: Int): Float {
    val r = ((pixel ushr 16) and 0xff) / 255f
    val g = ((pixel ushr 8) and 0xff) / 255f
    val b = (pixel and 0xff) / 255f
    return (0.2126f * r + 0.7152f * g + 0.0722f * b).coerceIn(0f, 1f)
}

private fun blurFlareMaskCompat(mask: Bitmap, radius: Int, passes: Int): Bitmap {
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
                val v = sum / count.coerceAtLeast(1)
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
                val v = sum / count.coerceAtLeast(1)
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
