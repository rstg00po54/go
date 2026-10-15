#!/usr/bin/env python3
"""Host-only coverage of Vivo NPU inference diagnostics and upload artifacts."""
import tempfile
import unittest
from pathlib import Path

from smoke_vivo_litert import save_npu_diagnostics, summarize_npu_logs
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

    def test_important_diagnostics_are_in_upload_allowlist(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp)
            report = {"board": 9, "status": "PROBE_PASS", "hardwareExecution": "UNVERIFIED"}
            save_npu_diagnostics(folder, SUCCESS, report, 9)
            summary_path, items = report_items(folder, report, "20261015_mtk_test", logger=lambda _: None)
            names = [name.rsplit("/", 1)[-1] for name, _ in items]
            self.assertIn("vendor_litert.txt", names)
            self.assertIn("npu_diagnostics.txt", names)
            self.assertEqual(report["npuDiagnostics"]["delegateStatus"], "FULL_GRAPH_DELEGATED")
            self.assertTrue(summary_path.endswith(".json"))


if __name__ == "__main__":
    unittest.main()
