#!/usr/bin/env python3
"""Upload tiny fixed-SGF endgame result bundles to the existing public report branch.

Git temporary index/commit-tree keeps the working branch, index and worktree
unchanged. The target repo is deliberately restricted to rstg00po54/go.
"""
import datetime
import os
from pathlib import Path
import re
import subprocess
import tempfile


def upload_results(project, report_dir, remote="origin", branch="ui-test-results"):
    project, report_dir = Path(project), Path(report_dir)
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,60}", branch):
        raise RuntimeError("Invalid report branch")
    def git(*params, payload=None, env=None, timeout=60):
        options = dict(os.environ)
        options["GIT_TERMINAL_PROMPT"] = "0"
        if env:
            options.update(env)
        task = subprocess.run(["git", "-C", str(project)] + list(params),
                              input=payload, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=timeout, env=options)
        if task.returncode:
            raise RuntimeError("git %s failed: %s" % (
                params[0], task.stderr.decode("utf-8", "replace")[-600:]))
        return task.stdout.decode("utf-8", "replace").strip()

    remote_url = git("remote", "get-url", remote)
    if not re.search(r"(^|[:/])rstg00po54/go(?:\.git)?$", remote_url):
        raise RuntimeError("Report upload blocked: remote is not rstg00po54/go")

    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    prefix = "runs/%s_endgame" % stamp
    files = [report_dir / name for name in ("endgame.json", "endgame.csv", "fixtures.json")]
    files.extend(sorted((report_dir / "positions").glob("*.sgf")))
    if any(not file.is_file() for file in files):
        raise RuntimeError("Cannot upload incomplete endgame report: %s" % report_dir)
    items = []
    for file in files:
        if file.stat().st_size > 4 * 1024 * 1024:
            raise RuntimeError("Unexpected large report: %s" % file)
        name = file.name if file.parent == report_dir else "positions/" + file.name
        items.append((prefix + "/" + name, file.read_bytes()))
    with tempfile.TemporaryDirectory(prefix="katago_endgame_git_") as temp:
        env = {"GIT_INDEX_FILE": str(Path(temp) / "index")}
        known = git("ls-remote", "--heads", remote, "refs/heads/" + branch)
        parent = known.split()[0] if known else None
        if parent:
            git("fetch", "--no-tags", "--quiet", "--depth=1", remote, parent, timeout=90)
            git("read-tree", parent + "^{tree}", env=env)
        else:
            git("read-tree", "--empty", env=env)
        for name, payload in items:
            blob = git("hash-object", "-w", "--stdin", payload=payload)
            git("update-index", "--add", "--cacheinfo",
                "100644," + blob + "," + name, env=env)
        tree = git("write-tree", env=env)
        cmd = ["-c", "user.name=KataGo Endgame Benchmark",
               "-c", "user.email=katago-endgame@users.noreply.github.com",
               "commit-tree", tree]
        if parent:
            cmd.extend(["-p", parent])
        cmd.extend(["-m", "Fixed SGF endgame CPU/GPU benchmark %s" % stamp])
        commit = git(*cmd)
        git("push", remote, commit + ":refs/heads/" + branch, timeout=120)
    return ("https://github.com/rstg00po54/go/blob/%s/%s/endgame.json" %
            (branch, prefix))
