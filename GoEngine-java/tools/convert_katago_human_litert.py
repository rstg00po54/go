#!/usr/bin/env python3
"""Convert KataGo's real b18c384nbt-humanv0 ONNX graph to LiteRT.

Requires official KataGo 1.18+ 'dumponnx' export and independently validates
the SGF metadata input, dynamic head sizes, output-scale multiplier and one
real CPU inference. Never relabels/reshapes the 10b graph as Human SL.

Requires: onnx, numpy, ai-edge-litert, onnx2tf[tensorflow].
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess

MODEL_NAME = "b18c384nbt-humanv0"
INPUTS = ("InputSpatial", "InputGlobal", "InputMeta", "InputMask")
OUTPUTS = ("OutputPolicyPass", "OutputPolicy", "OutputValue", "OutputScoreValue",
           "OutputOwnership")


def shape(desc):
    return [int(d.dim_value) for d in desc.type.tensor_type.shape.dim]


def validate_onnx(path, size):
    import onnx
    model = onnx.load(str(path))
    meta = {x.key: x.value for x in model.metadata_props}
    if meta.get("katago.name") != MODEL_NAME:
        raise ValueError("Not the original b18 Human SL weights: " + str(meta.get("katago.name")))
    if meta.get("katago.build.nnXLen") != str(size) or meta.get("katago.build.nnYLen") != str(size):
        raise ValueError("Wrong original Human SL ONNX board size")
    if meta.get("katago.metaEncoderVersion") != "1":
        raise ValueError("Human SL must preserve the SGF metadata encoder version 1")
    nc = int(meta.get("katago.numInputChannels", "-1"))
    ng = int(meta.get("katago.numInputGlobalChannels", "-1"))
    nm = int(meta.get("katago.numInputMetaChannels", "-1"))
    np = int(meta.get("katago.numPolicyChannels", "-1"))
    nv = int(meta.get("katago.numValueChannels", "-1"))
    ns = int(meta.get("katago.numScoreValueChannels", "-1"))
    no = int(meta.get("katago.numOwnershipChannels", "-1"))
    if (nc, ng, nm, nv, ns, no) != (22, 19, 192, 3, 6, 1) or np not in (1, 2, 4):
        raise ValueError("Unexpected Human SL feature or output channel contract: " +
                         str((nc, ng, nm, np, nv, ns, no)))
    declared_inputs = {x.name: shape(x) for x in model.graph.input}
    expected = {
        "InputSpatial": [0, 22, size, size],
        "InputGlobal": [0, 19, 1, 1],
        "InputMeta": [0, 192, 1, 1],
        "InputMask": [0, 1, size, size],
    }
    if declared_inputs != expected or tuple(x.name for x in model.graph.output) != OUTPUTS:
        raise ValueError("Human ONNX tensor signature mismatch: " + str(declared_inputs))
    applied = meta.get("katago.build.scale8Applied")
    if applied not in ("true", "false"):
        raise ValueError("Missing Human ONNX scale8Applied contract")
    multiplier = float(meta.get("katago.postProcess.outputScaleMultiplier", "nan"))
    if multiplier != (8.0 if applied == "true" else 1.0):
        raise ValueError("Human output scale mismatch")
    if meta.get("katago.build.requireExactNNLen") == "true":
        raise ValueError("Masked-size human model cannot use exact-only graph")
    for inp in model.graph.input:
        inp.type.tensor_type.shape.dim[0].Clear()
        inp.type.tensor_type.shape.dim[0].dim_value = 1
    onnx.checker.check_model(model)
    contract = {
        "modelType": "human", "size": size, "weights": MODEL_NAME,
        "metaEncoderVersion": 1, "scale8Applied": applied == "true",
        "outputScaleMultiplier": multiplier,
        "numInputChannels": nc, "numInputGlobalChannels": ng,
        "numInputMetaChannels": nm, "numPolicyChannels": np,
        "numValueChannels": nv, "numScoreValueChannels": ns,
        "numOwnershipChannels": no,
    }
    return model, contract


def normalize_name(raw, choices):
    name = raw.split(":")[0].split("/")[-1]
    for candidate in choices:
        if name == candidate or name.endswith("_" + candidate):
            return candidate
    raise ValueError("Cannot identify LiteRT model input/head " + raw)


def inspect_tflite(path, size, contract):
    import numpy as np
    from ai_edge_litert.interpreter import Interpreter
    interpreter = Interpreter(model_path=str(path), num_threads=1)
    interpreter.allocate_tensors()
    inputs = interpreter.get_input_details()
    outputs = interpreter.get_output_details()
    if len(inputs) != 4 or len(outputs) != 5:
        raise ValueError("Human LiteRT must have 4 inputs and 5 outputs")
    shape_sets = {
        "InputSpatial": ([1, 22, size, size], [1, size, size, 22]),
        "InputGlobal": ([1, 19, 1, 1], [1, 1, 1, 19], [1, 19]),
        "InputMeta": ([1, 192, 1, 1], [1, 1, 1, 192], [1, 192]),
        "InputMask": ([1, 1, size, size], [1, size, size, 1]),
    }
    output_counts = {"OutputPolicyPass": contract["numPolicyChannels"],
                     "OutputPolicy": size*size*contract["numPolicyChannels"],
                     "OutputValue": 3, "OutputScoreValue": 6,
                     "OutputOwnership": size*size}
    descriptors = {}
    in_order, out_order = [], []
    for detail in inputs:
        key = normalize_name(detail["name"], INPUTS)
        dims = list(map(int, detail["shape"]))
        if key in descriptors or dims not in shape_sets[key] or detail["dtype"] != np.float32:
            raise ValueError("Invalid Human input shape/dtype " + str((key, dims)))
        descriptors[key] = {"name": detail["name"], "shape": dims,
                            "count": int(np.prod(dims)), "index": int(detail["index"])}
        in_order.append(key)
    if set(in_order) != set(INPUTS): raise ValueError("Missing Human InputMeta")
    heads = {}
    for detail in outputs:
        key = normalize_name(detail["name"], OUTPUTS)
        dims = list(map(int, detail["shape"]))
        if key in heads or int(np.prod(dims)) != output_counts[key] or detail["dtype"] != np.float32:
            raise ValueError("Invalid Human head shape/dtype " + str((key, dims)))
        heads[key] = {"name": detail["name"], "shape": dims,
                      "count": int(np.prod(dims)), "index": int(detail["index"])}
        out_order.append(key)
    if set(out_order) != set(OUTPUTS): raise ValueError("Missing Human outputs")
    for detail in inputs:
        key = normalize_name(detail["name"], INPUTS)
        dims = list(map(int, detail["shape"]))
        values = np.zeros(dims, dtype=np.float32)
        if key == "InputMask": values.fill(1.0)
        if key == "InputSpatial":
            if dims[1] == 22: values[:, 0, :, :] = 1.0
            else: values[:, :, :, 0] = 1.0
        # Human profile metadata is generated by native KataGo at run time;
        # zero metadata here only verifies finite numerical execution.
        interpreter.set_tensor(int(detail["index"]), values)
    interpreter.invoke()
    for item in outputs:
        arr = interpreter.get_tensor(int(item["index"]))
        if not np.isfinite(arr).all(): raise ValueError("Nonfinite Human head")
    return {"format": "tflite-f32", "inputs": descriptors, "outputs": heads,
            "inputOrder": in_order, "outputOrder": out_order,
            "cpu_inference": "PASS"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--onnx", type=Path, required=True)
    parser.add_argument("--size", type=int, choices=(9, 13, 19), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--signature", type=Path, required=True)
    args = parser.parse_args()
    import onnx
    model, contract = validate_onnx(args.onnx, args.size)
    if shutil.which("onnx2tf") is None: raise RuntimeError("Install onnx2tf[tensorflow]==2.6.9")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.signature.parent.mkdir(parents=True, exist_ok=True)
    batched = args.output.parent / ("human_%dx%d_batch1.onnx" % (args.size, args.size))
    onnx.save(model, str(batched))
    outdir = args.output.parent / ("human_onnx2tf_%d" % args.size)
    subprocess.run(["onnx2tf", "-i", str(batched.resolve()),
                    "-o", str(outdir), "-coion"], check=True)
    files = list(outdir.rglob("*_float32.tflite"))
    if len(files) != 1: raise RuntimeError("Expected unique converted Human graph: " + str(files))
    shutil.copy2(files[0], args.output)
    contract.update(inspect_tflite(args.output, args.size, contract))
    args.signature.write_text(json.dumps(contract, indent=2) + "\n", encoding="utf-8")
    print("KATAGO_HUMAN_LITERT_READY board=%d file=%s bytes=%d meta=192 policyChannels=%d" %
          (args.size, args.output, args.output.stat().st_size,
           contract["numPolicyChannels"]), flush=True)


if __name__ == "__main__":
    main()
