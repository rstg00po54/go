#!/usr/bin/env python3
"""Compare small Human SL LiteRT graphs with a masked 19x19 graph on Linux CPU.

No APK change. This checks numerical compatibility, NOT MediaTek NPU delegation.
Requires ai-edge-litert and numpy (same runtime used by convert_katago_human_litert.py).
"""
import argparse
import json
import time
from pathlib import Path
import numpy as np
from ai_edge_litert.interpreter import Interpreter

INPUTS=("InputSpatial","InputGlobal","InputMeta","InputMask")
OUTPUTS=("OutputPolicyPass","OutputPolicy","OutputValue","OutputScoreValue","OutputOwnership")

def key(name, choices):
    hits=[x for x in choices if x.lower() in name.lower()]\n    if len(hits)>1:\n        longest=max(map(len,hits))\n        hits=[x for x in hits if len(x)==longest]
    if len(hits)!=1:
        raise ValueError("Cannot uniquely identify tensor "+repr(name)+" among "+str(choices))
    return hits[0]

def load(path):
    it=Interpreter(model_path=str(path),num_threads=1)
    it.allocate_tensors()
    ins={key(t["name"],INPUTS):t for t in it.get_input_details()}
    outs={key(t["name"],OUTPUTS):t for t in it.get_output_details()}
    if set(ins)!=set(INPUTS) or set(outs)!=set(OUTPUTS):
        raise ValueError("Unexpected model IO")
    return it,ins,outs

def spatial_array(shape,values,side):
    out=np.zeros(shape,np.float32)
    if len(shape)!=4:raise ValueError("Unexpected spatial rank")
    if shape[1]==22:
        out[0,:,:side,:side]=values.transpose(2,0,1)
    elif shape[-1]==22:
        out[0,:side,:side,:]=values
    else:raise ValueError("Unrecognized spatial layout "+str(shape))
    return out

def mask_array(shape,side):
    out=np.zeros(shape,np.float32)
    if shape[1]==1:out[0,0,:side,:side]=1
    elif shape[-1]==1:out[0,:side,:side,0]=1
    else:raise ValueError("Unrecognized mask layout "+str(shape))
    return out

def patch_input(detail,name,board,size,meta):
    shape=tuple(map(int,detail["shape"]))
    if name=="InputSpatial":return spatial_array(shape,board,size)
    if name=="InputMask":return mask_array(shape,size)
    if name=="InputMeta":return np.asarray(meta,np.float32).reshape(shape)
    if name=="InputGlobal":return np.zeros(shape,np.float32)
    raise ValueError(name)

def run(it,ins,outs,board,size,meta):
    for name,t in ins.items():
        it.set_tensor(int(t["index"]),patch_input(t,name,board,size,meta))
    begin=time.perf_counter()
    it.invoke()
    ms=(time.perf_counter()-begin)*1000
    result={name:it.get_tensor(int(t["index"])).copy() for name,t in outs.items()}
    for name,a in result.items():
        if not np.isfinite(a).all():raise ValueError("Nonfinite output "+name)
    return result,ms

def crop(arr,name,actual_size,model_size,channels):
    if name not in ("OutputPolicy","OutputOwnership"):
        return arr.flatten()
    shape=arr.shape
    if name=="OutputOwnership":channels=1
    if len(shape)==4 and shape[1]==channels and shape[2]==model_size:
        return arr[0,:,:actual_size,:actual_size].transpose(1,2,0).flatten()
    if len(shape)==4 and shape[-1]==channels and shape[1]==model_size:
        return arr[0,:actual_size,:actual_size,:].flatten()
    if arr.size==model_size*model_size*channels:
        raise ValueError("Ambiguous output layout "+repr(shape))
    raise ValueError("Unexpected output "+name+" shape "+repr(shape))

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--small",required=True,type=Path)
    p.add_argument("--large",required=True,type=Path)
    p.add_argument("--size",type=int,default=9,choices=(9,11,13,15))
    p.add_argument("--samples",type=int,default=3)
    p.add_argument("--output",type=Path)
    args=p.parse_args()
    s,si,so=load(args.small)
    l,li,lo=load(args.large)
    rng=np.random.default_rng(20261015)
    results=[]
    for i in range(args.samples):
        board=np.zeros((args.size,args.size,22),np.float32)
        board[:,:,0]=1
        if i:
            # Synthetic but identical feature vectors fed to both graphs.
            board[:,:,1:]=rng.uniform(-0.3,0.3,(args.size,args.size,21)).astype(np.float32)
        meta=np.zeros(192,np.float32)
        small,small_ms=run(s,si,so,board,args.size,meta)
        large,large_ms=run(l,li,lo,board,args.size,meta)
        policy_channels=small["OutputPolicyPass"].size
        heads={}
        for name in OUTPUTS:
            a=crop(small[name],name,args.size,args.size,policy_channels)
            b=crop(large[name],name,args.size,19,policy_channels)
            if a.shape!=b.shape:raise ValueError("Head shape mismatch "+name)
            delta=np.abs(a-b)
            heads[name]={"maxAbs":float(delta.max()),"meanAbs":float(delta.mean()),
                         "smallMeanAbs":float(np.mean(np.abs(a))),"count":int(a.size)}
        row={"sample":i,"smallMs":round(small_ms,3),"largeMs":round(large_ms,3),"heads":heads}
        results.append(row)
        print(json.dumps(row,ensure_ascii=False))
    summary={"small":str(args.small),"large":str(args.large),"board":args.size,
             "samples":results,
             "meanSmallMs":float(np.mean([x["smallMs"] for x in results])),
             "meanLargeMs":float(np.mean([x["largeMs"] for x in results]))}
    if args.output:
        args.output.parent.mkdir(parents=True,exist_ok=True)
        args.output.write_text(json.dumps(summary,indent=2,ensure_ascii=False),encoding="utf-8")
    print("CPU_ONLY_COMPARE_DONE: inspect all five heads; no automatic PASS threshold")

if __name__=="__main__":main()
