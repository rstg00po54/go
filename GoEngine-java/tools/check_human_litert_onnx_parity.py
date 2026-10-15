#!/usr/bin/env python3
"""Independently verify experimental Human SL TFLite heads against original ONNX.

Numerical validation precedes any attempt to run a new Neuron graph. This
compares ALL five heads for several different 192-channel rank metadata vectors,
not merely model compilation or finiteness. ONNX and LiteRT output scales must
agree; CPU-only comparison cannot prove physical MediaTek NPU execution.
"""
import argparse
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
from ai_edge_litert.interpreter import Interpreter

HEADS = ("OutputPolicyPass", "OutputPolicy", "OutputValue",
         "OutputScoreValue", "OutputOwnership")
INPUTS = ("InputSpatial", "InputGlobal", "InputMeta", "InputMask")


def raw_name(name):
    return name.split(":")[0].split("/")[-1].removeprefix("serving_default_")


def expected_shape(key, n, input_meta):
    if key == "InputSpatial": return [1, 22, n, n]
    if key == "InputGlobal": return [1, 19, 1, 1]
    if key == "InputMeta": return [1, 192, 1, 1]
    if key == "InputMask": return [1, 1, n, n]
    raise ValueError(key)


def reshape_input(value, shape):
    if tuple(value.shape) == tuple(shape):
        return value
    if len(shape) == 4 and (value.shape[1] == shape[-1]) and (
            value.shape[2] == shape[1]):
        return np.transpose(value, [0, 2, 3, 1])
    if shape == [1, value.size]:
        return value.reshape(shape)
    raise ValueError("Unexpected tensor shape %s -> %s" % (value.shape, shape))


def arrange_output(key, value, expected):
    if value.shape == expected.shape: return value
    if len(value.shape) == 4 and tuple(value.shape) == (
            expected.shape[0], expected.shape[2], expected.shape[3], expected.shape[1]):
        return np.transpose(value, (0, 3, 1, 2))
    if value.size == expected.size:
        return value.reshape(expected.shape)
    raise ValueError("Incorrect head layout for " + key)


def verify(onnx, tflite, signature, size):
    metadata = json.loads(signature.read_text())
    if metadata["modelType"] != "human" or metadata["numInputMetaChannels"] != 192:
        raise ValueError("Not a b18 Human SL graph")
    original = ort.InferenceSession(str(onnx), providers=["CPUExecutionProvider"])
    cpu = Interpreter(model_path=str(tflite), num_threads=1)
    cpu.allocate_tensors()
    inputs = cpu.get_input_details()
    outputs = cpu.get_output_details()
    if len(inputs) != 4 or len(outputs) != 5:
        raise ValueError("Incorrect TFLite tensor signature")
    by_input = {raw_name(t["name"]): t for t in inputs}
    by_output = {raw_name(t["name"]): t for t in outputs}
    if set(by_input) != set(INPUTS) or set(by_output) != set(HEADS):
        raise ValueError("Missing Human SL tensors")
    cases = []
    for seed in (0, 9, 23):
        rng = np.random.default_rng(seed)
        values = {}
        for key in INPUTS:
            value = np.zeros(expected_shape(key, size, 192), dtype=np.float32)
            if key == "InputMask": value.fill(1.)
            elif key == "InputSpatial":
                value[:, 0, :, :] = 1.
                for _ in range(3):
                    row, col = rng.integers(0, size, size=2)
                    value[:, 1, row, col] = 1.
            elif key == "InputMeta":
                # Stress different rank encodings. Values are only synthetic
                # inputs, not a replacement for native KataGo metadata.
                value[0, seed % 192, 0, 0] = 1.
                value[0, 60 + seed % 100, 0, 0] = 0.5
            values[key] = value
        expected_outputs = original.run(list(HEADS), values)
        for key, detail in by_input.items():
            cpu.set_tensor(int(detail["index"]), reshape_input(
                values[key], list(map(int, detail["shape"]))))
        cpu.invoke()
        per_case = {}
        for key, expected in zip(HEADS, expected_outputs):
            actual = arrange_output(key, cpu.get_tensor(int(by_output[key]["index"])),
                                    expected)
            if actual.shape != expected.shape:
                raise ValueError("Head shape mismatch " + key)
            if not np.isfinite(actual).all():
                raise ValueError("Nonfinite " + key)
            diff = np.abs(actual - expected)
            abs_max = float(diff.max(initial=0))
            mae = float(diff.mean())
            per_case[key] = {"maxAbs": abs_max, "mae": mae}
            if not np.allclose(actual, expected, rtol=0.02, atol=0.05):
                raise ValueError("Human ONNX/LiteRT parity failed key=%s case=%d maxAbs=%f mae=%f" %
                                 (key, seed, abs_max, mae))
        print("HUMAN_ONNX_TFLITE_PARITY_PASS case=%d %s" %
              (seed, json.dumps(per_case)), flush=True)
        cases.append(per_case)
    return {"status": "PASS", "cases": cases}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--onnx", type=Path, required=True)
    p.add_argument("--tflite", type=Path, required=True)
    p.add_argument("--signature", type=Path, required=True)
    p.add_argument("--size", type=int, choices=(9, 11, 13, 15, 19), required=True)
    p.add_argument("--report", type=Path, required=True)
    args = p.parse_args()
    result = verify(args.onnx, args.tflite, args.signature, args.size)
    args.report.write_text(json.dumps(result, indent=2)+"\n")
    print("HUMAN_ONNX_TFLITE_ALL_HEADS_PASS", flush=True)


if __name__ == "__main__":
    main()
