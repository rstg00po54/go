#!/usr/bin/env python3
"""Guard the in-process KataGo GTP globals and JNI teardown contract.

These tests are source-level checks, NOT a replacement for RK3588 device
stress tests. They ensure the previously observed null expectedSVTable race
cannot be accidentally reintroduced by a cleanup refactor.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
GTP = ROOT / "native/KataGo/cpp/command/gtp.cpp"
JNI = ROOT / "app/src/main/cpp/EngineSession.cpp"
CMAKE = ROOT / "native/KataGo/cpp/CMakeLists.txt"


class SessionLifetimeTests(unittest.TestCase):
    def test_jni_target_has_separate_compile_definition(self):
        src = CMAKE.read_text(encoding="utf-8")
        jni = src.split("if(KATAGO_BUILD_JNI_LIBRARY)", 1)[1].split("\nelse()", 1)[0]
        self.assertIn("target_compile_definitions(katago PRIVATE KATAGO_EMBEDDED_GTP)", jni)
        self.assertIn("add_executable(katago", src)
        self.assertNotIn("KATAGO_EMBEDDED_GTP", src.split("if(KATAGO_BUILD_JNI_LIBRARY)", 1)[0])

    def test_process_wide_table_lives_across_jni_gtp_sessions(self):
        src = GTP.read_text(encoding="utf-8")
        begin = src.index("int MainCmds::gtpWithIO(")
        startup = src[begin:begin + 1100]
        self.assertRegex(startup, r"#ifdef KATAGO_EMBEDDED_GTP\s+static std::once_flag")
        self.assertIn("std::call_once(initGlobalTablesOnce", startup)
        self.assertIn("Board::initHash();", startup)
        self.assertIn("ScoreValue::initTables();", startup)
        self.assertIn("#else", startup)  # CLI retains per-run startup
        cleanup = src[src.rfind("NeuralNet::globalCleanup();"):]
        self.assertRegex(cleanup, r"#ifndef KATAGO_EMBEDDED_GTP\s+ScoreValue::freeTables\(\);\s+#endif")
        self.assertIn("ScoreValue::freeTables();", cleanup)

    def test_session_destroy_joins_before_next_create(self):
        src = JNI.read_text(encoding="utf-8")
        self.assertIn("std::mutex sessionLifecycleMutex;", src)
        self.assertIn("void shutdown()", src)
        self.assertIn("std::call_once(shutdownOnce_", src)
        self.assertIn('inputBuf_.send("quit");', src)
        self.assertIn("if (worker_.joinable()) worker_.join();", src)
        create = src.split("KATAGO_SESSION_JNI(nativeCreateSession)", 1)[1].split(
            "KATAGO_SESSION_JNI(nativeSendCommand)", 1)[0]
        destroy = src.split("KATAGO_SESSION_JNI(nativeDestroySession)", 1)[1].split(
            "// A read-only", 1)[0]
        for part in (create, destroy):
            self.assertIn("std::lock_guard<std::mutex> lifecycleLock(sessionLifecycleMutex);", part)
        self.assertLess(create.index("lifecycleLock(sessionLifecycleMutex)"), create.index("setenv("))
        self.assertLess(destroy.index("lifecycleLock(sessionLifecycleMutex)"), destroy.index("sessions.erase(it);"))
        self.assertLess(destroy.index("sessions.erase(it);"), destroy.index("removed->shutdown();"))
        self.assertLess(destroy.index("removed->shutdown();"), destroy.index("removed.reset();"))


if __name__ == "__main__":
    unittest.main()
