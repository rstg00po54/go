#!/usr/bin/env python3
"""Host-only regression for real MediaTek UI KataGo/Human SL game parser."""
import unittest

from smoke_vivo_mtk_game import DEFAULT_SIZES, field, parse_game_log, vertex


def example_log(sizes=DEFAULT_SIZES, human=True, include_final=True):
    run_id = "example1234"
    lines = ["I/NpuSmoke: START id=" + run_id + " sizes=9,13,19,9 humanSL=" + str(human).lower()]
    for idx, size in enumerate(sizes):
        lines.extend([
            f"I/NpuSmoke: STEP_BEGIN id={run_id} index={idx} board={size}",
            f"I/KataGoMTK: MTK_LITERT_INIT board={size} npuRequested=true graph=katago_10b_{size}x{size}.tflite",
            f"I/KataGoEngine: MTK_GAME_BACKEND_READY board={size} humanSL={str(human).lower()} backend=NPU/MediaTek",
            f"I/NpuSmoke: STEP_PASS id={run_id} index={idx} board={size} active={size} "
            "startupMs=202 black=D4 blackMs=42 white=F5 whiteMs=39",
        ])
    if include_final:
        lines.append(f"I/NpuSmoke: PASS id={run_id} steps={len(sizes)} sizes=9,13,19,9")
    return "\n".join(lines)


class MtkRealGameTests(unittest.TestCase):
    def test_each_board_and_return_to_nine_pass(self):
        data = parse_game_log(example_log(), "example1234", DEFAULT_SIZES)
        self.assertEqual(data["status"], "PASS", data["errors"])
        self.assertEqual([s["status"] for s in data["steps"]], ["PASS"]*4)

    def test_real_npu_and_human_required(self):
        raw = example_log()
        self.assertEqual(parse_game_log(raw.replace("NPU/MediaTek", "CPU/Eigen")
                                        .replace("MTK_GAME_BACKEND_READY", "CPU_READY"),
                                        "example1234", DEFAULT_SIZES)["status"], "FAIL")
        self.assertEqual(parse_game_log(raw.replace("humanSL=true", "humanSL=false"),
                                        "example1234", DEFAULT_SIZES)["status"], "FAIL")
        self.assertEqual(parse_game_log(raw.replace("MTK_LITERT_INIT", "CPU_INIT"),
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

    def test_npu_fallback_rejected(self):
        raw = example_log() + "\nI/KataGoEngine: NPU/MediaTek falling back to CPU"
        self.assertEqual(parse_game_log(raw, "example1234", DEFAULT_SIZES)["status"], "FAIL")


if __name__ == "__main__":
    unittest.main()
