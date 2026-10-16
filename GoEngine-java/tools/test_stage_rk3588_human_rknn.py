#!/usr/bin/env python3
"""Unit tests for bundling the pinned RK3588 Human b18 model, without network."""
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import stage_rk3588_human_rknn as model


class BundlingTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        self.cache = root/"cache"/model.MODEL
        self.output = root/"apk"/"engine"/model.MODEL
        self.body = b"RKNN-fixture-a1" * 27
        self.len_patch = mock.patch.object(model,"EXPECTED_SIZE",len(self.body))
        self.sha_patch = mock.patch.object(model,"EXPECTED_SHA256",
                                           hashlib.sha256(self.body).hexdigest())
        self.len_patch.start()
        self.sha_patch.start()

    def tearDown(self):
        self.sha_patch.stop()
        self.len_patch.stop()
        self.tmp.cleanup()

    def test_download_once_and_reuse_verification(self):
        calls=[]
        def download():
            calls.append(1)
            return io.BytesIO(self.body)
        model.stage(True,self.cache,self.output,download)
        self.assertTrue(model.valid_model(self.output))
        pinned = self.cache.parent / ("release_" + model.EXPECTED_SHA256[:12]) / model.MODEL
        self.assertEqual(pinned.read_bytes(),self.body)
        self.assertFalse(self.cache.exists())
        model.stage(True,self.cache,self.output,download)
        self.assertEqual(len(calls),1)

    def test_disabled_build_removes_stale_asset_not_cache(self):
        self.cache.parent.mkdir(parents=True,exist_ok=True)
        self.cache.write_bytes(self.body)
        self.output.parent.mkdir(parents=True,exist_ok=True)
        self.output.write_bytes(self.body)
        model.stage(False,self.cache,self.output)
        self.assertFalse(self.output.exists())
        self.assertTrue(self.cache.exists())

    def test_bad_download_does_not_install_partial_file(self):
        with self.assertRaises(ValueError):
            model.stage(True,self.cache,self.output,lambda: io.BytesIO(b"incorrect bytes"))
        self.assertFalse(self.cache.exists())
        self.assertFalse(self.output.exists())
        self.assertFalse(any(self.cache.parent.glob("*.tmp")))

    def test_different_existing_model_preserved_release_cached_and_reused(self):
        self.cache.parent.mkdir(parents=True, exist_ok=True)
        original=b"a previously compiled and hardware-tested Human graph"
        self.cache.write_bytes(original)
        calls=[]
        def download():
            calls.append(True)
            return io.BytesIO(self.body)
        model.stage(True, self.cache, self.output, download)
        pinned=self.cache.parent / ("release_" + model.EXPECTED_SHA256[:12]) / model.MODEL
        self.assertEqual(self.cache.read_bytes(), original)
        self.assertEqual(pinned.read_bytes(), self.body)
        self.assertEqual(self.output.read_bytes(), self.body)
        # Repeated builds must work offline, even after deleting generated output.
        self.output.unlink()
        model.stage(True, self.cache, self.output,
                    lambda: self.fail("Download should not repeat"))
        self.assertEqual(len(calls),1)
        self.assertEqual(self.cache.read_bytes(),original)

    def test_invalid_pinned_release_cache_fails_without_modifying_original(self):
        self.cache.parent.mkdir(parents=True, exist_ok=True)
        original=b"locally compiled RKNN"
        self.cache.write_bytes(original)
        pinned=self.cache.parent / ("release_" + model.EXPECTED_SHA256[:12]) / model.MODEL
        pinned.parent.mkdir(parents=True, exist_ok=True)
        pinned.write_bytes(b"invalid release cache")
        with self.assertRaises(ValueError):
            model.stage(True,self.cache,self.output,
                        lambda: self.fail("Do not trust an invalid Release cache"))
        self.assertEqual(self.cache.read_bytes(),original)
        self.assertFalse(self.output.exists())

    def test_existing_verified_apk_asset_does_not_redownload(self):
        self.output.parent.mkdir(parents=True, exist_ok=True)
        self.output.write_bytes(self.body)
        self.cache.parent.mkdir(parents=True, exist_ok=True)
        self.cache.write_bytes(b"different graph")
        model.stage(True,self.cache,self.output,
                    lambda: self.fail("Already-verified APK asset should be reused"))
        self.assertEqual(self.output.read_bytes(), self.body)
        self.assertEqual(self.cache.read_bytes(), b"different graph")


if __name__=="__main__":
    unittest.main()
