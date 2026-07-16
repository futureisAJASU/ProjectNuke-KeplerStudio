package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import kotlin.math.max

enum class FlareGuardMode {
    NightLight,
    DaySun
}

fun createFlareMaskV0(bitmap: Bitmap, threshold: Float = 0.90f): Bitmap {
    var mask: Bitmap? = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
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
            mask = null
            error("nativeCreateFlareMask failed: $result")
        }
        return mask!!
    } catch (t: Throwable) {
        mask?.recycle()
        throw t
    }
}

fun applyFlareGuardV0(source: Bitmap, strength: Float = 0.28f): Bitmap {
    var output: Bitmap? = source.copyOrThrow(Bitmap.Config.ARGB_8888, true)
    try {
        val result = NativePhotoCore.nativeApplyFlareGuardInPlace(
            bitmap = output!!,
            mode = FlareGuardMode.NightLight.ordinal,
            strength = strength.coerceIn(0f, 1f),
            revision = 0
        )
        if (result < 0) {
            output?.recycle()
            output = null
            error("nativeApplyFlareGuardInPlace failed: $result")
        }
        return output!!
    } catch (t: Throwable) {
        output?.recycle()
        throw t
    }
}

fun applyDaySunFlareGuardV0(source: Bitmap, strength: Float = 0.24f): Bitmap {
    var output: Bitmap? = source.copyOrThrow(Bitmap.Config.ARGB_8888, true)
    try {
        val result = NativePhotoCore.nativeApplyFlareGuardInPlace(
            bitmap = output!!,
            mode = FlareGuardMode.DaySun.ordinal,
            strength = strength.coerceIn(0f, 1f),
            revision = 0
        )
        if (result < 0) {
            output?.recycle()
            output = null
            error("nativeApplyFlareGuardInPlace failed: $result")
        }
        return output!!
    } catch (t: Throwable) {
        output?.recycle()
        throw t
    }
}
