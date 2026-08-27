/*
 * Kepler ENN JNI bridge over Samsung's public Exynos NDK v1 API (enn::api).
 * Function signatures follow the official exynos-eco/enn-sdk-samples reference
 * (MIT, (c) Samsung Electronics).
 *
 * Status contract: the raw EnnReturn of every ENN call is preserved across the
 * boundary via explicit return structures (primitive arrays / direct ints).
 * Nothing is collapsed to -1/false, and there is no global "last error" state.
 */
#include <jni.h>
#include <android/log.h>
#include <cstring>
#include "enn/include/enn_api-public_ndk_v1.hpp"

#define KEPLER_ENN_LOG_TAG "KeplerEnn"

namespace {

/* Local guard failures at this adapter are reported in the EnnReturn space. */
constexpr jint LOCAL_INVAL = static_cast<jint>(ENN_RET_INVAL);
constexpr jint LOCAL_SIZE = static_cast<jint>(ENN_RET_SIZE);
constexpr jint LOCAL_FAILED = static_cast<jint>(ENN_RET_FAILED);
constexpr jint LOCAL_SUCCESS = static_cast<jint>(ENN_RET_SUCCESS);

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

/*
 * Returns long[2] = [rawStatus, modelId]; modelId is 0 unless status is
 * ENN_RET_SUCCESS. The raw status is never collapsed.
 */
JNIEXPORT jlongArray JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeOpenModel(
        JNIEnv* env, jobject, jstring path) {
    EnnReturn result = ENN_RET_INVAL;
    EnnModelId model_id = 0;
    if (path != nullptr) {
        const char* utf = env->GetStringUTFChars(path, nullptr);
        if (utf != nullptr) {
            result = enn::api::EnnOpenModel(utf, &model_id);
            env->ReleaseStringUTFChars(path, utf);
        }
    }
    if (result != ENN_RET_SUCCESS) {
        model_id = 0;
        __android_log_print(ANDROID_LOG_ERROR, KEPLER_ENN_LOG_TAG,
                            "EnnOpenModel failed: %d", static_cast<int>(result));
    }
    jlong values[2] = {static_cast<jlong>(result), static_cast<jlong>(model_id)};
    jlongArray out = env->NewLongArray(2);
    if (out == nullptr) return nullptr;
    env->SetLongArrayRegion(out, 0, 2, values);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeCloseModel(
        JNIEnv*, jobject, jlong model_id) {
    return static_cast<jint>(enn::api::EnnCloseModel(static_cast<EnnModelId>(model_id)));
}

/*
 * Returns long[4] = [rawStatus, bufferSetPtr, nInBuf, nOutBuf].
 * On failure the pointer/counts are zeroed so Kotlin can never adopt a handle
 * the runtime did not successfully hand out.
 */
JNIEXPORT jlongArray JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeAllocateAllBuffers(
        JNIEnv* env, jobject, jlong model_id) {
    EnnBufferPtr* buffer_set = nullptr;
    NumberOfBuffersInfo info = {0, 0};
    EnnReturn result =
        enn::api::EnnAllocateAllBuffers(static_cast<EnnModelId>(model_id), &buffer_set, &info);
    jlong values[4] = {static_cast<jlong>(result), 0L, 0L, 0L};
    if (result == ENN_RET_SUCCESS && buffer_set != nullptr &&
            info.n_in_buf > 0 && info.n_out_buf > 0) {
        values[1] = reinterpret_cast<jlong>(buffer_set);
        values[2] = static_cast<jlong>(info.n_in_buf);
        values[3] = static_cast<jlong>(info.n_out_buf);
    } else if (result == ENN_RET_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, KEPLER_ENN_LOG_TAG,
                            "EnnAllocateAllBuffers returned SUCCESS with an unusable buffer set");
    } else {
        __android_log_print(ANDROID_LOG_ERROR, KEPLER_ENN_LOG_TAG,
                            "EnnAllocateAllBuffers failed: %d", static_cast<int>(result));
    }
    jlongArray out = env->NewLongArray(4);
    if (out == nullptr) return nullptr;
    env->SetLongArrayRegion(out, 0, 4, values);
    return out;
}

JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeReleaseBuffers(
        JNIEnv*, jobject, jlong buffer_set, jint buffer_count) {
    auto* buffers = reinterpret_cast<EnnBufferPtr*>(buffer_set);
    if (buffers == nullptr || buffer_count <= 0) return LOCAL_INVAL;
    return static_cast<jint>(enn::api::EnnReleaseBuffers(buffers, buffer_count));
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
    if (result != ENN_RET_SUCCESS) return nullptr;
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

JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeMemcpyHostToDevice(
        JNIEnv* env, jobject, jlong buffer_set, jint index, jbyteArray data) {
    auto* buffers = reinterpret_cast<EnnBufferPtr*>(buffer_set);
    if (buffers == nullptr || data == nullptr || index < 0) return LOCAL_INVAL;
    const jsize length = env->GetArrayLength(data);
    if (length < 0 || static_cast<uint32_t>(length) > buffers[index]->size) {
        __android_log_print(ANDROID_LOG_ERROR, KEPLER_ENN_LOG_TAG,
                            "memcpy in size %d exceeds buffer %u", length, buffers[index]->size);
        return LOCAL_SIZE;
    }
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return LOCAL_FAILED;
    std::memcpy(buffers[index]->va, bytes, static_cast<size_t>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return LOCAL_SUCCESS;
}

/*
 * Copies exactly `out.length` bytes from the output buffer into the caller's
 * array; fails when the tensor buffer is smaller than requested.
 */
JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeMemcpyDeviceToHost(
        JNIEnv* env, jobject, jlong buffer_set, jint index, jbyteArray out) {
    auto* buffers = reinterpret_cast<EnnBufferPtr*>(buffer_set);
    if (buffers == nullptr || out == nullptr || index < 0) return LOCAL_INVAL;
    const jsize length = env->GetArrayLength(out);
    if (length < 0 || static_cast<uint32_t>(length) > buffers[index]->size) {
        __android_log_print(ANDROID_LOG_ERROR, KEPLER_ENN_LOG_TAG,
                            "memcpy out request %d exceeds buffer %u",
                            length, buffers[index]->size);
        return LOCAL_SIZE;
    }
    jbyte* bytes = env->GetByteArrayElements(out, nullptr);
    if (bytes == nullptr) return LOCAL_FAILED;
    std::memcpy(bytes, buffers[index]->va, static_cast<size_t>(length));
    env->ReleaseByteArrayElements(out, bytes, 0);
    return LOCAL_SUCCESS;
}

JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeExecute(
        JNIEnv*, jobject, jlong model_id) {
    return static_cast<jint>(enn::api::EnnExecuteModel(static_cast<EnnModelId>(model_id)));
}

JNIEXPORT jstring JNICALL
Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeGetMetaInfo(
        JNIEnv* env, jobject, jint meta_id, jlong model_id) {
    char buffer[ENN_INFO_GRAPH_STR_LENGTH_MAX] = {0};
    EnnReturn result = enn::api::EnnGetMetaInfo(
            static_cast<EnnMetaTypeId>(meta_id), static_cast<EnnModelId>(model_id), buffer);
    if (result != ENN_RET_SUCCESS) return nullptr;
    return env->NewStringUTF(buffer);
}

}  // extern "C"
