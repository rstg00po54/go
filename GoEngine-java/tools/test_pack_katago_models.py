#!/usr/bin/env python3
"""Offline, stdlib-only tests for compact KataGo model packaging."""
import hashlib
from pathlib import Path
import tempfile
import unittest

from pack_katago_models import create_delta, restore_delta, compact, copy_tree_files


class CompactKataGoModelTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)
        self.base = self.path / "base.bin"
        self.target = self.path / "target.bin"
        self.patch = self.path / "patch.bin"
        self.restored = self.path / "restored.bin"
        source = hashlib.shake_256(b"compact-model-synthetic-seed").digest(1024 * 1024)
        self.base.write_bytes(source)
        self.target.write_bytes(source[:12345] + b"EXTRA" + source[12345:600000] +
                                b"REPLACEMENT" + source[600004:])

    def test_shifted_large_model_is_reconstructed_identically(self):
        create_delta(self.base, self.target, self.patch)
        self.assertLess(self.patch.stat().st_size, 10000)
        restore_delta(self.base, self.patch, self.restored)
        self.assertEqual(self.restored.read_bytes(), self.target.read_bytes())

    def test_wrong_base_hash_is_rejected(self):
        create_delta(self.base, self.target, self.patch)
        changed = bytearray(self.base.read_bytes())
        changed[0] ^= 1
        self.base.write_bytes(changed)
        with self.assertRaisesRegex(ValueError, "Base SHA256"):
            restore_delta(self.base, self.patch, self.restored)

    def test_corrupt_patch_is_rejected(self):
        create_delta(self.base, self.target, self.patch)
        raw = bytearray(self.patch.read_bytes())
        raw[-2] ^= 1
        self.patch.write_bytes(raw)
        with self.assertRaises(ValueError):
            restore_delta(self.base, self.patch, self.restored)

    def test_compact_keeps_19_and_metadata(self):
        engine = self.path / "stage" / "engine"
        engine.mkdir(parents=True)
        for name in ("katago_human_19x19.tflite", "katago_human_9x9.tflite"):
            data = self.base.read_bytes() if "19x19" in name else self.target.read_bytes()
            (engine / name).write_bytes(data)
        (engine / "katago_human_9x9.json").write_text('{"modelType":"human"}')
        compact(engine)
        self.assertTrue((engine / "katago_human_19x19.tflite").is_file())
        self.assertFalse((engine / "katago_human_9x9.tflite").exists())
        self.assertTrue((engine / "katago_human_9x9.tflite.kgdelta").exists())
        self.assertTrue((engine / "katago_human_9x9.json").exists())
        restored = self.path / "restored9.bin"
        restore_delta(engine / "katago_human_19x19.tflite",
                      engine / "katago_human_9x9.tflite.kgdelta", restored)
        self.assertEqual(restored.read_bytes(), self.target.read_bytes())


if __name__ == "__main__":
    unittest.main()
