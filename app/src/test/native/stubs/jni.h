#pragma once

#include <cstdint>

using jint = std::int32_t;
using jlong = std::int64_t;
using jfloat = float;
using jboolean = std::uint8_t;
using jobject = void*;
using jstring = void*;
using jclass = void*;

#define JNIEXPORT
#define JNICALL
#define JNI_TRUE static_cast<jboolean>(1)
#define JNI_FALSE static_cast<jboolean>(0)

struct JNIEnv {
    jboolean IsSameObject(jobject left, jobject right) {
        return left == right ? JNI_TRUE : JNI_FALSE;
    }
    const char* GetStringUTFChars(jstring, jboolean*) { return nullptr; }
    void ReleaseStringUTFChars(jstring, const char*) {}
    jstring NewStringUTF(const char*) { return nullptr; }
};
