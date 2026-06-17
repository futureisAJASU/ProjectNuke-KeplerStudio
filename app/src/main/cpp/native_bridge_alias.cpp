#include <jni.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeRenderPreviewInPlace(
    JNIEnv* env,
    jobject thiz,
    jobject bitmap,
    jfloat exposure,
    jfloat contrast,
    jfloat shadows,
    jfloat highlights,
    jfloat whites,
    jfloat blacks,
    jfloat temperature,
    jfloat tint,
    jfloat saturation,
    jfloat vibrance,
    jfloat clarity,
    jfloat dehaze,
    jfloat sharpness,
    jfloat noiseReduction,
    jint noiseEngine,
    jint detailEngine,
    jint toneEngine,
    jint hazeEngine,
    jint revision
);

extern "C" JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeRenderPreviewInPlaceNative(
    JNIEnv* env,
    jobject thiz,
    jobject bitmap,
    jfloat exposure,
    jfloat contrast,
    jfloat shadows,
    jfloat highlights,
    jfloat whites,
    jfloat blacks,
    jfloat temperature,
    jfloat tint,
    jfloat saturation,
    jfloat vibrance,
    jfloat clarity,
    jfloat dehaze,
    jfloat sharpness,
    jfloat noiseReduction,
    jint noiseEngine,
    jint detailEngine,
    jint toneEngine,
    jint hazeEngine,
    jint revision
) {
    return Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeRenderPreviewInPlace(
        env,
        thiz,
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
    );
}
