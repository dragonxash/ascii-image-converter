# -*- coding: utf-8 -*-
"""
验证「色块模式 + PC-98 16 色」这套组合的行为，重点回答一个问题：

    PC-98 到底有没有"固定的 16 色"？

结论应该是：**没有**。PC-98 是「同屏 16 色，从 4096 色调色板里挑」，
那 16 个颜色是每张图按自己的构图挑的。本脚本用几组不同色系的画面跑一遍，
看调色板是不是真的跟着画面变，以及是不是都落在 4096 色域里。

顺带盯住一件事：**调色板 16 个槽位里不能有重复色**。
平均色会被吸到 12 位网格上，两个桶容易撞到同一格点——按"桶数"判停的话，
16 个桶可能只给出 6 种颜色，白扔 10 个色位。大块平涂的画面最容易踩这个坑。

跑法：
    python verify_pc98_palette.py
"""

import math
import random
import sys

import verify_ansi_roundtrip as v

PALETTE_SIZE = 16          # PC-98 同屏色数
OLD_PALETTE_SIZE = 8       # PC-9801VM/UV 那一代：同屏 8 色

failures = []


def check(name, ok, detail=""):
    mark = "PASS" if ok else "FAIL"
    line = f"  [{mark}] {name}"
    if detail:
        line += f"  —— {detail}"
    print(line)
    if not ok:
        failures.append(name)


def hex_of(c):
    return f"#{v.red(c):02X}{v.green(c):02X}{v.blue(c):02X}"


def clamp(x):
    return max(0, min(255, int(x)))


def lerp(a, b, t):
    return v.rgb(
        clamp(v.red(a) + (v.red(b) - v.red(a)) * t),
        clamp(v.green(a) + (v.green(b) - v.green(a)) * t),
        clamp(v.blue(a) + (v.blue(b) - v.blue(a)) * t),
    )


# ---------------------------------------------------------------------------
# 造几组"画面"。注意每组都必须有远超 16 种不同的颜色——
# 如果源画面本身只有 4 种颜色，那挑出 16 色是数学上不可能的，
# 那种测试场景没有意义（会给出假失败）。
# ---------------------------------------------------------------------------

def gradient_scene(base_a, base_b, width=96, height=64, noise=7, seed=1, mono=False):
    """斜向渐变 + 低频"云层" + 噪点，模拟真实照片的连续色调。

    [mono] 为 True 时三通道加同一份噪声，得到真正的灰阶画面
    （否则各通道独立加噪，出来的其实是 #887777 这种偏色，不是灰）。
    """
    rnd = random.Random(seed)
    out = []
    for y in range(height):
        for x in range(width):
            t = (x + y) / (width + height - 2)
            c = lerp(base_a, base_b, t)
            cloud = 0.5 + 0.5 * math.sin(x * 0.21) * math.cos(y * 0.17)
            c = lerp(c, base_b if t < 0.5 else base_a, 0.32 * cloud)
            if mono:
                d = rnd.randint(-noise, noise)
                out.append(v.rgb(clamp(v.red(c) + d), clamp(v.green(c) + d),
                                 clamp(v.blue(c) + d)))
            else:
                out.append(v.rgb(
                    clamp(v.red(c) + rnd.randint(-noise, noise)),
                    clamp(v.green(c) + rnd.randint(-noise, noise)),
                    clamp(v.blue(c) + rnd.randint(-noise, noise)),
                ))
    return out


def flat_scene(regions, width=96, height=64, noise=5, seed=2):
    """大块平涂 + 每块内轻微变化，模拟 PC-98 CG 那种赛璐璐上色。"""
    rnd = random.Random(seed)
    out = []
    for y in range(height):
        for x in range(width):
            # 用坐标做伪随机分区，形成不规则色块
            idx = (x // 24 + (y // 16) * 3 + (x * y) // 997) % len(regions)
            base = regions[idx]
            out.append(v.rgb(
                clamp(v.red(base) + rnd.randint(-noise, noise)),
                clamp(v.green(base) + rnd.randint(-noise, noise)),
                clamp(v.blue(base) + rnd.randint(-noise, noise)),
            ))
    return out


SCENES = {
    "黄昏草原（暖色主导）": gradient_scene(
        v.rgb(0xFF, 0xC8, 0x66), v.rgb(0x2B, 0x1B, 0x12), seed=1),
    "深海（冷色主导）": gradient_scene(
        v.rgb(0x2E, 0xC8, 0xE8), v.rgb(0x02, 0x08, 0x14), seed=2),
    "灰阶老照片": gradient_scene(
        v.rgb(0xF0, 0xF0, 0xF0), v.rgb(0x08, 0x08, 0x08), seed=3, mono=True),
    "赛博霓虹（洋红/青）": flat_scene([
        v.rgb(0xFF, 0x2A, 0x9B), v.rgb(0x18, 0xE8, 0xE8), v.rgb(0x2A, 0x0A, 0x3C),
        v.rgb(0xFF, 0xF0, 0x3C), v.rgb(0x40, 0x10, 0x60), v.rgb(0x0A, 0x40, 0x50),
    ], seed=4),
    "森林（单一绿系）": flat_scene([
        v.rgb(0x1E, 0x4A, 0x22), v.rgb(0x3C, 0x7A, 0x38), v.rgb(0x7A, 0xB0, 0x50),
        v.rgb(0x10, 0x20, 0x12), v.rgb(0x5A, 0x8A, 0x30), v.rgb(0x28, 0x60, 0x44),
    ], seed=5),
}


def main():
    print("=" * 74)
    print("PC-98 调色板验证：16 色到底是不是固定的？")
    print("=" * 74)

    # -----------------------------------------------------------------------
    print("\n[1] 每组画面各挑 16 色，看调色板会不会跟着变")
    palettes = {}
    for name, colors in SCENES.items():
        source_distinct = len(set(colors))
        # 理论可达上限：源画面量化到 12 位网格后剩几种色，再和 16 取小。
        # 比如纯灰阶画面全部落在灰轴上，最多只有 16 个灰阶可用，
        # 但如果源画面的灰度范围没铺满 0..255，能用的格点还会更少。
        reachable = min(PALETTE_SIZE, len({v.snap_to_pc98(c) for c in colors}))
        palette = v.quantize(colors, PALETTE_SIZE)
        palettes[name] = palette
        print(f"\n  {name}")
        print(f"    格子数 {len(colors)}，源画面不同颜色 {source_distinct} 种")
        print(f"    量化后理论上限 {reachable} 色 → 实际挑出 {len(palette)} 色")
        print(f"    {' '.join(hex_of(c) for c in palette)}")
        check(f"{name}：源画面颜色数 > 16（测试前提）", source_distinct > PALETTE_SIZE,
              f"{source_distinct} 种")
        check(f"{name}：把色位吃满（达到理论可达上限）",
              len(palette) == reachable, f"实际 {len(palette)} / 上限 {reachable}")
        check(f"{name}：槽位没有重复色",
              len({hex_of(c) for c in palette}) == len(palette),
              f"去重后 {len({hex_of(c) for c in palette})} 色")

    # -----------------------------------------------------------------------
    print("\n[2] 调色板两两是否相同（应当全都不同 → 证明不是固定色表）")
    names = list(palettes.keys())
    identical_pairs = 0
    for i in range(len(names)):
        for j in range(i + 1, len(names)):
            a = {hex_of(c) for c in palettes[names[i]]}
            b = {hex_of(c) for c in palettes[names[j]]}
            shared = a & b
            same = a == b
            if same:
                identical_pairs += 1
            print(f"    {names[i][:10]:<12} vs {names[j][:10]:<12} "
                  f"重叠 {len(shared):>2}/16"
                  + ("   ← 完全相同！" if same else ""))
    check("没有任何两组画面得到完全相同的调色板", identical_pairs == 0,
          f"{identical_pairs} 对相同")

    gray = {hex_of(c) for c in palettes["灰阶老照片"]}
    cyber = {hex_of(c) for c in palettes["赛博霓虹（洋红/青）"]}
    check("灰阶画面的调色板与彩色画面不重叠", len(gray & cyber) == 0,
          f"重叠 {len(gray & cyber)} 色")
    check("灰阶画面的调色板确实都是灰（R == G == B）",
          all(hex_of(c)[1:3] == hex_of(c)[3:5] == hex_of(c)[5:7] for c in
              palettes["灰阶老照片"]))

    # -----------------------------------------------------------------------
    print("\n[3] 所有调色板是否都落在 4096 色域内（每通道 4 bit = 17 的倍数）")
    off_grid = []
    for name, palette in palettes.items():
        for c in palette:
            for ch, val in (("R", v.red(c)), ("G", v.green(c)), ("B", v.blue(c))):
                if val % 17 != 0:
                    off_grid.append((name, hex_of(c), ch, val))
    check("无越界颜色（全部是 17 的倍数）", not off_grid,
          f"{len(off_grid)} 个越界" if off_grid else "0 个越界")

    gamut = {v.rgb(r, g, b)
             for r in range(0, 256, 17)
             for g in range(0, 256, 17)
             for b in range(0, 256, 17)}
    check("4096 色域本身的定义是对的（16^3）", len(gamut) == 4096,
          f"{len(gamut)} 色")
    all_used = set()
    for palette in palettes.values():
        all_used.update(palette)
    check("五组画面挑出的颜色全部落在 4096 色域内",
          all_used <= gamut, f"合计用到 {len(all_used)} 种不同颜色")

    # -----------------------------------------------------------------------
    print("\n[4] 老机型 8 色同屏（PC-9801VM/UV 那一代）")
    for name in names:
        reachable8 = min(OLD_PALETTE_SIZE,
                         len({v.snap_to_pc98(c) for c in SCENES[name]}))
        old = v.quantize(SCENES[name], OLD_PALETTE_SIZE)
        check(f"{name}：8 色模式把色位吃满", len(old) == reachable8,
              f"实际 {len(old)} / 上限 {reachable8}")
    old = v.quantize(SCENES["黄昏草原（暖色主导）"], OLD_PALETTE_SIZE)
    check("8 色全部落在 12 位网格上",
          all(v.red(c) % 17 == 0 and v.green(c) % 17 == 0 and v.blue(c) % 17 == 0
              for c in old))

    # -----------------------------------------------------------------------
    print("\n[5] 色块模式：马赛克降采样是不是每块取区域平均色")
    width, height = 96, 64
    field = [[v.rgb((x * 255) // (width - 1), (y * 255) // (height - 1), 128)
              for x in range(width)] for y in range(height)]

    columns, rows = 16, 8          # 色块模式的网格
    bw, bh = width // columns, height // rows
    blocks = []
    for by in range(rows):
        for bx in range(columns):
            acc_r = acc_g = acc_b = 0
            for y in range(by * bh, (by + 1) * bh):
                for x in range(bx * bw, (bx + 1) * bw):
                    c = field[y][x]
                    acc_r += v.red(c)
                    acc_g += v.green(c)
                    acc_b += v.blue(c)
            n = bw * bh
            blocks.append(v.rgb(acc_r // n, acc_g // n, acc_b // n))

    check("色块数量 == 网格格数", len(blocks) == columns * rows, f"{len(blocks)} 块")
    check("色块一律是正方形（16x8 网格 / 96x64 图 → 每块 6x8 像素）",
          bw == 6 and bh == 8, f"{bw}x{bh}")
    mid_block = blocks[(rows // 2) * columns + columns // 2]
    cx = (columns // 2) * bw + bw // 2
    cy = (rows // 2) * bh + bh // 2
    expect = field[cy][cx]
    check("块颜色等于该区域的平均色",
          abs(v.red(mid_block) - v.red(expect)) <= 6
          and abs(v.green(mid_block) - v.green(expect)) <= 6,
          f"块 {hex_of(mid_block)} vs 区域中心 {hex_of(expect)}")

    # -----------------------------------------------------------------------
    print("\n[6] 端到端：色块降采样 + PC-98 16 色 → 复古像素画")
    palette = v.quantize(blocks, PALETTE_SIZE)
    final = [v.nearest_in(palette, c) for c in blocks]
    used = set(final)
    print(f"    {columns}x{rows} = {len(blocks)} 块 → 量化后 {len(used)} 色")
    print(f"    调色板：{' '.join(sorted(hex_of(c) for c in used))}")
    check("色块数（128）远超色数（16），符合「有限色画多块」的预期",
          len(blocks) > PALETTE_SIZE)
    check("成图颜色数 <= 16（同屏色数约束）", len(used) <= PALETTE_SIZE,
          f"{len(used)} 色")
    check("成图所有颜色都来自调色板", used <= set(palette))
    check("成图所有颜色都在 12 位精度上（每通道 17 的倍数）",
          all(all(int(hex_of(c)[i:i + 2], 16) % 17 == 0 for i in (1, 3, 5))
              for c in used))
    check("调色板里每个色位都被用上了（没有空转的色位）",
          len(used) == len(palette),
          f"用到 {len(used)} / 调色板 {len(palette)}")

    # -----------------------------------------------------------------------
    print("\n" + "=" * 74)
    if failures:
        print(f"结果：{len(failures)} 项未通过")
        for name in failures:
            print(f"  - {name}")
        print("=" * 74)
        return 1
    print("结果：全部通过")
    print("结论：调色板随构图变化（非固定色表），始终落在 PC-98 的 4096 色域内，")
    print("      且 16 个色位都被真实用上（无重复、无空转）。")
    print("=" * 74)
    return 0


if __name__ == "__main__":
    sys.exit(main())
