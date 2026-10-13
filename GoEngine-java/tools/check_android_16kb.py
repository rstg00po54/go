#!/usr/bin/env python3
"""Verify ELF PT_LOAD page alignment of every packaged Android native library.

Usage: python3 tools/check_android_16kb.py app/build/outputs/apk/debug/app-debug.apk

This checks ELF segment alignment, NOT APK zip entry offsets. The app currently
uses extracted native libraries (useLegacyPackaging=true), so it does not need
16 KB zip entry alignment for those libraries.
"""
import argparse
import struct
import sys
import zipfile
from pathlib import Path

PAGE_SIZE = 16384
PT_LOAD = 1


def load_segments(name, content):
    if len(content) < 52 or content[:4] != b"\x7fELF":
        raise ValueError(f"{name}: missing or truncated ELF header")
    if content[5] != 1:
        raise ValueError(f"{name}: expected little-endian ELF")
    if content[4] == 2:  # ELF64
        if len(content) < 64:
            raise ValueError(f"{name}: truncated ELF64 header")
        offset, entry_size, count = struct.unpack_from("<Q", content, 32)[0], *struct.unpack_from("<HH", content, 54)
        header_format = "<IIQQQQQQ"
    elif content[4] == 1:  # ELF32
        offset, entry_size, count = struct.unpack_from("<I", content, 28)[0], *struct.unpack_from("<HH", content, 42)
        header_format = "<IIIIIIII"
    else:
        raise ValueError(f"{name}: unsupported ELF class")
    header_size = struct.calcsize(header_format)
    if entry_size < header_size or count <= 0 or offset + entry_size * count > len(content):
        raise ValueError(f"{name}: missing or corrupt program header table")
    segments = []
    for index in range(count):
        header = struct.unpack_from(header_format, content, offset + entry_size * index)
        if header[0] == PT_LOAD:
            if content[4] == 2:
                segments.append((header[2], header[3], header[7]))
            else:
                segments.append((header[1], header[2], header[7]))
    if not segments:
        raise ValueError(f"{name}: no PT_LOAD segments")
    return segments


def inspect_library(name, content, page_size=PAGE_SIZE):
    segments = load_segments(name, content)
    failures = []
    for index, (offset, vaddr, align) in enumerate(segments):
        if align < page_size or align % page_size or (vaddr - offset) % page_size:
            failures.append(f"LOAD[{index}] off=0x{offset:x} vaddr=0x{vaddr:x} align=0x{align:x}")
    return segments, failures


def check_apk(apk):
    with zipfile.ZipFile(apk) as archive:
        names = sorted(name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so"))
        if not names:
            raise ValueError("APK contains no native libraries")
        failed = 0
        for name in names:
            segments, problems = inspect_library(name, archive.read(name))
            labels = ", ".join(f"0x{alignment:x}" for _, _, alignment in segments)
            if problems:
                failed += 1
                print(f"FAIL {name}: " + "; ".join(problems))
            else:
                print(f"PASS {name}: PT_LOAD p_align = {labels}")
    return len(names), failed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Built APK to verify")
    args = parser.parse_args()
    if not args.apk.is_file():
        parser.error(f"APK not found: {args.apk}")
    try:
        count, failed = check_apk(args.apk)
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    print(f"16 KB ELF alignment: {count - failed}/{count} PASS")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
