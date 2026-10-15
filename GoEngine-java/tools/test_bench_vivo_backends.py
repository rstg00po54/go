#!/usr/bin/env python3
"""Offline checks of real benchmark marker parsing; no Android required."""
import unittest
from pathlib import Path

from bench_vivo_backends import parse_backend_log, parse_backends

ROOT = Path(__file__).resolve().parents[1]
IDENT = "test0987_gpu"


def trace(backend="GPU", ident=IDENT, board=9, startup=1234, samples=(100, 110, 120),
          warmups=(220,), npu_infer=True):
    lines = [
        "I/KataGoBench: START id=%s backend=%s board=%s visits=32 maxTime=12 warmups=%s repeats=%s human=false"
        % (ident, backend, board, len(warmups), len(samples)),
        "I/KataGoBench: READY id=%s backend=%s board=%s actual=%s/start startupMs=%d"
        % (ident, backend, board, backend, startup),
    ]
    for i, ms in enumerate(list(warmups) + list(samples)):
        lines.append(
            "I/KataGoBench: MOVE id=%s backend=%s board=%s iter=%d warmup=%s ms=%d vertex=F5"
            % (ident, backend, board, i, "true" if i < len(warmups) else "false", ms))
    if backend == "NPU" and npu_infer:
        lines.extend([
            "I/KataGoMTK: MTK_GTP_INFER board=%s" % board,
            "I/KataGoMTK: MTK_LITERT_INFER board=%s ms=2.0" % board,
            "I/KataGoMTK: MTK_LITERT_INFER board=%s ms=3.0" % board,
        ])
    lines.append("I/KataGoBench: PASS id=%s backend=%s board=%s measured=%s warmups=%s"
                 % (ident, backend, board, len(samples), len(warmups)))
    return "\n".join(lines)


class VivoBackendBenchmarkTests(unittest.TestCase):
    def test_cpu_gpu_npu_options(self):
        self.assertEqual(parse_backends("cpu,gpu,npu"), ("CPU", "GPU", "NPU"))
        self.assertEqual(parse_backends("gpu,npu"), ("GPU", "NPU"))
        for spec in ("gpu,gpu", "foo", "", "cpu,,npu"):
            with self.assertRaises(Exception):
                parse_backends(spec)

    def test_gpu_timing_excludes_warmups(self):
        r = parse_backend_log(trace(), IDENT, "GPU", 9, 1, 3)
        self.assertEqual(r["status"], "PASS", r["errors"])
        self.assertEqual(r["startupMs"], 1234)
        self.assertEqual(r["latencyMs"]["meanMs"], 110.0)
        self.assertEqual(r["latencyMs"]["medianMs"], 110.0)
        self.assertEqual(r["latencyMs"]["p95NearestRankMs"], 120)
        self.assertEqual([m["elapsedMs"] for m in r["warmupMoves"]], [220])
        self.assertEqual(len(r["measuredMoves"]), 3)

    def test_npu_calls_and_inference_time(self):
        ident = "run112_npu"
        good = trace("NPU", ident)
        result = parse_backend_log(good, ident, "NPU", 9, 1, 3)
        self.assertEqual(result["status"], "PASS", result["errors"])
        self.assertEqual(result["liteRtInferenceCalls"], 1)
        self.assertEqual(result["liteRtMeanInferenceMs"], 2.5)
        self.assertEqual(parse_backend_log(trace("NPU", ident, npu_infer=False),
                                           ident, "NPU", 9, 1, 3)["status"], "FAIL")

    def test_fallback_or_missing_terminal_cannot_pass(self):
        good = trace()
        self.assertEqual(parse_backend_log(good.replace("actual=GPU/start", "actual=CPU/Eigen"),
                                           IDENT, "GPU", 9, 1, 3)["status"], "FAIL")
        self.assertEqual(parse_backend_log(good.replace("PASS id=", "NO_PASS id="),
                                           IDENT, "GPU", 9, 1, 3)["status"], "FAIL")
        self.assertEqual(parse_backend_log(good.replace("warmup=false ms=110", "warmup=true ms=110"),
                                           IDENT, "GPU", 9, 1, 3)["status"], "FAIL")
        self.assertEqual(parse_backend_log(good.replace("iter=2", "iter=1"),
                                           IDENT, "GPU", 9, 1, 3)["status"], "FAIL")
        self.assertEqual(parse_backend_log(good, "other_id", "GPU", 9, 1, 3)["status"], "FAIL")

    def test_host_intent_and_java_benchmark_are_wired(self):
        ui = (ROOT / "app/src/main/java/com/badukai/MainActivity.java").read_text()
        app = (ROOT / "app/src/main/java/com/badukai/engine/KataGoBackendBenchmark.java").read_text()
        self.assertIn("isBackendBenchIntent()", ui)
        self.assertIn('"katago_backend_bench"', ui)
        self.assertIn("KataGoBackendBenchmark.run(", ui)
        self.assertIn('engine.generateMove("black")', app)
        self.assertIn("SystemClock.elapsedRealtimeNanos()", app)
        self.assertIn("engine.clearBoard()", app)
        self.assertIn('"MOVE id="', app)
        self.assertIn('"READY id="', app)
        self.assertIn("if (!actual.startsWith(backendName", app)


if __name__ == "__main__":
    unittest.main()
