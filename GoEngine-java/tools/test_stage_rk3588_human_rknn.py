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
        self.assertEqual(self.cache.read_bytes(),self.body)
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

    def test_corrupt_cache_is_not_silently_overwritten(self):
        self.cache.parent.mkdir(parents=True,exist_ok=True)
        self.cache.write_bytes(b"not the release")
        with self.assertRaises(ValueError):
            model.stage(True,self.cache,self.output,lambda: io.BytesIO(self.body))
        self.assertEqual(self.cache.read_bytes(),b"not the release")
        self.assertFalse(self.output.exists())


if __name__=="__main__":
    unittest.main()
