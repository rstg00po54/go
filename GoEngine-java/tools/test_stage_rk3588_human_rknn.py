#!/usr/bin/env python3
"""Unit tests for bundling the pinned RK3588 Human b18 model, without network."""
import hashlib
import json
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


    def test_13_19_are_pinned_verified_and_reused_offline(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            fixtures={}
            pins={}
            for size in (13,19):
                data=(b"RKNN_MULTI_SOURCE_%d_" % size) * 68000
                sha=hashlib.sha256(data).hexdigest()
                pins[size]=(sha,len(data))
                fixtures[size]={
                    "model":data,
                    "manifest":json.dumps({
                        "model":"b18c384nbt-humanv0",
                        "board":size,"target":"rk3588","quantized":False,
                        "inputs":{"InputSpatial":[1,22,size,size],
                                  "InputGlobal":[1,19,1,1],
                                  "InputMeta":[1,192,1,1],
                                  "InputMask":[1,1,size,size]},
                        "outputs":["OutputPolicyPass","OutputPolicy","OutputValue",
                                   "OutputScoreValue","OutputOwnership"],
                        "sha256":sha,"runtimeVerified":False
                    }).encode()
                }
            calls=[]
            def fetch(url):
                size=13 if "13x13" in url else 19
                kind="manifest" if url.endswith(".json") else "model"
                calls.append((size,kind))
                return io.BytesIO(fixtures[size][kind])
            with mock.patch.object(model,"PINS",pins):
                model.stage_extra_models(True, required=True, root=root, fetch=fetch)
                self.assertEqual(len(calls),4)
                for size in (13,19):
                    out=root/f"app/build/generated/rk3588HumanAssets/engine/katago_human_{size}x{size}.rknn"
                    meta=out.with_suffix(".rknn.json")
                    self.assertTrue(model.verify_extra(size,out,meta))
                model.stage_extra_models(True, required=True, root=root,
                    fetch=lambda _:self.fail("Release must be cached after first stage"))
                self.assertEqual(len(calls),4)
                model.stage_extra_models(False, root=root)
                for size in (13,19):
                    out=root/f"app/build/generated/rk3588HumanAssets/engine/katago_human_{size}x{size}.rknn"
                    self.assertFalse(out.exists())
                model.stage_extra_models(True, required=True, root=root,
                    fetch=lambda _:self.fail("Validated cache must allow offline build"))

    def test_keep_litert_manifests_while_removing_stale_generated_rknn_names(self):
        # Locally downloaded LiteRT models are untracked and may occupy
        # katago_human_13x13.json / katago_human_19x19.json.
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            app_assets=root/"app/src/main/assets/engine"
            generated=root/"app/build/generated/rk3588HumanAssets/engine"
            app_assets.mkdir(parents=True)
            generated.mkdir(parents=True)
            for size in (13,19):
                name=f"katago_human_{size}x{size}"
                (app_assets/(name+".json")).write_text("LiteRT user manifest")
                (app_assets/(name+".tflite")).write_bytes(b"user LiteRT weights")
                # Simulate stale assets from an older APK build.
                (generated/(name+".json")).write_text("obsolete RKNN manifest")
                (generated/(name+".rknn.json")).write_text("previous APK metadata")
            model.stage_extra_models(False, root=root)
            for size in (13,19):
                name=f"katago_human_{size}x{size}"
                self.assertEqual((app_assets/(name+".json")).read_text(),
                                 "LiteRT user manifest")
                self.assertEqual((app_assets/(name+".tflite")).read_bytes(),
                                 b"user LiteRT weights")
                self.assertFalse((generated/(name+".json")).exists())
                self.assertFalse((generated/(name+".rknn.json")).exists())

    def test_stage_multisize_uses_distinct_rknn_metadata_name(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            app_assets=root/"app/src/main/assets/engine"
            app_assets.mkdir(parents=True)
            for size in (13,19):
                (app_assets/f"katago_human_{size}x{size}.json").write_text(
                    "untracked LiteRT JSON")
            pins={}
            fixtures={}
            for size in (13,19):
                payload=(f"HumanRKNN{size}".encode())*120000
                digest=hashlib.sha256(payload).hexdigest()
                pins[size]=(digest,len(payload))
                fixtures[size]=(payload,json.dumps({
                    "model":"b18c384nbt-humanv0","board":size,
                    "target":"rk3588","quantized":False,
                    "inputs":{"InputSpatial":[1,22,size,size],
                              "InputGlobal":[1,19,1,1],
                              "InputMeta":[1,192,1,1],
                              "InputMask":[1,1,size,size]},
                    "outputs":["OutputPolicyPass","OutputPolicy","OutputValue",
                               "OutputScoreValue","OutputOwnership"],
                    "sha256":digest}).encode())
            def fetch(url):
                size=13 if "13x13" in url else 19
                return io.BytesIO(fixtures[size][1 if url.endswith(".json") else 0])
            generated=root/"app/build/generated/rk3588HumanAssets/engine"
            generated.mkdir(parents=True)
            for size in (13,19):
                (generated/f"katago_human_{size}x{size}.json").write_text(
                    "stale generated JSON")
            with mock.patch.object(model,"PINS",pins):
                model.stage_extra_models(True,required=True,root=root,fetch=fetch)
                for size in (13,19):
                    name=f"katago_human_{size}x{size}"
                    self.assertTrue(model.verify_extra(
                        size, generated/(name+".rknn"),
                        generated/(name+".rknn.json")))
                    self.assertFalse((generated/(name+".json")).exists())
                    self.assertEqual((app_assets/(name+".json")).read_text(),
                                     "untracked LiteRT JSON")

    def test_13_19_reject_model_not_matching_release_pin(self):
        with tempfile.TemporaryDirectory() as td:
            root=Path(td)
            sha=hashlib.sha256(b"other graph").hexdigest()
            info={"model":"b18c384nbt-humanv0","board":13,"target":"rk3588",
                  "quantized":False,"inputs":{"InputSpatial":[1,22,13,13],
                  "InputGlobal":[1,19,1,1],"InputMeta":[1,192,1,1],
                  "InputMask":[1,1,13,13]},
                  "outputs":["OutputPolicyPass","OutputPolicy","OutputValue",
                             "OutputScoreValue","OutputOwnership"],"sha256":sha}
            folder=root/"models"
            folder.mkdir(parents=True)
            b=folder/"fake.rknn"
            m=folder/"fake.json"
            b.write_bytes(b"other graph"*200000)
            m.write_text(json.dumps(info))
            self.assertFalse(model.verify_extra(13,b,m))
            with mock.patch.object(model,"PINS",{13:(sha,b.stat().st_size)}):
                self.assertFalse(model.verify_extra(13,b,m))



if __name__=="__main__":
    unittest.main()
