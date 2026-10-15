#!/usr/bin/env python3
"""Exercise KataGo's actual Java LiteRT ONNX output scaling implementation."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/badukai/engine/KataGoLiteRtOutputScale.java"
CONVERTER = ROOT / "tools/convert_katago_litert.py"

JAVA_TEST = r"""
import com.badukai.engine.KataGoLiteRtOutputScale;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
public class VerifyKataGoOutputScale {
    static void near(float got,float expected) {
        if (Math.abs(got-expected)>0.00001f) throw new AssertionError(got+" != "+expected);
    }
    public static void main(String[] args) throws Exception {
        String known9 = "eaf37768b48021e579e38f9bae0efbcb17f5c5a84d80d288430ee11784f1f039";
        if (KataGoLiteRtOutputScale.resolve(9,null,known9)!=8.0)
            throw new AssertionError("Legacy graph must restore documented ONNX scale8");
        if (KataGoLiteRtOutputScale.resolve(19,1.0,"newgraph")!=1.0)
            throw new AssertionError("Non-scaled graph should remain unchanged");
        if (KataGoLiteRtOutputScale.resolve(13,8.0,"newgraph")!=8.0)
            throw new AssertionError("Scaled new graph should restore 8x");
        for (int wrongBoard : new int[]{13,19}) {
            try {
                KataGoLiteRtOutputScale.resolve(wrongBoard,null,known9);
                throw new AssertionError("Mismatched board accepted");
            } catch (IllegalArgumentException expected) {}
        }
        try {
            KataGoLiteRtOutputScale.resolve(9,null,"unverified graph");
            throw new AssertionError("Unknown missing-scale graph accepted");
        } catch (IllegalArgumentException expected) {}
        for (Double bad : new Double[]{0.0,2.0,Double.NaN,Double.POSITIVE_INFINITY}) {
            try {
                KataGoLiteRtOutputScale.resolve(9,bad,known9);
                throw new AssertionError("Unsafe scale accepted: "+bad);
            } catch (IllegalArgumentException expected) {}
        }
        float[][] heads = {{-0.95458984f},{-2.625f},{-0.75341797f},{-0.063598633f},
                           {0.16772461f}};
        KataGoLiteRtOutputScale.restore(heads,8f);
        near(heads[0][0],-7.63671875f);
        near(heads[1][0],-21f);
        near(heads[2][0],-6.02734375f);
        near(heads[3][0],-0.50878906f);
        near(heads[4][0],1.3417969f);
        File file = new File(args[0]);
        Files.write(file.toPath(),"katago".getBytes(StandardCharsets.UTF_8));
        String expectedSha = "ae0b54a48ba0c31ea95469b00fe26b4054a09f75f340c44ce9ed26bd4308c15f";
        String actual = KataGoLiteRtOutputScale.sha256(file);
        // Verify via Python on the host rather than embedding a guessed digest.
        System.out.println("KATAGO_LITERT_GRAPH_SHA256="+actual);
        System.out.println("KATAGO_OUTPUT_SCALE_PASS");
    }
}
"""

class OutputScaleTests(unittest.TestCase):
    def test_java_scale_guard_and_restoration(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("requires JDK")
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            (directory / "VerifyKataGoOutputScale.java").write_text(JAVA_TEST, encoding="utf-8")
            subprocess.run(["javac", "-d", str(directory), str(JAVA),
                            str(directory / "VerifyKataGoOutputScale.java")],
                           capture_output=True, text=True, check=True)
            result = subprocess.run(["java", "-cp", str(directory), "VerifyKataGoOutputScale",
                                     str(directory / "sample.txt")],
                                    capture_output=True, text=True, check=True)
            self.assertIn("KATAGO_OUTPUT_SCALE_PASS", result.stdout)
            import hashlib
            self.assertIn("KATAGO_LITERT_GRAPH_SHA256=" + hashlib.sha256(b"katago").hexdigest(),
                          result.stdout)

    def test_onnx_output_scale_metadata_guard(self):
        import sys
        sys.path.insert(0, str(ROOT / "tools"))
        from convert_katago_litert import output_scale_from_onnx
        class Prop:
            def __init__(self, key, value): self.key, self.value = key, value
        class Model:
            def __init__(self, props): self.metadata_props = [Prop(*p) for p in props]
        expected = output_scale_from_onnx(Model([
            ("katago.build.scale8Applied","true"),
            ("katago.postProcess.outputScaleMultiplier","8")]))
        self.assertEqual(expected, {"scale8Applied":True,"outputScaleMultiplier":8.0})
        self.assertEqual(output_scale_from_onnx(Model([
            ("katago.build.scale8Applied","false"),
            ("katago.postProcess.outputScaleMultiplier","1")])),
            {"scale8Applied":False,"outputScaleMultiplier":1.0})
        for props in [
            [("katago.build.scale8Applied","true"),
             ("katago.postProcess.outputScaleMultiplier","1")],
            [("katago.build.scale8Applied","true")],
            [("katago.postProcess.outputScaleMultiplier","8")],
        ]:
            with self.assertRaises(ValueError): output_scale_from_onnx(Model(props))

if __name__ == "__main__":
    unittest.main()
