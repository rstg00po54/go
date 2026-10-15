#!/usr/bin/env bash
# Opt-in 11/15 MediaTek 10b graphs. Independent from stable 9/13/19 resources.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/engine"
URL="https://github.com/rstg00po54/go/releases/download/vivo-litert-10b-11-15-experimental-v1"
mkdir -p "$DEST"
declare -A HASH=(
  [katago_10b_11x11.json]=82f59c137d9c955bed2d823568d511686a63165c0d799fba6977cd47f839b045
  [katago_10b_11x11.tflite]=220c0ca68df0ff0a70524eee089f9d97954ba9d5de2a4dc45999f77a9bda4190
  [katago_10b_15x15.json]=6224bae2a0b8466f7583c99f8b682fc44510416a9cfea1056223f43752e7abd9
  [katago_10b_15x15.tflite]=e8345397350ffd6575c4a743413980290a07dd09d4a364c32f5c86e087e75715
)
for size in 11 15; do
  for ext in tflite json; do
    name="katago_10b_${size}x${size}.${ext}"
    dest="$DEST/$name"
    if [[ -f "$dest" ]] && printf '%s  %s\n' "${HASH[$name]}" "$dest" | sha256sum -c --status; then
      echo "MODEL_CACHE_OK $name"
      continue
    fi
    tmp="${dest}.partial"
    # Retain partial transfer across connection resets; wget -c resumes when server supports ranges.
    wget -c --tries=30 --waitretry=3 --timeout=60 -O "$tmp" "$URL/$name"
    printf '%s  %s\n' "${HASH[$name]}" "$tmp" | sha256sum -c
    mv -f "$tmp" "$dest"
  done
done
echo "VIVO_LITERT_11_15_READY"
