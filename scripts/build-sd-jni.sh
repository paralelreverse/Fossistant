#!/usr/bin/env bash
# Rebuilds app/src/main/jniLibs/arm64-v8a/libsd_jni.so from app/src/main/cpp/sd/sd_jni.cpp and
# stable-diffusion.cpp (CPU) with the Android NDK.
#
# Usage: scripts/build-sd-jni.sh
# Env overrides: ANDROID_SDK, NDK_VERSION, CMAKE_VERSION, SD_CPP_REF (used only when third_party/stable-diffusion.cpp
#                is missing), BUILD_JOBS.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_SDK="${ANDROID_SDK:-${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}"
NDK_VERSION="${NDK_VERSION:-27.0.12077973}"
CMAKE_VERSION="${CMAKE_VERSION:-3.22.1}"
SD_CPP_REF="${SD_CPP_REF:-c6dfa9d}"
ABI=arm64-v8a

# The NDK's CMake toolchain breaks on paths with spaces (e.g. "C:/Users/HP ELITEBOOK"); use 8.3 short paths on Windows.
# --driver-mode=g++: CMake may turn clang++.exe into CLANG_~1.EXE, which then acts as the C driver.
shortpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m -s "$1"; else echo "$1"; fi; }
ROOT="$(shortpath "$ROOT")"
ANDROID_SDK="$(shortpath "$ANDROID_SDK")"

NDK="$ANDROID_SDK/ndk/$NDK_VERSION"
CMAKE_BIN="$ANDROID_SDK/cmake/$CMAKE_VERSION/bin"
SD_DIR="$ROOT/third_party/stable-diffusion.cpp"
BUILD_DIR="$ROOT/build/sd-jni-$ABI"
OUT="$ROOT/app/src/main/jniLibs/$ABI/libsd_jni.so"

if [ ! -d "$SD_DIR" ]; then
    git clone --filter=blob:none https://github.com/leejet/stable-diffusion.cpp.git "$SD_DIR"
    git -C "$SD_DIR" checkout "$SD_CPP_REF"
    git -C "$SD_DIR" submodule update --init --depth 1 ggml
fi

"$CMAKE_BIN/cmake" -S "$ROOT/app/src/main/cpp/sd" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-29 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_FLAGS=--driver-mode=g++ \
    -DSD_CPP_DIR="$SD_DIR"
# Few parallel jobs: stable-diffusion.cpp has some very large translation units and the PC has 16 GB.
"$CMAKE_BIN/cmake" --build "$BUILD_DIR" --target sd_jni --parallel "${BUILD_JOBS:-3}"

STRIP="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip* | head -n 1)"
"$STRIP" --strip-unneeded -o "$OUT" "$BUILD_DIR/libsd_jni.so"
echo "Wrote $OUT ($(wc -c < "$OUT") bytes, stable-diffusion.cpp $SD_CPP_REF)"
