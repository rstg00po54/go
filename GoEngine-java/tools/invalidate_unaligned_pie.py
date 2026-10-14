#!/usr/bin/env python3
"""Invalidate an old Android KataGo PIE whose PT_LOAD segments are not 16 KB-aligned.

Deleting only the CMake-generated executable makes Ninja relink it using the
current linker flags without recompiling the C++ objects.  Packaged prebuilts
and JNI libraries are never modified.
"""
import argparse
from pathlib import Path

from check_katago_pie import validate_pie


def needs_relink(path):
    if not path.is_file():
        return False
    valid, _ = validate_pie(path)
    return not valid


def invalidate_if_needed(path):
    if not needs_relink(path):
        return False
    path.unlink()
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("generated_pie", type=Path)
    args = parser.parse_args()
    path = args.generated_pie
    if path.name != "katago" or not path.parent.name.startswith("build_android_arm64_"):
        parser.error("Only a generated build_android_arm64_*/katago executable can be invalidated")
    if invalidate_if_needed(path):
        print("Removed stale 4 KB-aligned KataGo PIE; Ninja will relink: " + str(path), flush=True)
    else:
        print("KataGo PIE already aligned (or not built yet): " + str(path), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
