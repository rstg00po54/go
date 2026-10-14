#!/usr/bin/env bash
# Stage three independently validated KataGo LiteRT models into Android assets.
# GitHub release is created only after all ONNX->TFLite conversions + CPU smoke pass.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="vivo-litert-10b-9-13-19-v1"
URL="https://github.com/rstg00po54/go/releases/download/$TAG"
DEST="$ROOT/app/src/main/assets/engine"
mkdir -p "$DEST"
for size in 9 13 19; do
    name="katago_10b_${size}x${size}"
    for ext in tflite json; do
        tmp="$DEST/$name.$ext.partial"
        echo "Downloading $name.$ext"
        curl -fL --retry 3 -o "$tmp" "$URL/$name.$ext"
        test -s "$tmp"
        mv -f "$tmp" "$DEST/$name.$ext"
    done
done
echo "Verified per-size LiteRT model assets prepared in $DEST"
echo "Run: bash build.sh --apk-only -PenableKataGoNpuJni=false"
