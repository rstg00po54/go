#!/usr/bin/env python3
"""Reject vivo LiteRT probe APKs missing any of the six pinned KataGo graphs/manifests."""
import argparse
import hashlib
from pathlib import Path
import sys
import zipfile
import tempfile
import shutil
from pack_katago_models import restore_delta

EXPECTED = {
    "katago_10b_9x9.tflite": (12020404, "eaf37768b48021e579e38f9bae0efbcb17f5c5a84d80d288430ee11784f1f039"),
    "katago_10b_9x9.json": (1704, "f53e2d36342416d325a39a7147e4b9e6e59f4d62825e24725f4fb398265f7ec3"),
    "katago_10b_13x13.tflite": (12020404, "646788f1928fb39450486b5033eee7f5d84eb48f21064f3791c46dd9c74e5658"),
    "katago_10b_13x13.json": (1716, "bb089a6ce61b1b9d32db0fb20db3afd8a4763390209d68962d3967d78cbda097"),
    "katago_10b_19x19.tflite": (12020404, "9bbab05d27e2fa0b9cac64dcb4d81f7121f6731d3e0176ba08e59041f0382181"),
    "katago_10b_19x19.json": (1716, "1447d7a005b7920de1e1f80fa75fb6029d6baee42380689b71eef362ca8acf69"),
}


# The v1 release's graph binaries are unchanged, but scale8-aware manifests
# now carry explicit ONNX output metadata. Old cached manifests stay accepted.
ALTERNATE_SCALE_AWARE_JSON = {
    "katago_10b_9x9.json": (1761, "4ed35051e22638a7c2ed43db2c022a5ceefb75f9c5b10d3704f7b05688dc2b2a"),
    "katago_10b_13x13.json": (1773, "c6ad7043dc85d3cdd64fb7523ff03b9ded52c032d5ff3a410391763bce37739f"),
    "katago_10b_19x19.json": (1773, "5ae0ccacaa62eb74060ef33b74db3a58e1f7dd7590c36bf055c5d42135aeafe1"),
}


def verify_apk(path, expected=None):
    if expected is None: expected = EXPECTED
    if not Path(path).is_file(): raise ValueError("APK missing: " + str(path))
    with zipfile.ZipFile(path) as archive:
        seen = set(archive.namelist())
        for name, (length, digest) in expected.items():
            asset = "assets/engine/" + name
            # Only the known original manifests have a second accepted hash.
            accepted = [(length, digest)]
            if expected is EXPECTED and name in ALTERNATE_SCALE_AWARE_JSON:
                accepted.append(ALTERNATE_SCALE_AWARE_JSON[name])
            if asset in seen:
                source_type = "full"
                info = archive.getinfo(asset)
                size = info.file_size
                sha = hashlib.sha256()
                with archive.open(info) as data:
                    for chunk in iter(lambda: data.read(1024 * 1024), b""):
                        sha.update(chunk)
                actual = sha.hexdigest()
            elif name.endswith(".tflite") and asset + ".kgdelta" in seen:
                source_type = "delta"
                # Compact build: independently restore the original from its
                # base, then verify against the exact same pinned digest.
                # The suffix-based name works for both katago_10b and human.
                base_asset = "assets/engine/" + name.rsplit("_", 1)[0] + "_19x19.tflite"
                if base_asset not in seen:
                    raise ValueError("Missing compact model base inside APK: " + base_asset)
                with tempfile.TemporaryDirectory(prefix="verify_litert_") as tmp:
                    base = Path(tmp) / "base.tflite"
                    patch = Path(tmp) / "patch.kgdelta"
                    restored = Path(tmp) / "restored.tflite"
                    for archive_name, local in ((base_asset, base),
                                                (asset + ".kgdelta", patch)):
                        with archive.open(archive_name) as src, local.open("wb") as dst:
                            shutil.copyfileobj(src, dst, 1024 * 1024)
                    restore_delta(base, patch, restored)
                    size = restored.stat().st_size
                    sha = hashlib.sha256()
                    with restored.open("rb") as data:
                        for chunk in iter(lambda: data.read(1024 * 1024), b""):
                            sha.update(chunk)
                    actual = sha.hexdigest()
            else:
                raise ValueError("Missing model inside APK: " + asset +
                                 " (build with -PvivoProbeApk=true)")
            if size not in [item[0] for item in accepted]:
                raise ValueError("Incorrect model length in APK: %s actual=%d accepted=%s" %
                                 (asset, size, [item[0] for item in accepted]))
            if (size, actual) not in accepted:
                raise ValueError("Incorrect model SHA256 in APK: " + asset)
            print("APK_MODEL_OK %s bytes=%d source=%s" % (asset, size, source_type), flush=True)
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
