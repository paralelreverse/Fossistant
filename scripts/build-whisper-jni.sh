#!/usr/bin/env bash
# Rebuilds app/src/main/jniLibs/arm64-v8a/libwhisper_jni.so from app/src/main/cpp/whisper_jni.cpp and
# whisper.cpp (Vulkan GPU backend + CPU fallback) with the Android NDK.
#
# Usage: scripts/build-whisper-jni.sh
# Env overrides: ANDROID_SDK (default: %LOCALAPPDATA%/Android/Sdk), NDK_VERSION, CMAKE_VERSION,
#                WHISPER_TAG, VULKAN_HEADERS_TAG, SPIRV_HEADERS_TAG.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_SDK="${ANDROID_SDK:-${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}"
NDK_VERSION="${NDK_VERSION:-27.0.12077973}"
CMAKE_VERSION="${CMAKE_VERSION:-3.22.1}"
WHISPER_TAG="${WHISPER_TAG:-v1.9.5}"
VULKAN_HEADERS_TAG="${VULKAN_HEADERS_TAG:-v1.4.366}"
SPIRV_HEADERS_TAG="${SPIRV_HEADERS_TAG:-vulkan-sdk-1.4.363.0}"
ABI=arm64-v8a
API=29

# Paths with spaces ("C:/Users/Jane Doe") break the Vulkan shader build: a nested CMake run splits them at the
# space. On Windows the project and the SDK are mapped to temporary drive letters (subst, removed on exit).
# CMake may also turn clang++.exe into CLANG_~1.EXE (C driver), hence --driver-mode=g++ below.
SUBST_DRIVES=()
map_drive() {
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

NDK="$ANDROID_SDK/ndk/$NDK_VERSION"
CMAKE_BIN="$ANDROID_SDK/cmake/$CMAKE_VERSION/bin"
export PATH="$( (command -v cygpath >/dev/null 2>&1 && cygpath -u "$CMAKE_BIN") || echo "$CMAKE_BIN"):$PATH"
if command -v g++ >/dev/null 2>&1; then
    export PATH="$(dirname "$(command -v g++)"):$PATH"
fi
WHISPER_DIR="$ROOT/third_party/whisper.cpp"
VK_DIR="$ROOT/third_party/Vulkan-Headers"
SPIRV_DIR="$ROOT/third_party/SPIRV-Headers"
SPIRV_PREFIX="$ROOT/build/spirv-headers-install"
BUILD_DIR="$ROOT/build/whisper-jni-$ABI"
OUT="$ROOT/app/src/main/jniLibs/$ABI/libwhisper_jni.so"

[ -d "$WHISPER_DIR" ] || git clone --depth 1 --branch "$WHISPER_TAG" https://github.com/ggml-org/whisper.cpp.git "$WHISPER_DIR"
[ -d "$VK_DIR" ] || git clone --depth 1 --branch "$VULKAN_HEADERS_TAG" https://github.com/KhronosGroup/Vulkan-Headers.git "$VK_DIR"
[ -d "$SPIRV_DIR" ] || git clone --depth 1 --branch "$SPIRV_HEADERS_TAG" https://github.com/KhronosGroup/SPIRV-Headers.git "$SPIRV_DIR"

if [ ! -d "$SPIRV_PREFIX/share/cmake/SPIRV-Headers" ]; then
    "$CMAKE_BIN/cmake" -S "$SPIRV_DIR" -B "$ROOT/build/spirv-headers" -G Ninja -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
        -DCMAKE_INSTALL_PREFIX="$SPIRV_PREFIX" -DSPIRV_HEADERS_ENABLE_TESTS=OFF -DSPIRV_HEADERS_ENABLE_INSTALL=ON
    "$CMAKE_BIN/cmake" --install "$ROOT/build/spirv-headers"
fi

HOST_TAG="$(ls "$NDK/toolchains/llvm/prebuilt" | head -n 1)"
GLSLC="$(ls "$NDK"/shader-tools/*/glslc* | head -n 1)"
VULKAN_LIB="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/sysroot/usr/lib/aarch64-linux-android/$API/libvulkan.so"

"$CMAKE_BIN/cmake" -S "$ROOT/app/src/main/cpp" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_FLAGS="--driver-mode=g++ -I$VK_DIR/include -I$SPIRV_PREFIX/include" \
    -DWHISPER_CPP_DIR="$WHISPER_DIR" \
    -DVulkan_INCLUDE_DIR="$VK_DIR/include" \
    -DVulkan_LIBRARY="$VULKAN_LIB" \
    -DVulkan_GLSLC_EXECUTABLE="$GLSLC" \
    -DSPIRV-Headers_DIR="$SPIRV_PREFIX/share/cmake/SPIRV-Headers"
"$CMAKE_BIN/cmake" --build "$BUILD_DIR" --target whisper_jni

STRIP="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip* | head -n 1)"
"$STRIP" --strip-unneeded -o "$OUT" "$BUILD_DIR/libwhisper_jni.so"
echo "Wrote $OUT ($(wc -c < "$OUT") bytes, whisper.cpp $(git -C "$WHISPER_DIR" describe --tags 2>/dev/null || echo unknown))"
