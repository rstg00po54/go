#!/usr/bin/env python3
"""Tests for the incremental APK bloat preflight. Python 3.8+."""
import io
from pathlib import Path
import tempfile
import unittest
import zipfile

from check_apk_overhead import inspect, run


class ApkOverheadTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = Path(self.tmp.name) / "app/build/outputs/apk/debug/app-debug.apk"
        self.apk.parent.mkdir(parents=True)
        mem = io.BytesIO()
        with zipfile.ZipFile(mem, "w") as archive:
            archive.writestr("assets/engine/katago_human_19x19.tflite", b"test-model")
        self.clean_zip = mem.getvalue()

    def test_normal_apk_is_preserved(self):
        self.apk.write_bytes(self.clean_zip)
        self.assertEqual(run(self.apk, limit_mib=0.5, prune_stale=True), 0)
        self.assertTrue(self.apk.is_file())

    def test_zip_with_large_orphan_prefix_is_removed(self):
        self.apk.write_bytes(b"OLD-UNREACHABLE-DATA-" * 50000 + self.clean_zip)
        _, _, overhead, count = inspect(self.apk)
        self.assertGreater(overhead, 500 * 1024)
        self.assertEqual(count, 1)
        self.assertEqual(run(self.apk, limit_mib=0.5, prune_stale=True), 0)
        self.assertFalse(self.apk.exists())

    def test_strict_check_rejects_bloat_without_deleting(self):
        self.apk.write_bytes(b"x" * 1024 * 1024 + self.clean_zip)
        self.assertEqual(run(self.apk, limit_mib=0.5, fail_large=True), 1)
        self.assertTrue(self.apk.is_file())

    def test_nonstandard_path_must_never_be_pruned(self):
        target = Path(self.tmp.name) / "source.apk"
        target.write_bytes(b"x" * 1024 * 1024 + self.clean_zip)
        self.assertEqual(run(target, limit_mib=0.5, prune_stale=True), 2)
        self.assertTrue(target.is_file())


if __name__ == "__main__":
    unittest.main()
