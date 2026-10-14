#!/usr/bin/env python3
"""Guard against rebuilding a Vivo APK that has models but no KataGo engine."""
from pathlib import Path
import tempfile
import unittest
import zipfile

from verify_vivo_playable_apk import verify_apk, REQUIRED

ROOT = Path(__file__).resolve().parents[1]


class PlayableVivoApkTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.apk = Path(self.tmp.name) / "app-debug.apk"

    def make(self, binaries):
        with zipfile.ZipFile(self.apk, "w") as archive:
            for name in binaries:
                archive.writestr(name, b"N" * 2048)

    def test_complete_cpu_gpu_pie_and_vendor_plugins(self):
        self.make(REQUIRED)
        verify_apk(self.apk)

    def test_missing_katago_executable_is_a_build_failure(self):
        self.make([x for x in REQUIRED if not x.endswith("libkatago_exec.so")])
        with self.assertRaisesRegex(ValueError, "libkatago_exec.so"):
            verify_apk(self.apk)

    def test_missing_cpu_jni_is_a_build_failure(self):
        self.make([x for x in REQUIRED if not x.endswith("/libkatago.so")])
        with self.assertRaisesRegex(ValueError, "libkatago.so"):
            verify_apk(self.apk)

    def test_rknn_library_rejected(self):
        self.make([*REQUIRED, "lib/arm64-v8a/librknnrt.so"])
        with self.assertRaisesRegex(ValueError, "librknnrt.so"):
            verify_apk(self.apk)

    def test_one_command_build_is_playable_not_probe_only(self):
        script = (ROOT / "tools/build_vivo_npu.sh").read_text()
        self.assertIn('bash "$ROOT/build.sh"', script)
        self.assertIn("-PbundleVivoLiteRtModels=true", script)
        self.assertIn("-PenableKataGoNpuJni=false", script)
        self.assertNotIn("-PvivoProbeApk=true", script.split("ALLOW_4K_RKNN_RUNTIME=0", 1)[1])

    def test_gradle_autodownloads_models_in_full_apk(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text()
        self.assertIn('val bundleVivoLiteRtModels = providers.gradleProperty("bundleVivoLiteRtModels")', gradle)
        self.assertIn("val prepareVivoModels = vivoProbeApk || bundleVivoLiteRtModels", gradle)
        self.assertIn("if (prepareVivoModels && name.startsWith(\"merge\")", gradle)
        self.assertIn("dependsOn(prepareVivoLiteRtModels)", gradle)
        self.assertIn("finalizedBy(verifyVivoLiteRtApk)", gradle)


if __name__ == "__main__":
    unittest.main()
