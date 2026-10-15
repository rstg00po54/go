#!/usr/bin/env python3
"""Offline tests for fail-closed model packaging checks."""
import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile

from verify_vivo_litert_apk import verify_apk
import verify_vivo_litert_apk as verifier
from unittest.mock import patch


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

    def test_scale_aware_and_legacy_manifests_are_both_checksum_pinned(self):
        old = b'{"old":true}'
        new = b'{"outputScaleMultiplier":8.0,"scale8Applied":true}'
        filename = "katago_10b_9x9.json"
        accepted_old = (len(old), hashlib.sha256(old).hexdigest())
        accepted_new = (len(new), hashlib.sha256(new).hexdigest())
        with patch.object(verifier, "EXPECTED", {filename: accepted_old}), \
             patch.object(verifier, "ALTERNATE_SCALE_AWARE_JSON", {filename: accepted_new}):
            for payload in (old, new):
                self.build({"assets/engine/" + filename: payload})
                verify_apk(self.apk)
            self.build({"assets/engine/" + filename: b'{"outputScaleMultiplier":3.0}'})
            with self.assertRaisesRegex(ValueError, "model length|model SHA256"):
                verify_apk(self.apk)

    def test_rknn_runtime_must_not_be_in_vivo_apk(self):
        self.build({"assets/engine/" + self.name: self.data,
                    "lib/arm64-v8a/librknnrt.so": b"rk3588-only"})
        with self.assertRaisesRegex(ValueError, "librknnrt"):
            verify_apk(self.apk, self.expected)


if __name__ == "__main__":
    unittest.main()
