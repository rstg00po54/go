#!/usr/bin/env bash
# Real GTP genmove latency benchmark (CPU -> GPU, default 16/24/32 threads).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$SCRIPT_DIR/benchmark_katago_genmove_android.py" "$@"
