#!/usr/bin/env python3
"""Synthetic regression for exact Human b18 Mish algebraic ONNX lowering."""
import importlib.util
from pathlib import Path
import unittest

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

ROOT = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "human_mish_optimizer", ROOT / "optimize_human_mish_onnx.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def make_model(n=3, shared=False, wrong_constant=False):
    x = helper.make_tensor_value_info("input", TensorProto.FLOAT, [1, 1, 2, 2])
    y = helper.make_tensor_value_info("out", TensorProto.FLOAT, [1, 1, 2, 2])
    nodes = []
    const = numpy_helper.from_array(
        np.array(2. if wrong_constant else 1., dtype=np.float32), name="const_one")
    weight = numpy_helper.from_array(np.array(0.25, dtype=np.float32),
                                     name="test_weight")
    inp = "input"
    for i in range(n):
        names = [f"exp{i}", f"add{i}", f"log{i}", f"tanh{i}", f"prod{i}"]
        nodes.extend([
            helper.make_node("Exp", [inp], [names[0]]),
            helper.make_node("Add", [names[0], "const_one"], [names[1]]),
            helper.make_node("Log", [names[1]], [names[2]]),
            helper.make_node("Tanh", [names[2]], [names[3]]),
            helper.make_node("Mul", [names[3], inp], [names[4]]),
        ])
        inp = names[4]
        if shared and i == 0:
            nodes.append(helper.make_node("Add", [names[0], "test_weight"],
                                          ["unused_shared"]))
    nodes.append(helper.make_node("Identity", [inp], ["out"]))
    graph = helper.make_graph(nodes, "test_b18_mish", [x], [y],
                              initializer=[const, weight])
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
    model.ir_version = 9
    onnx.checker.check_model(model)
    return model


class MishOptimizerTests(unittest.TestCase):
    def test_exact_mish_numeric_and_preserved_weights(self):
        model = make_model()
        old_init = module.initializer_hashes(model.graph)
        original = onnx.ModelProto()
        original.CopyFrom(model)
        result = module.optimize_model(model, 3)
        self.assertEqual(result["rewrittenChains"], 3)
        self.assertTrue(result["allOriginalInitializersBitIdentical"])
        for name, hash_value in old_init.items():
            self.assertEqual(module.initializer_hashes(model.graph)[name], hash_value)
        self.assertFalse(any(n.op_type in ("Exp", "Log", "Tanh")
                             for n in model.graph.node))
        # Three chained Mish operations, including large negative values.
        import onnxruntime as ort
        options = ort.SessionOptions()
        options.intra_op_num_threads = 1
        a = ort.InferenceSession(original.SerializeToString(), options,
                                 providers=["CPUExecutionProvider"])
        b = ort.InferenceSession(model.SerializeToString(), options,
                                 providers=["CPUExecutionProvider"])
        for values in (np.array([-20., -5., 0., 10.], dtype=np.float32),
                       np.array([-1.5, 0.5, 2.3, 15.], dtype=np.float32)):
            source = a.run(None, {"input": values.reshape((1,1,2,2))})[0]
            revised = b.run(None, {"input": values.reshape((1,1,2,2))})[0]
            np.testing.assert_allclose(source, revised, rtol=1.e-3, atol=1.e-4)

    def test_standard_onnx_mish_operator(self):
        x = helper.make_tensor_value_info("input", TensorProto.FLOAT, [1,2])
        y = helper.make_tensor_value_info("out", TensorProto.FLOAT, [1,2])
        model = helper.make_model(helper.make_graph([
            helper.make_node("Mish", ["input"], ["out"])], "direct_mish", [x], [y]),
            opset_imports=[helper.make_opsetid("", 20)])
        model.ir_version = 9
        result = module.optimize_model(model, 1)
        self.assertEqual(result["matchedSourceForms"], {"Mish": 1})
        self.assertEqual(result["rewrittenChains"], 1)
        self.assertNotIn("Mish", [n.op_type for n in model.graph.node])

    def test_standard_softplus_tanh_mish_operator(self):
        x = helper.make_tensor_value_info("input", TensorProto.FLOAT, [1,2])
        y = helper.make_tensor_value_info("out", TensorProto.FLOAT, [1,2])
        model = helper.make_model(helper.make_graph([
            helper.make_node("Softplus", ["input"], ["sp"]),
            helper.make_node("Tanh", ["sp"], ["tanh"]),
            helper.make_node("Mul", ["input", "tanh"], ["out"])
        ], "softplus_mish", [x], [y]),
            opset_imports=[helper.make_opsetid("", 17)])
        model.ir_version = 9
        result = module.optimize_model(model, 1)
        self.assertEqual(result["matchedSourceForms"], {"Softplus/Tanh/Mul":1})

    def test_gated_softplus_with_different_multiplier_remains_exact(self):
        x=helper.make_tensor_value_info("input", TensorProto.FLOAT, [1,2])
        y=helper.make_tensor_value_info("out", TensorProto.FLOAT, [1,2])
        graph=helper.make_graph([
            helper.make_node("Mul", ["input", "gate"], ["z"]),
            helper.make_node("Softplus", ["z"], ["sp"]),
            helper.make_node("Tanh", ["sp"], ["tanh"]),
            helper.make_node("Mul", ["input", "tanh"], ["out"])
        ], "b18_gated_mish", [x], [y], initializer=[
            numpy_helper.from_array(np.array(0.6,dtype=np.float32),name="gate")])
        model=helper.make_model(graph,opset_imports=[helper.make_opsetid("",17)])
        model.ir_version=9
        original=onnx.ModelProto(); original.CopyFrom(model)
        data=module.optimize_model(model,1)
        self.assertEqual(data["gatedActivations"],1)
        import onnxruntime as ort
        opts=ort.SessionOptions(); opts.intra_op_num_threads=1
        a=ort.InferenceSession(original.SerializeToString(),opts,
                               providers=["CPUExecutionProvider"])
        b=ort.InferenceSession(model.SerializeToString(),opts,
                               providers=["CPUExecutionProvider"])
        for vals in ([-19.,-1.],[-.01, 10.], [2.,20.]):
            z=np.array(vals,dtype=np.float32).reshape(1,2)
            np.testing.assert_allclose(a.run(None,{"input":z})[0],
                                       b.run(None,{"input":z})[0],
                                       rtol=1e-3,atol=1e-4)

    def test_reject_changed_mish_identity(self):
        with self.assertRaisesRegex(ValueError, "Expected 1 safe Mish"):
            module.optimize_model(make_model(1, wrong_constant=True), 1)

    def test_reject_shared_exp_internal_tensor(self):
        with self.assertRaisesRegex(ValueError, "Expected 1 safe Mish"):
            module.optimize_model(make_model(1, shared=True), 1)

    def test_detect_missing_chains(self):
        with self.assertRaisesRegex(ValueError, "Expected 120 safe Mish"):
            module.optimize_model(make_model(1), 120)


if __name__ == "__main__":
    unittest.main()
