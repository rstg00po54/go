#!/usr/bin/env python3
"""Upload ADB referee smoke artifacts using the existing UI test results branch.

Same storage scheme as tools/test_app_ui.py: the Git remote's ui-test-results
branch, runs/<UTC timestamp>_...json and runs/<run>/ diagnostic files.
The test machine's existing Git credentials are used, never embedded tokens.
Git plumbing uses an isolated index and never checks out the results branch.
The GitHub repository is public and files are uploaded WITHOUT redaction,
matching the established test_app_ui.py upload convention.
"""
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from uuid import uuid4

REPO_NAME = "rstg00po54/go"
RESULT_BRANCH = "ui-test-results"
MAX_FILE_SIZE = 12 * 1024 * 1024
ALLOWED = {"result.json","dialog.txt","review_panel.txt","confirmation_dialog.txt",
           "logcat.txt","activity.txt","gradle.log","crash.txt","analysis_dialog.png",
           "review_screen.png","resumed_screen.png","armed_logcat.txt","resume_logcat.txt","confirmation_dialog.png"}


class UploadError(RuntimeError):
    pass


def git(repo_root,*params,input_data=None,env=None,timeout=35):
    options = dict(os.environ)
    options["GIT_TERMINAL_PROMPT"] = "0"
    if env:
        options.update(env)
    try:
        p = subprocess.run(["git","-C",str(repo_root)]+list(params),
                           input=input_data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,
                           env=options,timeout=timeout)
    except (OSError,subprocess.TimeoutExpired) as exc:
        raise UploadError("git %s failed: %r" % (params[0],exc)) from exc
    if p.returncode:
        raise UploadError("git %s failed: %s" %
                          (params[0],p.stderr.decode("utf-8","replace")[-500:]))
    return p.stdout.decode("utf-8","replace").strip()


def choose_remote(repo_root,preferred="auto"):
    candidates = ("origin","github") if preferred=="auto" else (preferred,)
    for remote in candidates:
        try:
            url = git(repo_root,"remote","get-url",remote)
        except UploadError:
            continue
        if re.search(r"(^|[:/])rstg00po54/go(?:\.git)?$",url):
            return remote
        if preferred!="auto":
            raise UploadError("Remote %s points to another repository, refusing upload" % remote)
    raise UploadError("No remote for rstg00po54/go found; set --upload-remote origin or github")


def report_items(run_dir,report,run_name,logger=print):
    """Return file data, never absolute paths or arbitrary project files."""
    summary_path = "runs/"+run_name+".json"
    items = [(summary_path,(json.dumps(report,ensure_ascii=False,indent=2)+"\n").encode("utf-8"))]
    for file in sorted(run_dir.rglob("*")):
        if not file.is_file() or file.is_symlink() or file.name not in ALLOWED:
            continue
        relative = file.relative_to(run_dir)
        if len(relative.parts)>2 or any(not re.fullmatch(r"[A-Za-z0-9_.-]+",p) for p in relative.parts):
            continue
        if file.stat().st_size>MAX_FILE_SIZE:
            logger("GitHub upload: skipping file > 12 MB: "+str(relative))
            continue
        items.append(("runs/"+run_name+"/"+relative.as_posix(),file.read_bytes()))
    return summary_path,items


def upload_referee_results(run_dir,report,remote="auto",branch=RESULT_BRANCH,
                           repo_root=None,logger=print,run_kind="referee"):
    """Publish final PASS/FAIL/INTERRUPTED report + diagnostic artifacts.

    Branch and checkout state stay unchanged. If concurrent pushes race,
    retry from the latest remote tree. The caller must retain local results.
    """
    run_dir = Path(run_dir).resolve()
    repo_root = Path(repo_root or Path(__file__).resolve().parents[2]).resolve()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,60}",branch):
        raise UploadError("Invalid upload branch")
    if not re.fullmatch(r"[a-z][a-z0-9_-]{0,30}",run_kind):
        raise UploadError("Invalid report kind")
    chosen = choose_remote(repo_root,remote)
    time_id = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    run_name = time_id+"_"+run_kind+"_"+str(os.getpid())+"_"+uuid4().hex[:8]
    report_path,items = report_items(run_dir,report,run_name,logger)
    logger("Uploading %d result artifacts to %s/%s (PUBLIC, raw diagnostics)..." %
           (len(items),chosen,branch))
    # New temporary index avoids switching the user's source branch or modifying
    # the checkout's index; no commit is made on rk3588-engine.
    with tempfile.TemporaryDirectory(prefix="referee_results_git_") as directory:
        env = {"GIT_INDEX_FILE":str(Path(directory)/"index")}
        objects = []
        for name,payload in items:
            sha = git(repo_root,"hash-object","-w","--stdin",input_data=payload)
            objects.append((name,sha))
        for attempt in range(3):
            remote_head = git(repo_root,"ls-remote","--heads",chosen,
                              "refs/heads/"+branch,timeout=45)
            parent = remote_head.split()[0] if remote_head else None
            if parent:
                git(repo_root,"fetch","--no-tags","--quiet","--depth=1",chosen,parent,timeout=60)
                git(repo_root,"read-tree",parent+"^{tree}",env=env)
            else:
                git(repo_root,"read-tree","--empty",env=env)
            for name,sha in objects:
                git(repo_root,"update-index","--add","--cacheinfo",
                    "100644,"+sha+","+name,env=env)
            tree = git(repo_root,"write-tree",env=env)
            args=["-c","user.name=KataGo UI Test",
                  "-c","user.email=katago-ui-test@users.noreply.github.com",
                  "commit-tree",tree]
            if parent:
                args.extend(["-p",parent])
            args.extend(["-m",run_kind+" smoke "+report.get("status","ERROR")+" "+time_id])
            commit = git(repo_root,*args)
            try:
                git(repo_root,"push",chosen,commit+":refs/heads/"+branch,timeout=90)
                url = "https://github.com/"+REPO_NAME+"/blob/"+branch+"/"+report_path
                logger("Results uploaded: "+url)
                return url
            except UploadError:
                if attempt==2:
                    raise
                logger("Result branch advanced or push interrupted; retrying upload...")
    raise UploadError("Cannot push report after retries")
