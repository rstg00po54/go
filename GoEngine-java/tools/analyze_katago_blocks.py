#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Inspect actual named block weights and convolution operators in a KataGo TFLite model.

Run from the project directory:
  python3 tools/analyze_katago_blocks.py app/build/outputs/apk/debug/app-debug.apk
  python3 tools/analyze_katago_blocks.py app/build/outputs/apk/debug/app-debug.apk --block 17

This tool is read-only. It reuses the lightweight FlatBuffer reader in
inspect_katago_models.py; no TensorFlow or third-party libraries required.
"""
import argparse
from collections import Counter, defaultdict
import re
import sys
import zipfile

from inspect_katago_models import FB, fmt_size

BLOCK_PATTERN = re.compile(r'(?:^|\.)blocks\.(\d+)(?=\.|$)')


def block_for_name(name):
    """Return a b18 block index when a tensor has a trustworthy block prefix."""
    match = BLOCK_PATTERN.search(name)
    return int(match.group(1)) if match else None


def collect_info(data):
    fb = FB(memoryview(data))
    root = fb.root
    subgraphs = fb.tables(root, 2)
    if len(subgraphs) != 1:
        raise ValueError(f'Expected one TFLite subgraph, got {len(subgraphs)}')
    graph = subgraphs[0]
    buffer_sizes = [len(fb.blob(b, 0)) for b in fb.tables(root, 4)]
    tensors = []
    for t in fb.tables(graph, 0):
        tensors.append({
            'name': fb.string(t, 3),
            'shape': fb.indexes(t, 0),
            'buffer': fb.scalar(t, 2, '<I', 0),
        })
    # One buffer can have multiple tensor references. Attribute it only once.
    aliases = defaultdict(list)
    for t in tensors:
        if 0 <= t['buffer'] < len(buffer_sizes) and buffer_sizes[t['buffer']]:
            aliases[t['buffer']].append(t['name'])

    assigned = defaultdict(list)
    ambiguous = []
    for bi, names in aliases.items():
        owners = {block_for_name(n) for n in names if block_for_name(n) is not None}
        if len(owners) == 1:
            assigned[next(iter(owners))].append(bi)
        elif len(owners) > 1:
            ambiguous.append((bi, owners))

    codes = []
    for ct in fb.tables(root, 1):
        code = fb.scalar(ct, 3, '<i', -1)
        if code == -1:
            code = fb.scalar(ct, 0, '<b', 0)
        codes.append(code)

    # Count real CONV_2D operators by their filter tensor, not by a name guess.
    conv_by_block = defaultdict(list)
    conv_unassigned = []
    for op in fb.tables(graph, 3):
        opcode_idx = fb.scalar(op, 0, '<I', 0)
        if opcode_idx >= len(codes) or codes[opcode_idx] != 3:
            continue
        inputs = fb.indexes(op, 1)
        if len(inputs) < 2 or not (0 <= inputs[1] < len(tensors)):
            conv_unassigned.append('(invalid filter input)')
            continue
        t = tensors[inputs[1]]
        owner = block_for_name(t['name'])
        record = (t['name'], t['shape'], buffer_sizes[t['buffer']] if t['buffer'] < len(buffer_sizes) else 0)
        if owner is None:
            conv_unassigned.append(record)
        else:
            conv_by_block[owner].append(record)

    total_static = sum(buffer_sizes)
    block_bytes = {n:sum(buffer_sizes[i] for i in indexes) for n,indexes in assigned.items()}
    assigned_ids = {i for values in assigned.values() for i in values}
    unassigned_bytes = sum(n for i,n in enumerate(buffer_sizes) if n and i not in assigned_ids)
    return {
        'tensors': tensors, 'buffers':buffer_sizes,
        'blocks':assigned, 'block_bytes':block_bytes,
        'convs':conv_by_block, 'other_convs':conv_unassigned,
        'total_convs': sum(len(v) for v in conv_by_block.values())+len(conv_unassigned),
        'ambiguous':ambiguous, 'total_static':total_static,
        'unassigned_bytes':unassigned_bytes,
    }


def report(info, detail):
    print('Human b18 TFLite：逐块权重与卷积统计（基于真实 FlatBuffer）')
    print('说明：权重列按张量名称分组，只计入可明确归属该 Block 的常量缓冲区；')
    print('      不是运行内存、运算量，也不是网络块的完整执行耗时。')
    print('\n  Block    常量缓冲区     常量大小      卷积算子')
    print('  -----    ----------    ----------    --------')
    for n in range(18):
        print(f"  {n:>5}    {len(info['blocks'][n]):>10}    {fmt_size(info['block_bytes'].get(n,0)):>10}    {len(info['convs'][n]):>8}")
    counted=sum(info['block_bytes'].values())
    print(f"\n  18块合计: {fmt_size(counted)}；总静态缓冲区: {fmt_size(info['total_static'])}")
    print(f"  其他或无法明确归属的常量: {fmt_size(info['unassigned_bytes'])}")
    print(f"  归属18块的卷积: {sum(map(len,info['convs'].values()))}/{info['total_convs']} 个")
    if info['ambiguous']:
        print(f"  警告：{len(info['ambiguous'])} 个缓冲区被多个块引用，未重复计数")

    for n in detail:
        if n not in range(18):
            print(f'\n  Block {n} 不在 0..17 范围内，跳过')
            continue
        print(f'\n  === Block {n}：实际卷积权重张量（最多前 30 项）===')
        shapes = Counter(tuple(shape) for _,shape,_ in info['convs'][n])
        print('  卷积滤波器形状:', ', '.join(f'{list(s)} ×{count}' for s,count in shapes.most_common()) or '(未定位到)')
        for name,shape,sz in info['convs'][n][:30]:
            print(f'  {fmt_size(sz):>12}  {str(shape):<20}  {name}')
        if len(info['convs'][n]) > 30:
            print(f'  ... 另有 {len(info["convs"][n])-30} 项')

    print('\n  提醒：卷积形状是 TFLite O-H-W-I 约定；')
    print('        例如 [192,3,3,192] = 192 输出通道、3×3卷积核、192 输入通道。')
    print('  模型、APK 内容没有被修改。')


def main():
    parser=argparse.ArgumentParser(description='拆解 KataGo Human b18 的 18 个主干块')
    parser.add_argument('apk',help='Android APK 路径')
    parser.add_argument('--block',type=int,action='append',help='打印指定 Block 的卷积详情，例如 --block 0 --block 17')
    args=parser.parse_args()
    name='assets/engine/katago_human_19x19.tflite'
    with zipfile.ZipFile(args.apk) as archive:
        with archive.open(name) as fh:
            data=fh.read()
    info=collect_info(data)
    report(info, args.block if args.block is not None else [0,17])


if __name__=='__main__':
    try:
        main()
    except (OSError,ValueError,zipfile.BadZipFile,KeyError) as exc:
        print(f'模型分析失败: {exc}', file=sys.stderr)
        sys.exit(1)
