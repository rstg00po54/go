#!/usr/bin/env python3
"""Stage SHA-pinned original 10b RKNN 11/15 into GENERATED Android assets.

Never overwrite 10b 9/13/19 models or source LiteRT .tflite/.json files.
Release models are unquantized and must be separately compiled from the
same original g170 weights for the exact board dimensions.
"""
import argparse
import hashlib
import json
import os
import subprocess
from pathlib import Path
import shutil
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SIZES = (11,15)
# Immutable model SHA-256, byte count, filled after successful GitHub build.
PINS = {
    11: ("fea8c70b6dd520482b22cc4d5140b616ca7157ab61c44c1b512fdab80e4398d6", 6767177),
    15: ("071099d4fc7b72e2433e38eaf21dc43956adb8bd2b1e625fda3e2b41a5910750", 6770057),
}

def verify(n, binary, manifest):
    if n not in PINS or not binary.is_file() or not manifest.is_file():
        return False
    try:
        metadata=json.loads(manifest.read_text(encoding="utf-8"))
        required={
            "model":"g170-b10c128-s1141046784-d204142634",
            "board":n,"target":"rk3588","quantized":False,
            "inputs":{"InputSpatial":[1,22,n,n],"InputGlobal":[1,19,1,1],
                      "InputMask":[1,1,n,n]},
            "outputs":["OutputPolicyPass","OutputPolicy","OutputValue",
                       "OutputScoreValue","OutputOwnership"],
        }
        if any(metadata.get(k)!=v for k,v in required.items()): return False
        expectedSha,expectedSize=PINS[n]
        if binary.stat().st_size!=expectedSize or metadata.get("sha256")!=expectedSha:
            return False
        digest=hashlib.sha256()
        with binary.open("rb") as inp:
            for block in iter(lambda:inp.read(1024*1024),b""): digest.update(block)
        return digest.hexdigest()==expectedSha
    except (ValueError,TypeError,OSError):
        return False

def model_diagnostics(n, binary, manifest):
    """Summarize the actual bytes and metadata, so a failed download is diagnosable."""
    expected_sha, expected_size = PINS[n]
    items=[f"board={n}",f"expectedBytes={expected_size}",f"expectedSha256={expected_sha}"]
    if binary.is_file():
        digest=hashlib.sha256()
        with binary.open("rb") as stream:
            for block in iter(lambda:stream.read(1024*1024),b""):
                digest.update(block)
        items += [f"actualBytes={binary.stat().st_size}",
                  f"actualSha256={digest.hexdigest()}"]
    else:
        items.append("model=missing")
    try:
        meta=json.loads(manifest.read_text(encoding="utf-8"))
        for k in ("model","board","target","quantized","sha256","inputs","outputs"):
            items.append(f"{k}={meta.get(k)!r}")
    except (OSError,UnicodeError,ValueError,AttributeError) as error:
        items.append(f"manifestError={type(error).__name__}:{error}")
    return " ".join(items)


def stream_download(url, target, fetch=None):
    """Download to a temporary path, never overwrite an existing verified cache."""
    if fetch is not None:
        with fetch(url) as source, target.open("wb") as destination:
            shutil.copyfileobj(source,destination,1024*1024)
    elif shutil.which("curl"):
        # curl retries transient VPN/CDN/proxy failures and checks HTTP errors.
        subprocess.run(["curl","--fail","--location","--silent","--show-error",
                        "--retry","3","--retry-all-errors","--connect-timeout","20",
                        "--max-time","300","--output",str(target),url],check=True)
    else:
        request=urllib.request.Request(url,headers={"User-Agent":"Badukai-RK3588-10b"})
        with urllib.request.urlopen(request,timeout=90) as source,target.open("wb") as destination:
            shutil.copyfileobj(source,destination,1024*1024)


def safe_copy(src,dst):
    dst.parent.mkdir(parents=True,exist_ok=True)
    fd, name=tempfile.mkstemp(dir=dst.parent,prefix=dst.name+".",suffix=".tmp")
    try:
        with os.fdopen(fd,"wb") as output,src.open("rb") as input_file:
            shutil.copyfileobj(input_file,output,1024*1024)
        os.replace(name,dst)
    finally: Path(name).unlink(missing_ok=True)

def stage(enabled,required=False,root=ROOT,fetch=None):
    output_dir=root/"app/build/generated/rk3588HumanAssets/engine"
    for n in SIZES:
        name=f"katago_10b_{n}x{n}"
        output=output_dir/(name+".rknn")
        manifest=output_dir/(name+".rknn.json")
        if not enabled:
            output.unlink(missing_ok=True)
            manifest.unlink(missing_ok=True)
            continue
        if n not in PINS:
            if required: raise ValueError(f"No pinned 10b {n}x{n} RKNN release yet")
            print(f"RKNN_10B_MULTI_SKIP board={n} reason=no_pin",flush=True)
            output.unlink(missing_ok=True)
            manifest.unlink(missing_ok=True)
            continue
        if verify(n,output,manifest):
            print(f"RKNN_10B_MULTI_READY board={n} reused=1",flush=True)
            continue
        cache_dir=root/"build/rk3588_10b/releases"/str(n)
        cache=cache_dir/(name+".rknn")
        cache_meta=cache_dir/(name+".rknn.json")
        if not verify(n,cache,cache_meta):
            if cache.exists() or cache_meta.exists():
                raise ValueError(f"10b {n} invalid downloaded cache; will not replace developer files")
            tag=f"rk3588-dual-human-10b-{n}x{n}-experimental-v1"
            url=f"https://github.com/rstg00po54/go/releases/download/{tag}/"
            cache_dir.mkdir(parents=True,exist_ok=True)
            print(f"RKNN_10B_MULTI_DOWNLOAD board={n}",flush=True)
            last_error = None
            for attempt in range(1,4):
                temp_meta=cache_meta.with_suffix(cache_meta.suffix+".download")
                temp_binary=cache.with_suffix(cache.suffix+".download")
                try:
                    print(f"RKNN_10B_MULTI_DOWNLOAD_ATTEMPT board={n} attempt={attempt}/3",flush=True)
                    stream_download(url+name+".rknn.json",temp_meta,fetch)
                    stream_download(url+name+".rknn",temp_binary,fetch)
                    if not verify(n,temp_binary,temp_meta):
                        raise ValueError("Downloaded 10b RKNN failed validation: "+
                                         model_diagnostics(n,temp_binary,temp_meta))
                    os.replace(temp_meta,cache_meta)
                    os.replace(temp_binary,cache)
                    last_error=None
                    break
                except (OSError,ValueError,subprocess.CalledProcessError) as error:
                    last_error=error
                    print(f"RKNN_10B_MULTI_DOWNLOAD_RETRY board={n} attempt={attempt} "
                          f"reason={error}",flush=True)
                finally:
                    temp_meta.unlink(missing_ok=True)
                    temp_binary.unlink(missing_ok=True)
            if last_error is not None:
                if required:
                    raise RuntimeError(f"RKNN 10b {n} download verification failed after 3 attempts: "
                                       f"{last_error}") from last_error
                print(f"RKNN_10B_MULTI_SKIP board={n} reason=download_error",flush=True)
                continue
        if not verify(n,cache,cache_meta):
            raise ValueError("RKNN 10b cache integrity check failed: "+
                             model_diagnostics(n,cache,cache_meta))
        safe_copy(cache,output)
        safe_copy(cache_meta,manifest)
        if not verify(n,output,manifest):
            output.unlink(missing_ok=True); manifest.unlink(missing_ok=True)
            raise ValueError(f"10b {n} corrupt during asset staging")
        print(f"RKNN_10B_MULTI_READY board={n} sha256={PINS[n][0]}",flush=True)

def main():
    p=argparse.ArgumentParser(description=__doc__)
    group=p.add_mutually_exclusive_group(required=True)
    group.add_argument("--enabled",action="store_true")
    group.add_argument("--disabled",action="store_true")
    p.add_argument("--require-five-sizes",action="store_true")
    a=p.parse_args()
    stage(a.enabled,required=a.require_five_sizes)

if __name__=="__main__": main()
