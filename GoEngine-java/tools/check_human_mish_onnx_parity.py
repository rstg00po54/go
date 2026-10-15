#!/usr/bin/env python3
"""Require b18 Human original/re-written ONNX agreement over 5 output heads.

The 192-channel Human rank vector is varied between test cases. No original
initializer, model I/O metadata, or network architecture is intentionally
modified. These are CPU reference checks, not proof of NPU execution.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort

INPUT_NAMES = ("InputSpatial", "InputGlobal", "InputMeta", "InputMask")
OUTPUT_NAMES = ("OutputPolicyPass", "OutputPolicy", "OutputValue",
                "OutputScoreValue", "OutputOwnership")


def compare(original_path, rewritten_path, size, atol=0.002, rtol=0.001):
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 2
    opts.inter_op_num_threads = 1
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    original = ort.InferenceSession(str(original_path), sess_options=opts,
                                    providers=["CPUExecutionProvider"])
    rewritten = ort.InferenceSession(str(rewritten_path), sess_options=opts,
                                     providers=["CPUExecutionProvider"])
    if set(v.name for v in original.get_inputs()) != set(INPUT_NAMES) or (
            set(v.name for v in original.get_outputs()) != set(OUTPUT_NAMES)):
        raise ValueError("The original b18 Human graph has an unexpected I/O signature")
    if [(v.name, v.shape) for v in original.get_inputs()] != (
            [(v.name, v.shape) for v in rewritten.get_inputs()]):
        raise ValueError("Original and rewritten Human input signature differs")
    if [(v.name, v.shape) for v in original.get_outputs()] != (
            [(v.name, v.shape) for v in rewritten.get_outputs()]):
        raise ValueError("Original and rewritten Human output signature differs")

    cases = []
    for trial, seed in enumerate((0, 9, 42, 106)):
        rng = np.random.default_rng(seed)
        spatial = np.zeros((1,22,size,size), dtype=np.float32)
        spatial[:,0,:,:] = 1.
        if trial > 0:
            for i in range(1 + trial * 3):
                row, col = rng.integers(0,size,size=2)
                spatial[0,1 + (i%2),row,col] = 1.
        global_ = np.zeros((1,19,1,1), dtype=np.float32)
        global_[0,5,0,0] = .5 + trial
        meta = np.zeros((1,192,1,1), dtype=np.float32)
        meta[0,(trial*17)%192,0,0] = 1.
        meta[0,96+(trial*11)%96,0,0] = .5
        inputs = {"InputSpatial":spatial, "InputGlobal":global_,
                  "InputMeta":meta,
                  "InputMask":np.ones((1,1,size,size), dtype=np.float32)}
        src = original.run(list(OUTPUT_NAMES), inputs)
        dst = rewritten.run(list(OUTPUT_NAMES), inputs)
        results = {}
        for key, expected, actual in zip(OUTPUT_NAMES, src, dst):
            if expected.shape != actual.shape:
                raise ValueError("Changed Human output tensor dimensions: "+key)
            if not np.isfinite(expected).all() or not np.isfinite(actual).all():
                raise ValueError("Nonfinite result in "+key)
            error = np.abs(expected.astype(np.float64)-actual.astype(np.float64))
            max_abs = float(error.max(initial=0))
            mae = float(error.mean())
            results[key] = {"maxAbs":max_abs,"mae":mae,
                            "refAbsMax":float(np.max(np.abs(expected)))}
            if not np.allclose(expected, actual, rtol=rtol, atol=atol):
                raise ValueError(
                    "Human b18 original/optimized mismatch case=%d head=%s "
                    "maxAbs=%g mae=%g" % (trial, key, max_abs, mae))
        cases.append({"case":trial, "rankCode":(trial*17)%192, "heads":results})
        print("HUMAN_MISH_PARITY_PASS case=%d %s" %
              (trial,json.dumps(results,sort_keys=True)),flush=True)
    return {"model":"b18c384nbt-humanv0","board":size,"status":"PASS",
            "graphBackend":"onnxruntime-cpu", "rankVectorsTested":len(cases),
            "atol":atol,"rtol":rtol,"cases":cases,
            "hardwareExecution":"NOT_TESTED"}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--original",type=Path,required=True)
    p.add_argument("--rewritten",type=Path,required=True)
    p.add_argument("--size",type=int,choices=(9,11,13,15,19),required=True)
    p.add_argument("--report",type=Path,required=True)
    args=p.parse_args()
    results=compare(args.original,args.rewritten,args.size)
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(results,indent=2,sort_keys=True)+"\n",
                           encoding="utf-8")
    print("HUMAN_MISH_ALL_HEADS_PARITY_PASS",flush=True)


if __name__ == "__main__":
    main()
