# -*- coding: utf-8 -*-
"""对比 1.2.0 与 1.3.0 的中位切分量化：修复前后 16 个色位里实际有几种颜色。

用来坐实「平涂画面浪费最狠」这个结论——拿颜色分布很散的"照片"当样本是发现不了这个 bug 的。

跑法：
    python verify_quantize_before_after.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import verify_ansi_roundtrip as v
import verify_pc98_palette as p


def old_quantize(colors, max_colors):
    """1.2.0 里的写法：判停看桶数 + 只按原始跨度选桶 + 不去重。"""
    if not colors or max_colors <= 0:
        return []
    buckets = [list(colors)]
    while len(buckets) < max_colors:            # ← 判停看桶数
        target, widest = -1, -1
        for i, b in enumerate(buckets):
            if len(b) < 2:
                continue
            _, span = v.widest_channel_range(b)
            if span > widest:                   # ← 只看原始跨度
                widest, target = span, i
        if target < 0:
            break
        b = buckets[target]
        ch, _ = v.widest_channel_range(b)
        b = sorted(b, key=lambda c: v.channel_value(c, ch))
        mid = len(b) // 2
        buckets[target] = b[:mid]
        buckets.append(b[mid:])
    return [v.average_of(x) for x in buckets if x]   # ← 不去重


print("%-22s %8s %8s %8s" % ("场景", "旧:槽位", "旧:色数", "新:色数"))
for name, colors in p.SCENES.items():
    old = old_quantize(colors, 16)
    new = v.quantize(colors, 16)
    print("%-22s %8d %8d %8d" % (name, len(old), len(set(old)), len(new)))
    if len(set(old)) < 16:
        from collections import Counter
        counts = Counter(p.hex_of(c) for c in old)
        dupes = " ".join("%s×%d" % (c, n) for c, n in counts.most_common() if n > 1)
        print("      旧调色板: %s" % " ".join(p.hex_of(c) for c in old))
        print("      重复项  : %s" % dupes)
