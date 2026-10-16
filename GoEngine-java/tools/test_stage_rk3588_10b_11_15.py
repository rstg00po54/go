#!/usr/bin/env python3
"""Offline regression tests for SHA-pinned original 10b 11/15 RKNN APK staging."""
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import stage_rk3588_10b_11_15 as stage

class StagingTests(unittest.TestCase):
    def _models(self):
        result={}
        pins={}
        for n in stage.SIZES:
            body=("g170-model-%d" % n).encode()*120000
            sha=hashlib.sha256(body).hexdigest()
            pins[n]=(sha,len(body))
            meta={
                "model":"g170-b10c128-s1141046784-d204142634",
                "board":n,"target":"rk3588","quantized":False,
                "inputs":{"InputSpatial":[1,22,n,n],"InputGlobal":[1,19,1,1],
                          "InputMask":[1,1,n,n]},
                "outputs":["OutputPolicyPass","OutputPolicy","OutputValue",
                           "OutputScoreValue","OutputOwnership"],"sha256":sha,
            }
            result[n]=(body,json.dumps(meta).encode())
        return pins,result

    def test_cached_assets_reused_offline_and_litert_files_preserved(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            pins,data=self._models()
            litert=root/"app/src/main/assets/engine"
            litert.mkdir(parents=True)
            for n in stage.SIZES:
                (litert/f"katago_10b_{n}x{n}.json").write_text("local LiteRT")
            requests=[]
            def fetch(url):
                n=11 if "11x11" in url else 15
                requests.append(url)
                return io.BytesIO(data[n][1 if url.endswith(".json") else 0])
            with mock.patch.object(stage,"PINS",pins):
                stage.stage(True,required=True,root=root,fetch=fetch)
                self.assertEqual(len(requests),4)
                for n in stage.SIZES:
                    f=root/f"app/build/generated/rk3588HumanAssets/engine/katago_10b_{n}x{n}.rknn"
                    self.assertTrue(stage.verify(n,f,f.with_suffix(".rknn.json")))
                    self.assertEqual((litert/f"katago_10b_{n}x{n}.json").read_text(),
                                     "local LiteRT")
                stage.stage(True,required=True,root=root,
                            fetch=lambda u:self.fail("should be offline"))
                for n in stage.SIZES:
                    f=root/f"app/build/generated/rk3588HumanAssets/engine/katago_10b_{n}x{n}.rknn"
                    f.unlink()
                stage.stage(True,required=True,root=root,
                            fetch=lambda u:self.fail("cached graph must be reused"))
                stage.stage(False,root=root)
                for n in stage.SIZES:
                    f=root/f"app/build/generated/rk3588HumanAssets/engine/katago_10b_{n}x{n}.rknn"
                    self.assertFalse(f.exists())
                    self.assertFalse(f.with_suffix(".rknn.json").exists())
                    self.assertTrue((litert/f"katago_10b_{n}x{n}.json").exists())

    def test_corrupt_first_download_retries_and_never_caches_bad_model(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            pins,data=self._models()
            seen={11:0,15:0}
            def fetch(url):
                n=11 if "11x11" in url else 15
                if url.endswith(".json"):
                    return io.BytesIO(data[n][1])
                seen[n]+=1
                if n==11 and seen[n]==1:
                    return io.BytesIO(b"corrupted download from proxy")
                return io.BytesIO(data[n][0])
            with mock.patch.object(stage,"PINS",pins):
                stage.stage(True,required=True,root=root,fetch=fetch)
                self.assertEqual(seen,{11:2,15:1})
                for n in stage.SIZES:
                    directory=root/"build/rk3588_10b/releases"/str(n)
                    binary=directory/f"katago_10b_{n}x{n}.rknn"
                    manifest=directory/f"katago_10b_{n}x{n}.rknn.json"
                    self.assertTrue(stage.verify(n,binary,manifest))
                    self.assertFalse(list(directory.glob("*.download")))
                self.assertFalse(any(root.rglob("*corrupted*")))

    def test_invalid_download_explains_size_hash_and_never_uses_unverified_assets(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            pins,data=self._models()
            def fetch(url):
                n=11 if "11x11" in url else 15
                return io.BytesIO(data[n][1] if url.endswith(".json") else b"not a valid RKNN")
            with mock.patch.object(stage,"PINS",pins):
                with self.assertRaisesRegex(RuntimeError,"actualBytes=16"):
                    stage.stage(True,required=True,root=root,fetch=fetch)
                target=root/"app/build/generated/rk3588HumanAssets/engine/katago_10b_11x11.rknn"
                self.assertFalse(target.exists())
                self.assertFalse((root/"build/rk3588_10b/releases/11/katago_10b_11x11.rknn").exists())
                self.assertFalse(list((root/"build/rk3588_10b/releases/11").glob("*.download")))

    def test_bad_sha_or_shape_cannot_pass(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            pins,data=self._models()
            binary=root/"model.rknn"
            meta=root/"model.rknn.json"
            with mock.patch.object(stage,"PINS",pins):
                binary.write_bytes(data[11][0])
                meta.write_bytes(data[11][1])
                self.assertTrue(stage.verify(11,binary,meta))
                meta.write_bytes(data[15][1])
                self.assertFalse(stage.verify(11,binary,meta))
                binary.write_bytes(b"wrong")
                meta.write_bytes(data[11][1])
                self.assertFalse(stage.verify(11,binary,meta))

if __name__=="__main__":
    unittest.main()
