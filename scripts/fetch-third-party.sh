#!/usr/bin/env bash
# Fetches the native sources the app builds against into third_party/ (not stored in this repo),
# at the exact versions the app was tested with, and applies Fossistant's llama.cpp patch.
# Usage: ./scripts/fetch-third-party.sh   (needs git; about 600 MB)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TP="$ROOT/third_party"
mkdir -p "$TP"

fetch() { # name url commit
  local dir="$TP/$1"
  if [ -d "$dir/.git" ] || [ -f "$dir/CMakeLists.txt" ]; then
    echo "== $1 already present, skipping"
    return
  fi
  echo "== $1 @ $3"
  git init -q "$dir"
  git -C "$dir" remote add origin "$2"
  git -C "$dir" fetch -q --depth 1 origin "$3"
  git -C "$dir" checkout -q FETCH_HEAD
}

fetch llama.cpp            https://github.com/ggml-org/llama.cpp.git                 5047748f114564fcf271e6a648f60e2d2eae6264
fetch whisper.cpp          https://github.com/ggml-org/whisper.cpp.git               d1be6fde11ac6e0407606b4e42fe72d34add8037
fetch Vulkan-Headers       https://github.com/KhronosGroup/Vulkan-Headers.git        73814d006638aa00bd553215b054f0f02e457e22
fetch SPIRV-Headers        https://github.com/KhronosGroup/SPIRV-Headers.git         496543121ce6419f23d6fa5d7194ba66c36212d2

if [ ! -f "$TP/stable-diffusion.cpp/CMakeLists.txt" ]; then
  echo "== stable-diffusion.cpp @ c6dfa9d (ggml c57b71a)"
  git clone -q --filter=blob:none https://github.com/leejet/stable-diffusion.cpp.git "$TP/stable-diffusion.cpp"
  git -C "$TP/stable-diffusion.cpp" checkout -q c6dfa9d
  git -C "$TP/stable-diffusion.cpp" submodule update -q --init --recursive --depth 1
fi

# Fossistant's changes to llama.cpp (Vulkan on Android GPUs, multimodal helpers).
if git -C "$TP/llama.cpp" apply --check "$ROOT/scripts/patches/llama.cpp.patch" 2>/dev/null; then
  git -C "$TP/llama.cpp" apply "$ROOT/scripts/patches/llama.cpp.patch"
  echo "== applied llama.cpp.patch"
else
  echo "== llama.cpp.patch already applied (or llama.cpp was changed by hand)"
fi
echo "Done. Build with ./gradlew assembleSlimDebug"
