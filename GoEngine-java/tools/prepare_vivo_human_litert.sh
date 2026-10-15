#!/usr/bin/env bash
# Explicit opt-in staging for TEST-ONLY b18 Human LiteRT graphs.
# Never runs from a standard Android build. Existing 10b assets untouched.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="vivo-human-litert-9-13-19-experimental-v0"
URL="https://github.com/rstg00po54/go/releases/download/$TAG"
DEST="$ROOT/app/src/main/assets/engine"
mkdir -p "$DEST"
case "${1:---size}" in
  --all) SIZES=(9 13 19) ;;
  --size) SIZES=("${2:-9}") ;;
  *) echo "Usage: bash tools/prepare_vivo_human_litert.sh --size 9|13|19 OR --all" >&2; exit 2 ;;
esac
for size in "${SIZES[@]}"; do
  case "$size" in 9|13|19) ;; *) echo "Unsupported board size: $size" >&2; exit 2;; esac
  stem="katago_human_${size}x${size}"
  graphTmp="$(mktemp "$DEST/.${stem}.XXXXXX")"
  manifestTmp="$(mktemp "$DEST/.${stem}.json.XXXXXX")"
  trap 'rm -f -- "$graphTmp" "$manifestTmp"' EXIT
  echo "Downloading experimental b18 Human SL board=$size (~100 MiB graph)"
  curl --fail --location --retry 3 --connect-timeout 15 -o "$graphTmp" "$URL/${stem}.tflite"
  curl --fail --location --retry 3 --connect-timeout 15 -o "$manifestTmp" "$URL/${stem}.json"
  python3 - "$graphTmp" "$manifestTmp" "$size" <<'PY'
import hashlib, json, pathlib, sys
graph, manifest, size = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), int(sys.argv[3])
doc = json.loads(manifest.read_text())
assert doc['modelType'] == 'human' and doc['size'] == size
assert doc['weights'] == 'b18c384nbt-humanv0'
assert doc['metaEncoderVersion'] == 1 and doc['numInputMetaChannels'] == 192
assert doc['numInputChannels'] == 22 and doc['numInputGlobalChannels'] == 19
assert doc['numPolicyChannels'] in (1,2,4) and doc['numScoreValueChannels'] == 6
assert doc['cpu_inference'] == 'PASS'
assert set(doc['inputOrder']) == {'InputSpatial','InputGlobal','InputMeta','InputMask'}
assert set(doc['outputOrder']) == {'OutputPolicyPass','OutputPolicy','OutputValue',
                                  'OutputScoreValue','OutputOwnership'}
h = hashlib.sha256()
with graph.open('rb') as f:
    for data in iter(lambda: f.read(1<<20), b''): h.update(data)
assert h.hexdigest() == doc['graphSha256'], 'Human graph checksum mismatch'
print('HUMAN_GRAPH_VERIFIED board=%s bytes=%s sha256=%s' % (size, graph.stat().st_size, h.hexdigest()))
PY
  mv -f "$graphTmp" "$DEST/${stem}.tflite"
  mv -f "$manifestTmp" "$DEST/${stem}.json"
  trap - EXIT
done
echo "HUMAN_EXPERIMENTAL_ASSETS_READY dest=$DEST sizes=${SIZES[*]}"
