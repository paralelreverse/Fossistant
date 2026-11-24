#!/usr/bin/env bash
# Fetches the native sources the app builds against into third_party/ (not stored in this repo),
# at the exact versions the app was tested with.
# Usage: ./scripts/fetch-third-party.sh   (needs git)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TP="$ROOT/third_party"
mkdir -p "$TP"

fetch() { # name url ref
  local dir="$TP/$1"
  if [ -d "$dir/.git" ] || [ -f "$dir/CMakeLists.txt" ] || [ -d "$dir/include" ]; then
    echo "== $1 already present, skipping"
    return
  fi
  echo "== $1 @ $3"
  git init -q "$dir"
  git -C "$dir" remote add origin "$2"
  git -C "$dir" fetch -q --depth 1 origin "$3"
  git -C "$dir" checkout -q FETCH_HEAD
}

fetch whisper.cpp          https://github.com/ggml-org/whisper.cpp.git               d1be6fde11ac6e0407606b4e42fe72d34add8037
fetch Vulkan-Headers       https://github.com/KhronosGroup/Vulkan-Headers.git        v1.4.366
fetch SPIRV-Headers        https://github.com/KhronosGroup/SPIRV-Headers.git         vulkan-sdk-1.4.363.0

if [ ! -f "$TP/stable-diffusion.cpp/CMakeLists.txt" ]; then
  echo "== stable-diffusion.cpp @ c6dfa9d (ggml c57b71a)"
  git clone -q --filter=blob:none https://github.com/leejet/stable-diffusion.cpp.git "$TP/stable-diffusion.cpp"
  git -C "$TP/stable-diffusion.cpp" checkout -q c6dfa9d
  git -C "$TP/stable-diffusion.cpp" submodule update -q --init --recursive --depth 1
fi

echo "Done. Build with ./gradlew assembleSlimDebug"
