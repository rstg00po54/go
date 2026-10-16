#!/usr/bin/env python3
"""Host-only NPU smoke parser and publication regression tests."""
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import smoke_android_npu_switch as smoke


def fixture(sizes=(9, 13, 19, 9), run_id="unique", bad_index=None, bad_mode=""):
    lines = ["I/NpuSmoke( 100): START id=%s sizes=%s humanSL=true" %
             (run_id, ",".join(map(str, sizes)))]
    for index, size in enumerate(sizes):
        real_size = 9 if index == bad_index and bad_mode == "wrong-model" else size
        name = "katago_10b_%dx%d.rknn" % (real_size, real_size)
        directory = "/data/user/0/com.badukai.java.nputest/files/engine/"
        lines.append("I/NpuSmoke( 100): STEP_BEGIN id=%s index=%d board=%d" % (run_id, index, size))
        lines.append("I/KataGoEngine( 100): NPU_MODEL_SELECTED board=%d path=%s%s" %
                     (size, directory, name))
        lines.append("I/KataGoRKNN( 100): RKNN_INIT board=%d path=%s%s" % (size, directory, name))
        if index != bad_index or bad_mode != "fallback":
            lines.append("I/KataGoRKNN( 100): RKNN_READY board=%d model=%s%s" %
                         (real_size, directory, name))
        if index == bad_index and bad_mode == "fallback":
            lines.append("E/KataGoRKNN( 100): RKNN_DISABLED_FALLBACK_EIGEN: mismatch")
        lines.append("I/NpuSmoke( 100): STEP_PASS id=%s index=%d board=%d active=%d "
                     "startupMs=302 black=D4 blackMs=200 white=C3 whiteMs=210" %
                     (run_id, index, size, size))
    lines.append("I/NpuSmoke( 100): PASS id=%s steps=%d sizes=%s" %
                 (run_id, len(sizes), ",".join(map(str, sizes))))
    return "\n".join(lines)


class NpuLogTests(unittest.TestCase):
    def test_all_four_stages_and_return_to_nine(self):
        report = smoke.parse_log(fixture(), "unique", (9, 13, 19, 9))
        self.assertEqual(report["status"], "PASS")
        self.assertEqual(len(report["steps"]), 4)
        self.assertEqual(report["steps"][1]["whiteMs"], 210)
        self.assertEqual(report["steps"][3]["model"], "katago_10b_9x9.rknn")

    def test_human_npu_requires_real_runner_and_all_five_native_heads(self):
        base = fixture(sizes=(9,))
        lines = [
            "I/KataGoHumanRKNN( 100): HUMAN_RKNN_READY board=9 inputs=4 outputs=5 scale8Compensation=8",
            "I/KataGoHumanRKNN( 100): HUMAN_RKNN_INFER_TIMING count=1 inputMs=0.1 runMs=11.0 outputMs=0.2 totalMs=11.3 avgMs=11.3",
        ]
        for name in ("policyPass", "policy", "value", "score", "ownership"):
            lines.append("I/KataGoHumanRKNN( 100): HUMAN_RKNN_NATIVE_PARITY "
                         "board=9 case=0 head=%s maxAbs=0.002 meanAbs=0.001 bad=0" % name)
        injected = base.replace("I/NpuSmoke( 100): STEP_PASS", "\n".join(lines) +
                                "\nI/NpuSmoke( 100): STEP_PASS", 1)
        self.assertEqual(smoke.parse_log(injected, "unique", (9,), True)["status"], "PASS")
        stale = injected.replace(" scale8Compensation=8", "")
        self.assertEqual(smoke.parse_log(stale, "unique", (9,), True)["status"], "FAIL")
        missing = smoke.parse_log(base, "unique", (9,), True)
        self.assertEqual(missing["status"], "FAIL")
        bad = injected.replace("head=policy maxAbs=0.002 meanAbs=0.001 bad=0",
                               "head=policy maxAbs=0.002 meanAbs=0.001 bad=1")
        self.assertEqual(smoke.parse_log(bad, "unique", (9,), True)["status"], "FAIL")
        fallback = injected.replace("I/NpuSmoke( 100): STEP_PASS",
                                    "E/KataGoHumanRKNN( 100): HUMAN_RKNN_CPU_FALLBACK reason=native_parity_mismatch\n"
                                    "I/NpuSmoke( 100): STEP_PASS", 1)
        self.assertEqual(smoke.parse_log(fallback, "unique", (9,), True)["status"], "FAIL")

    def test_human_npu_13_19_requires_each_real_native_parity_head(self):
        sizes = (13, 19)
        log = fixture(sizes=sizes)
        for size in sizes:
            lines = [
                "I/KataGoHumanRKNN( 100): HUMAN_RKNN_READY board=%d inputs=4 outputs=5 scale8Compensation=8" % size,
                "I/KataGoHumanRKNN( 100): HUMAN_RKNN_INFER_TIMING board=%d count=1 inputMs=0.1 runMs=20.0 outputMs=0.2 totalMs=20.3 avgMs=20.3" % size,
            ]
            for head in ("policyPass", "policy", "value", "score", "ownership"):
                lines.append(
                    "I/KataGoHumanRKNN( 100): HUMAN_RKNN_NATIVE_PARITY "
                    "board=%d case=0 head=%s maxAbs=0.002 meanAbs=0.001 bad=0" % (size,head))
            marker = "I/NpuSmoke( 100): STEP_PASS id=unique index=%d board=%d" % (
                sizes.index(size),size)
            log = log.replace(marker, "\n".join(lines)+"\n"+marker, 1)
        result = smoke.parse_log(log,"unique",sizes,True)
        self.assertEqual(result["status"],"PASS",result["errors"])
        for size in sizes:
            wrong=log.replace("HUMAN_RKNN_READY board=%d" % size,
                              "HUMAN_RKNN_READY board=9",1)
            self.assertEqual(smoke.parse_log(wrong,"unique",sizes,True)["status"],"FAIL")
        fallback=log.replace("I/NpuSmoke( 100): STEP_PASS id=unique index=1",
                             "E/KataGoHumanRKNN( 100): HUMAN_RKNN_CPU_FALLBACK reason=native_parity_mismatch\n"
                             "I/NpuSmoke( 100): STEP_PASS id=unique index=1",1)
        self.assertEqual(smoke.parse_log(fallback,"unique",sizes,True)["status"],"FAIL")

    def test_human_timing_only_requires_npu_and_no_cpu_comparison(self):
        base = fixture(sizes=(9,))
        lines = [
            "I/KataGoEngine( 100): HUMAN_RKNN_EXPERIMENT_REQUESTED board=9 compareCpu=false model=/test.rknn",
            "I/KataGoHumanRKNN( 100): HUMAN_RKNN_READY board=9 inputs=4 outputs=5 scale8Compensation=8",
            "I/KataGoHumanRKNN( 100): HUMAN_RKNN_INFER_TIMING count=1 inputMs=0.1 runMs=10.0 outputMs=0.4 totalMs=10.5 avgMs=10.5",
            "I/KataGoHumanRKNN( 100): HUMAN_RKNN_INFER_TIMING count=2 inputMs=0.1 runMs=12.0 outputMs=0.4 totalMs=12.5 avgMs=11.5",
        ]
        injected = base.replace("I/NpuSmoke( 100): STEP_PASS",
                                "\n".join(lines) + "\nI/NpuSmoke( 100): STEP_PASS", 1)
        report = smoke.parse_log(injected, "unique", (9,), True, True)
        self.assertEqual(report["status"], "PASS", report["errors"])
        self.assertEqual(report["steps"][0]["human_npu_timing_samples"], 2)
        self.assertEqual(report["steps"][0]["human_npu_total_median_ms"], 11.5)
        self.assertEqual(report["steps"][0]["human_npu_run_median_ms"], 11.0)
        missing_timing = injected.replace("HUMAN_RKNN_INFER_TIMING", "HUMAN_RKNN_INFER_OTHER")
        self.assertEqual(smoke.parse_log(missing_timing, "unique", (9,), True, True)["status"], "FAIL")
        compare_enabled = injected.replace("compareCpu=false", "compareCpu=true")
        self.assertEqual(smoke.parse_log(compare_enabled, "unique", (9,), True, True)["status"], "FAIL")

    def test_thirteen_receiving_nine_model_fails(self):
        report = smoke.parse_log(fixture(bad_index=1, bad_mode="wrong-model"), "unique", (9, 13, 19, 9))
        self.assertEqual(report["status"], "FAIL")
        self.assertIn("Wrong", str(report["errors"]).replace("wrong", "Wrong"))

    def test_cpu_fallback_does_not_pass(self):
        report = smoke.parse_log(fixture(bad_index=1, bad_mode="fallback"), "unique", (9, 13, 19, 9))
        self.assertEqual(report["status"], "FAIL")
        self.assertTrue(any("fallback" in error.lower() for error in report["errors"]))

    def test_event_predicate_is_boolean_and_progress_count_handles_absent_events(self):
        self.assertIs(smoke.event("unrelated", "NpuSmoke", "STEP_BEGIN", "unique"), False)
        line = "I/NpuSmoke( 100): STEP_BEGIN id=unique index=0 board=9"
        self.assertIs(smoke.event(line, "NpuSmoke", "STEP_BEGIN", "unique"), True)
        self.assertEqual(sum(smoke.event(item, "NpuSmoke", "STEP_BEGIN", "unique")
                             for item in ["unrelated", line, "I/NpuSmoke: PASS id=other"]), 1)

    def test_stale_logcat_does_not_pass(self):
        report = smoke.parse_log(fixture(run_id="previous"), "current", (9, 13, 19, 9))
        self.assertEqual(report["status"], "FAIL")
        self.assertEqual(report["steps"], [])

    def test_partial_crash_keeps_previous_successful_steps(self):
        full = fixture()
        truncated = full.split("I/NpuSmoke( 100): STEP_BEGIN id=unique index=2")[0]
        truncated += "I/NpuSmoke( 100): STEP_BEGIN id=unique index=2 board=19\\n"
        truncated += "F/libc( 100): Fatal signal 11 (SIGSEGV) in com.badukai.java.nputest\\n"
        result = smoke.parse_log(truncated, "unique", (9, 13, 19, 9))
        self.assertEqual(result["status"], "FAIL")
        self.assertEqual([x["status"] for x in result["steps"][:2]], ["PASS", "PASS"])
        self.assertEqual(result["steps"][2]["status"], "FAIL")

    def test_requires_final_pass(self):
        log = fixture().rsplit("\n", 1)[0]
        self.assertEqual(smoke.parse_log(log, "unique", (9, 13, 19, 9))["status"], "FAIL")

    def test_native_crash_never_passes(self):
        log = fixture() + "\nF/libc( 100): Fatal signal 6 (SIGABRT) in com.badukai.java.nputest"
        self.assertEqual(smoke.parse_log(log, "unique", (9, 13, 19, 9))["status"], "FAIL")

    def test_command_line_size_validation(self):
        self.assertEqual(smoke.sizes_arg("9,13,19,9"), (9, 13, 19, 9))
        for raw in ("", "9,11", "9,9,", ",".join(["9"] * 13)):
            with self.assertRaises(Exception):
                smoke.sizes_arg(raw)

    def test_upload_existing_skips_adb(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            (base / "report.json").write_text('{"status":"PASS","steps":[]}', encoding="utf-8")
            with mock.patch.object(smoke, "upload_referee_results", return_value="https://github.com/results") as upload:
                with mock.patch.object(smoke, "command", side_effect=AssertionError("ADB should not be called")):
                    self.assertEqual(smoke.main(["--upload-existing", str(base)]), 0)
            self.assertEqual(upload.call_args.kwargs["run_kind"], "npu")

    def test_git_failure_preserves_existing_report(self):
        with tempfile.TemporaryDirectory() as folder:
            base = Path(folder)
            target = base / "report.json"
            target.write_text('{"status":"FAIL","steps":[]}', encoding="utf-8")
            with mock.patch.object(smoke, "upload_referee_results", side_effect=smoke.UploadError("bad credentials")):
                self.assertEqual(smoke.main(["--upload-existing", str(base)]), 1)
            self.assertIn('"status":"FAIL"', target.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
