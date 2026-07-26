package com.projectnuke.keplerstudio.bridge

import android.graphics.Bitmap
import com.projectnuke.keplerstudio.editor.PresetColorLook
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
        luminanceNoiseReduction: Float = noiseReduction,
        colorNoiseReduction: Float = noiseReduction,
        noiseDetailProtection: Float = 0.50f,
        noiseEngine: Int,
        detailEngine: Int,
        toneEngine: Int,
        hazeEngine: Int,
        revision: Int,
        look: PresetColorLook? = null
    ): Int {
        val scratchPlan =
            NativeScratchPlanner.mainRender(
                rowBytes = bitmap.rowBytes,
                needsFiveRows =
                    luminanceNoiseReduction > 0.001f || colorNoiseReduction > 0.001f,
                needsThreeRows = sharpness > 0.001f,
            )
        if (!scratchPlan.withinNativeBudget) return -12
        // Kotlin keeps highlights signed opposite the native kernel, so pass the negated value here.
        val result = nativeRenderPreviewInPlaceNative(
            bitmap,
            exposure,
            contrast,
            shadows,
            -highlights,
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
            luminanceNoiseReduction,
            colorNoiseReduction,
            noiseDetailProtection,
            noiseEngine,
            detailEngine,
            toneEngine,
            hazeEngine,
            revision
        )
        if (result >= 0) {
            applyPresetColorLookInPlace(bitmap, look)
        }
        return result
    }

    fun nativeApplySpecialEffectInPlace(
        bitmap: Bitmap,
        effect: Int,
        strength: Float,
        revision: Int
    ): Int {
        val plan = NativeScratchPlanner.specialEffect(bitmap.rowBytes, bitmap.height, effect)
        if (!plan.withinNativeBudget) return -12
        return nativeApplySpecialEffectInPlaceNative(bitmap, effect, strength, revision)
    }

    fun nativeApplyFlareGuardInPlace(
        bitmap: Bitmap,
        mode: Int,
        strength: Float,
        revision: Int
    ): Int {
        val plan =
            NativeScratchPlanner.flareCorrection(
                bitmap.rowBytes,
                bitmap.width,
                bitmap.height,
            )
        if (!plan.withinNativeBudget) return -12
        return nativeApplyFlareGuardInPlaceNative(bitmap, mode, strength, revision)
    }

    fun nativeCreateFlareMask(
        source: Bitmap,
        mask: Bitmap,
        threshold: Float,
        radius: Int,
        passes: Int
    ): Int {
        val plan =
            NativeScratchPlanner.flareMask(source.width, source.height, radius, passes)
        if (!plan.withinNativeBudget) return -12
        return nativeCreateFlareMaskNative(source, mask, threshold, radius, passes)
    }

    fun nativeBlendSelectionLayerInPlace(
        target: Bitmap,
        local: Bitmap,
        mask: Bitmap,
        inverted: Boolean,
        opacity: Float
    ): Int = nativeBlendSelectionLayerInPlaceNative(target, local, mask, inverted, opacity)

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
        luminanceNoiseReduction: Float,
        colorNoiseReduction: Float,
        noiseDetailProtection: Float,
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

    private external fun nativeBlendSelectionLayerInPlaceNative(
        target: Bitmap,
        local: Bitmap,
        mask: Bitmap,
        inverted: Boolean,
        opacity: Float
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
