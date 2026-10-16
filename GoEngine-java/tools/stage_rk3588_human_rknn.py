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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--enabled", action="store_true")
    mode.add_argument("--disabled", action="store_true")
    args = parser.parse_args()
    stage(args.enabled)


if __name__ == "__main__":
    main()
