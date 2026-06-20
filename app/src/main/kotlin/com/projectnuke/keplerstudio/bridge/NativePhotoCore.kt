package com.projectnuke.keplerstudio.bridge

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.editor.PresetLookHandoff
import com.projectnuke.keplerstudio.editor.applyPresetColorLookInPlace

/**
 * Kotlin -> C++ bridge.
 * Kotlin은 세션/명령만 전달하고, 픽셀 반복 처리는 C++에서 수행한다.
 * 프리셋 LUT는 네이티브 렌더 직후 Kotlin 후처리로 적용한다.
 */
object NativePhotoCore {
    init {
        System.loadLibrary("kepler_photocore")
    }

    external fun nativeVersion(): String

    external fun nativeCreateSession(sourcePath: String): Long

    external fun nativeReleaseSession(handle: Long)

    /**
     * Preview와 export가 같은 네이티브 픽셀 파이프라인을 사용한다.
     */
    fun nativeRenderPreviewInPlace(
        bitmap: Bitmap,
        exposure: Float,
        contrast: Float,
        shadows: Float,
        highlights: Float,
        whites: Float,
        blacks: Float,
        temperature: Float,
        tint: Float,
        saturation: Float,
        vibrance: Float,
        clarity: Float,
        dehaze: Float,
        sharpness: Float,
        noiseReduction: Float,
        noiseEngine: Int,
        detailEngine: Int,
        toneEngine: Int,
        hazeEngine: Int,
        revision: Int
    ): Int {
        val result = nativeRenderPreviewInPlaceNative(
            bitmap,
            exposure,
            contrast,
            shadows,
            highlights,
            whites,
            blacks,
            temperature,
            tint,
            saturation,
            vibrance,
            clarity,
            dehaze,
            sharpness,
            noiseReduction,
            noiseEngine,
            detailEngine,
            toneEngine,
            hazeEngine,
            revision
        )
        applyPresetColorLookInPlace(bitmap, PresetLookHandoff.consumeActive())
        return result
    }

    fun nativeApplySpecialEffectInPlace(
        bitmap: Bitmap,
        effect: Int,
        strength: Float,
        revision: Int
    ): Int = nativeApplySpecialEffectInPlaceNative(bitmap, effect, strength, revision)

    fun nativeApplyFlareGuardInPlace(
        bitmap: Bitmap,
        mode: Int,
        strength: Float,
        revision: Int
    ): Int = nativeApplyFlareGuardInPlaceNative(bitmap, mode, strength, revision)

    fun nativeCreateFlareMask(
        source: Bitmap,
        mask: Bitmap,
        threshold: Float,
        radius: Int,
        passes: Int
    ): Int = nativeCreateFlareMaskNative(source, mask, threshold, radius, passes)

    fun nativeRenderCropTransform(
        source: Bitmap,
        destination: Bitmap,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        rotationDegrees: Float,
        flipHorizontal: Boolean,
        revision: Int
    ): Int = nativeRenderCropTransformNative(
        source,
        destination,
        cropLeft,
        cropTop,
        cropRight,
        cropBottom,
        rotationDegrees,
        flipHorizontal,
        revision
    )

    private external fun nativeRenderPreviewInPlaceNative(
        bitmap: Bitmap,
        exposure: Float,
        contrast: Float,
        shadows: Float,
        highlights: Float,
        whites: Float,
        blacks: Float,
        temperature: Float,
        tint: Float,
        saturation: Float,
        vibrance: Float,
        clarity: Float,
        dehaze: Float,
        sharpness: Float,
        noiseReduction: Float,
        noiseEngine: Int,
        detailEngine: Int,
        toneEngine: Int,
        hazeEngine: Int,
        revision: Int
    ): Int

    private external fun nativeApplySpecialEffectInPlaceNative(
        bitmap: Bitmap,
        effect: Int,
        strength: Float,
        revision: Int
    ): Int

    private external fun nativeApplyFlareGuardInPlaceNative(
        bitmap: Bitmap,
        mode: Int,
        strength: Float,
        revision: Int
    ): Int

    private external fun nativeCreateFlareMaskNative(
        source: Bitmap,
        mask: Bitmap,
        threshold: Float,
        radius: Int,
        passes: Int
    ): Int

    private external fun nativeRenderCropTransformNative(
        source: Bitmap,
        destination: Bitmap,
        cropLeft: Float,
        cropTop: Float,
        cropRight: Float,
        cropBottom: Float,
        rotationDegrees: Float,
        flipHorizontal: Boolean,
        revision: Int
    ): Int
}
