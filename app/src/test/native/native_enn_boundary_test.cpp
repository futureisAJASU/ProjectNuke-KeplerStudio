#include <cstring>
#include <iostream>

#include "enn/include/enn_api-public_ndk_v1.hpp"
#include "jni.h"

extern "C" jint Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeMemcpyHostToDevice(
        JNIEnv*, jobject, jlong, jint, jint, jbyteArray);
extern "C" jint Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeMemcpyDeviceToHost(
        JNIEnv*, jobject, jlong, jint, jint, jbyteArray);
extern "C" jintArray Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeGetBufferInfoByIndex(
        JNIEnv*, jobject, jlong, jint, jint);
extern "C" jlongArray Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeOpenModel(
        JNIEnv*, jobject, jstring);

namespace {

int get_buffer_info_calls = 0;

}  // namespace

namespace enn::api {

EnnReturn EnnInitialize() { return ENN_RET_SUCCESS; }
EnnReturn EnnDeinitialize() { return ENN_RET_SUCCESS; }
EnnReturn EnnOpenModel(const char*, EnnModelId* model_id) {
    *model_id = 42;
    return ENN_RET_SUCCESS;
}
EnnReturn EnnCloseModel(EnnModelId) { return ENN_RET_SUCCESS; }
EnnReturn EnnAllocateAllBuffers(const EnnModelId, EnnBufferPtr**, NumberOfBuffersInfo*, const int, const bool) {
    return ENN_RET_FAILED;
}
EnnReturn EnnReleaseBuffers(EnnBufferPtr*, const int32_t) { return ENN_RET_SUCCESS; }
EnnReturn EnnGetBufferInfoByIndex(EnnBufferInfo* info, const EnnModelId, const enn_buf_dir_e,
                                  const uint32_t) {
    ++get_buffer_info_calls;
    info->n = 1;
    info->width = 2;
    info->height = 2;
    info->channel = 1;
    info->size = 4;
    return ENN_RET_SUCCESS;
}
EnnReturn EnnExecuteModel(EnnModelId, const int) { return ENN_RET_SUCCESS; }
EnnReturn EnnGetMetaInfo(const EnnMetaTypeId, const EnnModelId, char* output) {
    std::strcpy(output, "test");
    return ENN_RET_SUCCESS;
}

}  // namespace enn::api

int main() {
    JNIEnv env;
    jobject receiver = nullptr;
    jbyte input_values[4] = {1, 2, 3, 4};
    jbyte output_values[4] = {0, 0, 0, 0};
    EnnBuffer input{input_values, 4, 0};
    EnnBuffer output{output_values, 4, 0};
    EnnBufferPtr buffers[2] = {&input, &output};
    const jlong buffer_set = reinterpret_cast<jlong>(buffers);
    FakeByteArray data(4);
    data.values = {9, 8, 7, 6};
    FakeByteArray out(4);

    if (Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeMemcpyHostToDevice(
                &env, receiver, buffer_set, 2, -1, &data) != ENN_RET_INVAL) return 1;
    if (Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeMemcpyHostToDevice(
                &env, receiver, buffer_set, 2, 2, &data) != ENN_RET_INVAL) return 2;
    if (Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeMemcpyHostToDevice(
                &env, receiver, buffer_set, 2, 3, &data) != ENN_RET_INVAL) return 3;
    if (Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeMemcpyHostToDevice(
                &env, receiver, buffer_set, 2, 0, &data) != ENN_RET_SUCCESS) return 4;
    if (std::memcmp(input_values, data.values.data(), sizeof(input_values)) != 0) return 5;

    if (Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeMemcpyDeviceToHost(
                &env, receiver, buffer_set, 2, 1, &out) != ENN_RET_SUCCESS) return 6;
    if (std::memcmp(output_values, out.values.data(), sizeof(output_values)) != 0) return 7;

    buffers[0] = nullptr;
    if (Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeMemcpyHostToDevice(
                &env, receiver, buffer_set, 2, 0, &data) != ENN_RET_INVAL) return 8;
    buffers[0] = &input;
    input.va = nullptr;
    if (Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeMemcpyHostToDevice(
                &env, receiver, buffer_set, 2, 0, &data) != ENN_RET_INVAL) return 9;
    input.va = input_values;

    get_buffer_info_calls = 0;
    if (Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeGetBufferInfoByIndex(
                &env, receiver, 42, 0, -1) != nullptr) return 10;
    if (get_buffer_info_calls != 0) return 11;
    if (Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeGetBufferInfoByIndex(
                &env, receiver, 42, ENN_DIR_EXT, 0) != nullptr) return 12;
    if (get_buffer_info_calls != 0) return 13;
    if (Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeGetBufferInfoByIndex(
                &env, receiver, 42, ENN_DIR_IN, 0) == nullptr) return 14;

    FakeString path{"model.nnc"};
    const int arrays_before_pending = env.new_long_array_calls;
    env.string_failure = true;
    env.string_failure_pending = true;
    if (Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeOpenModel(
                &env, receiver, &path) != nullptr) return 15;
    if (!env.pending_exception || env.new_long_array_calls != arrays_before_pending) return 16;

    env.pending_exception = false;
    env.string_failure_pending = false;
    auto* structured_failure = reinterpret_cast<FakeLongArray*>(
            Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeOpenModel(
                    &env, receiver, &path));
    if (structured_failure == nullptr || structured_failure->values[0] != ENN_RET_INVAL) return 17;

    env.string_failure = false;
    const int releases_before_success = env.release_string_calls;
    auto* structured_success = reinterpret_cast<FakeLongArray*>(
            Java_com_projectnuke_keplerstudio_editor_ExynosEnnNative_nativeOpenModel(
                    &env, receiver, &path));
    if (structured_success == nullptr || structured_success->values[0] != ENN_RET_SUCCESS ||
            env.release_string_calls != releases_before_success + 1) return 18;

    std::cout << "native ENN JNI boundary checks passed\n";
    return 0;
}
