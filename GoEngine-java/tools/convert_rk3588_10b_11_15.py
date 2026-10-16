#!/usr/bin/env python3
"""Convert original KataGo 10b fixed 11x11/15x15 dumponnx to RK3588 RKNN.

Always export a fresh ONNX from g170 original weights for each board size.
Preserves the five policy/value/score/ownership heads. Compiled files must
subsequently pass real RK3588 runtime validation; build does not prove parity.
"""
import argparse
from pathlib import Path
import onnx

def prepare(src, dst, n):
    m=onnx.load(str(src))
    props={x.key:x.value for x in m.metadata_props}
    if props.get("katago.name") != "g170-b10c128-s1141046784-d204142634":
        raise ValueError("Refusing non-original g170 10b weights")
    if props.get("katago.build.nnXLen") != str(n) or props.get("katago.build.nnYLen") != str(n):
        raise ValueError("Wrong board metadata; re-export ONNX using dumponnx")
    inputs={"InputSpatial":[1,22,n,n],"InputGlobal":[1,19,1,1],"InputMask":[1,1,n,n]}
    outputs=["OutputPolicyPass","OutputPolicy","OutputValue","OutputScoreValue","OutputOwnership"]
    if [v.name for v in m.graph.input] != list(inputs) or [v.name for v in m.graph.output] != outputs:
        raise ValueError("10b inputs or five outputs differ from existing RKNN contract")
    for v in m.graph.input:
        ds=v.type.tensor_type.shape.dim
        if len(ds)!=4: raise ValueError("Invalid dimension count")
        for d,num in zip(ds,inputs[v.name]):
            d.Clear()
            d.dim_value=num
    source=next((x.version for x in m.opset_import if x.domain in ("","ai.onnx")),None)
    if source is None: raise ValueError("Missing ONNX opset")
    if source > 19:
        m=onnx.version_converter.convert_version(m,19)
    m=onnx.shape_inference.infer_shapes(m)
    onnx.checker.check_model(m)
    dst.parent.mkdir(parents=True,exist_ok=True)
    onnx.save(m,str(dst))
    return inputs,outputs

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--size",type=int,choices=(11,15),required=True)
    p.add_argument("--onnx",type=Path,required=True)
    p.add_argument("--prepared",type=Path,required=True)
    p.add_argument("--output",type=Path,required=True)
    a=p.parse_args()
    inputs,outputs=prepare(a.onnx,a.prepared,a.size)
    from rknn.api import RKNN
    r=RKNN(verbose=True)
    try:
        if r.config(target_platform="rk3588",optimization_level=3)!=0: raise RuntimeError("rknn config failed")
        if r.load_onnx(model=str(a.prepared))!=0: raise RuntimeError("rknn load_onnx failed")
        if r.build(do_quantization=False)!=0: raise RuntimeError("rknn build failed")
        a.output.parent.mkdir(parents=True,exist_ok=True)
        if r.export_rknn(str(a.output))!=0: raise RuntimeError("rknn export failed")
    finally: r.release()
    import json,hashlib
    if a.output.stat().st_size < 1000000: raise RuntimeError("10b RKNN too small")
    meta={"model":"g170-b10c128-s1141046784-d204142634","board":a.size,
          "target":"rk3588","quantized":False,"inputs":inputs,"outputs":outputs,
          "sha256":hashlib.sha256(a.output.read_bytes()).hexdigest(),
          "runtimeVerified":False,"fiveHeadParityVerified":False}
    a.output.with_suffix(".rknn.json").write_text(json.dumps(meta,indent=2)+"\n")
    print("KATAGO_10B_RKNN_BUILD_OK",json.dumps(meta),flush=True)

if __name__=="__main__": main()
