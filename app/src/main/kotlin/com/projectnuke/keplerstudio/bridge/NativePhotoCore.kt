package com.projectnuke.keplerstudio.bridge

import android.graphics.Bitmap

/**
 * Kotlin -> C++ bridge.
 * Kotlin은 세션/명령만 전달하고, 픽셀 반복 처리는 C++에서 수행한다.
 */
object NativePhotoCore {
    init {
        System.loadLibrary("kepler_photocore")
    }

    external fun nativeVersion(): String

    external fun nativeCreateSession(sourcePath: String): Long

    external fun nativeReleaseSession(handle: Long)

    /**
     * MVP용 in-place bitmap renderer.
     * Preview와 export가 같은 네이티브 픽셀 파이프라인을 사용한다.
     */
    external fun nativeRenderPreviewInPlace(
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
        revision: Int
    ): Int
}
