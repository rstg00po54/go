#!/usr/bin/env bash
# Stage the exact RKNN artifact and official Rockchip Android arm64 runtime.
# Rerunnable offline after MiniGoCpp's benchmark populated its cache.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="$ROOT/build/rknn_sdk"
MODEL_TARGET="$ROOT/app/src/main/assets/engine/katago_10b_19x19.rknn"
MODEL_CACHE="${RKNN_MODEL_CACHE:-$HOME/.cache/minigocpp/rknn_android/katago_10b_19x19.rknn}"
RUNTIME_CACHE="${RKNN_RUNTIME_CACHE:-$HOME/.cache/minigocpp/rknn_android/librknnrt.so}"
HEADER_CACHE="${RKNN_HEADER_CACHE:-$HOME/.cache/minigocpp/rknn_android/rknn_api.h}"
TAG=v2.3.2
BASE="https://raw.githubusercontent.com/airockchip/rknn-toolkit2/$TAG/rknpu2/runtime/Android/librknn_api"
mkdir -p "$SDK/include" "$ROOT/app/src/main/assets/engine"

fetch() {
    local url="$1" target="$2"
    if [[ -s "$target" ]]; then return; fi
    if command -v curl >/dev/null; then
        curl -fL --retry 3 "$url" -o "$target.tmp"
    elif command -v wget >/dev/null; then
        wget -q "$url" -O "$target.tmp"
    else
        echo "Need curl or wget to download RKNN artifacts" >&2; exit 1
    fi
    test -s "$target.tmp"
    mv "$target.tmp" "$target"
}

if [[ ! -s "$MODEL_TARGET" ]]; then
    if [[ -s "$MODEL_CACHE" ]]; then cp "$MODEL_CACHE" "$MODEL_TARGET"
    else fetch "https://github.com/rstg00po54/MiniGoCpp/releases/download/katago-10b-rknn-v1/katago_10b_19x19.rknn" "$MODEL_TARGET"
    fi
fi
if [[ ! -s "$SDK/librknnrt.so" ]]; then
    if [[ -s "$RUNTIME_CACHE" ]]; then cp "$RUNTIME_CACHE" "$SDK/librknnrt.so"
    else fetch "$BASE/arm64-v8a/librknnrt.so" "$SDK/librknnrt.so"
    fi
fi
if [[ ! -s "$SDK/include/rknn_api.h" ]]; then
    if [[ -s "$HEADER_CACHE" ]]; then cp "$HEADER_CACHE" "$SDK/include/rknn_api.h"
    else fetch "$BASE/include/rknn_api.h" "$SDK/include/rknn_api.h"
    fi
fi
# Opt in to download the size-specific models once. Normal builds remain
# usable offline with the previously validated 19x19 model.
if [[ "${RKNN_MULTISIZE:-0}" == 1 || "${1:-}" == "--multisize" ]]; then
    MULTI_URL="https://github.com/rstg00po54/MiniGoCpp/releases/download/katago-10b-rknn-multisize-v1"
    for size in 9 13; do
        filename="katago_10b_${size}x${size}.rknn"
        target="$ROOT/app/src/main/assets/engine/$filename"
        fetch "$MULTI_URL/$filename" "$target"
        fetch "$MULTI_URL/$filename.sha256" "$target.sha256"
        expected="$(awk 'NR==1 {print $1}' "$target.sha256")"
        actual="$(sha256sum "$target" | awk '{print $1}')"
        if [[ -z "$expected" || "$actual" != "$expected" ]]; then
            echo "ERROR: checksum mismatch for $filename; discarding invalid file" >&2
            rm -f "$target" "$target.sha256"
            exit 1
        fi
        echo "Verified RKNN ${size}x${size}: $(wc -c < "$target") bytes"
    done
fi
echo "RKNN staged: model=$(wc -c < "$MODEL_TARGET") bytes; runtime=$SDK/librknnrt.so"
echo "Use: bash tools/build_katago_from_source.sh rknnjni"
