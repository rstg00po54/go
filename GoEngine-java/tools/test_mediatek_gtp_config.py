#!/usr/bin/env python3
"""Exercise the *actual* pure Java GTP config override implementation on host JVM."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/badukai/engine/KataGoGtpConfig.java"
CONFIG = ROOT / "app/src/main/assets/engine/default_gtp.cfg"

TEST = r"""
import com.badukai.engine.KataGoGtpConfig;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VerifyGtpConfig {
  static String[] keys = {"logDir", "homeDataDir", "defaultBoardSize",
                          "numSearchThreads", "maxVisits", "nnMaxBatchSize"};
  static String[] values = {"/data/local/tmp/logs", "/data/local/tmp/home",
                            "9", "2", "8", "1"};
  static int count(String text, String key) {
    Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=").matcher(text);
    int n=0;
    while (matcher.find()) n++;
    return n;
  }
  public static void main(String[] args) throws Exception {
    String config = new String(Files.readAllBytes(Paths.get(args[0])), "UTF-8");
    for (int i=0; i<keys.length; i++)
      config = KataGoGtpConfig.set(config, keys[i], values[i]);
    for (int i=0; i<keys.length; i++) {
      if (count(config, keys[i]) != 1)
        throw new AssertionError("Duplicate/missing GTP key: " + keys[i]);
      if (!config.contains(keys[i] + " = " + values[i] + "\n"))
        throw new AssertionError("Wrong override: " + keys[i]);
    }
    // Some configuration comments contain examples, which must stay comments.
    if (!config.contains("# defaultBoardSize = 19"))
      throw new AssertionError("Example config comment lost");
    String duplicated = "numSearchThreads = 4\n# numSearchThreads = 7\nnumSearchThreads = 5\n";
    String fixed = KataGoGtpConfig.set(duplicated, "numSearchThreads", "2");
    if (count(fixed, "numSearchThreads") != 1 ||
        !fixed.contains("# numSearchThreads = 7"))
      throw new AssertionError("Duplicate-removal or comment preservation failed");
    System.out.println("MEDIATEK_GTP_CONFIG_PASS");
  }
}
"""


class GtpConfigTests(unittest.TestCase):
    def test_real_gtp_config_no_duplicates(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("requires JDK 17")
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            source = root / "VerifyGtpConfig.java"
            source.write_text(TEST, encoding="utf-8")
            subprocess.run(
                ["javac", "-d", str(root), str(JAVA), str(source)],
                check=True, capture_output=True, text=True,
            )
            result = subprocess.run(
                ["java", "-cp", str(root), "VerifyGtpConfig", str(CONFIG)],
                check=True, capture_output=True, text=True,
            )
            self.assertIn("MEDIATEK_GTP_CONFIG_PASS", result.stdout)


if __name__ == "__main__":
    unittest.main()
