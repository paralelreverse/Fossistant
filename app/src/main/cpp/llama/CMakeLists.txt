# Builds libllama_jni.so (llama.cpp with the Vulkan GPU backend + CPU fallback) for app/src/main/jniLibs.
# Not wired into Gradle: the app ships the prebuilt .so. Rebuild with scripts/build-llama-jni.sh.
cmake_minimum_required(VERSION 3.14)
project(llama_jni C CXX)

set(LLAMA_CPP_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp" CACHE PATH "llama.cpp checkout")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# Vulkan: the GPU path that works on Mali (Pixel) and Adreno, with kernels for Bonsai's ternary Q2_0.
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # No "+fp16": with FP16 vector arithmetic ggml accumulates f16 dot products in half precision, which overflows
    # on Bonsai 2 (Hadamard-rotated activations) and turned its output into garbage on phones. Dot-product int8
    # kernels are exact, so they stay.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
endif()

add_subdirectory(${LLAMA_CPP_DIR} llama_build EXCLUDE_FROM_ALL)

add_library(llama_jni SHARED llama_jni.cpp)
target_link_libraries(llama_jni PRIVATE llama ggml log android)
target_compile_options(llama_jni PRIVATE -O3)
target_link_options(llama_jni PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL           # only the JNI entry points are exported (whisper_jni has its own ggml)
    -Wl,-z,max-page-size=16384       # 16 KB page alignment (required on Android 15+ 16 KB devices)
)
