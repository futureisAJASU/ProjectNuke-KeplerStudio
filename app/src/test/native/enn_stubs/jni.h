#pragma once

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

using jint = std::int32_t;
using jlong = std::int64_t;
using jsize = std::int32_t;
using jbyte = std::int8_t;
using jboolean = std::uint8_t;
using jobject = void*;
using jstring = void*;
using jarray = void*;
using jbyteArray = void*;
using jintArray = void*;
using jlongArray = void*;

#define JNIEXPORT
#define JNICALL
#define JNI_ABORT 2
#define JNI_TRUE static_cast<jboolean>(1)
#define JNI_FALSE static_cast<jboolean>(0)

struct FakeString {
    std::string utf;
};

struct FakeByteArray {
    jsize length;
    std::vector<jbyte> values;

    explicit FakeByteArray(jsize size) : length(size), values(static_cast<size_t>(size)) {}
};

struct FakeLongArray {
    jsize length;
    std::vector<jlong> values;

    explicit FakeLongArray(jsize size) : length(size), values(static_cast<size_t>(size)) {}
};

struct FakeIntArray {
    jsize length;
    std::vector<jint> values;

    explicit FakeIntArray(jsize size) : length(size), values(static_cast<size_t>(size)) {}
};

struct JNIEnv {
    bool pending_exception = false;
    bool string_failure = false;
    bool string_failure_pending = false;
    int release_string_calls = 0;
    int new_long_array_calls = 0;

    bool ExceptionCheck() const { return pending_exception; }

    const char* GetStringUTFChars(jstring value, jboolean*) {
        if (string_failure) {
            pending_exception = string_failure_pending;
            return nullptr;
        }
        return static_cast<FakeString*>(value)->utf.c_str();
    }

    void ReleaseStringUTFChars(jstring, const char*) { ++release_string_calls; }

    jsize GetArrayLength(jarray value) {
        return static_cast<FakeByteArray*>(value)->length;
    }

    jbyte* GetByteArrayElements(jbyteArray value, jboolean*) {
        return static_cast<FakeByteArray*>(value)->values.data();
    }

    void ReleaseByteArrayElements(jbyteArray, jbyte*, jint) {}

    jlongArray NewLongArray(jsize size) {
        ++new_long_array_calls;
        return reinterpret_cast<jlongArray>(new FakeLongArray(size));
    }

    void SetLongArrayRegion(jlongArray value, jsize start, jsize count, const jlong* source) {
        auto* array = reinterpret_cast<FakeLongArray*>(value);
        std::memcpy(array->values.data() + start, source, static_cast<size_t>(count) * sizeof(jlong));
    }

    jintArray NewIntArray(jsize size) {
        return reinterpret_cast<jintArray>(new FakeIntArray(size));
    }

    void SetIntArrayRegion(jintArray value, jsize start, jsize count, const jint* source) {
        auto* array = reinterpret_cast<FakeIntArray*>(value);
        std::memcpy(array->values.data() + start, source, static_cast<size_t>(count) * sizeof(jint));
    }

    jstring NewStringUTF(const char* value) {
        auto* string = new FakeString();
        string->utf = value == nullptr ? "" : value;
        return reinterpret_cast<jstring>(string);
    }
};
