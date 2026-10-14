#!/usr/bin/env python3
"""Offline regression for RK3588 crash watcher; no device required."""
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import watch_npu_crash as watcher


class WatchCrashTests(unittest.TestCase):
    def setUp(self):
        self.first = (
            "10-14 16:23:01.001 F libc: Fatal signal 11 (SIGSEGV), pid: 111 (com.badukai.java)\n"
            "10-14 16:23:01.222 F DEBUG: backtrace:\n"
            "10-14 16:23:01.222 F DEBUG: #00 pc 00000000000dc798 /lib/arm64/libkatago_npu.so (BuildId: abc)\n"
        )
        self.second = (
            "10-14 16:24:09.205 F libc: Fatal signal 11 (SIGSEGV), pid: 9108 (com.badukai.java)\n"
            "10-14 16:24:09.530 F DEBUG: backtrace:\n"
            "10-14 16:24:09.530 F DEBUG: #00 pc 00000000000dc798 /lib/arm64/libkatago_npu.so (BuildId: def)\n"
        )

    def test_picks_latest_tombstone_after_old_one(self):
        key, body = watcher.latest_crash(self.first + self.second)
        self.assertIn("9108", key)
        self.assertIn("BuildId: def", body)
        self.assertNotIn("BuildId: abc", body)
        self.assertTrue(watcher.has_symbolizable_npu_frames(body))

    def test_waits_for_native_backtrace(self):
        key, body = watcher.latest_crash(self.second.split("backtrace:")[0])
        self.assertIsNotNone(key)
        self.assertFalse(watcher.has_symbolizable_npu_frames(body))

    def test_does_not_use_unrelated_gpu_stack(self):
        text = self.second.replace("libkatago_npu.so", "libkatago_gpu.so")
        self.assertFalse(watcher.has_symbolizable_npu_frames(text))

    def test_symbolizer_saves_both_logs_and_printable_location(self):
        with tempfile.TemporaryDirectory() as temp:
            mocked = mock.Mock(returncode=0,
                               stdout="#00 0xDC798 ScoreValue::expectedWhiteScoreValue @ nninputs.cpp:214\n")
            with mock.patch.object(watcher.subprocess, "run", return_value=mocked) as runner:
                code, output, folder = watcher.symbolize("8719e18a71a2a66c", self.second, Path(temp))
            self.assertEqual(code, 0)
            self.assertIn("nninputs.cpp:214", output)
            self.assertIn("libkatago_npu.so", (folder / "crash.txt").read_text())
            self.assertEqual((folder / "native_symbols.txt").read_text(), output)
            self.assertEqual(runner.call_args.args[0][0], "bash")
            self.assertEqual(runner.call_args.args[0][-1], str(folder / "crash.txt"))

    def test_no_crash_in_ordinary_log(self):
        self.assertEqual(watcher.latest_crash("--------- beginning of crash\n"), (None, ""))


if __name__ == "__main__":
    unittest.main()
