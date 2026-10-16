#!/usr/bin/env python3
"""Stage original Human SL b18 9/13/19 RKNN models into RK3588 APK assets.

Only RKNN-enabled builds bundle the ~59 MB model. Other Android targets must
never inherit a stale generated asset. Both cache and installed asset are
SHA-256 pinned to the already validated GitHub Release, not merely file size.
No .rknn binary is committed to git. No device/ADB interaction is performed.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MODEL = "katago_human_9x9.rknn"
CACHE = ROOT / "build/rk3588_human/9" / MODEL
OUTPUT = ROOT / "app/build/generated/rk3588HumanAssets/engine" / MODEL
RELEASE = ("https://github.com/rstg00po54/go/releases/download/"
           "rk3588-human-b18-9x9-device-verified-v1/" + MODEL)
# Exact SHA-256 and byte length of the published and RK3588-validated release.
EXPECTED_SHA256 = "78bee9b2561c4ba14024c1428d750e4a4cec3206011290b6b6b1d7b1e95947c7"
EXPECTED_SIZE = 59069801


def valid_model(path):
    if not path.is_file() or path.stat().st_size != EXPECTED_SIZE:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest() == EXPECTED_SHA256


def atomic_write(dest, reader):
    dest.parent.mkdir(parents=True, exist_ok=True)
    temp = None
    try:
        with tempfile.NamedTemporaryFile(prefix=MODEL + ".", suffix=".tmp",
                                         dir=dest.parent, delete=False) as sink:
            temp = Path(sink.name)
            shutil.copyfileobj(reader, sink, length=1024 * 1024)
        if not valid_model(temp):
            raise ValueError("Human b18 RKNN model SHA-256 / size mismatch")
        os.replace(temp, dest)
    finally:
        if temp is not None:
            temp.unlink(missing_ok=True)


def stage(enabled, cache=CACHE, output=OUTPUT, download=None):
    if not enabled:
        # In particular, an old RK3588 asset must not leak into vivo or CPU APKs.
        output.unlink(missing_ok=True)
        print("HUMAN_RKNN_ASSET_DISABLED")
        return
    # A previously built RKNN graph can have different binary bytes although
    # its weights/outputs are equivalent. Never delete the developer's tested
    # model just because the official Release has a different SHA-256.
    if valid_model(output):
        print("HUMAN_RKNN_ASSET_READY board=9 bytes=%d sha256=%s path=%s (reused)"
              % (EXPECTED_SIZE, EXPECTED_SHA256, output), flush=True)
        return

    source_cache = cache
    if not valid_model(cache):
        if cache.exists():
            print("HUMAN_RKNN_EXISTING_MODEL_PRESERVED path=%s (different from release)"
                  % cache, flush=True)
        # Keep the published binary in an independent, versioned directory.
        # Future builds can run offline without replacing the original graph.
        source_cache = cache.parent / ("release_" + EXPECTED_SHA256[:12]) / MODEL
        if not valid_model(source_cache):
            if source_cache.exists():
                raise ValueError("Pinned Release cache failed SHA-256 verification: "
                                 + str(source_cache))
            print("Downloading verified Human b18 RKNN Release once (~59 MB)...",
                  flush=True)
            if download is not None:
                with download() as source:
                    atomic_write(source_cache, source)
            else:
                request = urllib.request.Request(
                    RELEASE, headers={"User-Agent": "Badukai-RK3588-build"})
                with urllib.request.urlopen(request, timeout=60) as source:
                    atomic_write(source_cache, source)
    if not valid_model(output):
        with source_cache.open("rb") as source:
            atomic_write(output, source)
    print("HUMAN_RKNN_ASSET_READY board=9 bytes=%d sha256=%s path=%s"
          % (EXPECTED_SIZE, EXPECTED_SHA256, output), flush=True)



# Additional, size-specific Human b18 graphs. These are produced by separate
# original-weight 13/19 RKNN compilation workflows. Their manifest travels
# with the model; both are verified before inclusion in the APK. Once the CI
# publishes the exact hashes, PINS should be populated for immutable pinning.
EXTRA_SIZES = (11, 13, 15, 19)
# Published by workflow 38856959945; compiled/CPU-ONNX-parity-checked only.
# Device RK3588 numerical and real-game verification remains pending.
PINS = {
    11: ("d5e33822766ff86f4e06dcfda416ae791df3af292f157969f59a69713c46df29", 59067505),
    13: ("8029fdfe086e96d99d2ce7fb7340cc6bab49d232c03fb44fddcfc64866eb5b40", 59071345),
    19: ("1bcdddac3687e72705f8cd058bd2c6cd44a1ee221cf30182762598dc0ad43158", 59074417),
    15: ("73deb6fed2674fdf5061f140d80f994f121390789db534e45843a05a63158705", 59072369),
}


def verify_extra(size, binary, manifest):
    import json
    if size not in PINS or not binary.is_file() or not manifest.is_file():
        return False
    try:
        doc = json.loads(manifest.read_text(encoding="utf-8"))
        if not isinstance(doc, dict) or doc.get("model") != "b18c384nbt-humanv0":
            return False
        if doc.get("board") != size or doc.get("target") != "rk3588":
            return False
        if doc.get("quantized") is not False:
            return False
        if doc.get("inputs") != {
            "InputSpatial": [1,22,size,size], "InputGlobal": [1,19,1,1],
            "InputMeta": [1,192,1,1], "InputMask": [1,1,size,size]
        }:
            return False
        if doc.get("outputs") != ["OutputPolicyPass","OutputPolicy","OutputValue",
                                  "OutputScoreValue","OutputOwnership"]:
            return False
        digest = doc.get("sha256","")
        if not isinstance(digest,str) or len(digest) != 64 or not all(
                c in "0123456789abcdef" for c in digest):
            return False
        if size in PINS and (digest, binary.stat().st_size) != PINS[size]:
            return False
        actual = hashlib.sha256()
        with binary.open("rb") as f:
            for chunk in iter(lambda: f.read(1024*1024), b""):
                actual.update(chunk)
        return binary.stat().st_size > 1000000 and actual.hexdigest() == digest
    except (ValueError, OSError, TypeError):
        return False


def stage_extra_models(enabled, required=False, require_five=False, root=ROOT, fetch=None):
    """Install only complete, internally verified original b18 13/19 model pairs.

    The first enabled build may download the two model + manifest pairs.
    Offline rebuilds reuse the validated cache without any network call.
    If a release has not been published, normal 9x9 builds remain usable;
    --require-multisize causes such a build to fail explicitly instead.
    """
    import json
    for size in EXTRA_SIZES:
        size_required = (size in (13,19) and required) or (size in (11,15) and require_five)
        if size not in PINS:
            if size_required:
                raise ValueError("Missing immutable SHA256 pin for Human b18 %dx%d" % (size,size))
            print("HUMAN_RKNN_MULTI_ASSET_SKIPPED board=%d reason=not_pinned" % size,flush=True)
            continue
        name = "katago_human_%dx%d" % (size,size)
        output_dir = root / "app/build/generated/rk3588HumanAssets/engine"
        dest = output_dir / (name + ".rknn")
        # LiteRT already uses engine/<stem>.json. Keep the RKNN
        # release manifest in a separate APK asset so both backends can
        # coexist, even when the user's LiteRT files are untracked.
        dest_meta = output_dir / (name + ".rknn.json")
        # Remove only a stale manifest from our GENERATED assets directory.
        # Never touch the user's app/src/main/assets/engine/<stem>.json.
        (output_dir / (name + ".json")).unlink(missing_ok=True)
        if not enabled:
            dest.unlink(missing_ok=True)
            dest_meta.unlink(missing_ok=True)
            continue
        if verify_extra(size, dest, dest_meta):
            print("HUMAN_RKNN_MULTI_ASSET_READY board=%d reused=1" % size,flush=True)
            continue
        cache_dir = root / "build/rk3588_human/releases" / str(size)
        model = cache_dir / (name + ".rknn")
        meta = cache_dir / (name + ".json")
        if not verify_extra(size, model, meta):
            if model.exists() or meta.exists():
                if size_required:
                    raise ValueError("Cached Human RKNN multi-size release is invalid: " + str(cache_dir))
                print("HUMAN_RKNN_MULTI_ASSET_SKIPPED board=%d reason=invalid_cache" % size,flush=True)
                dest.unlink(missing_ok=True)
                dest_meta.unlink(missing_ok=True)
                continue
            tag = ("rk3588-dual-human-10b-%dx%d-experimental-v1" if size in (11,15)
                   else "rk3588-human-b18-%dx%d-experimental-v1") % (size,size)
            url = "https://github.com/rstg00po54/go/releases/download/" + tag + "/"
            print("HUMAN_RKNN_MULTI_DOWNLOAD board=%d" % size,flush=True)
            cache_dir.mkdir(parents=True,exist_ok=True)
            import urllib.error
            def transfer(file, target):
                if fetch is not None:
                    with fetch(url + file) as stream:
                        raw = stream.read()
                else:
                    req=urllib.request.Request(url+file,headers={"User-Agent":"Badukai-RK3588-build"})
                    with urllib.request.urlopen(req,timeout=90) as stream:
                        raw=stream.read()
                temp = target.with_suffix(target.suffix+".download")
                try:
                    temp.write_bytes(raw)
                    os.replace(temp,target)
                finally:
                    temp.unlink(missing_ok=True)
            try:
                # Manifest first: rejecting wrong-size metadata before model download.
                transfer(name+".json",meta)
                doc=json.loads(meta.read_text(encoding="utf-8"))
                if (doc.get("model")!="b18c384nbt-humanv0" or doc.get("board")!=size or
                    doc.get("target")!="rk3588" or doc.get("quantized") is not False):
                    raise ValueError("Human b18 release manifest does not match board")
                transfer(name+".rknn",model)
                if not verify_extra(size,model,meta):
                    raise ValueError("Human b18 model hash or tensor contract mismatch")
            except (OSError,ValueError,TypeError,urllib.error.URLError) as err:
                model.unlink(missing_ok=True)
                meta.unlink(missing_ok=True)
                dest.unlink(missing_ok=True)
                dest_meta.unlink(missing_ok=True)
                if size_required:
                    raise
                print("HUMAN_RKNN_MULTI_ASSET_SKIPPED board=%d reason=%s" %
                      (size,type(err).__name__),flush=True)
                continue
        output_dir.mkdir(parents=True,exist_ok=True)
        for source,target in ((model,dest),(meta,dest_meta)):
            with source.open("rb") as src:
                temp=target.with_suffix(target.suffix+".tmp")
                try:
                    with temp.open("wb") as out:
                        shutil.copyfileobj(src,out,length=1024*1024)
                    os.replace(temp,target)
                finally:
                    temp.unlink(missing_ok=True)
        if not verify_extra(size,dest,dest_meta):
            dest.unlink(missing_ok=True)
            dest_meta.unlink(missing_ok=True)
            raise ValueError("Copied Human RKNN graph failed post-stage verification")
        print("HUMAN_RKNN_MULTI_ASSET_READY board=%d sha256=%s" %
              (size,json.loads(dest_meta.read_text())["sha256"]),flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--enabled", action="store_true")
    mode.add_argument("--disabled", action="store_true")
    parser.add_argument("--require-multisize", action="store_true",
                        help="Fail build if either 13x13 or 19x19 RKNN release is absent")
    parser.add_argument("--require-five-sizes", action="store_true",
                        help="Fail unless 11x11 and 15x15 Human b18 are pinned and bundled")
    args = parser.parse_args()
    stage(args.enabled)
    stage_extra_models(args.enabled, required=args.require_multisize,
                       require_five=args.require_five_sizes)


if __name__ == "__main__":
    main()
