#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Read-only KataGo TFLite / RKNN inventory from an Android APK.
No third-party Python packages required. Does not modify the APK or models.
Usage: python3 inspect_katago_models.py /path/to/app-debug.apk
"""
import argparse
import collections
import hashlib
import io
import json
import struct
import sys
import zipfile

OPS = {
  0:'ADD',1:'AVERAGE_POOL_2D',2:'CONCATENATION',3:'CONV_2D',4:'DEPTHWISE_CONV_2D',
  6:'DEQUANTIZE',9:'FULLY_CONNECTED',14:'LOGISTIC',17:'MAX_POOL_2D',18:'MUL',
  19:'RELU',21:'RELU6',22:'RESHAPE',23:'RESIZE_BILINEAR',25:'SOFTMAX',28:'TANH',
  32:'CUSTOM',34:'PAD',36:'GATHER',39:'TRANSPOSE',40:'MEAN',41:'SUB',42:'DIV',
  45:'STRIDED_SLICE',53:'CAST',54:'PRELU',55:'MAXIMUM',57:'MINIMUM',59:'NEG',
  64:'SELECT',65:'SLICE',66:'SIN',67:'TRANSPOSE_CONV',70:'EXPAND_DIMS',
  74:'SUM',78:'POW',79:'ARG_MIN',83:'PACK',88:'UNPACK',92:'SQUARE',
  94:'FILL',99:'SQUARED_DIFFERENCE',101:'ABS',102:'SPLIT_V',
  104:'CEIL',107:'REVERSE_V2',117:'HARD_SWISH',124:'BATCH_MATMUL',
}
TYPES = {0:'FLOAT32',1:'FLOAT16',2:'INT32',3:'UINT8',4:'INT64',5:'STRING',
         6:'BOOL',7:'INT16',8:'COMPLEX64',9:'INT8',10:'FLOAT64',11:'COMPLEX128',
         12:'UINT64',15:'UINT32',16:'UINT16',17:'INT4',18:'BFLOAT16'}

def fmt_size(n):
    return f'{n / 1048576:.2f} MiB' if n >= 1048576 else f'{n / 1024:.1f} KiB'

class FB:
    """Small reader for documented FlatBuffers table/vectors, without TFLite dependency."""
    def __init__(self, blob):
        self.b = blob
        if len(blob) < 8 or bytes(blob[4:8]) != b'TFL3':
            raise ValueError('不是 TFLite FlatBuffer (缺少 TFL3 标识)')
        self.root = self.u32(0)
        self._check(self.root, 4)

    def _check(self, p, size):
        if p < 0 or p + size > len(self.b):
            raise ValueError(f'FlatBuffer 越界: {p}:{p+size}')

    def u16(self, p):
        self._check(p, 2)
        return struct.unpack_from('<H', self.b, p)[0]
    def u32(self, p):
        self._check(p, 4)
        return struct.unpack_from('<I', self.b, p)[0]
    def i32(self, p):
        self._check(p, 4)
        return struct.unpack_from('<i', self.b, p)[0]

    def field(self, t, idx):
        vt = t - self.i32(t)
        vlen = self.u16(vt)
        entry = vt + 4 + 2*idx
        if entry + 2 > vt + vlen:
            return None
        o = self.u16(entry)
        return t+o if o else None

    def scalar(self, t, idx, kind='<I', default=0):
        p = self.field(t, idx)
        if p is None:
            return default
        self._check(p, struct.calcsize(kind))
        return struct.unpack_from(kind, self.b, p)[0]

    def indirect(self, p):
        return p + self.u32(p)

    def table(self, t, idx):
        p = self.field(t, idx)
        return self.indirect(p) if p is not None else None

    def string(self, t, idx):
        p = self.field(t, idx)
        if p is None:
            return ''
        v = self.indirect(p)
        n = self.u32(v)
        self._check(v+4, n)
        return bytes(self.b[v+4:v+4+n]).decode('utf8', 'replace')

    def vector(self, t, idx, element_size=4):
        p = self.field(t, idx)
        if p is None:
            return 0, 0
        v = self.indirect(p)
        n = self.u32(v)
        self._check(v+4, n*element_size)
        return v+4, n

    def tables(self, t, idx):
        p, n = self.vector(t, idx)
        return [self.indirect(p+4*i) for i in range(n)]

    def indexes(self, t, idx):
        p, n = self.vector(t, idx)
        return [self.i32(p+4*i) for i in range(n)]

    def blob(self, t, idx):
        p, n = self.vector(t, idx, 1)
        return self.b[p:p+n]


def read_tflite(data, filename, show_top=8):
    f = FB(data)
    root = f.root
    codes=[]
    for ct in f.tables(root, 1):
        code = f.scalar(ct, 3, '<i', -1)
        if code == -1:
            code = f.scalar(ct, 0, '<b', 0)
        name = OPS.get(code, 'OP_'+str(code))
        if code == 32:
            name += ':'+f.string(ct,1)
        codes.append(name)
    buffers=[]
    for bt in f.tables(root, 4):
        buf = f.blob(bt, 0)
        buffers.append(buf)
    static = sum(len(x) for x in buffers)
    graph_tables=f.tables(root, 2)
    print('  文件大小:', fmt_size(len(data)))
    print('  FlatBuffer 版本:',f.scalar(root,0,'<I',0),'子图数:',len(graph_tables))
    print('  常量/权重缓冲区:',fmt_size(static),f'({static/len(data)*100:.1f}%)', '缓冲区数量:',len(buffers))
    print('  非缓冲区部分:',fmt_size(len(data)-static),'(包含图描述、元数据、对齐等；不全是算子)')
    for gi, g in enumerate(graph_tables):
        ts=f.tables(g,0)
        tensors=[]
        for t in ts:
            shape=f.indexes(t,0)
            dtype=TYPES.get(f.scalar(t,1,'<b',0),'未知')
            bi=f.scalar(t,2,'<I',0)
            name=f.string(t,3)
            tensors.append({'shape':shape,'type':dtype,'buffer':bi,'name':name})
        operators=f.tables(g,3)
        counts=collections.Counter()
        for op in operators:
            ci=f.scalar(op,0,'<I',0)
            counts[codes[ci] if ci < len(codes) else 'UNKNOWN_OP']+=1
        print(f'  子图 #{gi}: 张量 {len(ts)} 个，算子 {len(operators)} 个')
        for k,label in [(1,'输入'),(2,'输出')]:
            print('   ', label, '张量:')
            for i in f.indexes(g,k):
                if 0 <= i < len(tensors):
                    x=tensors[i]
                    print(f"      {x['name'] or '#'+str(i)}  {x['type']}  {x['shape']}")
        print('    算子数量（前12种）:',', '.join(f'{n}×{count}' for n,count in counts.most_common(12)))
        if gi==0:
            by_buffer=collections.defaultdict(list)
            for t in tensors:
                by_buffer[t['buffer']].append(t)
            largest=sorted(((len(buf),i) for i,buf in enumerate(buffers) if len(buf)>0),reverse=True)[:show_top]
            print('    最大的权重/常量缓冲区:')
            for n,i in largest:
                first=by_buffer.get(i,[{'name':'(未直接关联到主子图张量)','shape':[]}])[0]
                print(f"      {fmt_size(n):>12}  {first['name'][:68]}  {first['shape']}")
    return {'size':len(data),'buffers':buffers,'bytes':static}


def matching_buffer_bytes(base,other):
    """Compare identical constant buffers by index, as a quick sanity check."""
    a=base['buffers']; b=other['buffers']; same=0; compared=0; matches=0
    for x,y in zip(a,b):
        if not x and not y:
            continue
        compared +=1
        if len(x)==len(y) and x==y:
            same += len(x)
            matches +=1
    print(f'    相同索引处完全一致: {matches}/{compared} 个非空常量缓冲区，{fmt_size(same)} 字节相同')
    print('    注意：仅检查相同索引，不代表所有可复用权重的精确占比。')


def inspect_rknn(data, filename):
    print('  文件大小:',fmt_size(len(data)))
    print('  文件头前 32 字节:',bytes(data[:32]).hex(' '))
    print('  RKNN 是面向硬件的编译文件；不能直接按 TFLite 的权重/算子表来解析。')
    return {'size':len(data),'blob':data}


def block_match(a,b,size=4096):
    a_blocks=[bytes(a[i:i+size]) for i in range(0,len(a)-size+1,size)]
    b_blocks=[bytes(b[i:i+size]) for i in range(0,len(b)-size+1,size)]
    same_pos=sum(1 for x,y in zip(a_blocks,b_blocks) if x==y)
    pool=collections.Counter(a_blocks)
    common=0
    for blk in b_blocks:
        if pool[blk]>0:
            common+=1
            pool[blk]-=1
    print(f'    4KiB 固定块：同位置相同 {same_pos}/{len(b_blocks)} ({same_pos/max(1,len(b_blocks))*100:.1f}%)；')
    print(f'    忽略块位置后相同 {common}/{len(b_blocks)} ({common/max(1,len(b_blocks))*100:.1f}%)')
    print('    注：这是原始二进制相似度，不等于网络权重百分比。')


def main():
    p=argparse.ArgumentParser(description='分析 KataGo APK 内的 TFLite、RKNN 模型组成')
    p.add_argument('apk', help='app-debug.apk 的完整路径或相对路径')
    args=p.parse_args()
    names=['human','10b']
    boards=[19,9,11,13,15]
    with zipfile.ZipFile(args.apk) as z:
        print('=== 原始 KataGo 文件（仅观察头部，不尝试解码私有格式）===')
        for path in ['10b.bin', 'b18c384nbt-humanv0.bin']:
            key='assets/engine/'+path
            try:
                with z.open(key) as src:
                    head=src.read(256)
                info=z.getinfo(key)
                print(f'{path}: {fmt_size(info.file_size)}；前32字节: {head[:32].hex()}')
                visible=''.join(chr(x) if 32 <= x < 127 else ' ' for x in head[:128])
                print('  可见ASCII:', ' '.join(visible.split())[:120])
            except KeyError:
                print(path, '不存在')
        for path in ['katago_human_19x19.json','katago_10b_19x19.json']:
            key='assets/engine/'+path
            try:
                meta=json.loads(z.read(key))
                print(path, 'JSON字段:', list(meta)[:30] if isinstance(meta,dict) else type(meta).__name__)
            except (KeyError,ValueError) as exc:
                print(path, '无法读取:',exc)
        for model in names:
            for suffix in ['tflite','rknn']:
                prefix=f'assets/engine/katago_{model}_'
                print('\n'+'='*70)
                print(model.upper(),suffix.upper())
                base=None
                for n in boards:
                    fn=f'{prefix}{n}x{n}.{suffix}'
                    try:
                        with z.open(fn) as stream:
                            data=stream.read()
                    except KeyError:
                        print(f'  {n}x{n}: APK 中不存在，跳过')
                        continue
                    print('\n'+fn)
                    if suffix=='tflite':
                        try:
                            info=read_tflite(data,fn,6 if n==19 else 0)
                        except Exception as exc:
                            print('  FlatBuffer 解析失败:',repr(exc))
                            continue
                        if n==19: base=info
                        elif base:
                            matching_buffer_bytes(base,info)
                    else:
                        info=inspect_rknn(data,fn)
                        if n==19: base=info
                        elif base:
                            block_match(base['blob'],data)
                    if n==19 and suffix=='tflite':
                        print('  （其余棋盘仍打印算子和输入输出，以核对不同形状）')
    print('\n检查结束：原 APK 未修改。')

if __name__=='__main__':
    try: main()
    except (FileNotFoundError, zipfile.BadZipFile, PermissionError) as e:
        print('错误:',e,file=sys.stderr); sys.exit(1)