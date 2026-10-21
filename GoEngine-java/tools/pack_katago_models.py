#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Prepare byte-identical, board-specific KataGo model assets for Android.

Only files that have an existing 19x19 reference are eligible. Uses a small
copy/insert (KGMDEL01) format so Android does not require xdelta3/JNI.
Original model bytes are NEVER changed. This tool reconstructs each delta
before removing its original from the staged output.

Example:
  python3 tools/pack_katago_models.py \
    --assets app/src/main/assets \
    --generated app/build/generated/rk3588HumanAssets \
    --out app/build/generated/katagoCompactAssets
"""
import argparse
import hashlib
import mmap
import os
from pathlib import Path
import shutil
import struct
import tempfile

MAGIC = b"KGMDEL01"
ANCHOR = 24
STRIDE = 512
CHUNK = 65536
MAX_LITERAL = 1024 * 1024
# If a model unexpectedly differs considerably, do not silently ship a huge
# patch; fail so the user can turn the compact flag off for a normal build.
MAX_PATCH_RATIO = 0.10


def digest(data):
    return hashlib.sha256(data).digest()


def match_length(base, target, bp, tp):
    limit = min(len(base) - bp, len(target) - tp)
    length = 0
    while length + CHUNK <= limit and base[bp + length:bp + length + CHUNK] == target[tp + length:tp + length + CHUNK]:
        length += CHUNK
    lo, hi = 0, min(CHUNK, limit - length)
    while lo < hi:
        middle = (lo + hi + 1) // 2
        if base[bp + length:bp + length + middle] == target[tp + length:tp + length + middle]:
            lo = middle
        else:
            hi = middle - 1
    return length + lo


def create_delta(source, target, dest):
    """Find long byte-exact matches despite changed header/offset positions."""
    with source.open("rb") as fa, target.open("rb") as fb:
        with mmap.mmap(fa.fileno(), 0, access=mmap.ACCESS_READ) as base, \
                mmap.mmap(fb.fileno(), 0, access=mmap.ACCESS_READ) as desired:
            # Sparse dictionary: <30 MB for a ~107 MB network, rather than
            # indexing every byte. All matched bytes are copied verbatim.
            positions = {}
            for p in range(0, len(base) - ANCHOR + 1, STRIDE):
                key = base[p:p + ANCHOR]
                positions.setdefault(key, p)

            with dest.open("wb") as out:
                out.write(MAGIC)
                out.write(digest(base))
                out.write(digest(desired))
                out.write(struct.pack(">Q", len(desired)))
                at = 0
                inserted_from = 0
                source_hint = 0
                while at < len(desired):
                    candidate = None
                    # Stay at the existing offset whenever possible, e.g.
                    # successive chunks of huge identical weight arrays.
                    if source_hint < len(base) and base[source_hint:source_hint + ANCHOR] == desired[at:at + ANCHOR]:
                        candidate = source_hint
                    if candidate is None and at + ANCHOR <= len(desired):
                        candidate = positions.get(desired[at:at + ANCHOR])
                    if candidate is not None:
                        length = match_length(base, desired, candidate, at)
                        if length >= ANCHOR:
                            if at > inserted_from:
                                write_insert(out, desired, inserted_from, at)
                            while length:
                                n = min(length, 0x7fffffff)
                                out.write(b"C")
                                out.write(struct.pack(">QI", candidate, n))
                                candidate += n
                                at += n
                                length -= n
                            inserted_from = at
                            source_hint = candidate
                            continue
                    at += 1
                    source_hint += 1
                    if at - inserted_from >= MAX_LITERAL:
                        write_insert(out, desired, inserted_from, at)
                        inserted_from = at
                if at > inserted_from:
                    write_insert(out, desired, inserted_from, at)
                out.write(b"E")


def write_insert(out, data, begin, end):
    while begin < end:
        n = min(end - begin, MAX_LITERAL)
        out.write(b"I")
        out.write(struct.pack(">I", n))
        out.write(data[begin:begin+n])
        begin += n


def restore_delta(source, patch, dest):
    """Reference decoder for verification; mirrors KataGoModelDelta.java."""
    with source.open("rb") as original, patch.open("rb") as instructions:
        header = instructions.read(8 + 32 + 32 + 8)
        if len(header) != 80 or header[:8] != MAGIC:
            raise ValueError("Invalid patch header")
        base_sha, expected_sha = header[8:40], header[40:72]
        expected_len, = struct.unpack(">Q", header[72:80])
        with mmap.mmap(original.fileno(), 0, access=mmap.ACCESS_READ) as base:
            if digest(base) != base_sha:
                raise ValueError("Base SHA256 mismatch")
            written = 0
            sha = hashlib.sha256()
            with dest.open("wb") as target:
                while True:
                    op = instructions.read(1)
                    if op == b"E":
                        if instructions.read(1):
                            raise ValueError("Trailing patch bytes")
                        break
                    if op == b"C":
                        info = instructions.read(12)
                        if len(info) != 12:
                            raise ValueError("Truncated copy")
                        offset, length = struct.unpack(">QI", info)
                        if offset + length > len(base) or written + length > expected_len:
                            raise ValueError("Invalid copy bounds")
                        data = base[offset:offset+length]
                    elif op == b"I":
                        info = instructions.read(4)
                        if len(info) != 4:
                            raise ValueError("Truncated insert")
                        length, = struct.unpack(">I", info)
                        if written + length > expected_len:
                            raise ValueError("Insert exceeds model size")
                        data = instructions.read(length)
                        if len(data) != length:
                            raise ValueError("Truncated insert data")
                    else:
                        raise ValueError("Invalid delta instruction")
                    target.write(data)
                    sha.update(data)
                    written += len(data)
            if written != expected_len or sha.digest() != expected_sha:
                raise ValueError("Restored model hash/size mismatch")


def copy_tree_files(source, dest):
    if not source.is_dir():
        return
    for item in sorted(source.rglob("*")):
        if not item.is_file():
            continue
        target = dest / item.relative_to(source)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(item, target)


def compact(directory):
    if not directory.is_dir():
        return
    for kind in ("human", "10b"):
        for extension in ("tflite", "rknn"):
            base = directory / f"katago_{kind}_19x19.{extension}"
            if not base.is_file():
                continue
            for board in (9, 11, 13, 15):
                original = directory / f"katago_{kind}_{board}x{board}.{extension}"
                if not original.is_file():
                    continue
                patch = original.with_name(original.name + ".kgdelta")
                restored = patch.with_name(patch.name + ".verified")
                try:
                    create_delta(base, original, patch)
                    if patch.stat().st_size > original.stat().st_size * MAX_PATCH_RATIO:
                        raise ValueError(f"Patch too large for {original.name}: {patch.stat().st_size}")
                    restore_delta(base, patch, restored)
                    with original.open("rb") as a, restored.open("rb") as b:
                        while True:
                            aa, bb = a.read(CHUNK), b.read(CHUNK)
                            if aa != bb:
                                raise ValueError(f"Byte mismatch restoring {original.name}")
                            if not aa:
                                break
                    saved = original.stat().st_size - patch.stat().st_size
                    original.unlink()
                    print(f"COMPACT_MODEL_OK {original.name} patch={patch.stat().st_size} saved={saved}", flush=True)
                finally:
                    restored.unlink(missing_ok=True)


def main():
    arg = argparse.ArgumentParser(description=__doc__)
    arg.add_argument("--assets", type=Path, required=True)
    arg.add_argument("--generated", type=Path, required=True)
    arg.add_argument("--out", type=Path, required=True)
    opts = arg.parse_args()
    if not opts.assets.is_dir():
        arg.error(f"Source assets not found: {opts.assets}")
    # Assemble in an isolated staging dir. If generation fails, never leave
    # partial files in the destination source set.
    opts.out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=opts.out.parent, prefix="katago_pack_") as tmp:
        stage = Path(tmp)
        copy_tree_files(opts.assets, stage)
        copy_tree_files(opts.generated, stage)
        compact(stage / "engine")
        if opts.out.exists():
            shutil.rmtree(opts.out)
        shutil.move(str(stage), str(opts.out))
        print(f"COMPACT_ASSETS_READY {opts.out}", flush=True)


if __name__ == "__main__":
    main()
