#!/usr/bin/env python3
"""Convert a TRUE size-specific KataGo 10b ONNX graph into a LiteRT .tflite.

The model must originate from KataGo 'dumponnx -nn-x-len SIZE -nn-y-len SIZE'
with g170 weights. No RKNN graph or dimension-spoofing is permitted.

This script deliberately refuses to publish an unverified TFLite graph.
The tensor signature is saved as a JSON contract for the Android runtime.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess

MODEL_NAME = "g170-b10c128-s1141046784-d204142634"
INPUT_NAMES = ("InputSpatial", "InputGlobal", "InputMask")
OUTPUT_NAMES = ("OutputPolicyPass", "OutputPolicy", "OutputValue", "OutputScoreValue", "OutputOwnership")


def shape(desc):
    return [int(d.dim_value) for d in desc.type.tensor_type.shape.dim]


def validate_onnx(path, size):
    import onnx
    model = onnx.load(str(path))
    meta = {p.key: p.value for p in model.metadata_props}
    assert meta.get("katago.name") == MODEL_NAME, "Incorrect KataGo model weights"
    assert meta.get("katago.build.nnXLen") == str(size), "Wrong ONNX board X length"
    assert meta.get("katago.build.nnYLen") == str(size), "Wrong ONNX board Y length"
    got = {x.name: shape(x) for x in model.graph.input}
    expected = {"InputSpatial": [0, 22, size, size],
                "InputGlobal": [0, 19, 1, 1],
                "InputMask": [0, 1, size, size]}
    assert got == expected, "Unexpected ONNX shapes: %r" % got
    assert tuple(x.name for x in model.graph.output) == OUTPUT_NAMES, "Unexpected ONNX output heads"
    # KataGo dumponnx exports a dynamic batch=0; NPU models must be fixed
    # batch=1. Only this known dimension is made concrete; all 3 input
    # names, feature channels and size-specific convolution weights remain.
    for item in model.graph.input:
        item.type.tensor_type.shape.dim[0].Clear()
        item.type.tensor_type.shape.dim[0].dim_value = 1
    onnx.checker.check_model(model)
    return model



def output_scale_from_onnx(model):
    """Preserve the documented KataGo ONNX outputScaleMultiplier contract."""
    meta = {entry.key: entry.value for entry in model.metadata_props}
    applied = meta.get("katago.build.scale8Applied")
    value = meta.get("katago.postProcess.outputScaleMultiplier")
    if applied not in ("true", "false"):
        raise ValueError("KataGo dumponnx must declare katago.build.scale8Applied")
    # Graph scale8 is a numerical transformation; raw outputs need the inverse.
    expected = 8.0 if applied == "true" else 1.0
    if value is None:
        raise ValueError("Missing katago.postProcess.outputScaleMultiplier in ONNX graph")
    scale = float(value)
    if scale != expected:
        raise ValueError("ONNX outputScaleMultiplier disagrees with scale8Applied: "
                         f"{scale} vs {expected}")
    return {"scale8Applied": applied == "true", "outputScaleMultiplier": scale}


def classify_name(name, choices):
    matches = [item for item in choices if name == item or name.endswith("/" + item)
               or name.endswith(":" + item) or name.endswith("_" + item)]
    if len(matches) == 1: return matches[0]
    # onnx2tf -coion may append '.N' port suffixes.
    matches = [item for item in choices if name.split(":")[0].split("/")[-1] == item]
    if len(matches) == 1: return matches[0]
    raise ValueError("Unexpected tensor name, cannot safely infer head: " + name)


def inspect_tflite(path, size):
    import numpy as np
    from ai_edge_litert.interpreter import Interpreter
    runtime = Interpreter(model_path=str(path), num_threads=1)
    runtime.allocate_tensors()
    inp, out = runtime.get_input_details(), runtime.get_output_details()
    assert len(inp) == 3 and len(out) == 5, "Wrong tensor IO count"
    spatial = [1, size, size, 22]
    global_shapes = ([1, 19, 1, 1], [1, 1, 1, 19], [1, 19])
    input_expected = {
        "InputSpatial": [spatial, [1, 22, size, size]],
        "InputGlobal": global_shapes,
        "InputMask": [[1, size, size, 1], [1, 1, size, size]]
    }
    output_size = {"OutputPolicyPass": 1, "OutputPolicy": size * size,
                   "OutputValue": 3, "OutputScoreValue": 4, "OutputOwnership": size * size}
    inputs = {}
    for d in inp:
        name = classify_name(d["name"], INPUT_NAMES)
        dims = list(map(int, d["shape"]))
        assert name not in inputs and dims in input_expected[name], (name, dims)
        assert d["dtype"] == np.float32, (name, d["dtype"])
        inputs[name] = {"index": int(d["index"]), "name": d["name"], "shape": dims,
                        "count": int(np.prod(dims))}
    outputs = {}
    for d in out:
        name = classify_name(d["name"], OUTPUT_NAMES)
        dims = list(map(int, d["shape"]))
        assert name not in outputs and int(np.prod(dims)) == output_size[name], (name, dims)
        assert d["dtype"] == np.float32, (name, d["dtype"])
        outputs[name] = {"index": int(d["index"]), "name": d["name"], "shape": dims,
                         "count": int(np.prod(dims))}
    assert set(inputs) == set(INPUT_NAMES) and set(outputs) == set(OUTPUT_NAMES)
    # Smoke real CPU inference to catch invalid FlatBuffer/operator conversion.
    rng = np.random.default_rng(123)
    for name, entry in inputs.items():
        value = rng.uniform(-0.1, 0.1, entry["shape"]).astype(np.float32)
        if name == "InputMask": value[...] = 1.0
        runtime.set_tensor(entry["index"], value)
    runtime.invoke()
    for name, entry in outputs.items():
        value = runtime.get_tensor(entry["index"])
        assert np.isfinite(value).all(), "NaN/Inf in " + name
    return {"size": size, "weights": MODEL_NAME, "format": "tflite-f32",
            "inputOrder": [classify_name(d["name"], INPUT_NAMES) for d in inp],
            "outputOrder": [classify_name(d["name"], OUTPUT_NAMES) for d in out],
            "inputs": inputs, "outputs": outputs, "cpu_inference": "PASS"}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--onnx", type=Path, required=True)
    p.add_argument("--size", type=int, required=True, choices=(9, 13, 19))
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--signature", type=Path, required=True)
    args = p.parse_args()
    source_model = validate_onnx(args.onnx, args.size)
    import onnx
    args.output.parent.mkdir(parents=True, exist_ok=True)
    prepared = args.output.parent / ("katago_10b_" + str(args.size) + "x" + str(args.size) + "_batch1.onnx")
    onnx.save(source_model, str(prepared))
    args.signature.parent.mkdir(parents=True, exist_ok=True)
    dirname = args.output.parent / ("onnx2tf_" + str(args.size))
    if shutil.which("onnx2tf") is None: raise RuntimeError("Install onnx2tf[tensorflow]")
    subprocess.run(["onnx2tf", "-i", str(prepared.resolve()), "-o", str(dirname),
                    "-coion"], check=True)
    candidates = list(dirname.rglob("*_float32.tflite"))
    if len(candidates) != 1:
        raise RuntimeError("Expected a single float32 TFLite model, found: %s" % candidates)
    shutil.copy2(candidates[0], args.output)
    signature = inspect_tflite(args.output, args.size)
    signature.update(output_scale_from_onnx(source_model))
    args.signature.write_text(json.dumps(signature, indent=2) + "\n", encoding="utf-8")
    print("KATAGO_LITERT_READY size=%d path=%s bytes=%d" %
          (args.size, args.output, args.output.stat().st_size), flush=True)
    print("Signatures:", args.signature, flush=True)


if __name__ == "__main__":
    main()
