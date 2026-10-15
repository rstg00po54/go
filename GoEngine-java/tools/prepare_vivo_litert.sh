#!/usr/bin/env bash
# Stage pinned KataGo LiteRT models. Hash-verified and safe to rerun.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="vivo-litert-10b-9-13-19-v1"
URL="https://github.com/rstg00po54/go/releases/download/$TAG"
DEST="$ROOT/app/src/main/assets/engine"
mkdir -p "$DEST"
declare -A SHA256=(
  [katago_10b_9x9.tflite]=eaf37768b48021e579e38f9bae0efbcb17f5c5a84d80d288430ee11784f1f039
  [katago_10b_9x9.json]=4ed35051e22638a7c2ed43db2c022a5ceefb75f9c5b10d3704f7b05688dc2b2a
  [katago_10b_13x13.tflite]=646788f1928fb39450486b5033eee7f5d84eb48f21064f3791c46dd9c74e5658
  [katago_10b_13x13.json]=c6ad7043dc85d3cdd64fb7523ff03b9ded52c032d5ff3a410391763bce37739f
  [katago_10b_19x19.tflite]=9bbab05d27e2fa0b9cac64dcb4d81f7121f6731d3e0176ba08e59041f0382181
  [katago_10b_19x19.json]=5ae0ccacaa62eb74060ef33b74db3a58e1f7dd7590c36bf055c5d42135aeafe1
)
# Cached v1 manifests without output-scale metadata are supported only
# for their exact legacy checksums; new downloads use scale-aware manifests.
declare -A LEGACY_JSON_SHA256=(
  [katago_10b_9x9.json]=f53e2d36342416d325a39a7147e4b9e6e59f4d62825e24725f4fb398265f7ec3
  [katago_10b_13x13.json]=bb089a6ce61b1b9d32db0fb20db3afd8a4763390209d68962d3967d78cbda097
  [katago_10b_19x19.json]=1447d7a005b7920de1e1f80fa75fb6029d6baee42380689b71eef362ca8acf69
)
fetch_verified() {
  local name="$1" target="$DEST/$1" expected="${SHA256[$1]}"
  if [[ -s "$target" ]]; then
    if printf '%s  %s\n' "$expected" "$target" | sha256sum -c --status; then
      echo "MODEL_CACHE_OK $name ($(wc -c < "$target") bytes)"
      return 0
    fi
    if [[ -n "${LEGACY_JSON_SHA256[$name]:-}" ]] &&
       printf '%s  %s\n' "${LEGACY_JSON_SHA256[$name]}" "$target" | sha256sum -c --status; then
      echo "MODEL_LEGACY_CACHE_OK $name ($(wc -c < "$target") bytes)"
      return 0
    fi
  fi
  local tmp
  tmp="$(mktemp "$DEST/.${name}.XXXXXX")"
  echo "MODEL_DOWNLOAD $name"
  if ! curl --fail --location --retry 3 --retry-delay 2 --connect-timeout 15 \
      --output "$tmp" "$URL/$name"; then
    rm -f -- "$tmp"
    echo "ERROR: model download failed: $URL/$name" >&2
    return 1
  fi
  if ! printf '%s  %s\n' "$expected" "$tmp" | sha256sum -c --status; then
    rm -f -- "$tmp"
    echo "ERROR: model SHA256 mismatch: $name" >&2
    return 1
  fi
  mv -f -- "$tmp" "$target"
  echo "MODEL_READY $name ($(wc -c < "$target") bytes)"
}
for size in 9 13 19; do
  for ext in tflite json; do
    fetch_verified "katago_10b_${size}x${size}.${ext}"
  done
done
echo "VIVO_LITERT_MODELS_READY count=6 dest=$DEST"
