#!/usr/bin/env bash
# Explicit opt-in staging for TEST-ONLY b18 Human LiteRT graphs.
# Never runs from a standard Android build. Existing 10b assets untouched.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="vivo-human-litert-9-13-19-experimental-v0"
URL="https://github.com/rstg00po54/go/releases/download/$TAG"
DEST="$ROOT/app/src/main/assets/engine"
# Release artifact digests independently recorded in the successful conversion
# workflow; never silently accept a different model/manifest pair.
declare -A VERIFIED_SHA=(
  [katago_human_9x9.json]=b4854654a3f28147cc82f591c6313ae6062386878d8f15b9675c18b8154607ee
  [katago_human_9x9.tflite]=460463f71eb61fb8e5fd48df0309775de40cde520874edc402e07106e5f45d09
  [katago_human_13x13.json]=3d879e51a52e6338cd55e7c234e4e18e164d03520e3d6fd38341f0923204c4a7
  [katago_human_13x13.tflite]=16acb00c0c5894471a90949ee17c5fb58c4950dccd4087a643f46182a27f239c
  [katago_human_19x19.json]=c42798cf54d5d7bf7c49fea0c98b8ca12f5f5ff6fb817c923d80cf76270350a8
  [katago_human_19x19.tflite]=d2f239d8206f0f65b632a767af1cad50606a6d637479d60a3dfc9bb391b54cf5
)
mkdir -p "$DEST"

# Explicitly opt into offline-compiled MT6993 b18 ONLY for a 9-line device
# diagnostic. Preserve all installed 10b graphs and their manifests.
if [[ "${1:-}" == "--aot9" ]]; then
  base="https://github.com/rstg00po54/go/releases/download/vivo-human-9x9-mt6993-aot-neuron-test-v1"
  stem="katago_human_9x9"
  graphTmp="$(mktemp "$DEST/.${stem}.aot.XXXXXX")"
  manifestTmp="$(mktemp "$DEST/.${stem}.aot.json.XXXXXX")"
  trap 'rm -f -- "$graphTmp" "$manifestTmp"' EXIT
  curl --fail --location --retry 3 --connect-timeout 15 \
    -o "$graphTmp" "$base/human_9x9_mediatek_mt6993_aot.tflite"
  curl --fail --location --retry 3 --connect-timeout 15 \
    -o "$manifestTmp" "$base/human_9x9_mediatek_mt6993_aot.json"
  echo '4cbefc83200982184985115e4faf4da87ba32722083849cfd22e5e12dfc68f95  '"$graphTmp" | sha256sum -c --status || {
    echo "ERROR: MT6993 AOT compiled Human graph differs from independently validated artifact" >&2
    exit 1
  }
  echo '38229edc28bcd239017a1c57384a4e06a4f1a956ef8c206d1758c658f7e19be8  '"$manifestTmp" | sha256sum -c --status || {
    echo "ERROR: AOT Human manifest differs from independently validated artifact" >&2
    exit 1
  }
  python3 - "$graphTmp" "$manifestTmp" <<'PY'
import hashlib,json,pathlib,sys
graph, manifest = (pathlib.Path(x) for x in sys.argv[1:3])
d = json.loads(manifest.read_text())
assert d['modelType']=='human' and d['weights']=='b18c384nbt-humanv0'
assert d['size']==9 and d['aotCompileTarget']=='MT6993'
assert d['aotCompileStatus']=='PASS' and d['numInputMetaChannels']==192
assert d['numInputChannels']==22 and d['numInputGlobalChannels']==19
assert d['numPolicyChannels']==2 and d['numValueChannels']==3
assert d['numScoreValueChannels']==6 and d['numOwnershipChannels']==1
assert set(d['inputOrder'])=={'InputSpatial','InputGlobal','InputMeta','InputMask'}
assert set(d['outputOrder'])=={'OutputPolicyPass','OutputPolicy','OutputValue','OutputScoreValue','OutputOwnership'}
assert d['cpuInferenceValidationScope']=='source_tflite_only'
h=hashlib.sha256()
with graph.open('rb') as stream:
    for chunk in iter(lambda: stream.read(1<<20), b''): h.update(chunk)
assert h.hexdigest()==d['graphSha256'], 'AOT graph digest does not match manifest'
print('HUMAN_AOT9_VERIFIED bytes=%d sha256=%s' % (graph.stat().st_size,h.hexdigest()))
PY
  mv -f "$graphTmp" "$DEST/${stem}.tflite"
  mv -f "$manifestTmp" "$DEST/${stem}.json"
  trap - EXIT
  echo "HUMAN_AOT9_ASSETS_STAGED (Experimental MT6993 AOT; real vivo NPU parity NOT yet verified)"
  exit 0
fi

case "${1:---size}" in
  --all) SIZES=(9 13 19) ;;
  --size) SIZES=("${2:-9}") ;;
  *) echo "Usage: bash tools/prepare_vivo_human_litert.sh --size 9|13|19 OR --all OR --aot9" >&2; exit 2 ;;
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
  printf '%s  %s\n' "${VERIFIED_SHA[${stem}.tflite]}" "$graphTmp" | sha256sum -c --status || {
    echo "ERROR: Human TFLite graph differs from CPU-validated release hash" >&2; exit 1; }
  printf '%s  %s\n' "${VERIFIED_SHA[${stem}.json]}" "$manifestTmp" | sha256sum -c --status || {
    echo "ERROR: Human model JSON differs from CPU-validated release hash" >&2; exit 1; }
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
