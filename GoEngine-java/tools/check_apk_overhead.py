#!/usr/bin/env python3
"""Check oversized ZIP/APK overhead and optionally drop a stale APK *before* Gradle.

The difference between on-disk APK bytes and compressed bytes of indexed ZIP
entries includes ZIP headers, padding, and APK signing blocks. Normally small;
hundreds of MiB often indicate unreferenced old bytes from incremental ZIP
packaging. Do NOT rewrite an already signed APK with zipfile: that invalidates
its signature. Deleting only the disposable build OUTPUT allows AGP to create
and sign a fresh APK.

Usage:
  python3 tools/check_apk_overhead.py path/to/app-debug.apk
  python3 tools/check_apk_overhead.py path/to/app-debug.apk --prune-stale
  python3 tools/check_apk_overhead.py path/to/app-debug.apk --fail-large
"""
import argparse
from pathlib import Path
import sys
import zipfile

DEFAULT_LIMIT_MIB = 32


def inspect(path):
    with zipfile.ZipFile(path) as apk:
        compressed = sum(info.compress_size for info in apk.infolist())
        entries = len(apk.infolist())
    physical = path.stat().st_size
    return physical, compressed, physical - compressed, entries


def run(path, limit_mib=DEFAULT_LIMIT_MIB, prune_stale=False, fail_large=False):
    if not path.is_file():
        print("APK_OVERHEAD_SKIP no prior APK: " + str(path), flush=True)
        return 0
    try:
        physical, compressed, overhead, entries = inspect(path)
    except (OSError, zipfile.BadZipFile) as error:
        print(f"APK_OVERHEAD_ERROR {error}", file=sys.stderr)
        return 2
    threshold = limit_mib * 1024 * 1024
    print("APK_OVERHEAD_CHECK actual=%.1f MiB indexed=%.1f MiB overhead=%.1f MiB entries=%d"
          % (physical / 1048576, compressed / 1048576, overhead / 1048576, entries), flush=True)
    if overhead <= threshold:
        return 0
    if prune_stale:
        # NEVER touch the user's source, models or build intermediates.
        # Only remove this known APK build artifact; Gradle will repackage
        # and sign a fresh APK in its normal task lifecycle.
        if path.name != "app-debug.apk" or path.parent.name != "debug" or path.parent.parent.name != "apk":
            print("APK_OVERHEAD_ERROR refusing to remove unexpected path: " + str(path),
                  file=sys.stderr)
            return 2
        try:
            path.unlink()
        except OSError as error:
            print("APK_OVERHEAD_ERROR unable to delete old APK: " + str(error), file=sys.stderr)
            return 2
        print("APK_OVERHEAD_PRUNED old oversized build output; Gradle will repackage", flush=True)
        return 0
    if fail_large:
        print("APK_OVERHEAD_FAIL %.1f MiB unused/non-entry bytes exceed %.1f MiB"
              % (overhead / 1048576, limit_mib), file=sys.stderr)
        return 1
    print("APK_OVERHEAD_WARNING unusually large non-entry bytes", file=sys.stderr)
    return 0


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("apk", type=Path)
    p.add_argument("--limit-mib", type=float, default=DEFAULT_LIMIT_MIB)
    p.add_argument("--prune-stale", action="store_true")
    p.add_argument("--fail-large", action="store_true")
    args = p.parse_args()
    return run(args.apk, args.limit_mib, args.prune_stale, args.fail_large)


if __name__ == "__main__":
    sys.exit(main())
