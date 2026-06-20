package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.NativePhotoCore

enum class FlareGuardMode {
    NightLight,
    DaySun
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
