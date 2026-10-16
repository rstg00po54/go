#!/usr/bin/env python3
"""Host-only tests for RK3588 Human b18 core scheduling reports and upload."""
import contextlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import run_rk3588_human_cores as bench
from upload_referee_test_results import report_items


def sample_log(three_cores=True):
    data=["HUMAN_BENCH_START runs=5 warmup=2 board=9 model=original_b18"]
    modes=["core0_before"]+(["core012"] if three_cores else [])+["core0_after"]
    for mode in modes:
        for i in range(5):
            data.append(
                f"HUMAN_BENCH_SAMPLE phase={mode} index={i} inputMs=0.100 "
                "runMs=30.000 outputMs=0.200 totalMs=30.300")
        data.append(
            f"HUMAN_BENCH_SUMMARY phase={mode} runs=5 warmup=2 "
            "medianRunMs=30.000 p90RunMs=31.000 medianTotalMs=30.300 "
            "minRunMs=28.000 maxRunMs=32.000")
    if not three_cores:
        data.append("HUMAN_BENCH_CORE3_UNAVAILABLE reason=rknn_set_core_mask")
    data.append(
        f"HUMAN_BENCH_RESULT status=PASS cores3Available={int(three_cores)} "
        "core0RunMedianMs=30.0 core3RunMedianMs=20.0 "
        "core3VsCore0Ratio=0.667 core0BeforeMs=30.0 core0AfterMs=30.0 "
        "maxOutputDelta=0.0001 samplesPerPhase=5")
    return "\n".join(data)+"\n"


class BenchTests(unittest.TestCase):
    def test_parses_raw_samples_and_summary(self):
        data=bench.parse_output(sample_log())
        self.assertEqual(data["result"]["status"],"PASS")
        self.assertEqual(len(data["samples"]["core012"]),5)
        self.assertEqual(data["modes"]["core0_before"]["medianRunMs"],30.0)

    def test_unavailable_multi_core_keeps_baseline(self):
        data=bench.parse_output(sample_log(False))
        self.assertNotIn("core012",data["modes"])
        self.assertEqual(len(data["samples"]["core0_after"]),5)

    def test_only_bounded_benchmark_files_are_published(self):
        with tempfile.TemporaryDirectory() as td:
            folder=Path(td)
            (folder/"human_cores.txt").write_text(sample_log(),encoding="utf-8")
            (folder/"human_cores.json").write_text("{}",encoding="utf-8")
            (folder/"katago_human_9x9.rknn").write_bytes(b"not published")
            _,items=report_items(folder,{"status":"PASS"},"bench_test")
            names=[k for k,_ in items]
            self.assertIn("runs/bench_test/human_cores.txt",names)
            self.assertIn("runs/bench_test/human_cores.json",names)
            self.assertNotIn("runs/bench_test/katago_human_9x9.rknn",names)

    def test_pass_report_auto_upload(self):
        with tempfile.TemporaryDirectory() as td, \
             mock.patch.object(bench,"REPORT_ROOT",Path(td)), \
             mock.patch.object(bench,"upload_referee_results",return_value="https://example.invalid/report") as upload:
            def fake(serial,log_path):
                log_path.write_text(sample_log(),encoding="utf-8")
                return 0
            with mock.patch.object(bench,"run",side_effect=fake):
                with contextlib.redirect_stdout(io.StringIO()):
                    result=bench.main(["board"])
            self.assertEqual(result,0)
            self.assertEqual(upload.call_count,1)
            doc=upload.call_args.args[1]
            self.assertEqual(doc["status"],"PASS")
            self.assertTrue(doc["core3Available"])
            self.assertEqual(doc["core0MedianRunMs"],30.0)
            self.assertTrue((next(Path(td).iterdir())/"report.json").exists())

    def test_fail_still_uploads_and_no_upload_optout(self):
        with tempfile.TemporaryDirectory() as td, \
             mock.patch.object(bench,"REPORT_ROOT",Path(td)), \
             mock.patch.object(bench,"upload_referee_results",return_value="url") as upload:
            def failed(serial,log_path):
                log_path.write_text(sample_log().replace(
                    "HUMAN_BENCH_RESULT status=PASS",
                    "HUMAN_BENCH_RESULT status=FAIL"),encoding="utf-8")
                return 1
            with mock.patch.object(bench,"run",side_effect=failed):
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(bench.main(["board"]),1)
            self.assertEqual(upload.call_count,1)
            self.assertEqual(upload.call_args.args[1]["status"],"FAIL")
            upload.reset_mock()
            with mock.patch.object(bench,"run",side_effect=failed):
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(bench.main(["board","--no-upload"]),1)
            upload.assert_not_called()


if __name__=="__main__":
    unittest.main()
