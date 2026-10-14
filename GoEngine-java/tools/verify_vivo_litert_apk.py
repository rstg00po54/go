#!/usr/bin/env python3
"""Reject vivo LiteRT probe APKs missing any of the six pinned KataGo graphs/manifests."""
import argparse
import hashlib
from pathlib import Path
import sys
import zipfile

EXPECTED = {
    "katago_10b_9x9.tflite": (12020404, "eaf37768b48021e579e38f9bae0efbcb17f5c5a84d80d288430ee11784f1f039"),
    "katago_10b_9x9.json": (1704, "f53e2d36342416d325a39a7147e4b9e6e59f4d62825e24725f4fb398265f7ec3"),
    "katago_10b_13x13.tflite": (12020404, "646788f1928fb39450486b5033eee7f5d84eb48f21064f3791c46dd9c74e5658"),
    "katago_10b_13x13.json": (1716, "bb089a6ce61b1b9d32db0fb20db3afd8a4763390209d68962d3967d78cbda097"),
    "katago_10b_19x19.tflite": (12020404, "9bbab05d27e2fa0b9cac64dcb4d81f7121f6731d3e0176ba08e59041f0382181"),
    "katago_10b_19x19.json": (1716, "1447d7a005b7920de1e1f80fa75fb6029d6baee42380689b71eef362ca8acf69"),
}


def verify_apk(path, expected=None):
    if expected is None: expected = EXPECTED
    if not Path(path).is_file(): raise ValueError("APK missing: " + str(path))
    with zipfile.ZipFile(path) as archive:
        seen = set(archive.namelist())
        for name, (length, digest) in expected.items():
            asset = "assets/engine/" + name
            if asset not in seen:
                raise ValueError("Missing model inside APK: " + asset +
                                 " (build with -PvivoProbeApk=true)")
            info = archive.getinfo(asset)
            if info.file_size != length:
                raise ValueError("Incorrect model length in APK: %s actual=%d expected=%d" %
                                 (asset, info.file_size, length))
            sha = hashlib.sha256()
            with archive.open(info) as f:
                for chunk in iter(lambda: f.read(1024 * 1024), b""):
                    sha.update(chunk)
            if sha.hexdigest() != digest:
                raise ValueError("Incorrect model SHA256 in APK: " + asset)
            print("APK_MODEL_OK %s bytes=%d" % (asset, length), flush=True)
        if any(name.endswith("/librknnrt.so") for name in seen):
            raise ValueError("Vivo APK contains Rockchip-only librknnrt.so")
    print("VIVO_LITERT_APK_PASS models=%d apk=%s" % (len(expected), path), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    try:
        verify_apk(args.apk)
    except (OSError, zipfile.BadZipFile, ValueError) as e:
        print("VIVO_LITERT_APK_FAIL: " + str(e), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
