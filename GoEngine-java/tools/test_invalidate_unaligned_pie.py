#!/usr/bin/env python3
"""Verify that only stale 4 KB KataGo build outputs are invalidated."""
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import invalidate_unaligned_pie as pie
from check_katago_pie import validate_pie


def fake_elf(alignment):
    data = bytearray(120)
    data[0:6] = b"\x7fELF\x02\x01"
    data[6] = 1
    struct.pack_into("<HH", data, 16, 3, 183)  # ET_DYN / AArch64
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HH", data, 54, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 0, 0, alignment)
    return data


class PieRelinkTests(unittest.TestCase):
    def test_removes_4kb_generated_output_and_preserves_other_cache(self):
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root)/"build_android_arm64_eigen"
            folder.mkdir()
            binary = folder/"katago"
            obj = folder/"unchanged.o"
            binary.write_bytes(fake_elf(4096))
            obj.write_bytes(b"keep compiled object")
            self.assertTrue(pie.invalidate_if_needed(binary))
            self.assertFalse(binary.exists())
            self.assertEqual(obj.read_bytes(),b"keep compiled object")

    def test_preserves_valid_16kb_output(self):
        with tempfile.TemporaryDirectory() as root:
            binary = Path(root)/"build_android_arm64_eigen"/"katago"
            binary.parent.mkdir()
            original = fake_elf(16384)
            binary.write_bytes(original)
            self.assertFalse(pie.invalidate_if_needed(binary))
            self.assertEqual(binary.read_bytes(),original)

    def test_strict_validator_agrees_with_invalidator_and_cli(self):
        with tempfile.TemporaryDirectory() as root:
            folder = Path(root)/"build_android_arm64_eigen"
            folder.mkdir()
            binary = folder/"katago"
            binary.write_bytes(fake_elf(16384))
            valid, message = validate_pie(binary)
            self.assertTrue(valid, message)
            self.assertFalse(pie.needs_relink(binary))
            ok = subprocess.run([sys.executable,str(Path(pie.__file__).with_name("check_katago_pie.py")),
                                 str(binary)],capture_output=True,text=True)
            self.assertEqual(ok.returncode,0,ok.stdout+ok.stderr)
            invalid = bytearray(fake_elf(16384))
            struct.pack_into("<H",invalid,18,62)  # x86_64, not Android AArch64
            binary.write_bytes(invalid)
            self.assertFalse(validate_pie(binary)[0])
            self.assertTrue(pie.needs_relink(binary))
            self.assertTrue(pie.invalidate_if_needed(binary))
            self.assertFalse(binary.exists())

    def test_malformed_aligned_binary_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as root:
            binary = Path(root)/"build_android_arm64_eigen"/"katago"
            binary.parent.mkdir()
            bad = bytearray(fake_elf(16384))
            struct.pack_into("<H",bad,16,2)  # ET_EXEC is not a position-independent executable
            binary.write_bytes(bad)
            self.assertFalse(validate_pie(binary)[0])
            self.assertTrue(pie.invalidate_if_needed(binary))

    def test_no_output_needs_no_relink(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertFalse(pie.invalidate_if_needed(Path(root)/"katago"))

    def test_corrupted_executable_is_invalidated(self):
        with tempfile.TemporaryDirectory() as root:
            binary = Path(root)/"build_android_arm64_eigen"/"katago"
            binary.parent.mkdir()
            binary.write_bytes(b"not an ELF")
            self.assertTrue(pie.invalidate_if_needed(binary))
            self.assertFalse(binary.exists())

    def test_cli_rejects_nonbuild_output(self):
        with tempfile.TemporaryDirectory() as root:
            target = Path(root)/"libkatago_gpu.so"
            target.write_bytes(fake_elf(4096))
            process = subprocess.run([sys.executable,str(Path(pie.__file__)),str(target)],
                                     text=True,capture_output=True)
            self.assertNotEqual(process.returncode,0)
            self.assertTrue(target.exists())


if __name__ == "__main__":
    unittest.main()
