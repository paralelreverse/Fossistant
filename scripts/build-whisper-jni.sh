#!/usr/bin/env bash
# Rebuilds app/src/main/jniLibs/arm64-v8a/libwhisper_jni.so from app/src/main/cpp/whisper_jni.cpp and
# whisper.cpp with the Android NDK.
#
# Usage: scripts/build-whisper-jni.sh
# Env overrides: ANDROID_SDK (default: %LOCALAPPDATA%/Android/Sdk), NDK_VERSION, CMAKE_VERSION,
#                WHISPER_TAG (used only when third_party/whisper.cpp is missing).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_SDK="${ANDROID_SDK:-${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}"
NDK_VERSION="${NDK_VERSION:-27.0.12077973}"
CMAKE_VERSION="${CMAKE_VERSION:-3.22.1}"
WHISPER_TAG="${WHISPER_TAG:-v1.9.5}"
ABI=arm64-v8a

# The NDK's CMake toolchain breaks on paths with spaces (e.g. "C:/Users/HP ELITEBOOK"); use 8.3 short paths on Windows.
# CMake may still rewrite clang++.exe to its 8.3 name (CLANG_~1.EXE), which makes clang act as the C driver and
# drop the C++ runtime at link time, hence --driver-mode=g++ below.
shortpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m -s "$1"; else echo "$1"; fi; }
ROOT="$(shortpath "$ROOT")"
ANDROID_SDK="$(shortpath "$ANDROID_SDK")"

NDK="$ANDROID_SDK/ndk/$NDK_VERSION"
CMAKE_BIN="$ANDROID_SDK/cmake/$CMAKE_VERSION/bin"
WHISPER_DIR="$ROOT/third_party/whisper.cpp"
BUILD_DIR="$ROOT/build/whisper-jni-$ABI"
OUT="$ROOT/app/src/main/jniLibs/$ABI/libwhisper_jni.so"

if [ ! -d "$WHISPER_DIR" ]; then
    git clone --depth 1 --branch "$WHISPER_TAG" https://github.com/ggml-org/whisper.cpp.git "$WHISPER_DIR"
fi

"$CMAKE_BIN/cmake" -S "$ROOT/app/src/main/cpp" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-29 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release     -DCMAKE_CXX_FLAGS=--driver-mode=g++ \
    -DWHISPER_CPP_DIR="$WHISPER_DIR"
"$CMAKE_BIN/cmake" --build "$BUILD_DIR" --target whisper_jni

STRIP="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-strip* | head -n 1)"
"$STRIP" --strip-unneeded -o "$OUT" "$BUILD_DIR/libwhisper_jni.so"
echo "Wrote $OUT ($(wc -c < "$OUT") bytes, whisper.cpp $(git -C "$WHISPER_DIR" describe --tags 2>/dev/null || echo unknown))"
