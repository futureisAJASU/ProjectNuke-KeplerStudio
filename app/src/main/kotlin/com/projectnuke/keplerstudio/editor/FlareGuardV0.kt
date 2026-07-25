package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import kotlin.math.max

enum class FlareGuardMode {
    NightLight,
    DaySun
}

fun createFlareMaskV0(bitmap: Bitmap, threshold: Float = 0.90f): Bitmap {
    return createFlareMaskTracked(bitmap, threshold, null)
}

internal fun createFlareMaskTracked(bitmap: Bitmap, threshold: Float = 0.90f, diagnostics: MemoryTrackerScope?): Bitmap {
    var mask: Bitmap? = createBitmapOrThrow(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    var edge = diagnostics?.track(mask!!, "flareGuard:ruleMask") ?: 0L
    try {
        val result = NativePhotoCore.nativeCreateFlareMask(
            source = bitmap,
            mask = mask!!,
            threshold = threshold.coerceIn(0.70f, 0.98f),
            radius = max(6, max(bitmap.width, bitmap.height) / 96),
            passes = 2
        )
        if (result < 0) {
            mask?.recycle()
            diagnostics?.release(edge)
            mask = null
            error("nativeCreateFlareMask failed: $result")
        }
        return mask!!
    } catch (t: Throwable) {
        mask?.recycle()
        diagnostics?.release(edge)
        throw t
    }
}

fun applyFlareGuardV0(source: Bitmap, strength: Float = 0.28f): Bitmap {
    return applyFlareGuardTracked(source, strength, FlareGuardMode.NightLight, null)
}

internal fun applyFlareGuardTracked(source: Bitmap, strength: Float, mode: FlareGuardMode, diagnostics: MemoryTrackerScope?): Bitmap {
    var output: Bitmap? = source.copyOrThrow(Bitmap.Config.ARGB_8888, true)
    val edge = diagnostics?.track(output!!, "flareGuard:ruleFallbackOutput") ?: 0L
    try {
        val result = NativePhotoCore.nativeApplyFlareGuardInPlace(
            bitmap = output!!,
            mode = mode.ordinal,
            strength = strength.coerceIn(0f, 1f),
            revision = 0
        )
        if (result < 0) {
            output?.recycle()
            diagnostics?.release(edge)
            output = null
            error("nativeApplyFlareGuardInPlace failed: $result")
        }
        return output!!
    } catch (t: Throwable) {
        output?.recycle()
        diagnostics?.release(edge)
        throw t
    }
}

fun applyDaySunFlareGuardV0(source: Bitmap, strength: Float = 0.24f): Bitmap {
    return applyFlareGuardTracked(source, strength, FlareGuardMode.DaySun, null)
}
