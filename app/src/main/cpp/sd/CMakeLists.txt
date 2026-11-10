# Builds libsd_jni.so (stable-diffusion.cpp + JNI bridge, CPU) for app/src/main/jniLibs.
# Not wired into Gradle on purpose: the app ships the prebuilt .so. Rebuild with scripts/build-sd-jni.sh.
cmake_minimum_required(VERSION 3.14)
project(sd_jni C CXX)

set(SD_CPP_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/stable-diffusion.cpp" CACHE PATH "stable-diffusion.cpp checkout")

# Everything static and hidden behind the JNI entry points, so this ggml can't collide with llama.cpp's libggml*.so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # Same baseline as whisper: dotprod + fp16 run on practically every arm64 phone since 2018; no i8mm (missing on A78/A55).
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_subdirectory(${SD_CPP_DIR} sd_build EXCLUDE_FROM_ALL)

add_library(sd_jni SHARED sd_jni.cpp)
target_link_libraries(sd_jni PRIVATE stable-diffusion log)
target_compile_options(sd_jni PRIVATE -O3)
target_link_options(sd_jni PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL           # only the JNI entry points are exported
    -Wl,-z,max-page-size=16384       # 16 KB page alignment (required on Android 15+ 16 KB devices)
)
