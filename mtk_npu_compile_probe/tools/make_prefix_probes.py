#!/usr/bin/env python3
"""Generate experimental TFLite prefix graphs for locating a failing Neuron subgraph.

Usage:
 pip install tflite flatbuffers
 python3 tools/make_prefix_probes.py --source /path/to/katago_human_9x9.tflite --counts 100,300,600 --out segments

IMPORTANT: outputs are diagnostic only, NOT replacement KataGo models. Always
CPU-compile each generated file before treating NPU failure as meaningful.
Operator-prefix results need not be monotonic: compilers can prune unused ops.
"""
from __future__ import annotations

import argparse
import gc
import json
from pathlib import Path
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--out", default=Path("segments"), type=Path)
    parser.add_argument("--counts", required=True, help="Comma-separated prefix lengths")
    args = parser.parse_args()
    try:
        import flatbuffers
        import tflite
    except ImportError as ex:
        parser.error("Install tflite flatbuffers: " + str(ex))

    data = args.source.read_bytes()
    model_cls = getattr(tflite, "Model", None)
    get_root = getattr(model_cls, "GetRootAsModel", None)
    obj_cls = getattr(tflite, "ModelT", None)
    if get_root is None or obj_cls is None or not hasattr(obj_cls, "InitFromObj"):
        parser.error("Your tflite Python package lacks ModelT; upgrade it")
    editable = obj_cls.InitFromObj(get_root(data, 0))
    if len(editable.subgraphs) != 1:
        parser.error("This tool supports a single-subgraph TFLite model only")
    graph = editable.subgraphs[0]
    original_ops, original_outputs = graph.operators, graph.outputs
    total = len(original_ops)
    counts = sorted({int(x.strip()) for x in args.counts.split(",") if x.strip()})
    if not counts or any(x < 1 or x > total for x in counts):
        parser.error("All counts must be between 1 and " + str(total))

    # Original signatures refer to graph outputs; these temporary diagnostic
    # graphs have different output tensors.
    editable.signatureDefs = []
    args.out.mkdir(parents=True, exist_ok=True)
    index = []
    for count in counts:
        outputs = [int(i) for i in original_ops[count - 1].outputs if int(i) >= 0]
        if not outputs:
            print("SKIP", count, "operator has no output")
            continue
        output = outputs[0]
        graph.operators = original_ops[:count]
        graph.outputs = [output]
        # Keep constant buffers, original input tensors and tensor table.
        # Generated models may contain unused tensors and may not be valid;
        # CPU validation is essential before interpreting NPU errors.
        builder = flatbuffers.Builder(1024)
        builder.Finish(editable.Pack(builder), file_identifier=b"TFL3")
        path = args.out / ("cut_%04d.tflite" % count)
        path.write_bytes(builder.Output())
        tensor = graph.tensors[output]
        item = {"prefixOps": count, "totalOps": total,
                "outputTensorIndex": output, "outputTensorName": str(tensor.name),
                "outputTensorShape": list(tensor.shape or []),
                "bytes": path.stat().st_size, "model": str(path)}
        index.append(item)
        print(json.dumps(item, ensure_ascii=False))
        del builder
        gc.collect()
    graph.operators, graph.outputs = original_ops, original_outputs
    manifest = args.out / "prefix_index.json"
    manifest.write_text(json.dumps(index, indent=2, ensure_ascii=False), encoding="utf-8")
    print("Generated:", manifest)
    print("NOTE: these are NOT valid production replacements for Human b18.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, TypeError) as ex:
        sys.exit("ERROR: " + str(ex))
