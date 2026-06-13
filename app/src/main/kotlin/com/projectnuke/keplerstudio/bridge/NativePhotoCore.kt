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
     * MVP용 in-place preview renderer.
     * 실제 v0.2부터는 source file + tile/ROI 기반으로 옮기는 게 목표.
     */
    external fun nativeRenderPreviewInPlace(
        bitmap: Bitmap,
        exposure: Float,
        contrast: Float,
        shadows: Float,
        highlights: Float,
        sharpness: Float,
        revision: Int
    ): Int
}
