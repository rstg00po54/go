#!/usr/bin/env python3
"""Offline end-to-end tests for the existing ui-test-results Git uploader."""
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from upload_referee_test_results import (UploadError, choose_remote, report_items,
                                         upload_referee_results)


class RefereeUploadTests(unittest.TestCase):
    def test_reports_include_case_diagnostics_but_exclude_unrelated_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/"cgos9_1").mkdir()
            (root/"cgos9_1"/"result.json").write_text('{"status":"PASS"}')
            (root/"cgos9_1"/"logcat.txt").write_text("raw log")
            (root/"cgos9_1"/"review_panel.txt").write_text("智能裁判 · 请确认死子")
            (root/"cgos9_1"/"confirmation_dialog.txt").write_text("确认中国规则数子")
            (root/"cgos9_1"/"review_screen.png").write_bytes(b"\x89PNG\r\n\x1a\n")
            (root/"secret.txt").write_text("not a test result")
            summary,items = report_items(root,{"status":"FAIL"},"testcase",lambda _:None)
            names = [name for name,_ in items]
            self.assertEqual(summary,"runs/testcase.json")
            self.assertIn("runs/testcase/cgos9_1/result.json",names)
            self.assertIn("runs/testcase/cgos9_1/logcat.txt",names)
            self.assertIn("runs/testcase/cgos9_1/review_screen.png",names)
            self.assertIn("runs/testcase/cgos9_1/review_panel.txt",names)
            self.assertIn("runs/testcase/cgos9_1/confirmation_dialog.txt",names)
            self.assertNotIn("runs/testcase/secret.txt",names)

    def test_git_plumbing_preserves_worktree_and_old_uploads(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bare = root/"rstg00po54"/"go.git"
            bare.parent.mkdir()
            source = root/"source"
            source.mkdir()
            out = root/"outputs"
            out.mkdir()
            subprocess.run(["git","init","--bare",str(bare)],check=True,capture_output=True)
            subprocess.run(["git","init",str(source)],check=True,capture_output=True)
            subprocess.run(["git","-C",str(source),"config","user.name","Smoke Test"],check=True)
            subprocess.run(["git","-C",str(source),"config","user.email","smoke@example.invalid"],check=True)
            (source/"readme.txt").write_text("keep current branch intact")
            subprocess.run(["git","-C",str(source),"add","readme.txt"],check=True)
            subprocess.run(["git","-C",str(source),"commit","-m","Source branch"],check=True,capture_output=True)
            subprocess.run(["git","-C",str(source),"remote","add","github",str(bare)],check=True)
            current = subprocess.check_output(["git","-C",str(source),"rev-parse","HEAD"],text=True).strip()
            (out/"cgos9_1").mkdir()
            (out/"cgos9_1"/"result.json").write_text('{"status":"PASS"}')
            (out/"cgos9_1"/"dialog.txt").write_text("智能裁判")
            logs = []
            first = upload_referee_results(out,{"status":"PASS","cases":[{"game":"cgos9_1"}]},
                                           repo_root=source,logger=logs.append)
            second = upload_referee_results(out,{"status":"FAIL","error":"test issue"},
                                            repo_root=source,logger=logs.append)
            self.assertIn("/ui-test-results/runs/",first)
            self.assertIn("/ui-test-results/runs/",second)
            names = subprocess.check_output(
                ["git","-C",str(source),"ls-tree","-r","--name-only",
                 subprocess.check_output(["git","--git-dir",str(bare),"rev-parse",
                                         "refs/heads/ui-test-results"],text=True).strip()],
                text=True).splitlines()
            self.assertEqual(len([name for name in names if name.endswith(".json") and
                                  name.count("/")==1]),2)
            self.assertTrue(any(name.endswith("/cgos9_1/dialog.txt") for name in names))
            self.assertEqual(subprocess.check_output(["git","-C",str(source),"rev-parse","HEAD"],
                                                     text=True).strip(),current)
            self.assertEqual(subprocess.check_output(["git","-C",str(source),"status","--porcelain"],
                                                     text=True).strip(),"")

    def test_reject_unknown_remote_or_branch(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = Path(directory)
            subprocess.run(["git","init",str(repo)],capture_output=True,check=True)
            subprocess.run(["git","-C",str(repo),"remote","add","origin",
                            "https://github.com/other/project.git"],check=True)
            with self.assertRaises(UploadError):
                choose_remote(repo,"origin")
            with self.assertRaises(UploadError):
                upload_referee_results(repo,{"status":"FAIL"},branch="../main",
                                       repo_root=repo,logger=lambda _:None)


if __name__=="__main__":
    unittest.main()
