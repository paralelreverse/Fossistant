# Builds libwhisper_jni.so (whisper.cpp + JNI bridge) for app/src/main/jniLibs.
# Not wired into Gradle on purpose: the app ships the prebuilt .so, so normal builds don't need the
# whisper.cpp sources. Rebuild with scripts/build-whisper-jni.sh.
cmake_minimum_required(VERSION 3.14)
project(whisper_jni C CXX)

set(WHISPER_CPP_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/whisper.cpp" CACHE PATH "whisper.cpp checkout")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# ggml's own thread pool; avoids shipping an OpenMP runtime.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # dotprod + fp16 arithmetic: available on practically every arm64 phone since 2018 (Cortex-A55/A75+).
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

add_subdirectory(${WHISPER_CPP_DIR} whisper_build EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper log)
target_compile_options(whisper_jni PRIVATE -O3)
target_link_options(whisper_jni PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL           # only the JNI entry points are exported
    -Wl,-z,max-page-size=16384       # 16 KB page alignment (required on Android 15+ 16 KB devices)
)
