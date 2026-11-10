#!/usr/bin/env bash
# Rebuilds app/src/main/jniLibs/arm64-v8a/libllama_jni.so from app/src/main/cpp/llama/llama_jni.cpp and
# llama.cpp (Vulkan GPU backend + CPU) with the Android NDK.
#
# Usage: scripts/build-llama-jni.sh
# Env overrides: ANDROID_SDK, NDK_VERSION, CMAKE_VERSION, LLAMA_REF, VULKAN_HEADERS_TAG.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_SDK="${ANDROID_SDK:-${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}"
NDK_VERSION="${NDK_VERSION:-27.0.12077973}"
CMAKE_VERSION="${CMAKE_VERSION:-3.22.1}"
# Needs ggml-org/llama.cpp#30106 (Vulkan small-matmul fix for subgroup-size-16 GPUs such as Mali-G715): before it,
# prompts over ~32 tokens gave wrong results on Pixel/Mali phones.
LLAMA_REF="${LLAMA_REF:-fa3645f}"
VULKAN_HEADERS_TAG="${VULKAN_HEADERS_TAG:-v1.4.366}"
SPIRV_HEADERS_TAG="${SPIRV_HEADERS_TAG:-vulkan-sdk-1.4.363.0}"
ABI=arm64-v8a
API=29

# Paths with spaces ("C:/Users/HP ELITEBOOK") break the Vulkan shader build: a nested CMake run splits them at the
# space. On Windows the project and the SDK are mapped to temporary drive letters (subst, removed on exit).
# CMake may also turn clang++.exe into CLANG_~1.EXE (C driver), hence --driver-mode=g++ below.
SUBST_DRIVES=()
map_drive() { # map_drive VAR: rewrites $VAR to a space-free path (runs in this shell so cleanup sees the drive)
    local var="$1" path="${!1}"
    case "$path" in *" "*) ;; *) return ;; esac
    command -v cygpath >/dev/null 2>&1 || return
    local win letter
    win="$(cygpath -w "$path")"
    for letter in X Y W V U T; do
        if [ ! -e "/${letter,,}" ] && cmd //c subst "${letter}:" "$win" >/dev/null 2>&1 && [ -e "/${letter,,}" ]; then
            SUBST_DRIVES+=("$letter")
            printf -v "$var" '%s' "${letter}:"
            return
        fi
    done
}
cleanup_drives() { for letter in "${SUBST_DRIVES[@]}"; do cmd //c subst "${letter}:" //D >/dev/null 2>&1 || true; done; }
trap cleanup_drives EXIT
map_drive ROOT
map_drive ANDROID_SDK
echo "Building from ROOT=$ROOT SDK=$ANDROID_SDK"

NDK="$ANDROID_SDK/ndk/$NDK_VERSION"
CMAKE_BIN="$ANDROID_SDK/cmake/$CMAKE_VERSION/bin"
# The Vulkan shader generator is built for the host in a nested CMake run, which finds ninja via PATH.
export PATH="$( (command -v cygpath >/dev/null 2>&1 && cygpath -u "$CMAKE_BIN") || echo "$CMAKE_BIN"):$PATH"
LLAMA_DIR="$ROOT/third_party/llama.cpp"
VK_DIR="$ROOT/third_party/Vulkan-Headers"
SPIRV_DIR="$ROOT/third_party/SPIRV-Headers"
SPIRV_PREFIX="$ROOT/build/spirv-headers-install"
BUILD_DIR="$ROOT/build/llama-jni-$ABI"
OUT="$ROOT/app/src/main/jniLibs/$ABI/libllama_jni.so"

if [ ! -d "$LLAMA_DIR" ]; then
    git clone --filter=blob:none https://github.com/ggml-org/llama.cpp.git "$LLAMA_DIR"
    git -C "$LLAMA_DIR" checkout "$LLAMA_REF"
fi
[ -d "$VK_DIR" ] || git clone --depth 1 --branch "$VULKAN_HEADERS_TAG" https://github.com/KhronosGroup/Vulkan-Headers.git "$VK_DIR"
[ -d "$SPIRV_DIR" ] || git clone --depth 1 --branch "$SPIRV_HEADERS_TAG" https://github.com/KhronosGroup/SPIRV-Headers.git "$SPIRV_DIR"

# ggml-vulkan wants an installed SPIRV-Headers CMake package (header-only; built with the host toolchain).
if [ ! -d "$SPIRV_PREFIX/share/cmake/SPIRV-Headers" ]; then
    "$CMAKE_BIN/cmake" -S "$SPIRV_DIR" -B "$ROOT/build/spirv-headers" -G Ninja -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja"         -DCMAKE_INSTALL_PREFIX="$SPIRV_PREFIX" -DSPIRV_HEADERS_ENABLE_TESTS=OFF -DSPIRV_HEADERS_ENABLE_INSTALL=ON
    "$CMAKE_BIN/cmake" --install "$ROOT/build/spirv-headers"
fi

HOST_TAG="$(ls "$NDK/toolchains/llvm/prebuilt" | head -n 1)"
GLSLC="$(ls "$NDK"/shader-tools/*/glslc* | head -n 1)"
VULKAN_LIB="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/lib/aarch64-linux-android/$API/libvulkan.so"

"$CMAKE_BIN/cmake" -S "$ROOT/app/src/main/cpp/llama" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_FLAGS="--driver-mode=g++ -I$VK_DIR/include -I$SPIRV_PREFIX/include" \
    -DLLAMA_CPP_DIR="$LLAMA_DIR" \
    -DVulkan_INCLUDE_DIR="$VK_DIR/include" \
    -DVulkan_LIBRARY="$VULKAN_LIB" \
    -DVulkan_GLSLC_EXECUTABLE="$GLSLC"     -DSPIRV-Headers_DIR="$SPIRV_PREFIX/share/cmake/SPIRV-Headers"
"$CMAKE_BIN/cmake" --build "$BUILD_DIR" --target llama_jni

STRIP="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip* | head -n 1)"
"$STRIP" --strip-unneeded -o "$OUT" "$BUILD_DIR/libllama_jni.so"
echo "Wrote $OUT ($(wc -c < "$OUT") bytes, llama.cpp $(git -C "$LLAMA_DIR" describe --tags 2>/dev/null || echo unknown))"
