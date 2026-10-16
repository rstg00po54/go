#!/usr/bin/env python3
"""Stage verified original Human SL b18 9x9 RKNN into RK3588 APK assets.

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
           "rk3588-human-b18-9x9-experimental-v1/" + MODEL)
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
    if not valid_model(cache):
        if cache.exists():
            # Do not destroy an explicitly supplied alternative model.
            raise ValueError("Cached RK3588 Human model differs from pinned validated release: " + str(cache))
        print("Downloading validated RK3588 Human SL b18 9x9 RKNN (~59 MB)...", flush=True)
        if download is not None:
            with download() as source:
                atomic_write(cache, source)
        else:
            request = urllib.request.Request(RELEASE, headers={"User-Agent": "Badukai-RK3588-build"})
            with urllib.request.urlopen(request, timeout=60) as source:
                atomic_write(cache, source)
    if not valid_model(output):
        with cache.open("rb") as source:
            atomic_write(output, source)
    print("HUMAN_RKNN_ASSET_READY board=9 bytes=%d sha256=%s path=%s"
          % (EXPECTED_SIZE, EXPECTED_SHA256, output), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--enabled", action="store_true")
    mode.add_argument("--disabled", action="store_true")
    args = parser.parse_args()
    stage(args.enabled)


if __name__ == "__main__":
    main()
