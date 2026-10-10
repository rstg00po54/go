#!/usr/bin/env bash
# Benchmark Android KataGo on CPU first, then GPU, and compare per-thread results.
# CPU/GPU binaries and tuning caches are kept separate; the APK is not modified.
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MODE="${1:-both}"
summarize_results() {
    # Parse KataGo's original log lines, not intermediary TSV rows. This avoids
    # ambiguous awk field matching and always checks for missing thread counts.
    command -v python3 >/dev/null || { echo "python3 required to summarize benchmark logs" >&2; return 1; }
    python3 - "$REPORT_DIR" "$THREADS" "$MODE" <<'PY'
import csv
import re
import sys
from pathlib import Path

report_dir = Path(sys.argv[1])
thread_order = [int(s) for s in sys.argv[2].split(',')]
mode = sys.argv[3]
num = r'([0-9]+(?:\.[0-9]+)?)'
pattern = re.compile(
    r'numSearchThreads\s*=\s*(\d+):\s*\d+\s*/\s*\d+\s+positions,\s*'
    r'visits/s\s*=\s*' + num + r'\s+nnEvals/s\s*=\s*' + num +
    r'\s+nnBatches/s\s*=\s*' + num + r'\s+avgBatchSize\s*=\s*' + num
)

def read_mode(name):
    source = report_dir / (name + '.log')
    data = source.read_text(encoding='utf-8', errors='replace')
    records = {}
    for match in pattern.finditer(data):
        threads = int(match.group(1))
        records[threads] = tuple(float(x) for x in match.groups()[1:])
    missing = [t for t in thread_order if t not in records]
    if missing:
        raise RuntimeError('%s: missing thread results %s in %s (parsed: %s)' %
                           (name.upper(), missing, source, sorted(records)))
    with (report_dir / (name + '.tsv')).open('w', newline='') as output:
        writer = csv.writer(output, delimiter='\t')
        for t in thread_order:
            writer.writerow((t,) + records[t])
    return records

try:
    if mode == 'both':
        cpu, gpu = read_mode('cpu'), read_mode('gpu')
        print('\n======== CPU vs GPU (same number of threads) ========')
        print('%-8s %12s %12s %11s %12s %12s %11s %11s' %
              ('Threads', 'CPU visit/s', 'GPU visit/s', 'GPU gain', 'CPU NN/s', 'GPU NN/s', 'CPU batch', 'GPU batch'))
        summary = report_dir / 'summary.csv'
        with summary.open('w', newline='') as output:
            writer = csv.writer(output)
            writer.writerow(('threads', 'cpu_visits_s', 'gpu_visits_s', 'gpu_speedup_pct',
                             'cpu_nn_evals_s', 'gpu_nn_evals_s', 'cpu_avg_batch', 'gpu_avg_batch'))
            for t in thread_order:
                c, g = cpu[t], gpu[t]
                gain = (g[0] / c[0] - 1) * 100
                writer.writerow((t, '%.2f' % c[0], '%.2f' % g[0], '%+.1f' % gain,
                                 '%.2f' % c[1], '%.2f' % g[1], '%.2f' % c[3], '%.2f' % g[3]))
                print('%-8d %12.2f %12.2f %10.1f%% %12.2f %12.2f %11.2f %11.2f' %
                      (t, c[0], g[0], gain, c[1], g[1], c[3], g[3]))
        best_cpu = max(thread_order, key=lambda t: cpu[t][0])
        best_gpu = max(thread_order, key=lambda t: gpu[t][0])
        print('\nBest CPU: %.2f visits/s (%d threads)' % (cpu[best_cpu][0], best_cpu))
        print('Best GPU: %.2f visits/s (%d threads)' % (gpu[best_gpu][0], best_gpu))
        print('Best GPU vs best CPU: %+.1f%%' % ((gpu[best_gpu][0] / cpu[best_cpu][0] - 1) * 100))
        print('Summary CSV: %s' % summary)
        print('Full logs: %s/cpu.log | %s/gpu.log' % (report_dir, report_dir))
    else:
        results = read_mode(mode)
        print('\n======== %s statistics ========' % mode.upper())
        print('%-8s %12s %12s %12s' % ('Threads', 'Visits/s', 'NNEvals/s', 'AvgBatch'))
        for t in thread_order:
            row = results[t]
            print('%-8d %12.2f %12.2f %12.2f' % (t, row[0], row[1], row[3]))
except (OSError, RuntimeError, ZeroDivisionError) as error:
    print('Benchmark summary error: %s' % error, file=sys.stderr)
    sys.exit(1)
PY
}

# Rebuild summary.csv from existing logs without another CPU/GPU phone benchmark.
# Example: bash tools/benchmark_katago_android.sh summary build/katago_benchmark/20261010_094402
if [[ "$MODE" == "summary" ]]; then
    [[ $# -eq 2 ]] || { echo "Usage: $0 summary <existing-report-directory>" >&2; exit 2; }
    REPORT_DIR="$2"
    THREADS="${KATAGO_BENCH_THREADS:-2,4,6,8}"
    [[ -d "$REPORT_DIR" ]] || { echo "No such report directory: $REPORT_DIR" >&2; exit 1; }
    MODE="both"
    summarize_results
    exit 0
fi

if [[ $# -gt 1 || ( "$MODE" != "both" && "$MODE" != "cpu" && "$MODE" != "gpu" ) ]]; then
    echo "Usage: $0 [both|cpu|gpu] (default: both, CPU then GPU)" >&2
    exit 2
fi

WORK_DIR="${KATAGO_WORKDIR:-$HOME/.cache/goengine_katago}"
CPU_BINARY="$PROJECT_DIR/app/build/generated/katagoJniLibs/arm64-v8a/libkatago_exec.so"
[[ -f "$CPU_BINARY" ]] || CPU_BINARY="$WORK_DIR/build_android_arm64_eigen/katago"
GPU_BINARY="${KATAGO_BENCH_GPU_BINARY:-$PROJECT_DIR/build/katago_android_arm64_opencl/libkatago_exec_opencl.so}"
if [[ "$MODE" != "gpu" && ! -f "$CPU_BINARY" ]]; then
    echo "CPU executable not found: $CPU_BINARY" >&2
    exit 1
fi
if [[ "$MODE" != "cpu" && ! -f "$GPU_BINARY" ]]; then
    echo "GPU executable not found: $GPU_BINARY (build with tools/build_katago_from_source.sh opencl)" >&2
    exit 1
fi

MODEL="$PROJECT_DIR/app/src/main/assets/engine/10b.bin"
CONFIG="$PROJECT_DIR/app/src/main/assets/engine/default_gtp.cfg"
[[ -f "$MODEL" && -f "$CONFIG" ]] || { echo "Bundled 10b model/config not found" >&2; exit 1; }

ADB="${ADB:-$(command -v adb || true)}"
[[ -n "$ADB" ]] || { echo "adb not found; install Android platform-tools or set ADB=/path/to/adb" >&2; exit 1; }
SERIAL="${ANDROID_SERIAL:-}"
if [[ -z "$SERIAL" ]]; then
    mapfile -t devices < <("$ADB" devices | awk 'NR > 1 && $2 == "device" {print $1}')
    if [[ "${#devices[@]}" -ne 1 ]]; then
        echo "Connect exactly one authorized Android device (or set ANDROID_SERIAL)." >&2
        exit 1
    fi
    SERIAL="${devices[0]}"
fi

REMOTE="/data/local/tmp/katago_bench"
VISITS="${KATAGO_BENCH_VISITS:-200}"
THREADS="${KATAGO_BENCH_THREADS:-2,4,6,8}"
POSITIONS="${KATAGO_BENCH_POSITIONS:-5}"
BOARD="${KATAGO_BENCH_BOARD:-19}"
REPORT_DIR="${KATAGO_BENCH_REPORT_DIR:-$PROJECT_DIR/build/katago_benchmark/$(date +%Y%m%d_%H%M%S)}"

for value in "$VISITS" "$POSITIONS" "$BOARD"; do
    [[ "$value" =~ ^[0-9]+$ ]] || { echo "Invalid numeric benchmark parameter: $value" >&2; exit 2; }
done
[[ "$THREADS" =~ ^[0-9]+(,[0-9]+)*$ ]] || { echo "Invalid thread list: $THREADS" >&2; exit 2; }

mkdir -p "$REPORT_DIR"
echo "Device: $SERIAL | board: $BOARD | visits: $VISITS | positions: $POSITIONS | threads: $THREADS"
echo "Reports: $REPORT_DIR"
"$ADB" -s "$SERIAL" shell "mkdir -p $REMOTE/.katago $REMOTE/gtp_logs"
"$ADB" -s "$SERIAL" push "$MODEL" "$REMOTE/10b.bin" >/dev/null
"$ADB" -s "$SERIAL" push "$CONFIG" "$REMOTE/default_gtp.cfg" >/dev/null

run_benchmark() {
    local mode="$1" binary name log tab status
    if [[ "$mode" == "cpu" ]]; then binary="$CPU_BINARY"; else binary="$GPU_BINARY"; fi
    name="katago_$mode"
    log="$REPORT_DIR/$mode.log"
    tab="$REPORT_DIR/$mode.tsv"

    echo
    echo "======== Testing ${mode^^} ========"
    "$ADB" -s "$SERIAL" push "$binary" "$REMOTE/$name" >/dev/null
    "$ADB" -s "$SERIAL" shell "chmod 755 $REMOTE/$name"

    # Mali vendor libOpenCL.so may declare SONAME=libGLES_mali.so.
    # Stage it in the test dir without changing /vendor or the APK.
    if [[ "$mode" == "gpu" ]] && readelf -W -d "$binary" | grep -Fq '(NEEDED)             Shared library: [libGLES_mali.so]'; then
        echo "Staging legacy RK3588 Mali OpenCL runtime"
        # The old binary requires Mali's versioned OPENCL_1.0 symbols.
        # Never rename a phone's SONAME=libOpenCL.so loader as libGLES_mali.so.
        vendor_soname="$("$ADB" -s "$SERIAL" shell "readelf -d /vendor/lib64/libOpenCL.so 2>/dev/null | grep SONAME" || true)"
        if [[ "$vendor_soname" == *'[libGLES_mali.so]'* ]]; then
            "$ADB" -s "$SERIAL" shell "cp /vendor/lib64/libOpenCL.so $REMOTE/libGLES_mali.so"
        else
            echo "ERROR: this GPU binary is linked to RK3588 libGLES_mali.so." >&2
            echo "Use openclportable GPU output with KATAGO_BENCH_GPU_BINARY." >&2
            return 1
        fi
    fi

    # Keep a complete log but display only useful progress and benchmark lines.
    # This also preserves previously generated OpenCL tuning cache on the device.
    set +e
    "$ADB" -s "$SERIAL" shell "cd $REMOTE && HOME=$REMOTE TMPDIR=$REMOTE LD_LIBRARY_PATH=$REMOTE:/vendor/lib64:/system/vendor/lib64 $REMOTE/$name benchmark -model 10b.bin -config default_gtp.cfg -v $VISITS -t $THREADS -n $POSITIONS -boardsize $BOARD" 2>&1 |
        tee "$log" | awk '
          /Loading model and initializing benchmark|Loaded model 10b.bin|Using OpenCL Device 0:|Loaded tuning parameters from:|Performing autotuning|Done tuning|FP16Storage|Mali OpenCL detected|Testing different numbers of threads|Testing \(board size|numSearchThreads = *[0-9]+:|Error|ERROR|error:|Aborted|CANNOT LINK/ {
            if (index($0,"Using OpenCL Device 0:") > 0) print substr($0,1,105) "...";
            else print;
            fflush();
          }'
    status=${PIPESTATUS[0]}
    set -e
    if (( status != 0 )); then
        echo "$mode benchmark failed (exit $status). Full log: $log" >&2
        return "$status"
    fi

    [[ -s "$log" ]] || { echo "Benchmark log is empty: $log" >&2; return 1; }
    echo "Saved: $log"
}


case "$MODE" in
    both)
        run_benchmark cpu
        run_benchmark gpu
        summarize_results
        ;;
    cpu|gpu)
        run_benchmark "$MODE"
        summarize_results
        ;;
esac
