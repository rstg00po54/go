#!/usr/bin/env python3
"""Host-only coverage of Vivo NPU inference diagnostics and upload artifacts."""
import tempfile
import unittest
from pathlib import Path

from smoke_vivo_litert import parse_gtp_events, parse_parity_events, save_npu_diagnostics, summarize_npu_logs
from upload_referee_test_results import report_items


SUCCESS = [
    "I/litert: [cpu_registry.cc:75] XNNPACK CPU accelerator registered.",
    "I/litert: [compiler_plugin.cc:260] Loaded plugin at: /test/libLiteRtCompilerPlugin_MediaTek.so",
    "I/litert: [neuron_adapter_api.cc:283] Neuron api version: 9.0.11",
    "I/litert: [dispatch_delegate.cc:128] DispatchDelegate::Initialize katago_10b_9x9_batch1",
    "I/tflite: Replacing 1 out of 1 node(s) with delegate (DispatchDelegate) node, yielding 1 partitions for subgraph 0",
    "E/apuware_hidl: HidlNeuronClient can't get INeuronApusys2 service",
    "I/apuware_hidl: AidlNeuronClient get aidl version=5",
    "I/KataGoMTK: MTK_LITERT_INFER board=9 ms=12 npuRequested=true hardwareExecution=UNVERIFIED",
    "I/KataGoMTK: MTK_LITERT_INFER board=9 ms=9 npuRequested=true hardwareExecution=UNVERIFIED",
    "I/KataGoMTK: MTK_LITERT_INFER board=9 ms=15 npuRequested=true hardwareExecution=UNVERIFIED",
    "I/KataGoMTK: MTK_SMOKE_PASS id=test board=9 hardwareExecution=UNVERIFIED",
]


class VivoNpuDiagnosticsTests(unittest.TestCase):
    def test_full_delegation_is_not_hardware_proof(self):
        result = summarize_npu_logs(SUCCESS, 9)
        self.assertEqual(result["delegateStatus"], "FULL_GRAPH_DELEGATED")
        self.assertEqual(result["neuronApiVersion"], "9.0.11")
        self.assertEqual(result["aidlVersion"], "5")
        self.assertEqual(result["inferenceTiming"]["samplesMs"], [12.0, 9.0, 15.0])
        self.assertEqual(result["inferenceTiming"]["meanMs"], 12.0)
        self.assertEqual(result["fallbackIndicators"], [])
        self.assertEqual(result["hardwareExecution"], "UNVERIFIED")

    def test_cpu_fallback_and_partial_delegate_are_not_full(self):
        lines = SUCCESS + ["W/litert: Failed to delegate, fallback to CPU instead"]
        self.assertEqual(summarize_npu_logs(lines, 9)["delegateStatus"], "PARTIAL_OR_FALLBACK")
        partial = [line.replace("Replacing 1 out of 1", "Replacing 1 out of 3") for line in SUCCESS]
        self.assertEqual(summarize_npu_logs(partial, 9)["delegateStatus"], "PARTIAL_OR_FALLBACK")

    def test_unobserved_delegate_not_assumed_from_pass(self):
        lines = [line for line in SUCCESS if "Replacing 1 out of 1" not in line]
        self.assertEqual(summarize_npu_logs(lines, 9)["delegateStatus"], "NOT_OBSERVED")
        self.assertEqual(summarize_npu_logs(lines, 13)["inferenceTiming"]["count"], 0)

    def test_real_gtp_pass_reads_moves_and_neural_inference(self):
        ident = "5620d3ce6bd142ef"
        lines = [
            "I/KataGoMTK: MTK_GTP_BRIDGE_INSTALLED",
            "I/KataGoMTK: MTK_GTP_REPLY command=name reply== KataGo",
            "I/KataGoMTK: MTK_LITERT_INFER board=9 ms=5",
            "I/KataGoMTK: MTK_GTP_INFER board=9",
            "I/KataGoMTK: MTK_GTP_REPLY command=genmove black reply== F5",
            "I/KataGoMTK: MTK_LITERT_INFER board=9 ms=1",
            "I/KataGoMTK: MTK_GTP_INFER board=9",
            "I/KataGoMTK: MTK_GTP_REPLY command=genmove white reply== D5",
            "I/KataGoMTK: MTK_GTP_SMOKE_PASS id=" + ident +
                " board=9 black=F5 blackMs=23 white=D5 whiteMs=4",
        ]
        result = parse_gtp_events(lines, ident)
        self.assertTrue(result["passed"])
        self.assertEqual(result["searchMoves"], {"black": "F5", "white": "D5"})
        self.assertEqual(result["gtpNeuralInferenceCount"], 2)
        self.assertEqual(result["modelInferenceCount"], 2)

    def test_real_gtp_requires_both_moves_and_native_inference(self):
        ident = "test123"
        common = ["MTK_GTP_BRIDGE_INSTALLED", "MTK_LITERT_INFER board=9 ms=1",
                  "MTK_GTP_INFER board=9",
                  "MTK_GTP_REPLY command=genmove black reply== F5",
                  "MTK_GTP_SMOKE_PASS id=test123 board=9 black=F5 white=D5"]
        self.assertFalse(parse_gtp_events(common, ident)["passed"])
        self.assertFalse(parse_gtp_events(common + [
            "MTK_GTP_REPLY command=genmove white reply== D5",
            "MTK_GTP_SMOKE_FAIL id=test123 board=9 reason=error"], ident)["passed"])

    def test_cpu_eigen_litert_neural_parity_five_heads(self):
        lines = ["I/KataGoMTK: MTK_PARITY_CPU handle=0x100 sample=1 board=9 batch=1 ms=101"]
        for head, count in [("policyPass", 1), ("policy", 81),
                            ("value", 3), ("score", 4), ("ownership", 81)]:
            lines.append(
                "I/KataGoMTK: MTK_PARITY_HEAD handle=0x100 sample=1 board=9 "
                f"head={head} count={count} maxAbs=0.001 mae=0.0005 "
                "maxRel=0.0008 worst=0 cpu=0.5 npu=0.501")
        result = parse_parity_events(lines, 9)
        self.assertEqual(result["status"], "WITHIN_PROVISIONAL_TOLERANCE")
        self.assertEqual(result["sampleCount"], 1)
        self.assertEqual(result["heads"]["policy"]["comparedElements"], 81)
        self.assertEqual(result["heads"]["value"]["maxAbs"], 0.001)
        self.assertEqual(result["heads"]["policy"]["meanAbs"], 0.0005)
        self.assertEqual(result["samples"][0]["cpuMs"], 101)
        self.assertEqual(result["hardwareExecution"], "UNVERIFIED")

    def test_large_difference_not_marked_numerically_equal(self):
        lines = ["MTK_PARITY_CPU handle=0x100 sample=1 board=9 batch=1 ms=101"]
        for head, count in [("policyPass", 1), ("policy", 81),
                            ("value", 3), ("score", 4), ("ownership", 81)]:
            bad = "0.9" if head == "ownership" else "0.001"
            lines.append(f"MTK_PARITY_HEAD handle=0x100 sample=1 board=9 "
                         f"head={head} count={count} maxAbs={bad} mae=0.0005 "
                         f"maxRel={bad} worst=0 cpu=0.5 npu=0.501")
        self.assertEqual(parse_parity_events(lines, 9)["status"], "MISMATCH")
        self.assertEqual(parse_parity_events(lines[:-1], 9)["status"], "INCOMPLETE")
        self.assertEqual(parse_parity_events([], 9)["status"], "NO_DATA")
        self.assertEqual(parse_parity_events(lines, 13)["status"], "NO_DATA")
        self.assertEqual(parse_parity_events(lines + [lines[-1]], 9)["status"], "INCOMPLETE")

    def test_important_diagnostics_are_in_upload_allowlist(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            report = {"board": 9, "status": "PROBE_PASS", "hardwareExecution": "UNVERIFIED"}
            save_npu_diagnostics(folder, SUCCESS, report, 9)
            summary_path, items = report_items(folder, report, "20261015_mtk_test", logger=lambda _: None)
            names = [name.rsplit("/", 1)[-1] for name, _ in items]
            self.assertIn("vendor_litert.txt", names)
            self.assertIn("npu_diagnostics.txt", names)
            (folder / "parity_summary.json").write_text('{"status":"NO_DATA"}')
            _, uploaded = report_items(folder, report, "20261015_mtk_parity", logger=lambda _: None)
            self.assertTrue(any(name.endswith("/parity_summary.json") for name, _ in uploaded))
            self.assertEqual(report["npuDiagnostics"]["delegateStatus"], "FULL_GRAPH_DELEGATED")
            self.assertTrue(summary_path.endswith(".json"))


if __name__ == "__main__":
    unittest.main()
