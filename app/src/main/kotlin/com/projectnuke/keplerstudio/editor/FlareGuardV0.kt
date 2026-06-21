package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import kotlin.math.max

enum class FlareGuardMode {
    NightLight,
    DaySun
}

fun createFlareMaskV0(bitmap: Bitmap, threshold: Float = 0.90f): Bitmap {
    val mask = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    NativePhotoCore.nativeCreateFlareMask(
        source = bitmap,
        mask = mask,
        threshold = threshold.coerceIn(0.70f, 0.98f),
        radius = max(6, max(bitmap.width, bitmap.height) / 96),
        passes = 2
    )
    return mask
}

fun applyFlareGuardV0(source: Bitmap, strength: Float = 0.28f): Bitmap {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val result = NativePhotoCore.nativeApplyFlareGuardInPlace(
        bitmap = output,
        mode = FlareGuardMode.NightLight.ordinal,
        strength = strength.coerceIn(0f, 1f),
        revision = 0
    )
    if (result < 0) {
        output.recycle()
        error("nativeApplyFlareGuardInPlace failed: $result")
    }
    return output
}

fun applyDaySunFlareGuardV0(source: Bitmap, strength: Float = 0.24f): Bitmap {
    val output = source.copy(Bitmap.Config.ARGB_8888, true)
    val result = NativePhotoCore.nativeApplyFlareGuardInPlace(
        bitmap = output,
        mode = FlareGuardMode.DaySun.ordinal,
        strength = strength.coerceIn(0f, 1f),
        revision = 0
    )
    if (result < 0) {
        output.recycle()
        error("nativeApplyFlareGuardInPlace failed: $result")
    }
    return output
}
