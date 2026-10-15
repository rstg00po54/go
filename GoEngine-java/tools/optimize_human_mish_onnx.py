#!/usr/bin/env python3
"""Algebraically rewrite all b18 Human SL Mish chains without altering weights.

Original:  y = x * tanh(log(1 + exp(x)))
Equivalent: s = sigmoid(x); t = 1 - s; y = x * (1 - t*t)/(1 + t*t)

This rewrite deliberately does NOT approximate Mish, replace activations with
ReLU/SiLU, retrain, modify human-rank metadata, change topology, or alter
initializer data. On-device MT6993 NPU compatibility is NOT guaranteed.

The rewrite is fail-closed: it verifies full EXP->ADD(+1)->LOG->TANH->MUL
chains with identical x on the multiplication branch and no other consumers
of the four intermediate tensors, otherwise it leaves them unchanged.
"""
import argparse
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path


def initializer_hashes(graph):
    import onnx
    return {item.name: hashlib.sha256(item.SerializeToString()).hexdigest()
            for item in graph.initializer}


def constant_scalar_one(producer, initializer_by_name, graph):
    import numpy as np
    from onnx import numpy_helper
    if producer in initializer_by_name:
        val = numpy_helper.to_array(initializer_by_name[producer])
        return val.size == 1 and bool(np.isfinite(val).all()) and float(val.flat[0]) == 1.
    for node in graph.node:
        if node.op_type == "Constant" and producer in node.output:
            for att in node.attribute:
                if att.name == "value":
                    val = numpy_helper.to_array(att.t)
                    return val.size == 1 and bool(np.isfinite(val).all()) and float(val.flat[0]) == 1.
                if att.name == "value_float":
                    return float(att.f) == 1.
    return False


def optimize_model(model, expected=120):
    from onnx import TensorProto, helper, numpy_helper
    import numpy as np
    source_hash = initializer_hashes(model.graph)
    nodes = list(model.graph.node)
    producers = {}
    consumers = defaultdict(list)
    for node in nodes:
        for output in node.output:
            if output:
                producers[output] = node
        for inp in node.input:
            if inp:
                consumers[inp].append(node)
    consts = {v.name: v for v in model.graph.initializer}
    output_names = {v.name for v in model.graph.output}

    matches = []
    claimed = set()
    rejected = Counter()

    def record(chain, x, output, op_form):
        if not x or not output or any(id(n) in claimed for n in chain):
            rejected["missing_input_or_duplicate"] += 1
            return False
        if any(len(consumers[n.output[0]]) != 1 or n.output[0] in output_names
               for n in chain[:-1]):
            rejected["intermediate_shared"] += 1
            return False
        matches.append({"nodes": chain, "x": x, "out": output,
                        "originalOpForm": op_form})
        for node in chain:
            claimed.add(id(node))
        return True

    # ONNX's standard Mish operator may be directly exported by KataGo.
    # onnx2tf later lowers it to EXP/ADD/LOG/TANH/MUL. All variants use
    # the exact same algebra, and all original weight tensors stay intact.
    for node in nodes:
        if node.op_type == "Mish" and len(node.input) == 1 and len(node.output) == 1:
            record((node,), node.input[0], node.output[0], "Mish")

    for multiply in nodes:
        if multiply.op_type != "Mul" or len(multiply.input) != 2:
            continue
        for side in (0, 1):
            tanh = producers.get(multiply.input[side])
            if tanh is None or tanh.op_type != "Tanh" or len(tanh.input) != 1:
                continue
            parent = producers.get(tanh.input[0])
            if parent is None:
                continue
            if parent.op_type == "Softplus" and len(parent.input) == 1:
                x = parent.input[0]
                chain = (parent, tanh, multiply)
                op_form = "Softplus/Tanh/Mul"
            elif parent.op_type == "Log" and len(parent.input) == 1:
                add = producers.get(parent.input[0])
                if add is None or add.op_type != "Add" or len(add.input) != 2:
                    continue
                exp = next((producers.get(name) for name in add.input
                            if producers.get(name) is not None
                            and producers[name].op_type == "Exp"), None)
                if exp is None or len(exp.input) != 1 or len(exp.output) != 1:
                    continue
                x = exp.input[0]
                one = next((name for name in add.input if name != exp.output[0]), None)
                if one is None or not constant_scalar_one(one, consts, model.graph):
                    rejected["not_plus_one"] += 1
                    continue
                chain = (exp, add, parent, tanh, multiply)
                op_form = "Exp/Add/Log/Tanh/Mul"
            else:
                continue
            if multiply.input[1-side] != x:
                rejected["not_same_x"] += 1
                continue
            if len(multiply.output) != 1:
                rejected["malformed_output"] += 1
                continue
            if record(chain, x, multiply.output[0], op_form):
                break

    if len(matches) != expected:
        raise ValueError("Expected %d safe Mish chains but found %d. Rejected=%s; "
                         "model left unchanged. SourceOpTypes=%s" %
                         (expected, len(matches), dict(rejected),
                          dict(Counter(n.op_type for n in nodes))))

    all_value_names = (set(producers) | set(consts) |
                       {x.name for x in model.graph.input})
    replacement = {}
    remove = set()
    for i, item in enumerate(matches):
        first = item["nodes"][0]
        remove.update(id(n) for n in item["nodes"])
        prefix = "__b18_mish_equiv_%03d" % i
        names = {suffix: prefix + "_" + suffix
                 for suffix in ("sigmoid", "one_minus", "square",
                                "numerator", "denominator", "ratio")}
        if any(value in all_value_names for value in names.values()):
            raise ValueError("Generated Mish name collision")
        all_value_names.update(names.values())
        # A scalar FLOAT initializer broadcasts to arbitrary NCHW activations.
        # Use one shared 1.0 constant. It is *not* a replacement for weights.
        replacement[id(first)] = (item, names)

    one_name = "__b18_mish_equiv_one"
    if one_name in all_value_names:
        raise ValueError("Generated Mish one constant name collision")
    model.graph.initializer.append(
        numpy_helper.from_array(np.array(1.0, dtype=np.float32), name=one_name))
    new_nodes = []
    for node in nodes:
        if id(node) in replacement:
            item, names = replacement[id(node)]
            prefix = next(k for k in (names["sigmoid"],) if k)
            new_nodes.extend([
                helper.make_node("Sigmoid", [item["x"]], [names["sigmoid"]],
                                 name=prefix+"_op"),
                helper.make_node("Sub", [one_name, names["sigmoid"]], [names["one_minus"]]),
                helper.make_node("Mul", [names["one_minus"], names["one_minus"]],
                                 [names["square"]]),
                helper.make_node("Sub", [one_name, names["square"]],
                                 [names["numerator"]]),
                helper.make_node("Add", [one_name, names["square"]],
                                 [names["denominator"]]),
                helper.make_node("Div", [names["numerator"], names["denominator"]],
                                 [names["ratio"]]),
                helper.make_node("Mul", [item["x"], names["ratio"]], [item["out"]],
                                 name=names["sigmoid"]+"_mish_output_op"),
            ])
        elif id(node) not in remove:
            new_nodes.append(node)

    model.graph.ClearField("node")
    model.graph.node.extend(new_nodes)
    from onnx import checker
    checker.check_model(model)
    after_hash = initializer_hashes(model.graph)
    if any(after_hash[name] != value for name, value in source_hash.items()):
        raise ValueError("Original b18 weight tensor unexpectedly changed")
    return {
        "model": "b18c384nbt-humanv0",
        "rewrite": "exact_algebraic_mish_sigmoid_rational",
        "expectedChains": expected, "rewrittenChains": len(matches),
        "originalInitializerCount": len(source_hash),
        "allOriginalInitializersBitIdentical": True,
        "opsBefore": len(nodes), "opsAfter": len(new_nodes),
        "opTypesBefore": dict(Counter(n.op_type for n in nodes)),
        "opTypesAfter": dict(Counter(n.op_type for n in new_nodes)),
        "rejected": dict(rejected),
        "matchedSourceForms": dict(Counter(m["originalOpForm"] for m in matches)),
    }


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--report", type=Path, required=True)
    p.add_argument("--expected", type=int, default=120)
    args = p.parse_args()
    import onnx
    source = onnx.load(str(args.input))
    if args.output.resolve() == args.input.resolve():
        raise ValueError("Never overwrite original b18 ONNX")
    model = onnx.ModelProto()
    model.CopyFrom(source)
    before = initializer_hashes(source.graph)
    result = optimize_model(model, args.expected)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, str(args.output))
    # Verify on-disk round trip, all 4 input / 5 output contracts, original weights.
    again = onnx.load(str(args.output))
    onnx.checker.check_model(again)
    if before.items() - initializer_hashes(again.graph).items():
        raise RuntimeError("Saved model has different original weights")
    if [x.name for x in source.graph.input] != [x.name for x in again.graph.input] or (
            [x.name for x in source.graph.output] !=
            [x.name for x in again.graph.output]):
        raise RuntimeError("Human SL input/output identity changed")
    result["originalSha256"] = hashlib.sha256(args.input.read_bytes()).hexdigest()
    result["rewrittenSha256"] = hashlib.sha256(args.output.read_bytes()).hexdigest()
    args.report.write_text(json.dumps(result, indent=2, sort_keys=True)+"\n",
                           encoding="utf-8")
    print("HUMAN_MISH_REWRITE_PASS " + json.dumps(result, sort_keys=True), flush=True)


if __name__ == "__main__":
    main()
