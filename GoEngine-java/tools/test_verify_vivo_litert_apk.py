#!/usr/bin/env python3
"""Offline tests for fail-closed model packaging checks."""
import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile

from verify_vivo_litert_apk import verify_apk


class VivoModelPackageTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = Path(self.tmp.name) / "app-debug.apk"
        self.name = "katago_10b_9x9.tflite"
        self.data = b"example-valid-graph"
        self.expected = {self.name: (len(self.data), hashlib.sha256(self.data).hexdigest())}

    def build(self, entries):
        with zipfile.ZipFile(self.apk, "w") as z:
            for key, payload in entries.items(): z.writestr(key, payload)

    def test_expected_asset_passes(self):
        self.build({"assets/engine/" + self.name: self.data})
        verify_apk(self.apk, self.expected)

    def test_missing_graph_fails_build(self):
        self.build({"assets/engine/other-model.tflite": self.data})
        with self.assertRaisesRegex(ValueError, "Missing model inside APK"):
            verify_apk(self.apk, self.expected)

    def test_incorrect_hash_fails_build(self):
        wrong = b"example-invalid-graph"
        self.build({"assets/engine/" + self.name: wrong})
        self.expected[self.name] = (len(wrong), self.expected[self.name][1])
        with self.assertRaisesRegex(ValueError, "SHA256"):
            verify_apk(self.apk, self.expected)

    def test_rknn_runtime_must_not_be_in_vivo_apk(self):
        self.build({"assets/engine/" + self.name: self.data,
                    "lib/arm64-v8a/librknnrt.so": b"rk3588-only"})
        with self.assertRaisesRegex(ValueError, "librknnrt"):
            verify_apk(self.apk, self.expected)


if __name__ == "__main__":
    unittest.main()
