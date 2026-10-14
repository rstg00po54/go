#!/usr/bin/env python3
"""One authoritative validator for the Android ARM64 KataGo PIE executable.

Used by build.sh, Gradle stageKataGoNative, and the native build helper.
Validates ELF64 little-endian ET_DYN, EM_AARCH64 and all 16 KB PT_LOAD segments.
"""
import argparse
import struct
from pathlib import Path

from check_android_16kb import inspect_library

ELF_ET_DYN = 3
ELF_EM_AARCH64 = 183


def validate_pie(path, page_size=16384):
    path = Path(path)
    try:
        data = path.read_bytes()
    except OSError as exc:
        return False, f"{path}: cannot read executable: {exc}"
    if len(data) < 64 or data[:4] != b"\x7fELF" or data[4] != 2 or data[5] != 1:
        return False, f"{path}: expected little-endian ELF64 executable"
    elf_type, machine = struct.unpack_from("<HH", data, 16)
    if elf_type != ELF_ET_DYN or machine != ELF_EM_AARCH64:
        return False, f"{path}: expected Android AArch64 PIE (ET_DYN=3, EM_AARCH64=183); got type={elf_type}, machine={machine}"
    try:
        segments, failures = inspect_library(str(path), data, page_size=page_size)
    except ValueError as exc:
        return False, str(exc)
    if failures:
        return False, f"{path}: {page_size // 1024} KB ELF LOAD alignment failed: {'; '.join(failures)}"
    return True, f"{path}: AArch64 PIE OK (page={page_size // 1024} KB); PT_LOAD p_align={', '.join(hex(x[2]) for x in segments)}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("executable", type=Path)
    parser.add_argument("--page-size", type=int, choices=(4096, 16384), default=16384,
                        help="Minimum PT_LOAD alignment (default: 16 KB)")
    args = parser.parse_args()
    valid, message = validate_pie(args.executable, page_size=args.page_size)
    print(("PASS " if valid else "FAIL ") + message, flush=True)
    return 0 if valid else 1


if __name__ == "__main__":
    raise SystemExit(main())
