package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.roundToInt

fun renderCropTransform(source: Bitmap, cropState: CropState): Bitmap {
    val state = cropState.normalized()
    val transformed = transformBeforeCrop(source, state)
    val left = (state.cropLeft * transformed.width).roundToInt().coerceIn(0, transformed.width - 1)
    val top = (state.cropTop * transformed.height).roundToInt().coerceIn(0, transformed.height - 1)
    val right = (state.cropRight * transformed.width).roundToInt().coerceIn(left + 1, transformed.width)
    val bottom = (state.cropBottom * transformed.height).roundToInt().coerceIn(top + 1, transformed.height)
    val cropped = Bitmap.createBitmap(transformed, left, top, right - left, bottom - top)
    if (transformed !== source) transformed.recycle()
    return cropped.copy(Bitmap.Config.ARGB_8888, true)
}

private fun transformBeforeCrop(source: Bitmap, state: CropState): Bitmap {
    val matrix = Matrix()
    if (state.flipHorizontal) {
        matrix.postScale(-1f, 1f, source.width / 2f, source.height / 2f)
    }
    val totalRotation = state.rotationDegrees + state.straightenDegrees
    if (abs(totalRotation) > 0.001f) {
        matrix.postRotate(totalRotation, source.width / 2f, source.height / 2f)
    }
    return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
}
