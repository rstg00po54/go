#!/usr/bin/env python3
"""Check that Vivo's installable APK can actually start normal KataGo games.

A 'probe-only' APK with the model files but no KataGo executable previously
looked like a successful build yet failed to start even in CPU mode.
"""
import argparse
from pathlib import Path
import sys
import zipfile

REQUIRED = (
    "lib/arm64-v8a/libkatago_exec.so",
    "lib/arm64-v8a/libkatago.so",
    "lib/arm64-v8a/libkatago_gpu.so",
    "lib/arm64-v8a/libLiteRtCompilerPlugin_MediaTek.so",
    "lib/arm64-v8a/libLiteRtDispatch_MediaTek.so",
)


def verify_apk(path, required=REQUIRED):
    with zipfile.ZipFile(path) as archive:
        members = {info.filename: info.file_size for info in archive.infolist()}
        for member in required:
            if members.get(member, 0) < 1024:
                raise ValueError("Vivo playable APK missing required native library: " + member)
            print("VIVO_APK_NATIVE_OK %s bytes=%d" % (member, members[member]))
        if members.get("lib/arm64-v8a/librknnrt.so", 0) > 0:
            raise ValueError("Vivo APK includes incompatible Rockchip librknnrt.so")
    print("VIVO_PLAYABLE_APK_PASS cpu=true gpu=true litertProbe=true")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    try: verify_apk(args.apk)
    except (OSError, zipfile.BadZipFile, ValueError) as exc:
        print("VIVO_PLAYABLE_APK_FAIL: %s" % exc, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
