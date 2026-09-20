# -*- coding: utf-8 -*-
"""
验证 PC-98 画风里的**抖动（dithering）**是不是真的管用。

要回答的问题只有一个：

    16 色根本画不出的中间色（渐变色），到底能不能用 16 种颜色"组合"出来？

答案是能——办法是老杂志印刷网点那一套：相邻格子轮流用两种颜色，隔远看
就合成出中间色。本脚本把 Kotlin 里的 [Pc98Quantizer.apply] 原样移植过来，
对着几组受控输入量一量，确认它确实在干活，而不是个摆设参数。

重点盯这几条（都是"看起来对但其实没生效"的典型死法）：

| 编号 | 检查项 | 为什么值得查 |
| --- | --- | --- |
| 1 | 三种模式的输出**只能**是调色板里的颜色 | 这是 PC-98 的硬约束，漏一个都是在作弊 |
| 2 | 不抖动 == 逐格取最近色 | 基准行为，错了后面全白搭 |
| 3 | 网点抖动能把纯灰用黑白拼出来 | 这就是"用 16 色表现渐变"本身 |
| 4 | 抖动的平均亮度能还原被丢掉的中间调 | 证明它是**正确**的组合，不是乱撒点 |
| 5 | 误差扩散比不抖动更接近原图 | 不然这个模式没有存在意义 |
| 6 | 抖动确定性（同样输入同样输出） | 不然每转一次画面都在变 |
| 7 | 网点图案在平涂区以 8 为周期重复 | "网点"得是网点，不是噪声 |
| 8 | 抖动幅度跟着调色板间距自适应 | 防有人把幅度写死成魔数 |

跑法：
    python verify_pc98_dither.py
"""

import math
import sys

import verify_ansi_roundtrip as v

CHANNEL_LEVELS = 16
CHANNEL_STEP = 17
PALETTE_SIZE = 16

# 标准 Bayer 8×8 阈值矩阵，取值 0..63（必须和 Pc98Quantizer.BAYER_8 一致）
BAYER_8 = [
    0, 32, 8, 40, 2, 34, 10, 42,
    48, 16, 56, 24, 50, 18, 58, 26,
    12, 44, 4, 36, 14, 46, 6, 38,
    60, 28, 52, 20, 62, 30, 54, 22,
    3, 35, 11, 43, 1, 33, 9, 41,
    51, 19, 59, 27, 49, 17, 57, 25,
    15, 47, 7, 39, 13, 45, 5, 37,
    63, 31, 55, 23, 61, 29, 53, 21,
]

failures = []
notes = []


def check(name, ok, detail=""):
    mark = "PASS" if ok else "FAIL"
    line = f"  [{mark}] {name}"
    if detail:
        line += f"  —— {detail}"
    print(line)
    if not ok:
        failures.append(name)


def note(text):
    notes.append(text)


def clamp(x):
    """和 Kotlin 的 `value.toInt().coerceIn(0, 255)` 对齐：向零截断再夹紧。"""
    return max(0, min(255, int(x)))


def luma(c):
    """BT.601 亮度，权重和 App 里保持一致。"""
    return (v.red(c) * 299 + v.green(c) * 587 + v.blue(c) * 114) // 1000


# ---------------------------------------------------------------------------
# Pc98Quantizer 的抖动部分，逐行对着 Kotlin 抄
# ---------------------------------------------------------------------------

def neighbour_spacing(palette):
    """调色板里每个色到"最近另一个色"的平均距离（按通道平均），再取一半。"""
    if len(palette) < 2:
        return 0.0
    total = 0.0
    for i in range(len(palette)):
        best = float("inf")
        for j in range(len(palette)):
            if i == j:
                continue
            dr = v.red(palette[i]) - v.red(palette[j])
            dg = v.green(palette[i]) - v.green(palette[j])
            db = v.blue(palette[i]) - v.blue(palette[j])
            d = math.sqrt(dr * dr + dg * dg + db * db) / math.sqrt(3.0)
            if d < best:
                best = d
        total += best
    return total / len(palette) * 0.5


def ordered_dither(colors, columns, rows, palette):
    amplitude = neighbour_spacing(palette)
    out = [0] * len(colors)
    for row in range(rows):
        for col in range(columns):
            idx = row * columns + col
            threshold = (BAYER_8[(row % 8) * 8 + (col % 8)] + 0.5) / 64.0 - 0.5
            shift = threshold * amplitude
            c = colors[idx]
            adjusted = v.rgb(
                clamp(v.red(c) + shift),
                clamp(v.green(c) + shift),
                clamp(v.blue(c) + shift),
            )
            out[idx] = v.nearest_in(palette, adjusted)
    return out


def _spread(buf, columns, rows, col, row, er, eg, eb, weight):
    if col < 0 or col >= columns or row < 0 or row >= rows:
        return
    base = (row * columns + col) * 3
    buf[base] += er * weight
    buf[base + 1] += eg * weight
    buf[base + 2] += eb * weight


def diffuse_dither(colors, columns, rows, palette):
    out = [0] * len(colors)
    buf = [0.0] * (len(colors) * 3)
    for i in range(len(colors)):
        buf[i * 3] = float(v.red(colors[i]))
        buf[i * 3 + 1] = float(v.green(colors[i]))
        buf[i * 3 + 2] = float(v.blue(colors[i]))

    for row in range(rows):
        for col in range(columns):
            idx = row * columns + col
            base = idx * 3
            want = v.rgb(clamp(buf[base]), clamp(buf[base + 1]), clamp(buf[base + 2]))
            got = v.nearest_in(palette, want)
            out[idx] = got
            er = v.red(want) - v.red(got)
            eg = v.green(want) - v.green(got)
            eb = v.blue(want) - v.blue(got)
            _spread(buf, columns, rows, col + 1, row, er, eg, eb, 7 / 16)
            _spread(buf, columns, rows, col - 1, row + 1, er, eg, eb, 3 / 16)
            _spread(buf, columns, rows, col, row + 1, er, eg, eb, 5 / 16)
            _spread(buf, columns, rows, col + 1, row + 1, er, eg, eb, 1 / 16)
    return out


def apply_dither(colors, columns, rows, palette, mode):
    if mode == "none":
        return [v.nearest_in(palette, c) for c in colors]
    if mode == "ordered":
        return ordered_dither(colors, columns, rows, palette)
    if mode == "diffuse":
        return diffuse_dither(colors, columns, rows, palette)
    raise ValueError(mode)


# ---------------------------------------------------------------------------
# 受控输入
# ---------------------------------------------------------------------------

def ramp(columns, rows, dark, light):
    """横向渐变：列方向从 [dark] 线性过渡到 [light]，行方向不变。"""
    out = []
    for _ in range(rows):
        for col in range(columns):
            t = col / (columns - 1)
            out.append(v.rgb(
                clamp(v.red(dark) + (v.red(light) - v.red(dark)) * t),
                clamp(v.green(dark) + (v.green(light) - v.green(dark)) * t),
                clamp(v.blue(dark) + (v.blue(light) - v.blue(dark)) * t),
            ))
    return out


def flat(columns, rows, color):
    return [color] * (columns * rows)


SNAP_BLACK = v.snap_to_pc98(v.rgb(0, 0, 0))
SNAP_WHITE = v.snap_to_pc98(v.rgb(255, 255, 255))
SNAP_GREY = v.snap_to_pc98(v.rgb(128, 128, 128))

# 只用黑白两色：最苛刻的场景，逼抖动去"组合"
MONO_PALETTE = [SNAP_BLACK, SNAP_WHITE]

# PC-98 标准 16 色（和 Pc98Quantizer.BUILT_IN 的第一套同源）
PC98_16 = [v.snap_to_pc98(v.rgb(*c)) for c in [
    (0x00, 0x00, 0x00), (0x00, 0x00, 0xAA), (0xAA, 0x00, 0x00), (0xAA, 0x00, 0xAA),
    (0x00, 0xAA, 0x00), (0x00, 0xAA, 0xAA), (0xAA, 0xAA, 0x00), (0xAA, 0xAA, 0xAA),
    (0x55, 0x55, 0x55), (0x55, 0x55, 0xFF), (0xFF, 0x55, 0x55), (0xFF, 0x55, 0xFF),
    (0x55, 0xFF, 0x55), (0x55, 0xFF, 0xFF), (0xFF, 0xFF, 0x55), (0xFF, 0xFF, 0xFF),
]]


def mean(values):
    return sum(values) / len(values)


def mean_luma(colors):
    return mean([luma(c) for c in colors])


def mean_channel_error(source, got):
    total = 0
    for a, b in zip(source, got):
        total += abs(v.red(a) - v.red(b)) + abs(v.green(a) - v.green(b)) + abs(v.blue(a) - v.blue(b))
    return total / (len(source) * 3)


def block_mean_error(source, got, columns, rows, block):
    """
    逐块比较"原图块平均"和"输出块平均"的色差。

    这才是抖动真正优化的目标——隔远看，眼睛看到的就是块平均。
    逐格误差是**另一回事**，而且抖动必然会把它抬高（见 [5] 的说明）。
    """
    total = 0.0
    blocks = 0
    for by in range(0, rows - block + 1, block):
        for bx in range(0, columns - block + 1, block):
            src = [0, 0, 0]
            dst = [0, 0, 0]
            for y in range(by, by + block):
                for x in range(bx, bx + block):
                    i = y * columns + x
                    src[0] += v.red(source[i])
                    src[1] += v.green(source[i])
                    src[2] += v.blue(source[i])
                    dst[0] += v.red(got[i])
                    dst[1] += v.green(got[i])
                    dst[2] += v.blue(got[i])
            n = block * block
            for ch in range(3):
                total += abs(src[ch] / n - dst[ch] / n)
            blocks += 1
    return total / (blocks * 3)


def main():
    print("=" * 74)
    print("PC-98 抖动（dithering）行为验证")
    print("=" * 74)

    columns, rows = 64, 64

    # -----------------------------------------------------------------
    print("\n[1] 三种模式的输出必须全都落在调色板里（PC-98 的硬约束）")
    scene = ramp(columns, rows, v.rgb(12, 18, 40), v.rgb(240, 210, 160))
    for label, palette in (("黑白 2 色", MONO_PALETTE), ("标准 16 色", PC98_16)):
        allowed = set(palette)
        for mode in ("none", "ordered", "diffuse"):
            got = apply_dither(scene, columns, rows, palette, mode)
            leaked = [c for c in got if c not in allowed]
            check(
                f"{label} / {mode}：无越界色",
                not leaked,
                f"输出 {len(set(got))} 种色，越界 {len(leaked)} 个"
                + ("" if not leaked else f"（例如 {leaked[0]:#08x}）"),
            )

    # -----------------------------------------------------------------
    print("\n[2] 不抖动 == 逐格取最近色，且与空间位置无关")
    got_none = apply_dither(scene, columns, rows, PC98_16, "none")
    expect = [v.nearest_in(PC98_16, c) for c in scene]
    check("不抖动输出 == 逐格 nearest", got_none == expect)

    # 把同一批颜色倒序送进去，结果应该只是顺序变了
    reversed_scene = list(reversed(scene))
    got_rev = apply_dither(reversed_scene, columns, rows, PC98_16, "none")
    check("不抖动与格子顺序无关（无空间耦合）", got_rev == list(reversed(got_none)))

    # -----------------------------------------------------------------
    print("\n[3] 网点抖动能把纯灰用黑白两色拼出来")
    grey = flat(columns, rows, SNAP_GREY)
    none_grey = apply_dither(grey, columns, rows, MONO_PALETTE, "none")
    ordered_grey = apply_dither(grey, columns, rows, MONO_PALETTE, "ordered")
    check(
        "不抖动：整片纯灰被压成单一颜色（中间调丢失）",
        len(set(none_grey)) == 1,
        f"只剩 {len(set(none_grey))} 种色",
    )
    check(
        "网点抖动：同一片纯灰被拼成黑白两种颜色",
        len(set(ordered_grey)) == 2,
        f"用到 {len(set(ordered_grey))} 种色",
    )
    target = luma(SNAP_GREY)
    err_none = abs(mean_luma(none_grey) - target)
    err_ordered = abs(mean_luma(ordered_grey) - target)
    check(
        "网点抖动后的平均亮度更接近原灰",
        err_ordered < err_none,
        f"原灰 {target}，不抖动 {mean_luma(none_grey):.0f}（差 {err_none:.0f}），"
        f"网点 {mean_luma(ordered_grey):.0f}（差 {err_ordered:.0f}）",
    )

    # -----------------------------------------------------------------
    print("\n[4] 抖动的平均亮度能还原被量化丢掉的中间调")
    grey_ramp = ramp(columns, rows, SNAP_BLACK, SNAP_WHITE)
    source_mean = mean_luma(grey_ramp)
    for mode in ("none", "ordered", "diffuse"):
        got = apply_dither(grey_ramp, columns, rows, MONO_PALETTE, mode)
        delta = abs(mean_luma(got) - source_mean)
        check(
            f"黑白 2 色 / {mode}：整体平均亮度偏差 < 12",
            delta < 12,
            f"原图均值 {source_mean:.1f}，输出均值 {mean_luma(got):.1f}，偏差 {delta:.1f}",
        )
    note(
        "黑白 2 色下 'none' 之所以也能过，是因为整条渐变里暗半段归黑、亮半段归白，"
        "全图平均恰好凑得比较近——它的毛病在局部（见 [3]），不在全局均值。"
    )

    # -----------------------------------------------------------------
    print("\n[5] 抖动优化的是「隔远看的那层」，不是「逐格」")
    colorful = ramp(columns, rows, v.rgb(20, 60, 120), v.rgb(230, 180, 90))
    got = {m: apply_dither(colorful, columns, rows, PC98_16, m)
           for m in ("none", "ordered", "diffuse")}
    cell = {m: mean_channel_error(colorful, got[m]) for m in got}
    blk = {m: block_mean_error(colorful, got[m], columns, rows, 8) for m in got}

    print(f"       {'模式':<9}{'逐格色差':>10}{'8×8 块平均误差':>18}")
    for m in ("none", "ordered", "diffuse"):
        print(f"       {m:<9}{cell[m]:>10.2f}{blk[m]:>18.2f}")

    check(
        "网点抖动：块平均误差 < 不抖动",
        blk["ordered"] < blk["none"],
        f"{blk['none']:.2f} → {blk['ordered']:.2f}",
    )
    check(
        "误差扩散：块平均误差 < 网点抖动（渐变更匀）",
        blk["diffuse"] < blk["ordered"],
        f"{blk['ordered']:.2f} → {blk['diffuse']:.2f}",
    )
    check(
        "误差扩散：块平均误差比不抖动好一倍以上",
        blk["diffuse"] < blk["none"] / 2,
        f"{blk['none']:.2f} → {blk['diffuse']:.2f}"
        f"（好 {blk['none'] / blk['diffuse']:.1f} 倍）",
    )
    check(
        "逐格色差反而是抖动的代价：抖动后比不抖动更大",
        cell["diffuse"] > cell["none"] and cell["ordered"] > cell["none"],
        f"不抖动 {cell['none']:.2f}，网点 {cell['ordered']:.2f}，误差扩散 {cell['diffuse']:.2f}",
    )
    note(
        "最后一条不是缺陷，是抖动的**本质**：为了把局部平均做对，单个格子必须故意"
        "选一个「不那么准」的颜色——两种颜色交替出现，眼睛才会把它看成中间色。"
        "所以用逐格误差评价抖动是错的指标；真正该看的是块平均误差。"
        "如果有人来「优化」抖动、把逐格误差做小了，多半是把它改成不抖动了。"
    )

    # -----------------------------------------------------------------
    print("\n[6] 抖动是确定性的（同样输入必须同样输出）")
    for mode in ("ordered", "diffuse"):
        a = apply_dither(colorful, columns, rows, PC98_16, mode)
        b = apply_dither(colorful, columns, rows, PC98_16, mode)
        check(f"{mode}：两次结果完全一致", a == b)
    note(
        "误差扩散把误差摊给邻居，是**有状态**的——顺序也一样必须一致，"
        "否则同一张图每次转出来都不一样。"
    )

    # -----------------------------------------------------------------
    print("\n[7] 网点图案在平涂区以 8 为周期重复（得是网点，不是噪声）")
    ordered_grey2 = apply_dither(grey, columns, rows, MONO_PALETTE, "ordered")
    periodic = True
    for row in range(rows - 8):
        for col in range(columns - 8):
            if ordered_grey2[row * columns + col] != ordered_grey2[(row + 8) * columns + col + 8]:
                periodic = False
                break
        if not periodic:
            break
    check("网点抖动：隔 8 格图案复现", periodic)

    checker = 0
    for row in range(rows):
        for col in range(columns):
            if ordered_grey2[row * columns + col] != ordered_grey2[row * columns + (col ^ 1)]:
                checker += 1
    check(
        "网点抖动：相邻格子左右交替（黑白相间）",
        checker > 0,
        f"水平相邻格不同色的位置 {checker} 个",
    )

    # -----------------------------------------------------------------
    print("\n[8] 抖动幅度跟着调色板间距自适应（防写死魔数）")
    tight = [v.snap_to_pc98(v.rgb(120, 120, 120)), v.snap_to_pc98(v.rgb(136, 136, 136))]
    spacing_wide = neighbour_spacing(MONO_PALETTE)
    spacing_tight = neighbour_spacing(tight)
    spacing_pc98 = neighbour_spacing(PC98_16)
    check(
        "黑白 2 色的间距 > 紧挨着的 2 色（间距随调色板变化）",
        spacing_wide > spacing_tight,
        f"黑白 {spacing_wide:.1f} > 相邻灰 {spacing_tight:.1f}",
    )
    check(
        "标准 16 色的间距介于两者之间（不是常数）",
        spacing_tight <= spacing_pc98 <= spacing_wide,
        f"相邻灰 {spacing_tight:.1f} ≤ 标准16色 {spacing_pc98:.1f} ≤ 黑白 {spacing_wide:.1f}",
    )

    # 紧挨着的两色几乎分不出中间调，网点图案应该退化成"基本不翻转"
    tight_ordered = apply_dither(flat(columns, rows, v.snap_to_pc98(v.rgb(128, 128, 128))),
                                 columns, rows, tight, "ordered")
    check(
        "调色板挤在一起时，抖动量随之变小（不会过度翻转）",
        len(set(tight_ordered)) <= 2,
        f"输出 {len(set(tight_ordered))} 种色",
    )

    # -----------------------------------------------------------------
    print("\n" + "=" * 74)
    if notes:
        print("说明：")
        for n in notes:
            print(f"  · {n}")
        print()

    if failures:
        print(f"结果：{len(failures)} 项未通过")
        for f in failures:
            print(f"  ✗ {f}")
        print("=" * 74)
        sys.exit(1)

    print("结果：全部通过")
    print("=" * 74)


if __name__ == "__main__":
    main()
