#pragma once

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <exception>
#include <limits>
#include <new>
#include <utility>

namespace kepler_native {

constexpr bool checkedMultiply(std::size_t left, std::size_t right, std::size_t& out) noexcept {
    if (left != 0 && right > std::numeric_limits<std::size_t>::max() / left) return false;
    out = left * right;
    return true;
}

constexpr bool checkedAdd(std::size_t left, std::size_t right, std::size_t& out) noexcept {
    if (right > std::numeric_limits<std::size_t>::max() - left) return false;
    out = left + right;
    return true;
}

inline bool validateRgbaBitmapLayout(const AndroidBitmapInfo& info) noexcept {
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
    if (info.width == 0 || info.height == 0 || info.stride == 0) return false;
    if (info.width > static_cast<std::uint32_t>(std::numeric_limits<int>::max()) ||
        info.height > static_cast<std::uint32_t>(std::numeric_limits<int>::max()) ||
        info.stride > static_cast<std::uint32_t>(std::numeric_limits<int>::max())) {
        return false;
    }
    std::size_t minimumStride = 0;
    return checkedMultiply(static_cast<std::size_t>(info.width), 4U, minimumStride) &&
           static_cast<std::size_t>(info.stride) >= minimumStride;
}

inline bool checkedBitmapByteCount(const AndroidBitmapInfo& info, std::size_t& out) noexcept {
    return validateRgbaBitmapLayout(info) &&
           checkedMultiply(
               static_cast<std::size_t>(info.stride),
               static_cast<std::size_t>(info.height),
               out);
}

inline bool checkedRowOffset(
    const AndroidBitmapInfo& info,
    std::uint32_t row,
    std::size_t& out) noexcept {
    if (!validateRgbaBitmapLayout(info) || row >= info.height) return false;
    return checkedMultiply(
        static_cast<std::size_t>(row),
        static_cast<std::size_t>(info.stride),
        out);
}

inline bool getBitmapInfo(JNIEnv* env, jobject bitmap, AndroidBitmapInfo& out) noexcept {
    return env != nullptr && bitmap != nullptr &&
           AndroidBitmap_getInfo(env, bitmap, &out) == ANDROID_BITMAP_RESULT_SUCCESS;
}

class LockedBitmap final {
public:
    LockedBitmap(JNIEnv* env, jobject bitmap) noexcept : env_(env), bitmap_(bitmap) {}

    ~LockedBitmap() { unlock(); }

    LockedBitmap(const LockedBitmap&) = delete;
    LockedBitmap& operator=(const LockedBitmap&) = delete;

    LockedBitmap(LockedBitmap&& other) noexcept { moveFrom(other); }

    LockedBitmap& operator=(LockedBitmap&& other) noexcept {
        if (this != &other) {
            unlock();
            moveFrom(other);
        }
        return *this;
    }

    int lock() noexcept {
        if (locked_ || env_ == nullptr || bitmap_ == nullptr) return -1;
        void* acquired = nullptr;
        if (AndroidBitmap_lockPixels(env_, bitmap_, &acquired) != ANDROID_BITMAP_RESULT_SUCCESS ||
            acquired == nullptr) {
            return -1;
        }
        pixels = acquired;
        locked_ = true;
        return 0;
    }

    bool isLocked() const noexcept { return locked_; }

    void* pixels = nullptr;

private:
    void unlock() noexcept {
        if (locked_) AndroidBitmap_unlockPixels(env_, bitmap_);
        locked_ = false;
        pixels = nullptr;
    }

    void moveFrom(LockedBitmap& other) noexcept {
        env_ = other.env_;
        bitmap_ = other.bitmap_;
        pixels = other.pixels;
        locked_ = other.locked_;
        other.env_ = nullptr;
        other.bitmap_ = nullptr;
        other.pixels = nullptr;
        other.locked_ = false;
    }

    JNIEnv* env_ = nullptr;
    jobject bitmap_ = nullptr;
    bool locked_ = false;
};

template <typename Fn>
jint runNativeGuarded(const char* logTag, const char* functionName, Fn&& fn) noexcept {
    try {
        return std::forward<Fn>(fn)();
    } catch (const std::bad_alloc&) {
        __android_log_print(ANDROID_LOG_ERROR, logTag, "%s failed: bad_alloc", functionName);
        return -20;
    } catch (const std::exception& error) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            logTag,
            "%s failed: %s",
            functionName,
            error.what());
        return -21;
    } catch (...) {
        __android_log_print(
            ANDROID_LOG_ERROR,
            logTag,
            "%s failed: unknown exception",
            functionName);
        return -22;
    }
}

}  // namespace kepler_native

namespace kepler_native::compile_time_tests {
constexpr bool multiplyWorks() {
    std::size_t out = 0;
    return checkedMultiply(7, 9, out) && out == 63;
}
constexpr bool multiplyRejectsOverflow() {
    std::size_t out = 0;
    return !checkedMultiply(std::numeric_limits<std::size_t>::max(), 2, out);
}
constexpr bool addRejectsOverflow() {
    std::size_t out = 0;
    return !checkedAdd(std::numeric_limits<std::size_t>::max(), 1, out);
}
static_assert(multiplyWorks());
static_assert(multiplyRejectsOverflow());
static_assert(addRejectsOverflow());
}  // namespace kepler_native::compile_time_tests
