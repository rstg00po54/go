#!/usr/bin/env python3
"""Experimental original-weight KataGo b18 ONNX -> RK3588 RKNN compiler.

Compile on Linux x86_64 with rknn-toolkit2. This does NOT establish NPU
numerical correctness or integrate the result into KataGo's C++ backend.
"""
import argparse
import hashlib
import json
from pathlib import Path

INPUTS = ("InputSpatial", "InputGlobal", "InputMeta", "InputMask")
OUTPUTS = ("OutputPolicyPass", "OutputPolicy", "OutputValue",
           "OutputScoreValue", "OutputOwnership")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--onnx", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--size", type=int, required=True, choices=(9, 11, 13, 15, 19))
    args = p.parse_args()
    import onnx
    from rknn.api import RKNN

    graph = onnx.load(str(args.onnx), load_external_data=True)
    meta = {x.key: x.value for x in graph.metadata_props}
    if meta.get("katago.name") != "b18c384nbt-humanv0":
        raise RuntimeError("Refusing to compile graph not derived from the original Human b18")
    if meta.get("katago.metaEncoderVersion") != "1":
        raise RuntimeError("Missing Human rank/metadata encoder contract")
    if meta.get("katago.build.nnXLen") != str(args.size) or meta.get("katago.build.nnYLen") != str(args.size):
        raise RuntimeError("Board size mismatch")
    if [v.name for v in graph.graph.input] != list(INPUTS):
        raise RuntimeError("Human b18 must have four ordered inputs including InputMeta")
    if [v.name for v in graph.graph.output] != list(OUTPUTS):
        raise RuntimeError("Human b18 must have all five named output heads")
    dims = [[1, 22, args.size, args.size], [1, 19, 1, 1],
            [1, 192, 1, 1], [1, 1, args.size, args.size]]
    for tensor, expected in zip(graph.graph.input, dims):
        actual = [int(d.dim_value) for d in tensor.type.tensor_type.shape.dim]
        if len(actual) != 4 or actual[1:] != expected[1:] or actual[0] not in (0, 1):
            raise RuntimeError(f"Input {tensor.name} shape mismatch: {actual}")
        tensor.type.tensor_type.shape.dim[0].Clear()
        tensor.type.tensor_type.shape.dim[0].dim_value = 1
    onnx.checker.check_model(graph)
    original_opset = next((x.version for x in graph.opset_import if x.domain in ("", "ai.onnx")), None)
    if original_opset is None:
        raise RuntimeError("Missing default ONNX opset")
    if original_opset > 19:
        print(f"Converting ONNX opset {original_opset} to 19 using ONNX version_converter", flush=True)
        graph = onnx.version_converter.convert_version(graph, 19)
        onnx.checker.check_model(graph)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    static_path = args.output.with_suffix(".static.onnx")
    onnx.save(graph, str(static_path))
    rknn = RKNN(verbose=True)
    try:
        code = rknn.config(target_platform="rk3588", optimization_level=3)
        if code != 0:
            raise RuntimeError(f"RKNN config failed: {code}")
        code = rknn.load_onnx(model=str(static_path))
        if code != 0:
            raise RuntimeError(f"RKNN ONNX load failed: {code}")
        code = rknn.build(do_quantization=False)
        if code != 0:
            raise RuntimeError(f"RKNN build failed: {code}")
        code = rknn.export_rknn(str(args.output))
        if code != 0:
            raise RuntimeError(f"RKNN export failed: {code}")
    finally:
        rknn.release()
    manifest = {
        "model": "b18c384nbt-humanv0", "board": args.size,
        "source": str(args.onnx), "target": "rk3588",
        "quantized": False, "inputs": dict(zip(INPUTS, dims)),
        "outputs": OUTPUTS, "runtimeVerified": False,
        "fiveHeadParityVerified": False,
        "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
    }
    args.output.with_suffix(".json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("EXPERIMENTAL_RKNN_COMPILE_PASS", json.dumps(manifest))


if __name__ == "__main__":
    main()
