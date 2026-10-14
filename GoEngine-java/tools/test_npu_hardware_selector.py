#!/usr/bin/env python3
"""JVM-only SoC selector regression; no Android device required."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "app/src/main/java/com/badukai/engine/NpuHardware.java"

BUILD_STUB = """package android.os;
public final class Build {
  public static String SOC_MANUFACTURER = "";
  public static String SOC_MODEL = "";
  public static String HARDWARE = "";
  public static String BOARD = "";
  public static String DEVICE = "";
  public static class VERSION { public static int SDK_INT = 35; }
}
"""

MAIN = """import com.badukai.engine.NpuHardware;
public final class SelectorTest {
  static void check(NpuHardware.Vendor value, NpuHardware.Vendor expected) {
    if(value != expected) throw new AssertionError(value + " expected " + expected);
  }
  public static void main(String[] argv) {
    check(NpuHardware.classify("Rockchip", "RK3588S", "rk30board", "rk3588", "board"),
          NpuHardware.Vendor.ROCKCHIP_RK3588);
    check(NpuHardware.classify("MediaTek", "Dimensity 9500", "mt6993", "k6993", "PD2502"),
          NpuHardware.Vendor.MEDIATEK);
    check(NpuHardware.classify("", "MT6993", "mt6993", "", ""),
          NpuHardware.Vendor.MEDIATEK);
    check(NpuHardware.classify("Qualcomm", "Snapdragon", "qcom", "kalama", "device"),
          NpuHardware.Vendor.UNSUPPORTED);
    check(NpuHardware.classify("ARM", "arm64", "aarch64", "", ""),
          NpuHardware.Vendor.UNSUPPORTED);
    System.out.println("NPU_PLATFORM_SELECTOR_PASS");
  }
}
"""


class SoCSelection(unittest.TestCase):
    def test_rk3588_mediatek_and_other_socs(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("requires JDK")
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            folder = root / "android/os"
            folder.mkdir(parents=True)
            (folder / "Build.java").write_text(BUILD_STUB)
            (root / "SelectorTest.java").write_text(MAIN)
            subprocess.run(["javac", "-d", str(root), str(folder / "Build.java"),
                            str(SOURCE), str(root / "SelectorTest.java")], check=True)
            result = subprocess.run(["java", "-cp", str(root), "SelectorTest"],
                                    capture_output=True, text=True, check=True)
            self.assertIn("NPU_PLATFORM_SELECTOR_PASS", result.stdout)


if __name__ == "__main__":
    unittest.main()
