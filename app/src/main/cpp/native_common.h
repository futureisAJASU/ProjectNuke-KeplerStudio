#pragma once

#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <exception>
#include <limits>
#include <new>
#include <type_traits>
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

constexpr bool validateRgbaBitmapLayout(const AndroidBitmapInfo& info) noexcept {
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

constexpr bool checkedBitmapByteCount(const AndroidBitmapInfo& info, std::size_t& out) noexcept {
    return validateRgbaBitmapLayout(info) &&
           checkedMultiply(
               static_cast<std::size_t>(info.stride),
               static_cast<std::size_t>(info.height),
               out);
}

constexpr bool checkedRowOffset(
    const AndroidBitmapInfo& info,
    std::uint32_t row,
    std::size_t& out) noexcept {
    if (!validateRgbaBitmapLayout(info) || row >= info.height) return false;
    return checkedMultiply(
        static_cast<std::size_t>(row),
        static_cast<std::size_t>(info.stride),
        out);
}

constexpr bool checkedPixelOffset(
    const AndroidBitmapInfo& info,
    std::uint32_t row,
    std::uint32_t column,
    std::size_t& out) noexcept {
    if (column >= info.width) return false;
    std::size_t rowOffset = 0;
    std::size_t columnOffset = 0;
    if (!checkedRowOffset(info, row, rowOffset) ||
        !checkedMultiply(static_cast<std::size_t>(column), 4U, columnOffset) ||
        !checkedAdd(rowOffset, columnOffset, out)) {
        return false;
    }
    std::size_t byteCount = 0;
    std::size_t endOffset = 0;
    return checkedBitmapByteCount(info, byteCount) &&
           checkedAdd(out, 4U, endOffset) &&
           endOffset <= byteCount;
}

template <typename Byte>
inline bool checkedRowPointer(
    Byte* base,
    const AndroidBitmapInfo& info,
    std::uint32_t row,
    Byte*& out) noexcept {
    if (base == nullptr) return false;
    std::size_t offset = 0;
    if (!checkedRowOffset(info, row, offset)) return false;
    out = base + offset;
    return true;
}

template <typename Byte>
inline bool checkedPixelPointer(
    Byte* base,
    const AndroidBitmapInfo& info,
    std::uint32_t row,
    std::uint32_t column,
    Byte*& out) noexcept {
    if (base == nullptr) return false;
    std::size_t offset = 0;
    if (!checkedPixelOffset(info, row, column, offset)) return false;
    out = base + offset;
    return true;
}

inline bool dimensionsMatch(
    const AndroidBitmapInfo& left,
    const AndroidBitmapInfo& right) noexcept {
    return left.width == right.width && left.height == right.height;
}

inline bool isSameBitmap(JNIEnv* env, jobject left, jobject right) noexcept {
    return env != nullptr && left != nullptr && right != nullptr &&
           env->IsSameObject(left, right) == JNI_TRUE;
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

    void unlock() noexcept {
        if (locked_) AndroidBitmap_unlockPixels(env_, bitmap_);
        locked_ = false;
        pixels = nullptr;
    }

private:
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

template <typename Lock, typename... Rest>
constexpr int lockAll(Lock& first, Rest&... rest) noexcept {
    static_assert((std::is_same_v<Lock, Rest> && ...));
    Lock* ordered[] = {&first, &rest...};
    std::size_t lockedCount = 0;
    for (Lock* lock : ordered) {
        if (lock->lock() != 0) {
            const int failedIndex = static_cast<int>(lockedCount);
            while (lockedCount > 0) ordered[--lockedCount]->unlock();
            return failedIndex;
        }
        ++lockedCount;
    }
    return -1;
}

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
constexpr bool pixelOffsetWorks() {
    AndroidBitmapInfo info{};
    info.width = 3;
    info.height = 2;
    info.stride = 16;
    info.format = ANDROID_BITMAP_FORMAT_RGBA_8888;
    std::size_t out = 0;
    return checkedPixelOffset(info, 1, 2, out) && out == 24;
}
struct FakeLock {
    bool fail = false;
    int lockCount = 0;
    int unlockCount = 0;
    constexpr int lock() noexcept {
        ++lockCount;
        return fail ? -1 : 0;
    }
    constexpr void unlock() noexcept { ++unlockCount; }
};
constexpr bool multiLockRollbackWorks() {
    FakeLock first{};
    FakeLock second{true};
    FakeLock third{};
    return lockAll(first, second, third) == 1 &&
           first.lockCount == 1 &&
           first.unlockCount == 1 &&
           second.lockCount == 1 &&
           third.lockCount == 0;
}
static_assert(multiplyWorks());
static_assert(multiplyRejectsOverflow());
static_assert(addRejectsOverflow());
static_assert(pixelOffsetWorks());
static_assert(multiLockRollbackWorks());
}  // namespace kepler_native::compile_time_tests
