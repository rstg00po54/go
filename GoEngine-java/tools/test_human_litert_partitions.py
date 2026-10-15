#!/usr/bin/env python3
"""Regression that unsupported/ordinary/custom non-dispatch ops stay CPU candidates."""
import importlib.util
from pathlib import Path
from unittest import TestCase, main
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "diagnose_human_litert_partitions", ROOT / "diagnose_human_litert_partitions.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class OpPartitionTests(TestCase):
    def test_cpu_op_does_not_count_as_npu_dispatch(self):
        original = {"subgraphs": [{
            "ops": {"CONV_2D": 2, "ADD": 1}, "totalOps": 3}]}
        compiled = {"subgraphs": [{"ops": {
            "CUSTOM:litert.dispatch": 1, "ADD": 1,
            "CUSTOM:other_kernel": 1},
            "totalOps": 3}]}
        with patch.object(module, "summarize", side_effect=[original, compiled]):
            result = module.report(Path("a"), Path("b"))
        self.assertEqual(result["originalOpCount"], 3)
        self.assertEqual(result["aotDispatchOps"], {"CUSTOM:litert.dispatch": 1})
        self.assertEqual(result["aotNonDispatchOps"],
                         {"ADD": 1, "CUSTOM:other_kernel": 1})
        self.assertIsNone(result["aotOffloadOpCount"])

    def test_compiled_secondary_subgraphs_not_called_cpu_fallback(self):
        source = {"subgraphs": [{"ops": {"CONV_2D": 1}, "totalOps": 1}]}
        compiled = {"subgraphs": [
            {"ops": {"CUSTOM:LiteRtDispatch": 1}, "totalOps": 1},
            {"ops": {"CONV_2D": 1}, "totalOps": 1}]}
        with patch.object(module, "summarize", side_effect=[source, compiled]):
            result = module.report(Path("a"), Path("b"))
        self.assertEqual(result["aotNonDispatchOps"], {})
        self.assertEqual(result["aotEntryOpCount"], 1)

    def test_original_count_aggregates_by_type(self):
        source = {"subgraphs": [{"ops": {"CONV_2D": 2, "MUL": 2}, "totalOps": 4}]}
        compiled = {"subgraphs": [{"ops": {"ADD": 1}, "totalOps": 1}]}
        with patch.object(module, "summarize", side_effect=[source, compiled]):
            result = module.report(Path("a"), Path("b"))
        self.assertEqual(result["originalOpTypes"], {"CONV_2D": 2, "MUL": 2})
        self.assertFalse(result["aotDispatchOps"])


if __name__ == "__main__":
    main()
