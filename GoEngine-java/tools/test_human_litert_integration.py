#!/usr/bin/env python3
"""Build-time guards for b18 Human SL MediaTek LiteRT JNI integration.

These checks deliberately reject model relabeling, missing SGF metadata,
stale 10b-only JNI signatures, noninterleaved Human policy channels and
claiming Human NPU on the ordinary CPU fallback.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "app/src/main/java/com/badukai/engine"
NATIVE = ROOT / "native/KataGo/cpp/neuralnet"
CPP = ROOT / "app/src/main/cpp/EngineSession.cpp"


class HumanBridgeTests(unittest.TestCase):
    def test_export_requires_real_human_weights_and_metadata(self):
        code = (ROOT / "tools/convert_katago_human_litert.py").read_text()
        for token in ('"b18c384nbt-humanv0"', '"InputMeta"',
                      '"katago.metaEncoderVersion"', '192', '"graphSha256"',
                      '"outputScaleMultiplier"', 'model.graph.input',
                      'onnx.checker.check_model(model)'):
            self.assertIn(token, code)

    def test_java_rejects_missing_human_metadata_and_wrong_shapes(self):
        code = (BASE / "KataGoMediaTekLiteRt.java").read_text()
        for token in ('"b18c384nbt-humanv0"', '"InputMeta"',
                      'meta.length != 192', '"graphSha256"',
                      'getInt("numPolicyChannels")',
                      'getInt("numScoreValueChannels")',
                      'policyIsNchw', 'interleaved[p*policyChannels + ch]',
                      'KataGoLiteRtOutputScale.resolveDeclared(declaredScale)'):
            self.assertIn(token, code)
        self.assertIn('this(context, file, json, board, false);', code)

    def test_jni_owns_two_runners_with_safe_cpu_fallback(self):
        java = (BASE / "KataGoMediaTekNative.java").read_text()
        native = (NATIVE / "mediatekbackend.cpp").read_text()
        header = (NATIVE / "mediatekbackend.h").read_text()
        session = CPP.read_text()
        engine = (BASE / "KataGoEngine.java").read_text()
        for token in ('KataGoMediaTekLiteRt humanRunner', 'humanRunner.close()',
                      'usesHumanLiteRt()', 'MTK_HUMAN_CPU_FALLBACK'):
            self.assertIn(token, java)
        self.assertIn('jobject javaHumanRunner', native)
        self.assertIn('env->CallObjectMethod(runner, method, spatialArr, globalArr, maskArr, metaArr)', native)
        self.assertIn('const std::array<int,5> sizes = {{policyChannels, area * policyChannels, 3, scoreChannels, area}};', native)
        self.assertIn('jobject humanRunner, jboolean compareCpu', session)
        self.assertIn('jobject javaHumanRunner', header)
        self.assertIn('KATAGO_RKNN_MODEL', (NATIVE / "eigenbackend.cpp").read_text())
        self.assertIn('KataGoMediaTekBridge::hasHumanRunner()', (NATIVE / "eigenbackend.cpp").read_text())
        self.assertIn('model->numInputMetaChannels != 192', (NATIVE / "eigenbackend.cpp").read_text())
        self.assertIn('mediaTekSession.usesHumanLiteRt()', engine)

    def test_debug_only_real_human_npu_parity(self):
        smoke = (ROOT / "tools/smoke_vivo_mtk_game.py").read_text()
        activity = (ROOT / "app/src/main/java/com/badukai/MainActivity.java").read_text()
        cpp = (NATIVE / "eigenbackend.cpp").read_text()
        for text in ('--require-human-npu', 'MTK_PARITY_HEAD', 'MTK_HUMAN_LITERT_READY',
                     'Human SL still executed on CPU/Eigen'):
            self.assertIn(text, smoke)
        self.assertIn('"npu_smoke_parity"', activity)
        self.assertIn('engine.setMediaTekParityEnabled(parity)', activity)
        self.assertIn('board=%d model=%s head=%s', cpp)

if __name__ == "__main__":
    unittest.main()
