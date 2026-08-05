package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.concurrent.atomic.AtomicLong

internal fun cropTransformedDimensions(sourceWidth: Int, sourceHeight: Int, cropState: CropState): Pair<Int, Int> {
    val state = cropState.normalized()
    val rotation = state.rotationDegrees + state.straightenDegrees
    val size = rotatedCanvasSize(sourceWidth, sourceHeight, rotation)
    return (
        (state.cropRight - state.cropLeft).coerceIn(0.01f, 1f) * size.first
    ).roundToInt().coerceAtLeast(1) to (
        (state.cropBottom - state.cropTop).coerceIn(0.01f, 1f) * size.second
    ).roundToInt().coerceAtLeast(1)
}

/**
 * Deterministic crop-transform seam for production tests. When set, every
 * crop transform call site uses it instead of the native library; the
 * default (null) keeps the production native path unchanged.
 */
private val cropTransformTestLock = Any()
private class CropTransformInstallation(
    val generation: Long,
    private val transform: suspend (Bitmap, CropState) -> Bitmap,
) {
    @Volatile private var active = true

    suspend fun invoke(source: Bitmap, cropState: CropState): Bitmap {
        check(active) { "crop transform test owner closed before execution" }
        return transform(source, cropState)
    }

    fun close() {
        active = false
    }
}
private var cropTransformInstallation: CropTransformInstallation? = null
private val cropTransformInstallationGeneration = AtomicLong()

internal fun installCropTransformForTest(
    transform: suspend (Bitmap, CropState) -> Bitmap,
): AutoCloseable {
    val installation = CropTransformInstallation(cropTransformInstallationGeneration.incrementAndGet(), transform)
    synchronized(cropTransformTestLock) {
        check(cropTransformInstallation == null) { "crop transform test seam already installed" }
        cropTransformInstallation = installation
    }
    return AutoCloseable {
        installation.close()
        synchronized(cropTransformTestLock) {
            if (cropTransformInstallation === installation) cropTransformInstallation = null
        }
    }
}

internal fun cropTransformTestSeamCount(): Int = synchronized(cropTransformTestLock) {
    if (cropTransformInstallation == null) 0 else 1
}

suspend fun renderCropTransform(source: Bitmap, cropState: CropState): Bitmap =
    synchronized(cropTransformTestLock) { cropTransformInstallation }?.invoke(source, cropState)
        ?: renderCropTransformNative(source, cropState)

private suspend fun renderCropTransformNative(source: Bitmap, cropState: CropState): Bitmap {
    val state = cropState.normalized()
    val rotation = state.rotationDegrees + state.straightenDegrees
    val dimensions = cropTransformedDimensions(source.width, source.height, state)
    val outWidth = dimensions.first
    val outHeight = dimensions.second
    val output = createBitmapOrThrow(outWidth, outHeight, Bitmap.Config.ARGB_8888)
    try {
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
    } catch (t: Throwable) {
        if (!output.isRecycled) output.recycle()
        throw t
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
