/*
 * Kepler ENN JNI bridge over Samsung's public Exynos NDK v1 API (enn::api).
 * Function signatures follow the official exynos-eco/enn-sdk-samples reference
 * (MIT, (c) Samsung Electronics) but every call returns an explicit status so
 * failures can never be silently converted into success.
 */
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include "enn/include/enn_api-public_ndk_v1.hpp"

#define KEPLER_ENN_LOG_TAG "KeplerEnn"

namespace {

inline jboolean isFailure(EnnReturn code) { return code != ENN_RET_SUCCESS; }

}  // namespace

extern "C" {

JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeInitialize(JNIEnv*, jobject) {
    return static_cast<jint>(enn::api::EnnInitialize());
}

JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeDeinitialize(JNIEnv*, jobject) {
    return static_cast<jint>(enn::api::EnnDeinitialize());
}

JNIEXPORT jlong JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeOpenModel(
        JNIEnv* env, jobject, jstring path) {
    if (path == nullptr) return -1L;
    const char* utf = env->GetStringUTFChars(path, nullptr);
    if (utf == nullptr) return -1L;
    EnnModelId model_id = 0;
    EnnReturn result = enn::api::EnnOpenModel(utf, &model_id);
    env->ReleaseStringUTFChars(path, utf);
    if (isFailure(result)) {
        __android_log_print(ANDROID_LOG_ERROR, KEPLER_ENN_LOG_TAG,
                            "EnnOpenModel failed: %d", static_cast<int>(result));
        return -1L;
    }
    return static_cast<jlong>(model_id);
}

JNIEXPORT jboolean JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeCloseModel(
        JNIEnv*, jobject, jlong model_id) {
    return !isFailure(enn::api::EnnCloseModel(static_cast<EnnModelId>(model_id)));
}

/*
 * Returns [buffer_set_ptr, n_in_buf, n_out_buf] or null on failure.
 */
JNIEXPORT jlongArray JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeAllocateAllBuffers(
        JNIEnv* env, jobject, jlong model_id) {
    EnnBufferPtr* buffer_set = nullptr;
    NumberOfBuffersInfo info = {0, 0};
    EnnReturn result =
        enn::api::EnnAllocateAllBuffers(static_cast<EnnModelId>(model_id), &buffer_set, &info);
    if (isFailure(result) || buffer_set == nullptr ||
            info.n_in_buf <= 0 || info.n_out_buf <= 0) {
        __android_log_print(ANDROID_LOG_ERROR, KEPLER_ENN_LOG_TAG,
                            "EnnAllocateAllBuffers failed: %d", static_cast<int>(result));
        return nullptr;
    }
    jlong values[3] = {
        reinterpret_cast<jlong>(buffer_set),
        static_cast<jlong>(info.n_in_buf),
        static_cast<jlong>(info.n_out_buf),
    };
    jlongArray out = env->NewLongArray(3);
    if (out == nullptr) return nullptr;
    env->SetLongArrayRegion(out, 0, 3, values);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeReleaseBuffers(
        JNIEnv*, jobject, jlong buffer_set, jint buffer_count) {
    auto* buffers = reinterpret_cast<EnnBufferPtr*>(buffer_set);
    if (buffers == nullptr || buffer_count <= 0) return JNI_FALSE;
    return !isFailure(enn::api::EnnReleaseBuffers(buffers, buffer_count));
}

/*
 * Returns [n, width, height, channel, size] for one buffer of the loaded model.
 */
JNIEXPORT jintArray JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeGetBufferInfoByIndex(
        JNIEnv* env, jobject, jlong model_id, jint direction, jint index) {
    EnnBufferInfo info = {};
    EnnReturn result = enn::api::EnnGetBufferInfoByIndex(
            &info, static_cast<EnnModelId>(model_id),
            static_cast<enn_buf_dir_e>(direction), static_cast<uint32_t>(index));
    if (isFailure(result)) return nullptr;
    jint values[5] = {
        static_cast<jint>(info.n),
        static_cast<jint>(info.width),
        static_cast<jint>(info.height),
        static_cast<jint>(info.channel),
        static_cast<jint>(info.size),
    };
    jintArray out = env->NewIntArray(5);
    if (out == nullptr) return nullptr;
    env->SetIntArrayRegion(out, 0, 5, values);
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeMemcpyHostToDevice(
        JNIEnv* env, jobject, jlong buffer_set, jint index, jbyteArray data) {
    auto* buffers = reinterpret_cast<EnnBufferPtr*>(buffer_set);
    if (buffers == nullptr || data == nullptr || index < 0) return JNI_FALSE;
    const jsize length = env->GetArrayLength(data);
    if (length < 0 || static_cast<uint32_t>(length) > buffers[index]->size) {
        __android_log_print(ANDROID_LOG_ERROR, KEPLER_ENN_LOG_TAG,
                            "memcpy in size %d exceeds buffer %u", length, buffers[index]->size);
        return JNI_FALSE;
    }
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return JNI_FALSE;
    std::memcpy(buffers[index]->va, bytes, static_cast<size_t>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return JNI_TRUE;
}

/*
 * Copies exactly `out.length` bytes from the output buffer into the caller's
 * array; fails when the tensor buffer is smaller than requested.
 */
JNIEXPORT jboolean JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeMemcpyDeviceToHost(
        JNIEnv* env, jobject, jlong buffer_set, jint index, jbyteArray out) {
    auto* buffers = reinterpret_cast<EnnBufferPtr*>(buffer_set);
    if (buffers == nullptr || out == nullptr || index < 0) return JNI_FALSE;
    const jsize length = env->GetArrayLength(out);
    if (length < 0 || static_cast<uint32_t>(length) > buffers[index]->size) {
        __android_log_print(ANDROID_LOG_ERROR, KEPLER_ENN_LOG_TAG,
                            "memcpy out request %d exceeds buffer %u",
                            length, buffers[index]->size);
        return JNI_FALSE;
    }
    jbyte* bytes = env->GetByteArrayElements(out, nullptr);
    if (bytes == nullptr) return JNI_FALSE;
    std::memcpy(bytes, buffers[index]->va, static_cast<size_t>(length));
    env->ReleaseByteArrayElements(out, bytes, 0);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeExecute(
        JNIEnv*, jobject, jlong model_id) {
    return !isFailure(enn::api::EnnExecuteModel(static_cast<EnnModelId>(model_id)));
}

JNIEXPORT jstring JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeGetMetaInfo(
        JNIEnv* env, jobject, jint meta_id, jlong model_id) {
    char buffer[ENN_INFO_GRAPH_STR_LENGTH_MAX] = {0};
    EnnReturn result = enn::api::EnnGetMetaInfo(
            static_cast<EnnMetaTypeId>(meta_id), static_cast<EnnModelId>(model_id), buffer);
    if (isFailure(result)) return nullptr;
    return env->NewStringUTF(buffer);
}

}  // extern "C"
