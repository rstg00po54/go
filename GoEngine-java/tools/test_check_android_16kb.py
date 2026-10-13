#!/usr/bin/env python3
"""Offline regression tests for the 16 KB Android ELF checker."""
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from check_android_16kb import check_apk, inspect_library, load_segments


def make_elf64(align, vaddr=0, offset=0):
    elf = bytearray(128)
    elf[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HHI", elf, 16, 3, 183, 1)  # ET_DYN, AArch64
    struct.pack_into("<Q", elf, 32, 64)
    struct.pack_into("<HH", elf, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", elf, 64, 1, 5, offset, vaddr, 0, 64, 64, align)
    return bytes(elf)


class AndroidPageSizeTests(unittest.TestCase):
    def test_accepts_16k_or_larger_aligned_elf(self):
        for align in (0x4000, 0x10000):
            segments, errors = inspect_library("test.so", make_elf64(align))
            self.assertEqual(len(segments), 1)
            self.assertEqual(errors, [])

    def test_rejects_unaligned_4k_load_segment(self):
        _, errors = inspect_library("libkatago_exec.so", make_elf64(0x1000))
        self.assertIn("align=0x1000", " ".join(errors))

    def test_rejects_incongruent_offset_and_address(self):
        _, errors = inspect_library("bad.so", make_elf64(0x4000, vaddr=0x1000))
        self.assertTrue(errors)

    def test_rejects_invalid_elf(self):
        with self.assertRaises(ValueError):
            load_segments("wrong.so", b"not an ELF")

    def test_checks_all_packaged_so_files(self):
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory)/"app.apk"
            with zipfile.ZipFile(archive, "w") as output:
                output.writestr("lib/arm64-v8a/libkatago_exec.so", make_elf64(0x4000))
                output.writestr("lib/arm64-v8a/libc++_shared.so", make_elf64(0x1000))
            count, failed = check_apk(archive)
            self.assertEqual((count, failed), (2, 1))


if __name__ == "__main__":
    unittest.main()
