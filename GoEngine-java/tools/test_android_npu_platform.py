#!/usr/bin/env python3
"""Source-level guard: RKNN APK is RK3588-only; MediaTek mobile stays on CPU/GPU."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AndroidNpuPlatformTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = (ROOT / "app/src/main/java/com/badukai/engine/KataGoEngine.java").read_text()
        cls.ui = (ROOT / "app/src/main/java/com/badukai/MainActivity.java").read_text()
        cls.native = (ROOT / "native/KataGo/cpp/neuralnet/rknnbackend.cpp").read_text()

    def test_supported_platform_requires_rk3588(self):
        self.assertIn("public static boolean supportsRknnHardware()", self.engine)
        self.assertIn('identity.contains("rk3588")', self.engine)
        self.assertIn("rknnModelAvailable && supportsRknnHardware()", self.engine)

    def test_ui_hides_unsupported_npu_and_saved_choice_does_not_reload_it(self):
        self.assertIn("if (!KataGoEngine.supportsRknnHardware()) return false;", self.ui)
        self.assertIn("boolean npuBundled = KataGoEngine.supportsRknnHardware()", self.ui)
        self.assertIn('"NPU".equals(savedBackend) && hasBundledNpuModel(boardSize)', self.ui)

    def test_rknn_init_failure_is_logged_and_does_not_throw(self):
        self.assertIn("const int initRc = rknn_init(", self.native)
        block = self.native.split("const int initRc = rknn_init(", 1)[1].split("        try {", 1)[0]
        self.assertIn("if(initRc != RKNN_SUCC)", block)
        self.assertIn("RKNN_DISABLED_FALLBACK_EIGEN", block)
        self.assertIn("ctx = 0;", block)
        self.assertIn("return;", block)
        self.assertNotIn("throw std::", block)


if __name__ == "__main__":
    unittest.main()
