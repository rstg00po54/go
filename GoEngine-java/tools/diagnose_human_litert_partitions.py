#!/usr/bin/env python3
"""Report Human SL original operator types vs MediaTek AOT remaining CPU ops.

This is an *offline* FlatBuffer structural comparison. It cannot establish
whether Vivo's Neuron runtime accepts compiled bytecode or executes on NPU.
"""
import argparse
from collections import Counter
import json
from pathlib import Path


def enum_names():
    import tflite
    enum = tflite.BuiltinOperator
    return {v: k for k, v in vars(enum).items()
            if k.isupper() and isinstance(v, int)}


def summarize(path):
    import tflite
    data = path.read_bytes()
    model = tflite.Model.GetRootAsModel(data, 0)
    enums = enum_names()
    records = []
    for si in range(model.SubgraphsLength()):
        sg = model.Subgraphs(si)
        count = Counter()
        samples = {}
        for i in range(sg.OperatorsLength()):
            op = sg.Operators(i)
            opcode = model.OperatorCodes(op.OpcodeIndex())
            number = int(opcode.BuiltinCode())
            if number == 127 and opcode.DeprecatedBuiltinCode() not in (0, 127):
                number = int(opcode.DeprecatedBuiltinCode())
            name = enums.get(number, "BUILTIN_%d" % number)
            if name == "CUSTOM" and opcode.CustomCode() is not None:
                name += ":" + opcode.CustomCode().decode("utf-8", "replace")
            count[name] += 1
            if name not in samples:
                inputs = [int(op.Inputs(k)) for k in range(op.InputsLength())]
                outputs = [int(op.Outputs(k)) for k in range(op.OutputsLength())]
                samples[name] = {"opIndex": i, "inputs": inputs[:8],
                                 "outputs": outputs[:8]}
        records.append({"index": si, "totalOps": sum(count.values()),
                        "ops": dict(sorted(count.items())),
                        "exampleOps": samples,
                        "inputs": sg.InputsLength(), "outputs": sg.OutputsLength(),
                        "tensors": sg.TensorsLength()})
    return {"name": path.name, "sizeBytes": len(data), "subgraphs": records}


def activation_chain_report(path):
    """Look for the exact EXP->ADD->LOG->TANH->MUL pattern, not guessed op names."""
    import tflite
    model = tflite.Model.GetRootAsModel(path.read_bytes(), 0)
    sg = model.Subgraphs(0)
    names = enum_names()
    ops = []
    consumers = {}
    for i in range(sg.OperatorsLength()):
        op = sg.Operators(i)
        opcode = model.OperatorCodes(op.OpcodeIndex())
        name = names.get(int(opcode.BuiltinCode()), "UNKNOWN")
        inputs = [int(op.Inputs(k)) for k in range(op.InputsLength())]
        outputs = [int(op.Outputs(k)) for k in range(op.OutputsLength())]
        ops.append({"index": i, "type": name, "inputs": inputs, "outputs": outputs})
        for tid in inputs:
            consumers.setdefault(tid, []).append(i)

    wanted = ("EXP", "ADD", "LOG", "TANH", "MUL")
    matches = []
    for first in ops:
        if first["type"] != wanted[0]:
            continue
        chains = [(first["index"], first["outputs"][0])] if first["outputs"] else []
        for next_type in wanted[1:]:
            chained = []
            for indices, tensor in chains:
                for dest in consumers.get(tensor, []):
                    if ops[dest]["type"] != next_type or not ops[dest]["outputs"]:
                        continue
                    chained.append((indices + (dest,) if isinstance(indices, tuple)
                                    else (indices, dest), ops[dest]["outputs"][0]))
            chains = chained
        for chain, tensor in chains:
            matches.append(list(chain))
    counts = Counter(op["type"] for op in ops)
    result = {
        "pattern": "EXP->ADD->LOG->TANH->MUL",
        "matches": len(matches), "firstExamples": matches[:12],
        "expCount": counts.get("EXP", 0),
        "logCount": counts.get("LOG", 0),
        "tanhCount": counts.get("TANH", 0),
        "completeExpCoverage": len(matches) == counts.get("EXP", 0),
    }
    print("HUMAN_PARTITION_ACTIVATIONS " + json.dumps(result), flush=True)
    return result


def report(original, compiled):
    source = summarize(original)
    aot = summarize(compiled)
    source_counts = Counter()
    for sg in source["subgraphs"]:
        source_counts.update(sg["ops"])
    # Only subgraph 0 is the entry point of the AOT graph. Other subgraphs
    # may be internal/outlines; do not misclassify those as CPU fallback.
    top = aot["subgraphs"][0]
    entry_ops = top["ops"]
    dispatch = {name: count for name, count in entry_ops.items()
                if name.startswith("CUSTOM:") and "dispatch" in name.lower()}
    residual = {name: count for name, count in entry_ops.items()
                if not (name.startswith("CUSTOM:") and "dispatch" in name.lower())}
    other_custom = {name: count for name, count in entry_ops.items()
                    if name.startswith("CUSTOM:") and "dispatch" not in name.lower()}
    result = {"original": source, "compiled": aot,
              "originalOpCount": sum(source_counts.values()),
              "originalOpTypes": dict(sorted(source_counts.items())),
              "aotEntryOps": entry_ops,
              "aotDispatchOps": dispatch,
              "aotNonDispatchOps": residual,
              "aotNonDispatchCustomOps": other_custom,
              "aotEntryOpCount": top["totalOps"],
              "aotOffloadOpCount": None,
              "analysisScope": ("Static TFLite/AOT entry graph only. Custom "
                                "dispatch op does not prove physical NPU "
                                "execution or on-device compilation support.")}
    print("HUMAN_PARTITION_ORIGINAL total=%d types=%d" %
          (result["originalOpCount"], len(source_counts)), flush=True)
    for name, num in sorted(source_counts.items(), key=lambda kv: (-kv[1], kv[0])):
        print("HUMAN_PARTITION_OP %s %d" % (name, num), flush=True)
    print("HUMAN_PARTITION_AOT subgraphs=%d entryOps=%d" %
          (len(aot["subgraphs"]), result["aotEntryOpCount"]), flush=True)
    print("HUMAN_PARTITION_DISPATCH %s" % json.dumps(dispatch, sort_keys=True),
          flush=True)
    print("HUMAN_PARTITION_CPU_CANDIDATES %s" %
          json.dumps(residual, sort_keys=True), flush=True)
    print("HUMAN_PARTITION_SCOPE %s" % result["analysisScope"], flush=True)
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--original", type=Path, required=True)
    p.add_argument("--aot", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--expect-original-ops", type=int, default=1278)
    args = p.parse_args()
    data = report(args.original, args.aot)
    data["activationChainAnalysis"] = activation_chain_report(args.original)
    if data["originalOpCount"] != args.expect_original_ops:
        raise ValueError("Human b18 baseline operator count changed: %d" %
                         data["originalOpCount"])
    if not data["aotDispatchOps"]:
        print("HUMAN_PARTITION_WARNING No dispatch op in AOT entry graph",
              flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n",
                           encoding="utf-8")


if __name__ == "__main__":
    main()
