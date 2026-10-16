#!/usr/bin/env python3
"""Export deterministic CPU golden tensors for original b18 RK3588 NPU parity.

Reference is the *original* ONNX dumped from b18c384nbt-humanv0.bin, not
the rewritten RKNN graph. InputSpatial is serialized NHWC (device layout);
ONNX CPU executes NCHW. Outputs are written in their declared NCHW order.

Binary format, all little endian:
  8 bytes magic B18REF01, uint32 board_size, uint32 case_count
  for each case: four inputs and five outputs as float32 arrays, no padding.
Input order: Spatial, Global, Meta, Mask. Output order: PolicyPass, Policy,
Value, ScoreValue, Ownership. This is a synthetic-fixture test, not evidence
that the native KataGo rank encoder produces these metadata values.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

import numpy as np
import onnxruntime as ort

INPUTS = ("InputSpatial", "InputGlobal", "InputMeta", "InputMask")
OUTPUTS = ("OutputPolicyPass", "OutputPolicy", "OutputValue",
           "OutputScoreValue", "OutputOwnership")
SEEDS = (0, 9, 42, 106)


def fixtures(size):
    for trial, seed in enumerate(SEEDS):
        rng = np.random.default_rng(seed)
        spatial = np.zeros((1, 22, size, size), dtype=np.float32)
        spatial[:, 0, :, :] = 1.0
        if trial:
            for i in range(1 + trial * 3):
                y, x = rng.integers(0, size, size=2)
                spatial[0, 1 + i % 2, y, x] = 1.0
        global_features = np.zeros((1, 19, 1, 1), dtype=np.float32)
        global_features[0, 5, 0, 0] = 0.5 + trial
        meta = np.zeros((1, 192, 1, 1), dtype=np.float32)
        meta[0, (trial * 17) % 192, 0, 0] = 1.0
        meta[0, 96 + (trial * 11) % 96, 0, 0] = 0.5
        mask = np.ones((1, 1, size, size), dtype=np.float32)
        yield {
            "InputSpatial": spatial, "InputGlobal": global_features,
            "InputMeta": meta, "InputMask": mask
        }


def create_reference(onnx_path, output_path, manifest_path, size=9):
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 2
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    session = ort.InferenceSession(str(onnx_path), sess_options=opts,
                                   providers=["CPUExecutionProvider"])
    if [x.name for x in session.get_inputs()] != list(INPUTS):
        raise ValueError("Original Human b18 four-input contract changed")
    if [x.name for x in session.get_outputs()] != list(OUTPUTS):
        raise ValueError("Original Human b18 five-head contract changed")
    input_counts = (22 * size * size, 19, 192, size * size)
    output_counts = (2, 2 * size * size, 3, 6, size * size)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("wb") as stream:
        stream.write(b"B18REF01")
        stream.write(struct.pack("<II", size, len(SEEDS)))
        records = []
        for case_index, inputs in enumerate(fixtures(size)):
            outputs = session.run(list(OUTPUTS), inputs)
            record = {"case": case_index, "seed": SEEDS[case_index],
                      "metaNonzeroIndices": np.flatnonzero(
                          inputs["InputMeta"]).tolist(),
                      "outputs": {}}
            for name, count in zip(INPUTS, input_counts):
                array = inputs[name]
                if name in ("InputSpatial", "InputMask"):
                    array = array.transpose(0, 2, 3, 1)
                if array.size != count or not np.isfinite(array).all():
                    raise ValueError("Invalid input " + name)
                stream.write(np.ascontiguousarray(array, dtype="<f4").tobytes())
            for name, array, count in zip(OUTPUTS, outputs, output_counts):
                if array.size != count or not np.isfinite(array).all():
                    raise ValueError("Invalid CPU output " + name)
                stream.write(np.ascontiguousarray(array, dtype="<f4").tobytes())
                record["outputs"][name] = {
                    "count": count, "first": float(array.flat[0]),
                    "absMax": float(np.max(np.abs(array)))
                }
            records.append(record)
    digest = hashlib.sha256(output_path.read_bytes()).hexdigest()
    manifest = {
        "model": "b18c384nbt-humanv0", "board": size,
        "format": "B18REF01-le-f32", "cases": len(SEEDS),
        "source": "original b18 official dumponnx 1.18.1",
        "cpuBackend": "onnxruntime-cpu",
        "inputOrder": INPUTS, "outputOrder": OUTPUTS,
        "inputCounts": input_counts, "outputCounts": output_counts,
        "fixtureSha256": digest,
        "syntheticInputsOnly": True,
        "nativeKataGoRankEncoderVerified": False,
        "sourceOnnxSha256": hashlib.sha256(onnx_path.read_bytes()).hexdigest(),
        "records": records
    }
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"HUMAN_CPU_REFERENCE_READY cases={len(SEEDS)} board={size} "
          f"bytes={output_path.stat().st_size} sha256={digest}", flush=True)
    return manifest


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--onnx", type=Path, required=True)
    ap.add_argument("--size", type=int, default=9, choices=(9, 11, 13, 15, 19))
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--manifest", type=Path, required=True)
    args = ap.parse_args()
    create_reference(args.onnx, args.output, args.manifest, args.size)


if __name__ == "__main__":
    main()
