#!/usr/bin/env python3
"""Offline regression tests for the Human b18 NPU parity upload wrapper."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import run_rk3588_human_parity as parity
from upload_referee_test_results import report_items

HEADS = ["OutputPolicyPass", "OutputPolicy", "OutputValue",
         "OutputScoreValue", "OutputOwnership"]


def make_log(status="PASS"):
    lines = []
    for case in range(4):
        for head in HEADS:
            lines.append(
                f"HUMAN_HEAD_PARITY case={case} name={head} maxAbs=0.0010000 "
                "meanAbs=0.0005000 worstIndex=0 cpu=0.1234 npu=0.1235 "
                f"outOfTolerance={1 if status == 'FAIL' and case == 3 and head == HEADS[-1] else 0} "
                f"status={'FAIL' if status == 'FAIL' and case == 3 and head == HEADS[-1] else 'PASS'}"
            )
    lines.append(
        f"HUMAN_CPU_NPU_PARITY {status} cases=4 heads=20 "
        f"badHeads={1 if status == 'FAIL' else 0} maxAbs=0.0010000 "
        "atol=0.05 rtol=0.03"
    )
    return "\n".join(lines) + "\n"


class ParityReportTests(unittest.TestCase):
    def test_parse_complete_20_heads(self):
        report = parity.parse_output(make_log())
        self.assertEqual(len(report["heads"]), 20)
        self.assertEqual(report["summary"]["status"], "PASS")
        self.assertAlmostEqual(report["heads"][0]["maxAbs"], 0.001)

    def test_public_artifacts_allow_only_report_and_parity_log(self):
        with tempfile.TemporaryDirectory() as td:
            directory = Path(td)
            (directory / "human_parity.txt").write_text(make_log())
            (directory / "parity_summary.json").write_text("{}")
            (directory / "model.rknn").write_bytes(b"no model upload")
            (directory / "tokens.txt").write_text("not published")
            _, items = report_items(directory, {"status": "PASS"}, "sample")
            names = [p for p, _ in items]
            self.assertIn("runs/sample/human_parity.txt", names)
            self.assertIn("runs/sample/parity_summary.json", names)
            self.assertNotIn("runs/sample/model.rknn", names)
            self.assertNotIn("runs/sample/tokens.txt", names)

    def test_success_auto_upload_and_preserve_report(self):
        with tempfile.TemporaryDirectory() as td, \
             mock.patch.object(parity, "OUTPUT", Path(td)), \
             mock.patch.object(parity, "upload_referee_results", return_value="https://example.invalid/result") as upload:
            def fake_run(serial, log_path):
                log_path.write_text(make_log(), encoding="utf-8")
                return 0
            with mock.patch.object(parity, "run_test", side_effect=fake_run):
                with contextlib.redirect_stdout(io.StringIO()):
                    code = parity.main(["test-device"])
            self.assertEqual(code, 0)
            self.assertEqual(upload.call_count, 1)
            report = upload.call_args.args[1]
            self.assertEqual(report["status"], "PASS")
            self.assertEqual(len(report["heads"]), 20)
            self.assertEqual(report["serial"], "test-device")
            self.assertTrue((next(Path(td).iterdir()) / "report.json").exists())

    def test_failure_uploads_and_preserves_exit_code(self):
        with tempfile.TemporaryDirectory() as td, \
             mock.patch.object(parity, "OUTPUT", Path(td)), \
             mock.patch.object(parity, "upload_referee_results", return_value="url") as upload:
            def fake_run(serial, log_path):
                log_path.write_text(make_log("FAIL"), encoding="utf-8")
                return 1
            with mock.patch.object(parity, "run_test", side_effect=fake_run):
                with contextlib.redirect_stdout(io.StringIO()):
                    code = parity.main(["test-device"])
            self.assertEqual(code, 1)
            self.assertEqual(upload.call_count, 1)
            self.assertEqual(upload.call_args.args[1]["status"], "FAIL")

    def test_no_upload_still_saves_report(self):
        with tempfile.TemporaryDirectory() as td, \
             mock.patch.object(parity, "OUTPUT", Path(td)), \
             mock.patch.object(parity, "upload_referee_results") as upload:
            def fake_run(serial, log_path):
                log_path.write_text(make_log(), encoding="utf-8")
                return 0
            with mock.patch.object(parity, "run_test", side_effect=fake_run):
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(parity.main(["test-device", "--no-upload"]), 0)
            upload.assert_not_called()
            self.assertTrue((next(Path(td).iterdir()) / "parity_summary.json").exists())

    def test_upload_failure_does_not_change_inference_result(self):
        with tempfile.TemporaryDirectory() as td, \
             mock.patch.object(parity, "OUTPUT", Path(td)), \
             mock.patch.object(parity, "upload_referee_results", side_effect=OSError("offline")):
            def fake_run(serial, log_path):
                log_path.write_text(make_log(), encoding="utf-8")
                return 0
            with mock.patch.object(parity, "run_test", side_effect=fake_run):
                with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    code = parity.main(["test-device"])
            self.assertEqual(code, 0)
            self.assertTrue((next(Path(td).iterdir()) / "report.json").exists())


if __name__ == "__main__":
    unittest.main()
