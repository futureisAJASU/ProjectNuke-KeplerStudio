#include "native_cancellation.h"

#include <jni.h>
#include <mutex>
#include <unordered_map>

namespace {
std::mutex registryMutex;
std::unordered_map<std::int64_t, std::shared_ptr<std::atomic_bool>> registry;
std::atomic<std::int64_t> nextToken{1};
}

namespace kepler_native {

std::int64_t registerCancellationToken() noexcept {
    for (;;) {
        const auto token = nextToken.fetch_add(1, std::memory_order_relaxed);
        if (token <= 0) continue;
        std::lock_guard<std::mutex> guard(registryMutex);
        if (registry.emplace(token, std::make_shared<std::atomic_bool>(false)).second) return token;
    }
}

bool signalCancellation(std::int64_t token) noexcept {
    std::shared_ptr<std::atomic_bool> flag;
    {
        std::lock_guard<std::mutex> guard(registryMutex);
        const auto found = registry.find(token);
        if (found == registry.end()) return false;
        flag = found->second;
    }
    flag->store(true, std::memory_order_release);
    return true;
}

bool releaseCancellationToken(std::int64_t token) noexcept {
    std::lock_guard<std::mutex> guard(registryMutex);
    return registry.erase(token) == 1;
}

bool isCancellationRequested(std::int64_t token) noexcept {
    if (token == 0) return false;  // Legacy synchronous call.
    std::shared_ptr<std::atomic_bool> flag;
    {
        std::lock_guard<std::mutex> guard(registryMutex);
        const auto found = registry.find(token);
        if (found == registry.end()) return true;
        flag = found->second;
    }
    return flag->load(std::memory_order_acquire);
}

std::size_t activeCancellationTokenCount() noexcept {
    std::lock_guard<std::mutex> guard(registryMutex);
    return registry.size();
}

CancellationLease::CancellationLease(std::int64_t token) noexcept
    : token_(token), missing_(false) {
    if (token == 0) return;
    std::lock_guard<std::mutex> guard(registryMutex);
    const auto found = registry.find(token);
    if (found == registry.end()) {
        missing_ = true;
    } else {
        flag_ = found->second;
    }
}

bool CancellationLease::cancelled() const noexcept {
    if (token_ == 0) return false;
    if (missing_ || !flag_) return true;
    return flag_->load(std::memory_order_acquire);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeRegisterCancellationToken(
    JNIEnv*, jobject) {
    return static_cast<jlong>(kepler_native::registerCancellationToken());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeSignalCancellation(
    JNIEnv*, jobject, jlong token) {
    return kepler_native::signalCancellation(token) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeReleaseCancellationToken(
    JNIEnv*, jobject, jlong token) {
    return kepler_native::releaseCancellationToken(token) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_projectnuke_keplerstudio_bridge_NativePhotoCore_nativeActiveCancellationTokenCount(
    JNIEnv*, jobject) {
    return static_cast<jint>(kepler_native::activeCancellationTokenCount());
}
