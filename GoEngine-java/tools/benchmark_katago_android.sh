#!/usr/bin/env bash
# Benchmark Android KataGo on CPU first, then GPU, and compare per-thread results.
# CPU/GPU binaries and tuning caches are kept separate; the APK is not modified.
set -euo pipefail
export LC_ALL=C

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MODE="${1:-both}"
if [[ $# -gt 1 || ( "$MODE" != "both" && "$MODE" != "cpu" && "$MODE" != "gpu" ) ]]; then
    echo "Usage: $0 [both|cpu|gpu] (default: both, CPU then GPU)" >&2
    exit 2
fi

WORK_DIR="${KATAGO_WORKDIR:-$HOME/.cache/goengine_katago}"
CPU_BINARY="$PROJECT_DIR/app/build/generated/katagoJniLibs/arm64-v8a/libkatago_exec.so"
[[ -f "$CPU_BINARY" ]] || CPU_BINARY="$WORK_DIR/build_android_arm64_eigen/katago"
GPU_BINARY="$PROJECT_DIR/build/katago_android_arm64_opencl/libkatago_exec_opencl.so"
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
    if [[ "$mode" == "gpu" ]] && readelf -d "$binary" | grep -Fq '[libGLES_mali.so]'; then
        echo "Staging Mali OpenCL runtime"
        "$ADB" -s "$SERIAL" shell "cp /vendor/lib64/libOpenCL.so $REMOTE/libGLES_mali.so"
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

    # The benchmark reports search rate, inference rate, batches and mean batch size.
    awk -F 'numSearchThreads =|visits/s =|nnEvals/s =|nnBatches/s =|avgBatchSize =' '
        NF >= 6 {
            split($2, parts, ":");
            t=parts[1]; gsub(/[[:space:]]/, "", t);
            if(t ~ /^[0-9]+$/) printf "%d\t%.2f\t%.2f\t%.2f\t%.2f\n", t, $3+0, $4+0, $5+0, $6+0;
        }' "$log" > "$tab"
    [[ -s "$tab" ]] || { echo "No benchmark statistics found in $log" >&2; return 1; }
    echo "Saved: $log"
}

print_single() {
    local mode="$1"
    echo
    echo "======== ${mode^^} statistics ========"
    awk -F '\t' 'BEGIN { printf "%-8s %12s %12s %12s\n", "Threads","Visits/s","NNEvals/s","AvgBatch" }
        {printf "%-8d %12.2f %12.2f %12.2f\n",$1,$2,$3,$5}' "$REPORT_DIR/$mode.tsv"
}

compare_results() {
    local csv="$REPORT_DIR/summary.csv"
    awk -F '\t' -v order="$THREADS" '
        BEGIN { count=split(order,threadOrder,","); print "threads,cpu_visits_s,gpu_visits_s,gpu_speedup_pct,cpu_nn_evals_s,gpu_nn_evals_s,cpu_avg_batch,gpu_avg_batch" }
        FILENAME == ARGV[1] {
            cpu[$1]=$2; cpuEval[$1]=$3; cpuBatch[$1]=$5; next
        }
        FILENAME == ARGV[2] {
            gpu[$1]=$2; gpuEval[$1]=$3; gpuBatch[$1]=$5; next
        }
        END {
            for (i=1; i<=count; i++) {
                t=threadOrder[i]+0;
                if (!(t in cpu) || !(t in gpu) || cpu[t] <= 0) continue;
                gain=(gpu[t]/cpu[t]-1)*100;
                printf "%d,%.2f,%.2f,%+.1f,%.2f,%.2f,%.2f,%.2f\n",t,cpu[t],gpu[t],gain,cpuEval[t],gpuEval[t],cpuBatch[t],gpuBatch[t];
            }
        }' "$REPORT_DIR/cpu.tsv" "$REPORT_DIR/gpu.tsv" > "$csv"

    echo
    echo "======== CPU vs GPU (same number of threads) ========"
    awk -F, '
        NR==1 {printf "%-8s %12s %12s %11s %12s %12s %11s %11s\n", "Threads","CPU visit/s","GPU visit/s","GPU gain","CPU NN/s","GPU NN/s","CPU batch","GPU batch";next}
        {printf "%-8d %12.2f %12.2f %10.1f%% %12.2f %12.2f %11.2f %11.2f\n",$1,$2,$3,$4,$5,$6,$7,$8;
         if($2>bestCpu){bestCpu=$2;bestCpuThreads=$1}
         if($3>bestGpu){bestGpu=$3;bestGpuThreads=$1}
         n++
        }
        END {
            if(n==0) {print "No matching CPU/GPU results.";exit 1}
            printf "\nBest CPU: %.2f visits/s (%d threads)\n", bestCpu,bestCpuThreads;
            printf "Best GPU: %.2f visits/s (%d threads)\n", bestGpu,bestGpuThreads;
            printf "Best GPU vs best CPU: %+.1f%%\n",(bestGpu/bestCpu-1)*100;
        }' "$csv"
    echo "Summary CSV: $csv"
    echo "Full logs: $REPORT_DIR/cpu.log | $REPORT_DIR/gpu.log"
}

case "$MODE" in
    both)
        run_benchmark cpu
        run_benchmark gpu
        compare_results
        ;;
    cpu|gpu)
        run_benchmark "$MODE"
        print_single "$MODE"
        ;;
esac
