package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

fun renderCropTransform(source: Bitmap, cropState: CropState): Bitmap {
    val state = cropState.normalized()
    val rotation = state.rotationDegrees + state.straightenDegrees
    val size = rotatedCanvasSize(source.width, source.height, rotation)
    val outWidth = ((state.cropRight - state.cropLeft).coerceIn(0.01f, 1f) * size.first).roundToInt().coerceAtLeast(1)
    val outHeight = ((state.cropBottom - state.cropTop).coerceIn(0.01f, 1f) * size.second).roundToInt().coerceAtLeast(1)
    val output = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
    val result = NativePhotoCore.nativeRenderCropTransform(
        source,
        output,
        state.cropLeft,
        state.cropTop,
        state.cropRight,
        state.cropBottom,
        rotation,
        state.flipHorizontal,
        0
    )
    if (result < 0) {
        output.recycle()
        throw IllegalStateException("native crop transform failed: code=$result")
    }
    return output
}

private fun rotatedCanvasSize(width: Int, height: Int, degrees: Float): Pair<Int, Int> {
    val normalized = ((degrees % 360f) + 360f) % 360f
    if (abs(normalized) < 0.001f) return width to height
    val radians = Math.toRadians(normalized.toDouble()).toFloat()
    val c = abs(cos(radians))
    val s = abs(sin(radians))
    val outWidth = ceil(width * c + height * s).roundToInt().coerceAtLeast(1)
    val outHeight = ceil(width * s + height * c).roundToInt().coerceAtLeast(1)
    return outWidth to outHeight
}
