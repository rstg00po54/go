#!/usr/bin/env python3
"""Offline regression tests for actual Android AI move timings.

Run from GoEngine-java:
    python3 tools/test_bench_app_ai.py
"""
import datetime
import unittest

from bench_app_ai import analyze, export_csv, format_summary, log_time, parse_cases, stats


def entry(msec, text, priority="D"):
    base = datetime.datetime(2026, 10, 12, 13, 27)
    stamp = (base + datetime.timedelta(milliseconds=msec)).strftime("%m-%d %H:%M:%S.%f")[:-3]
    return f"{stamp} {priority}/MainActivity( 8815): {text}"


def sample_log():
    events = [
        entry(0, "Saved new-game preference boardSize=9 backend=CPU", "I"),
        entry(10, "Game backend requested=CPU actual=CPU/Eigen JNI ready=true", "I"),
        entry(100, "onBoardTap in, x=2, y=2, engineReady=true"),
        entry(110, "render in, message=AI 思考中..., engineReady=true"),
        entry(120, "render in, message=AI 思考中..., engineReady=true"),
        entry(300, "handleAiMove in, move=F4, aiColor=WHITE"),
        entry(305, "render in, message=轮到你了, engineReady=true"),
        # An occupied point must not generate a benchmark move.
        entry(400, "onBoardTap in, x=6, y=6, engineReady=true"),
        entry(500, "onBoardTap in, x=6, y=2, engineReady=true"),
        entry(510, "render in, message=AI 思考中..., engineReady=true"),
        entry(750, "handleAiMove in, move=D3, aiColor=WHITE"),
        entry(760, "render in, message=轮到你了, engineReady=true"),
        entry(1000, "Saved new-game preference boardSize=9 backend=GPU", "I"),
        entry(1050, "Game backend requested=GPU actual=GPU/OpenCL JNI ready=true", "I"),
        entry(1100, "onBoardTap in, x=2, y=2, engineReady=true"),
        entry(1110, "render in, message=AI 思考中..., engineReady=true"),
        entry(1180, "handleAiMove in, move=E5, aiColor=WHITE"),
        entry(1184, "render in, message=轮到你了, engineReady=true"),
        entry(1200, "onBoardTap in, x=6, y=6, engineReady=true"),
        entry(1205, "render in, message=AI 思考中..., engineReady=true"),
        entry(1280, "handleAiMove in, move=D5, aiColor=WHITE"),
        entry(1285, "render in, message=轮到你了, engineReady=true"),
        entry(2000, "Saved new-game preference boardSize=9 backend=CPU", "I"),
        entry(2050, "Game backend requested=CPU actual=CPU/Eigen JNI ready=true", "I"),
        entry(2100, "onBoardTap in, x=2, y=2, engineReady=true"),
        entry(2110, "render in, message=AI 思考中..., engineReady=true"),
        entry(2330, "handleAiMove in, move=F3, aiColor=WHITE"),
        entry(2335, "render in, message=轮到你了, engineReady=true"),
        entry(2400, "onBoardTap in, x=6, y=6, engineReady=true"),
        entry(2410, "render in, message=AI 思考中..., engineReady=true"),
        entry(2600, "handleAiMove in, move=C3, aiColor=WHITE"),
        entry(2602, "render in, message=轮到你了, engineReady=true"),
    ]
    return "\n".join(events) + "\n"


class BenchmarkTests(unittest.TestCase):
    def test_timestamp_math_is_exact_to_one_millisecond(self):
        first = log_time("10-12 13:27:00.100", 2026)
        later = log_time("10-12 13:27:00.300", 2026)
        self.assertIsInstance(first, int)
        self.assertEqual((later - first) / 1000, 200.0)
        midnight = log_time("10-13 00:00:00.000", 2026)
        before = log_time("10-12 23:59:59.999", 2026)
        self.assertEqual(midnight - before, 1000)
    def test_three_cases_and_rejected_tap(self):
        cases = parse_cases(sample_log(), year=2026)
        self.assertEqual([len(c["moves"]) for c in cases], [2, 2, 2])
        self.assertEqual([c["rejected_taps"] for c in cases], [1, 0, 0])
        self.assertEqual(cases[0]["moves"][0]["tap_to_ai_ms"], 200.0)
        self.assertEqual(cases[0]["moves"][0]["tap_to_ready_ms"], 205.0)
        self.assertEqual(cases[0]["moves"][1]["coordinate"], "(6,2)")
        self.assertEqual(cases[1]["actual"], "GPU/OpenCL JNI")
        self.assertEqual(cases[1]["startup_ms"], 50.0)

    def test_cpu_gpu_aggregate_and_warmup(self):
        result = analyze(sample_log(), ["cpu", "gpu", "cpu"], 2, 1, year=2026, size=9)
        self.assertEqual(result["by_backend"]["cpu"]["response"]["samples"], 2)
        self.assertEqual(result["by_backend"]["gpu"]["response"]["samples"], 1)
        self.assertEqual(result["by_backend"]["gpu"]["response"]["average_ms"], 80.0)
        self.assertAlmostEqual(result["by_backend"]["cpu"]["response"]["average_ms"], 225.0)
        self.assertIn("GPU total", format_summary(result))
        self.assertEqual(len(export_csv(result).strip().splitlines()), 7)

    def test_p95_nearest_rank(self):
        self.assertEqual(stats([1, 2, 3, 4])["p95_ms"], 4)
        self.assertIsNone(stats([]))

    def test_wrong_requested_backend_fails(self):
        with self.assertRaisesRegex(ValueError, "期望 gpu"):
            analyze(sample_log(), ["gpu", "cpu", "cpu"], 2, year=2026)

    def test_wrong_actual_backend_fails(self):
        bad = sample_log().replace("GPU/OpenCL JNI ready=true", "CPU/Eigen JNI ready=true")
        with self.assertRaisesRegex(ValueError, "后端启动不符"):
            analyze(bad, ["cpu", "gpu", "cpu"], 2, year=2026)

    def test_missing_ai_return_cannot_count_as_success(self):
        bad = sample_log().replace("handleAiMove in, move=E5", "ignoredAiMove in, move=E5")
        with self.assertRaisesRegex(ValueError, "只找到"):
            analyze(bad, ["cpu", "gpu", "cpu"], 2, year=2026)

    def test_warmup_cannot_exhaust_samples(self):
        with self.assertRaisesRegex(ValueError, "warmup_moves"):
            analyze(sample_log(), ["cpu", "gpu", "cpu"], 2, 2, year=2026)


if __name__ == "__main__":
    unittest.main()
