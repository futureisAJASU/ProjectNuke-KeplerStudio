package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.NativePhotoCore
import kotlin.math.max

enum class FlareGuardMode {
    NightLight,
    DaySun
}

suspend fun createFlareMaskV0(bitmap: Bitmap, threshold: Float = 0.90f): Bitmap {
    return createFlareMaskTracked(bitmap, threshold, null).requireAdopt()
}

internal suspend fun createFlareMaskTracked(
    bitmap: Bitmap,
    threshold: Float = 0.90f,
    diagnostics: MemoryTrackerScope?,
): TrackedBitmap {
    var owned =
        TrackedBitmap.acquire(
            createBitmapOrThrow(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888),
            diagnostics,
            "flareGuard:ruleMask",
        )
    try {
        val result = NativePhotoCore.nativeCreateFlareMask(
            source = bitmap,
            mask = owned.bitmap,
            threshold = threshold.coerceIn(0.70f, 0.98f),
            radius = max(6, max(bitmap.width, bitmap.height) / 96),
            passes = 2
        )
        if (result < 0) {
            owned.recycleAndRelease()
            error("nativeCreateFlareMask failed: $result")
        }
        return owned
    } catch (t: Throwable) {
        owned.recycleAndRelease()
        throw t
    }
}

suspend fun applyFlareGuardV0(source: Bitmap, strength: Float = 0.28f): Bitmap {
    return applyFlareGuardTracked(source, strength, FlareGuardMode.NightLight, null)
        .requireAdopt()
}

internal suspend fun applyFlareGuardTracked(
    source: Bitmap,
    strength: Float,
    mode: FlareGuardMode,
    diagnostics: MemoryTrackerScope?,
): TrackedBitmap {
    val output =
        TrackedBitmap.acquire(
            source.copyOrThrow(Bitmap.Config.ARGB_8888, true),
            diagnostics,
            "flareGuard:ruleFallbackOutput",
        )
    try {
        val result = NativePhotoCore.nativeApplyFlareGuardInPlace(
            bitmap = output.bitmap,
            mode = mode.ordinal,
            strength = strength.coerceIn(0f, 1f),
            revision = 0
        )
        if (result < 0) {
            output.recycleAndRelease()
            error("nativeApplyFlareGuardInPlace failed: $result")
        }
        return output
    } catch (t: Throwable) {
        output.recycleAndRelease()
        throw t
    }
}

suspend fun applyDaySunFlareGuardV0(source: Bitmap, strength: Float = 0.24f): Bitmap {
    return applyFlareGuardTracked(source, strength, FlareGuardMode.DaySun, null)
        .requireAdopt()
}
