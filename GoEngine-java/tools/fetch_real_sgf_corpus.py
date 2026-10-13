#!/usr/bin/env python3
"""Download reproducible, strictly filtered real scored Go games for NN tests.

Sources are the public 2015-Go9-small and 2019-1255games SGF collections from
LatinScribe/BetaGoZero, preserving their original SGF and provenance. Uses the
same acceptance checks as benchmark_real_sgf_nn_android.py. Does NOT upload.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import random
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from benchmark_real_sgf_nn_android import read_recorded_game

REPO = "LatinScribe/BetaGoZero"
BRANCH = "main"
TREE_URL = "https://api.github.com/repos/%s/git/trees/%s?recursive=1" % (REPO, BRANCH)
SOURCES = (
    ("archived9", 9, "DataSet/2015-Go9-small/"),
    ("archived19", 19, "DataSet/2019-1255games/"),
)


def fetch(url, attempts=3, timeout=25):
    for retry in range(attempts):
        request = Request(url, headers={"User-Agent": "KataGo-Real-SGF-Scoring-Benchmark",
                                        "Accept": "application/json"})
        try:
            with urlopen(request, timeout=timeout) as response:
                data = response.read(256 * 1024 if "raw.githubusercontent" in url else 10 * 1024 * 1024)
                if "raw.githubusercontent" in url and len(data) >= 256 * 1024:
                    raise ValueError("Unexpected oversized SGF at " + url)
                return data.decode("utf-8-sig", errors="replace")
        except (HTTPError, URLError, TimeoutError, OSError) as exc:
            if retry + 1 == attempts:
                raise RuntimeError("%s: %s" % (url, exc))
            time.sleep(0.6 * (retry + 1))


def list_source_paths(tree):
    if tree.get("truncated") is True:
        raise ValueError("GitHub tree truncated; source listing is incomplete")
    if not isinstance(tree.get("tree"), list):
        raise ValueError("Unexpected GitHub tree listing")
    result = {}
    for prefix, _, folder in SOURCES:
        paths = [x["path"] for x in tree["tree"] if x.get("type") == "blob"
                 and x.get("path", "").startswith(folder)
                 and x.get("path", "").lower().endswith(".sgf")]
        # Fixed pseudo-random ordering avoids sampling the earliest dates only.
        random.Random(20261013 + (9 if prefix == "archived9" else 19)).shuffle(paths)
        result[prefix] = paths
    return result


def validate_download(name, size, path, sgf):
    """Return accepted record and original SGF only if recorded score is numeric."""
    info = read_recorded_game(sgf, name)
    if info["size"] != size:
        raise ValueError("Wrong board size %s for %s" % (info["size"], name))
    url = "https://raw.githubusercontent.com/%s/%s/%s" % (REPO, BRANCH, path)
    info.update(source=url, sha256=hashlib.sha256(sgf.encode("utf-8")).hexdigest())
    return info


def populate(output, paths, target9, target19, workers=6):
    output.mkdir(parents=True, exist_ok=True)
    if not output.is_dir():
        raise ValueError("Cannot create output " + str(output))
    manifest = {"source_repo": REPO, "tree": TREE_URL, "seed": 20261013,
                "note": "Unmodified public recorded-game SGFs; only numeric scored Chinese-rules games",
                "games": [], "statistics": {}}
    all_kept = set()
    for label, size, _ in SOURCES:
        target = target9 if size == 9 else target19
        candidates = paths.get(label, [])
        selected, counts, skipped = [], {}, {}
        if target == 0:
            manifest["statistics"][label] = {"requested": 0, "downloaded": 0,
                                             "examined": 0, "candidates": len(candidates)}
            continue

        def download(item):
            url = "https://raw.githubusercontent.com/%s/%s/%s" % (REPO, BRANCH, item)
            try:
                sgf = fetch(url)
                record = validate_download(Path(item).name, size, item, sgf)
                return item, sgf, record, ""
            except (ValueError, RuntimeError) as exc:
                return item, None, None, str(exc)

        # Bounded batches: don't fetch the entire repository if only 80 samples are needed.
        # A deterministic candidate order plus stable workers yields reproducible selection.
        with ThreadPoolExecutor(max_workers=workers) as pool:
            for offset in range(0, len(candidates), 24):
                group = candidates[offset:offset + 24]
                for item, sgf, record, error in pool.map(download, group):
                    counts["examined"] = counts.get("examined", 0) + 1
                    if error:
                        reason = error.split(":", 1)[0][:110]
                        skipped[reason] = skipped.get(reason, 0) + 1
                        continue
                    if record["sha256"] in all_kept:
                        skipped["duplicate"] = skipped.get("duplicate", 0) + 1
                        continue
                    selected.append((item, sgf, record))
                    all_kept.add(record["sha256"])
                    if len(selected) >= target:
                        break
                if len(selected) >= target:
                    break
                print("%s: collected %d/%d valid, checked %d" % (
                    label, len(selected), target, counts.get("examined", 0)), flush=True)

        for i, (item, sgf, record) in enumerate(selected):
            filename = "%s_%03d_%s" % (label, i + 1, Path(item).name)
            path = output / filename
            path.write_text(sgf, encoding="utf-8")
            manifest["games"].append({
                "file": filename, "source": record["source"],
                "sha256": record["sha256"], "size": size,
                "result": record["official_re"], "komi": record["komi"],
                "moves": record["moves"], "terminal_passes": record["terminal_passes"],
            })

        manifest["statistics"][label] = {
            "requested": target, "downloaded": len(selected),
            "examined": counts.get("examined", 0), "candidates": len(candidates),
            "rejections": skipped,
        }
        print("%s %dx%d: %d/%d accepted from %d examined" % (
            label, size, size, len(selected), target, counts.get("examined", 0)), flush=True)

    # Remove stale SGFs from an earlier run; delete *only* downloader-managed files.
    keep = {x["file"] for x in manifest["games"]}
    for label, _, _ in SOURCES:
        for path in output.glob(label + "_*.sgf"):
            if path.name not in keep:
                path.unlink()

    manifest["unique_games"] = len(manifest["games"])
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                                          encoding="utf-8")
    if not manifest["unique_games"]:
        raise RuntimeError("No eligible real scored games; check network and filter requirements")
    return manifest


def main(argv=None):
    p = argparse.ArgumentParser(description="Build larger public real-scored SGF corpus, no ADB needed")
    p.add_argument("--nine", type=int, default=80, help="Target valid 9x9 games")
    p.add_argument("--nineteen", type=int, default=20, help="Target valid 19x19 games")
    p.add_argument("--workers", type=int, default=6)
    p.add_argument("--output", type=Path, default=Path("build/real_sgf_corpus"))
    args = p.parse_args(argv)
    if args.nine < 0 or args.nineteen < 0 or args.nine + args.nineteen < 1:
        p.error("Request at least one real game")
    if not 1 <= args.workers <= 12:
        p.error("workers must be 1..12")
    try:
        tree = json.loads(fetch(TREE_URL))
        paths = list_source_paths(tree)
        report = populate(args.output, paths, args.nine, args.nineteen, args.workers)
        print("READY: %d distinct numeric scored games in %s" % (
            report["unique_games"], args.output), flush=True)
        print("Validate offline: python3 tools/benchmark_real_sgf_nn_android.py "
              "--preflight --sgf-dir %s --limit 1000" % args.output, flush=True)
        print("RK3588 GPU: python3 tools/benchmark_real_sgf_nn_android.py "
              "--serial 8719e18a71a2a66c --mode gpu --sgf-dir %s --limit 1000 "
              "--repeats 1 --upload" % args.output, flush=True)
        return 0
    except (ValueError, RuntimeError, OSError) as exc:
        print("ERROR: " + str(exc), file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
