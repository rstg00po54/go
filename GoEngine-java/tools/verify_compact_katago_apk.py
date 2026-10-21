#!/usr/bin/env python3
"""Fail a compact build if the final APK still bundles duplicate full graphs.

Also print actual (compressed and uncompressed) APK size by asset family.
This deliberately checks the *APK*, not just Gradle's generated source directory.
No third-party modules. Usage:
  python3 tools/verify_compact_katago_apk.py \
    app/build/outputs/apk/debug/app-debug.apk \
    app/build/generated/katagoCompactAssets
"""
import argparse
import collections
import pathlib
import sys
import zipfile

GRAPH_EXTS = (".tflite", ".rknn")
DUPLICATE_SIZES = (9, 11, 13, 15)


def group_name(path):
    if path.startswith("assets/engine/"):
        filename = path[len("assets/engine/"):]
        if filename.startswith("katago_human_") and ".tflite" in filename:
            return "Human TFLite"
        if filename.startswith("katago_human_") and ".rknn" in filename:
            return "Human RKNN"
        if filename.startswith("katago_10b_") and ".tflite" in filename:
            return "10b TFLite"
        if filename.startswith("katago_10b_") and ".rknn" in filename:
            return "10b RKNN"
        if filename.endswith((".bin", ".bin.gz")):
            return "Original KataGo BIN"
        return "Other engine assets"
    if path.startswith("lib/"):
        return "Native libraries"
    return "Other APK content"


def verify_apk(apk, generated):
    if not apk.is_file():
        raise ValueError("APK not found: " + str(apk))
    if not generated.is_dir():
        raise ValueError("Compact asset staging directory missing: " + str(generated))
    root = generated / "engine"
    if not root.is_dir():
        raise ValueError("Compact stage lacks engine directory: " + str(root))
    required = {}
    for model in ("human", "10b"):
        for ext in GRAPH_EXTS:
            base = f"katago_{model}_19x19{ext}"
            if not (root / base).is_file():
                continue
            for board in DUPLICATE_SIZES:
                name = f"katago_{model}_{board}x{board}{ext}"
                patch = name + ".kgdelta"
                if (root / patch).is_file():
                    if (root / name).exists():
                        raise ValueError("Stage contains original and delta: " + name)
                    required["assets/engine/" + patch] = (root / patch).stat().st_size
                    required["assets/engine/" + base] = (root / base).stat().st_size
    if not required:
        raise ValueError("No compact model patches found; refusing misleading successful build")
    rows = collections.defaultdict(lambda: [0, 0, 0])
    with zipfile.ZipFile(apk) as archive:
        members = {info.filename: info for info in archive.infolist()}
        for path, size in required.items():
            if path not in members:
                raise ValueError("Compact model missing from final APK: " + path)
            if members[path].file_size != size:
                raise ValueError("Compact model incorrect length in APK: " + path)
        for path in members:
            for model in ("human", "10b"):
                for ext in GRAPH_EXTS:
                    for board in DUPLICATE_SIZES:
                        name = f"katago_{model}_{board}x{board}{ext}"
                        original = "assets/engine/" + name
                        delta = original + ".kgdelta"
                        if delta in required and path == original:
                            raise ValueError("Full duplicate model STILL PRESENT in final APK: " + original)
        for item in members.values():
            section = group_name(item.filename)
            row = rows[section]
            row[0] += item.compress_size
            row[1] += item.file_size
            row[2] += 1
    payload = sum(row[0] for row in rows.values())
    physical = apk.stat().st_size
    overhead = physical - payload
    print("APK_PHYSICAL_SIZE actual=%.1f MiB compressed_entries=%.1f MiB overhead=%.1f MiB"
          % (physical / 1048576, payload / 1048576, overhead / 1048576))
    if overhead > 32 * 1048576:
        raise ValueError("Final APK has %.1f MiB of non-entry bytes (maximum 32 MiB). "
                         "Old incremental APK may contain unreachable model data. "
                         "Use bash build.sh --vivo --apk-only or --rk3588 --apk-only "
                         "to repackage and sign the APK, without Gradle clean."
                         % (overhead / 1048576))
    print("COMPACT_APK_PASS verified_assets=%d" % len(required))
    print("APK_SIZE_BREAKDOWN  compressed MiB | extracted MiB | entries")
    for section, (compressed, extracted, count) in sorted(rows.items(),
            key=lambda kv: kv[1][0], reverse=True):
        print("  %-23s %9.1f | %9.1f | %d" %
              (section, compressed / 1048576, extracted / 1048576, count))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=pathlib.Path)
    parser.add_argument("generated", type=pathlib.Path)
    args = parser.parse_args()
    try:
        verify_apk(args.apk, args.generated)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        print("COMPACT_APK_FAIL: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
