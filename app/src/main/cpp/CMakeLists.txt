cmake_minimum_required(VERSION 3.22.1)
project(kepler_photocore)

add_library(
    kepler_photocore
    SHARED
    native_bridge.cpp
    native_special_effects.cpp
    native_crop_transform.cpp
    native_flare_guard.cpp
    native_flare_mask.cpp
    native_selection_blend.cpp
    native_cancellation.cpp
    native_corrections_v2.cpp
    native_processing_algorithms.cpp
)

# Kepler ENN bridge over Samsung's public Exynos NDK v1 API. The vendored
# client stub (jniLibs) binds to the vendor-side ENN implementation at load
# time on devices that expose libenn_user.samsung_slsi.so.
add_library(
    enn_service_so
    SHARED
    IMPORTED
)

set_target_properties(
    enn_service_so
    PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libenn_public_api_ndk_v1.so
)

add_library(
    enn_kepler_jni
    SHARED
    enn_kepler_jni.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

 target_link_libraries(
    kepler_photocore
    ${log-lib}
    ${jnigraphics-lib}
)

target_link_libraries(
    enn_kepler_jni
    enn_service_so
    ${log-lib}
)
