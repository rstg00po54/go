#!/usr/bin/env bash
# Convert the ORIGINAL KataGo Human SL b18 weights to an experimental RK3588 RKNN.
# Never overwrites the 10b RKNN models or the Android application's Human assets.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SIZE="${1:-9}"
case "$SIZE" in 9|11|13|15|19) ;; *) echo "Usage: $0 [9|11|13|15|19]" >&2; exit 2;; esac
MODEL="${HUMAN_B18_MODEL:-$ROOT/app/src/main/assets/engine/b18c384nbt-humanv0.bin}"
KATAGO="${KATAGO_DUMPONNX_BIN:-}"
OUT="${HUMAN_RKNN_OUT:-$ROOT/build/rk3588_human/$SIZE}"
mkdir -p "$OUT"
if [[ ! -s "$MODEL" ]]; then
  echo "Missing original Human b18 weights: $MODEL" >&2
  echo "Set HUMAN_B18_MODEL to the original b18c384nbt-humanv0.bin" >&2
  exit 1
fi
if [[ -z "$KATAGO" ]]; then
  echo "Set KATAGO_DUMPONNX_BIN to KataGo 1.18+ executable with dumponnx support." >&2
  echo "Existing GitHub workflow vivo_human_mish_graph_optimization.yml shows the known-working 1.18.1 release." >&2
  exit 1
fi
if [[ ! -x "$KATAGO" ]]; then echo "Not executable: $KATAGO" >&2; exit 1; fi
ORIGINAL="$OUT/human_${SIZE}_original.onnx"
OPTIMIZED="$OUT/human_${SIZE}_mish.onnx"
"$KATAGO" dumponnx -model "$MODEL" -out "$ORIGINAL" -nn-x-len "$SIZE" -nn-y-len "$SIZE"
python3 "$ROOT/tools/optimize_human_mish_onnx.py" --input "$ORIGINAL" \
  --output "$OPTIMIZED" --expected 120 --report "$OUT/mish_rewrite.json"
python3 "$ROOT/tools/check_human_mish_onnx_parity.py" --original "$ORIGINAL" \
  --rewritten "$OPTIMIZED" --size "$SIZE" --report "$OUT/cpu_parity.json"
python3 "$ROOT/tools/convert_human_onnx_to_rknn.py" --onnx "$OPTIMIZED" \
  --output "$OUT/katago_human_${SIZE}x${SIZE}.rknn" --size "$SIZE"
echo "EXPERIMENTAL_HUMAN_RKNN_BUILT $OUT/katago_human_${SIZE}x${SIZE}.rknn"
echo "NOT VERIFIED: RK3588 runtime execution, 5-head parity, rank-specific accuracy, or speed."
