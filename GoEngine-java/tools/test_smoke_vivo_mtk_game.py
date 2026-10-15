#!/usr/bin/env python3
"""Host-only regression for real MediaTek UI KataGo/Human SL game parser."""
import unittest

from smoke_vivo_mtk_game import DEFAULT_SIZES, field, parse_game_log, vertex


def example_log(sizes=DEFAULT_SIZES, human=True, include_final=True):
    run_id = "example1234"
    lines = ["I/NpuSmoke: START id=" + run_id + " sizes=" + ",".join(map(str, sizes)) + " humanSL=" + str(human).lower(),
             "I/NpuSmoke: MTK_CPU_TO_NPU_PRECHECK_READY id=" + run_id + " backend=CPU/Eigen JNI"]
    for idx, size in enumerate(sizes):
        lines.extend([
            f"I/NpuSmoke: STEP_BEGIN id={run_id} index={idx} board={size}",
            f"I/KataGoMTK: MTK_LITERT_INIT board={size} npuRequested=true graph=katago_10b_{size}x{size}.tflite",
            f"I/KataGoEngine: MTK_GAME_BACKEND_READY board={size} humanSL={str(human).lower()} backend=NPU/MediaTek",
            f"I/KataGoMTK: MTK_GTP_INFER board={size}",
            f"I/NpuSmoke: STEP_PASS id={run_id} index={idx} board={size} active={size} "
            "startupMs=202 black=D4 blackMs=42 white=F5 whiteMs=39",
        ])
    if include_final:
        lines.append("I/NpuSmoke: PASS id=%s steps=%d sizes=%s" % (run_id, len(sizes), ",".join(map(str, sizes))))
    return "\n".join(lines)


class MtkRealGameTests(unittest.TestCase):
    def test_mtk_wait_does_not_inject_asynchronous_stop_gtp_responses(self):
        from pathlib import Path
        engine = (Path(__file__).resolve().parents[1] /
                  "app/src/main/java/com/badukai/engine/KataGoEngine.java").read_text()
        wait = engine.split("private String waitForResponse(", 1)[1].split(
            "private String parseGtpMultilineResponse(", 1)[0]
        self.assertNotIn("mtk.stopSearch()", wait)
        self.assertIn("responseQueue.poll(", wait)
        native = (Path(__file__).resolve().parents[1] /
                  "app/src/main/cpp/EngineSession.cpp").read_text()
        self.assertIn('session->send("stop")', native)

    def test_mtk_gtp_reader_uses_real_newlines_not_literal_slashes(self):
        # The UI smoke previously appeared hung because the MediaTek reader
        # searched for the four literal characters backslash-n-backslash-n
        # instead of the GTP blank-line terminator.
        from pathlib import Path
        engine = (Path(__file__).resolve().parents[1] /
                  "app/src/main/java/com/badukai/engine/KataGoEngine.java").read_text()
        reader = engine.split("private void startMediaTekReaderThread()", 1)[1].split(
            "private void startNpuReaderThread()", 1)[0]
        self.assertIn(r'buffer.append(chunk.replace("\r\n", "\n"));', reader)
        self.assertIn(r'buffer.indexOf("\n\n")', reader)
        self.assertNotIn(r'buffer.indexOf("\\n\\n")', reader)

    def test_progress_markers_before_cpu_and_mtk_blocking_calls(self):
        from pathlib import Path
        source = (Path(__file__).resolve().parents[1] /
                  "app/src/main/java/com/badukai/MainActivity.java").read_text()
        for phase in ("human_model_prepare", "cpu_preload", "npu_start",
                      "genmove_black", "genmove_white"):
            self.assertIn("phase=" + phase, source)

    def test_each_board_and_return_to_nine_pass(self):
        data = parse_game_log(example_log(), "example1234", DEFAULT_SIZES)
        self.assertEqual(data["status"], "PASS", data["errors"])
        self.assertEqual([s["status"] for s in data["steps"]], ["PASS"]*len(DEFAULT_SIZES))

    def test_five_size_timing_summary(self):
        raw = example_log((11, 15))
        raw = raw.replace("I/NpuSmoke: STEP_PASS id=example1234 index=0",
            "I/KataGoMTK: MTK_LITERT_INFER board=11 model=10b ms=2\n"
            "I/KataGoMTK: MTK_LITERT_INFER board=11 model=human ms=8\n"
            "I/NpuSmoke: STEP_PASS id=example1234 index=0")
        result = parse_game_log(raw, "example1234", (11, 15))
        self.assertEqual(result["status"], "PASS", result["errors"])
        self.assertEqual(result["steps"][0]["inferenceTiming"]["10b"]["meanMs"], 2)
        self.assertEqual(result["steps"][0]["inferenceTiming"]["human"]["meanMs"], 8)

    def test_cpu_to_npu_switch_is_required(self):
        raw = example_log()
        self.assertEqual(parse_game_log(raw.replace("MTK_CPU_TO_NPU_PRECHECK_READY", "NO_CPU_PRECHECK"),
                                        "example1234", DEFAULT_SIZES)["status"], "FAIL")

    def test_real_npu_and_human_required(self):
        raw = example_log()
        self.assertEqual(parse_game_log(raw.replace("NPU/MediaTek", "CPU/Eigen")
                                        .replace("MTK_GAME_BACKEND_READY", "CPU_READY"),
                                        "example1234", DEFAULT_SIZES)["status"], "FAIL")
        self.assertEqual(parse_game_log(raw.replace("humanSL=true", "humanSL=false"),
                                        "example1234", DEFAULT_SIZES)["status"], "FAIL")
        self.assertEqual(parse_game_log(raw.replace("MTK_LITERT_INIT", "CPU_INIT"),
                                        "example1234", DEFAULT_SIZES)["status"], "FAIL")
        self.assertEqual(parse_game_log(raw.replace("MTK_GTP_INFER", "CPU_INFER"),
                                        "example1234", DEFAULT_SIZES)["status"], "FAIL")

    def test_partial_step_is_not_final_success(self):
        self.assertEqual(parse_game_log(example_log(include_final=False),
                                        "example1234", DEFAULT_SIZES)["status"], "FAIL")
        self.assertEqual(parse_game_log(example_log().replace("PASS id=", "WRONG id=", 1),
                                        "example1234", DEFAULT_SIZES)["status"], "FAIL")

    def test_wrong_board_and_move(self):
        raw = example_log().replace("active=19", "active=13")
        self.assertEqual(parse_game_log(raw, "example1234", DEFAULT_SIZES)["status"], "FAIL")
        self.assertFalse(vertex("T19", 13))
        self.assertTrue(vertex("Q16", 19))
        self.assertTrue(vertex("PASS", 9))
        self.assertEqual(field("STEP_PASS index=2 board=19", "board"), "19")

    def test_human_litert_requires_real_evaluation_and_eigen_parity(self):
        raw = example_log((9,))
        raw = raw.replace("MTK_LITERT_INIT board=9 npuRequested",
                          "MTK_LITERT_INIT board=9 model=10b npuRequested")
        extra = ["I/KataGoMTK: MTK_HUMAN_LITERT_READY board=9",
                 "I/KataGoMTK: MTK_LITERT_INIT board=9 model=human npuRequested=true",
                 "I/KataGoMTK: MTK_GTP_INFER board=9 model=human"]
        extra.extend("I/KataGoMTK: MTK_PARITY_HEAD board=9 model=human head=%s maxAbs=0.015" % head
                     for head in ("policyPass", "policy", "value", "score", "ownership"))
        raw = raw.replace("I/NpuSmoke: STEP_PASS", "\n".join(extra) + "\nI/NpuSmoke: STEP_PASS")
        result = parse_game_log(raw, "example1234", (9,), require_human_npu=True)
        self.assertEqual(result["status"], "PASS", result["errors"])
        self.assertEqual(result["steps"][0]["humanLiteRtInferenceCount"], 1)
        self.assertEqual(result["steps"][0]["humanParitySampleCount"], 5)
        self.assertEqual(parse_game_log(raw.replace("model=human", "model=10b"),
                                        "example1234", (9,), require_human_npu=True)["status"], "FAIL")
        self.assertEqual(parse_game_log(raw.replace("maxAbs=0.015", "maxAbs=1.25"),
                                        "example1234", (9,), require_human_npu=True)["status"], "FAIL")
        self.assertEqual(parse_game_log(raw.replace("MTK_HUMAN_LITERT_READY", "HUMAN_CPU"),
                                        "example1234", (9,), require_human_npu=True)["status"], "FAIL")
        # CPU Human fallback remains a valid legacy game, but is NOT a Human
        # NPU benchmark and must not be reported as one.
        self.assertEqual(parse_game_log(example_log((9,)), "example1234",
                                        (9,), require_human_npu=True)["status"], "FAIL")

    def test_npu_fallback_rejected(self):
        raw = example_log() + "\nI/KataGoEngine: NPU/MediaTek falling back to CPU"
        self.assertEqual(parse_game_log(raw, "example1234", DEFAULT_SIZES)["status"], "FAIL")


if __name__ == "__main__":
    unittest.main()
