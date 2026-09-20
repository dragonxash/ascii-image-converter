# -*- coding: utf-8 -*-
"""
PC-98 画风 —— 零依赖的最小可跑实现（教学用）

把 App 里 `Pc98Quantizer.kt` 做的事，用不到 150 行 Python 从头写一遍，
不依赖 Pillow / numpy，只用标准库，直接跑：

    python demo_pc98.py

会在当前目录下生成 4 张 PNG（320×200）：

    对比-1-原图.png        原始渐变画面
    对比-2-不抖动.png      量化到 16 色，每格直接取最近色
    对比-3-网点抖动.png    Bayer 8×8 有序抖动
    对比-4-误差扩散.png    Floyd–Steinberg 误差扩散

外加 4 张 `放大-*.png`（把左上角 48×48 放大 6 倍），
用来把抖动的"网点/噪点"颗粒看个清楚。

整条流水线只有四步：
    缩放 → 量化（挑 16 色）→ 吸附到 12 位 → 抖动（把颜色铺到每格）

约定：数字 0xRRGGBB 表示一个颜色，和 Android 的 Color 一样。
"""

import zlib
import struct
import math

WIDTH, HEIGHT = 320, 200

PALETTE_SIZE = 16      # PC-98 同屏色数
CHANNEL_LEVELS = 16    # 每通道 4 bit = 16 级
CHANNEL_STEP = 17      # 15 × 17 = 255

# 标准 Bayer 8×8 阈值矩阵，取值 0..63
BAYER_8 = [
    0, 32,  8, 40,  2, 34, 10, 42,
    48, 16, 56, 24, 50, 18, 58, 26,
    12, 44,  4, 36, 14, 46,  6, 38,
    60, 28, 52, 20, 62, 30, 54, 22,
    3, 35, 11, 43,  1, 33,  9, 41,
    51, 19, 59, 27, 49, 17, 57, 25,
    15, 47,  7, 39, 13, 45,  5, 37,
    63, 31, 55, 23, 61, 29, 53, 21,
]


# --------------------------------------------------------------------------
# 颜色的基本操作
# --------------------------------------------------------------------------

def rgb(r, g, b):
    return ((int(r) & 255) << 16) | ((int(g) & 255) << 8) | (int(b) & 255)


def red(c):
    return (c >> 16) & 255


def green(c):
    return (c >> 8) & 255


def blue(c):
    return c & 255


def clamp(x):
    """和 Kotlin 的 .toInt().coerceIn(0,255) 对齐：向零截断再夹紧。"""
    return max(0, min(255, int(x)))


def distance(a, b):
    """
    两个颜色的「感知距离」。注意权重不对称：绿 ×6、红 ×3、蓝 ×1。

    人眼对绿色最敏感、蓝色最不敏感，如果三通道等权（欧氏距离），
    挑出来的"最近色"经常看着不对——把一个偏黄的颜色判成偏红了。
    这组权重是 BT.601 亮度公式（0.299 / 0.587 / 0.114）的近似。
    """
    dr = red(a) - red(b)
    dg = green(a) - green(b)
    db = blue(a) - blue(b)
    return dr * dr * 3 + dg * dg * 6 + db * db


def nearest_in(palette, color):
    """调色板里离 color 最近的那个色。取严格小于，所以并列时保留先出现的。"""
    best = palette[0]
    best_d = distance(best, color)
    for p in palette[1:]:
        d = distance(p, color)
        if d < best_d:
            best_d = d
            best = p
    return best


# --------------------------------------------------------------------------
# 第一步：把颜色吸附到 PC-98 的 12 位精度
# --------------------------------------------------------------------------

def snap_channel(v):
    """
    把一个 0..255 的通道值吸到 16 级上，结果一定是 17 的倍数。

        0..8   → 0      9..25  → 17     26..42 → 34    ...
        247+   → 255

    这就是 PC-98 那 4096 色（4 bit × 3 通道）的真实精度。
    """
    return ((v * (CHANNEL_LEVELS - 1) + 127) // 255) * CHANNEL_STEP


def snap_to_pc98(c):
    return rgb(snap_channel(red(c)), snap_channel(green(c)), snap_channel(blue(c)))


# --------------------------------------------------------------------------
# 第二步：量化（中位切分 median cut）—— 从画面里挑 16 个代表色
# --------------------------------------------------------------------------

def widest_channel_range(bucket):
    """@return (通道下标, 该通道上的最大差值)，通道 0/1/2 = 红/绿/蓝。"""
    widest_channel, widest = 0, -1
    for ch in range(3):
        vals = [red(c) if ch == 0 else (green(c) if ch == 1 else blue(c)) for c in bucket]
        span = max(vals) - min(vals)
        if span > widest:
            widest, widest_channel = span, ch
    return widest_channel, widest


def snapped_color_count(bucket, cap=8):
    """
    桶里有几种不同的「12 位量化色」。

    只看 8 种就够排序用了——早期每个桶都含成百上千种色，全量去重纯属浪费。
    """
    seen = set()
    for c in bucket:
        seen.add(snap_to_pc98(c))
        if len(seen) >= cap:
            return cap
    return len(seen)


def average_of(bucket):
    """桶的平均色，顺便吸附到 12 位精度。"""
    n = len(bucket)
    r = sum(red(c) for c in bucket) // n
    g = sum(green(c) for c in bucket) // n
    b = sum(blue(c) for c in bucket) // n
    return snap_to_pc98(rgb(r, g, b))


def quantize(colors, max_colors=PALETTE_SIZE):
    """
    中位切分：反复把最该劈的那个桶，沿最宽的通道从中间劈成两半。

    两个**非常容易写错**的地方，写错了不会报错，只会白白浪费色位
    （16 个槽位最终只给出 6 种颜色）：

    1. 判停看「不同颜色的个数」，不看「桶的个数」。
       平均色会被吸附到 12 位网格上，两个不同的桶很容易撞到同一格点。
    2. 选桶要按「桶内还有几种量化色」挑，不能只看原始通道跨度。
       否则会挑到「跨度很大、但量化后只有一种色」的桶，白劈一次。

    大块平涂的画面最容易踩这两个坑——而那正是 PC-98 CG 的典型长相。
    """
    if not colors:
        return []

    buckets = [list(colors)]
    palette = [average_of(buckets[0])]
    guard = max_colors * 4          # 兜底，别让循环跑飞

    while len(set(palette)) < max_colors and guard > 0:
        guard -= 1

        # 选一个最该劈的桶
        target, best_distinct, best_span = -1, 1, 0
        for i, bucket in enumerate(buckets):
            if len(bucket) < 2:
                continue
            distinct = snapped_color_count(bucket)
            if distinct < 2:
                continue
            span = widest_channel_range(bucket)[1]
            if distinct > best_distinct or (distinct == best_distinct and span > best_span):
                target, best_distinct, best_span = i, distinct, span

        if target < 0:
            break                   # 剩下的桶劈开也分不出新颜色了

        bucket = buckets[target]
        channel = widest_channel_range(bucket)[0]
        key = (lambda c: red(c)) if channel == 0 else (
            (lambda c: green(c)) if channel == 1 else (lambda c: blue(c)))
        ordered = sorted(bucket, key=key)
        mid = len(ordered) // 2

        low, high = ordered[:mid], ordered[mid:]
        buckets[target] = low
        buckets.append(high)
        palette[target] = average_of(low)
        palette.append(average_of(high))

    # 去重，并且保持出现顺序
    out = []
    for c in palette:
        if c not in out:
            out.append(c)
    return out


# --------------------------------------------------------------------------
# 第三步：抖动 —— 用 16 种颜色「拼」出中间色
# --------------------------------------------------------------------------

def neighbour_spacing(palette):
    """
    调色板里每个色到「最近另一个色」的平均距离（按通道平均），再取一半。

    这就是网点抖动的偏移幅度。取一半的道理：偏移在 ±半步长时，
    正好能把一个中间值推到相邻那一色上去。

    **不写死成常数**，是为了换任何一套 16 色都能自适应：
    只有黑白时间距 127.5（可以大胆翻转），标准 16 色是 42.5，
    两个挨着的灰只有 8.5（本来就分不出中间调，几乎不该翻转）。
    """
    if len(palette) < 2:
        return 0.0
    total = 0.0
    for i, a in enumerate(palette):
        best = min(
            math.sqrt(
                (red(a) - red(b)) ** 2 + (green(a) - green(b)) ** 2 + (blue(a) - blue(b)) ** 2
            ) / math.sqrt(3.0)
            for j, b in enumerate(palette) if i != j
        )
        total += best
    return total / len(palette) * 0.5


def dither_none(colors, w, h, palette):
    """不抖动：每格直接取最近色。色块感最强，渐变会出现明显色阶断层。"""
    return [nearest_in(palette, c) for c in colors]


def dither_ordered(colors, w, h, palette):
    """
    网点抖动（Bayer 8×8）。

    思路：给每一格按它在 8×8 网格里的位置，加一个**固定的**偏移，再取最近色。
    偏移是固定的 → 图案是规则的网点，不是噪点（这正是"印刷网点"的观感来源）。
    """
    amp = neighbour_spacing(palette)
    out = [0] * len(colors)
    for y in range(h):
        for x in range(w):
            i = y * w + x
            # Bayer 取值 0..63 → 归一化到 -0.5 .. +0.5
            t = (BAYER_8[(y % 8) * 8 + (x % 8)] + 0.5) / 64.0 - 0.5
            shift = t * amp
            c = colors[i]
            adjusted = rgb(clamp(red(c) + shift), clamp(green(c) + shift), clamp(blue(c) + shift))
            out[i] = nearest_in(palette, adjusted)
    return out


def dither_diffuse(colors, w, h, palette):
    """
    误差扩散（Floyd–Steinberg）。

    思路：把「这一格本来想显示的颜色」和「实际给的颜色」之间的差，
    按权重摊给右边和下一行的邻居，让渐变更平滑。代价是多了点噪。

    权重用经典的那组：右 7/16、左下 3/16、下 5/16、右下 1/16。

    注意用**浮点缓冲**：误差必须能带上小数。
    用整数的话每一格的误差都被截断，几行之后误差就抹平了，等于没扩散。
    """
    out = [0] * len(colors)
    buf = [0.0] * (len(colors) * 3)
    for i, c in enumerate(colors):
        buf[i * 3] = red(c)
        buf[i * 3 + 1] = green(c)
        buf[i * 3 + 2] = blue(c)

    def spread(x, y, er, eg, eb, weight):
        if x < 0 or x >= w or y < 0 or y >= h:
            return
        base = (y * w + x) * 3
        buf[base] += er * weight
        buf[base + 1] += eg * weight
        buf[base + 2] += eb * weight

    for y in range(h):
        for x in range(w):
            i = y * w + x
            base = i * 3
            want = rgb(clamp(buf[base]), clamp(buf[base + 1]), clamp(buf[base + 2]))
            got = nearest_in(palette, want)
            out[i] = got
            er = red(want) - red(got)
            eg = green(want) - green(got)
            eb = blue(want) - blue(got)
            spread(x + 1, y, er, eg, eb, 7 / 16)
            spread(x - 1, y + 1, er, eg, eb, 3 / 16)
            spread(x, y + 1, er, eg, eb, 5 / 16)
            spread(x + 1, y + 1, er, eg, eb, 1 / 16)
    return out


# --------------------------------------------------------------------------
# 零依赖 PNG 写入
# --------------------------------------------------------------------------

def write_png(path, width, height, pixels):
    """pixels 是扁平的 0xRRGGBB 列表。手写 PNG，不需要 Pillow。"""
    raw = bytearray()
    for y in range(height):
        raw.append(0)                       # 每行的 filter byte
        row = pixels[y * width:(y + 1) * width]
        for c in row:
            raw.append(red(c))
            raw.append(green(c))
            raw.append(blue(c))
    data = zlib.compress(bytes(raw), 9)

    def chunk(tag, payload):
        body = tag + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(
            ">I", zlib.crc32(body) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
    png += chunk(b"IDAT", data)
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def upscale(colors, w, h, crop_w, crop_h, factor):
    """把左上角 crop_w×crop_h 的区域按最近邻放大 factor 倍，好看清颗粒。"""
    out = []
    for y in range(crop_h * factor):
        for x in range(crop_w * factor):
            out.append(colors[(y // factor) * w + (x // factor)])
    return out, crop_w * factor, crop_h * factor


# --------------------------------------------------------------------------
# 造一张测试画面
# --------------------------------------------------------------------------

def make_scene(w, h):
    """
    一张"像 PC-98 CG"的合成画面：天空渐变 + 地平线剪影 + 一点平涂色块。

    刻意做成**同时包含**平滑渐变和平涂区域：
    - 渐变区考验抖动（16 色直接画会断成一条条色带）
    - 平涂区考验量化（就是那个"16 个槽位只给出 6 种颜色"的坑）
    """
    out = []
    horizon = int(h * 0.62)
    for y in range(h):
        for x in range(w):
            u = x / (w - 1)          # 0..1 横向
            v = y / (h - 1)          # 0..1 纵向
            if y < horizon:
                # 天空：黄昏渐变，从深蓝紫到橙金
                t = y / horizon
                r = 30 + (245 - 30) * (t ** 1.6)
                g = 40 + (170 - 40) * (t ** 1.9)
                b = 95 + (70 - 95) * (t ** 0.7)
                # 叠一层横向的色相偏移，让渐变是二维的
                r += 25 * math.sin(u * math.pi)
                b += 20 * (1 - u)
            else:
                # 地面：几块平涂
                band = (y - horizon) * 5 // (h - horizon)
                base = [(28, 24, 46), (46, 34, 58), (24, 30, 52), (60, 40, 50), (18, 20, 34)][
                    min(band, 4)]
                r, g, b = base
            # 一点平滑的光晕，让画面不那么"数学"
            d = math.hypot(u - 0.72, v - 0.30)
            glow = max(0.0, 1.0 - d * 2.4)
            r += glow * 70
            g += glow * 45
            b += glow * 20
            out.append(rgb(clamp(r), clamp(g), clamp(b)))
    return out


def distinct_count(colors):
    return len(set(colors))


def mean_channel_error(src, got):
    total = sum(
        abs(red(a) - red(b)) + abs(green(a) - green(b)) + abs(blue(a) - blue(b))
        for a, b in zip(src, got)
    )
    return total / (len(src) * 3)


def block_mean_error(src, got, w, h, block):
    """
    块平均误差 —— 抖动**该**用这个指标评价。

    把原图和输出都按 block×block 求块内平均，再比块平均的差。
    隔远看，眼睛看到的就是块平均。
    """
    total, blocks = 0.0, 0
    for by in range(0, h - block + 1, block):
        for bx in range(0, w - block + 1, block):
            s = [0, 0, 0]
            d = [0, 0, 0]
            for y in range(by, by + block):
                for x in range(bx, bx + block):
                    i = y * w + x
                    s[0] += red(src[i]); s[1] += green(src[i]); s[2] += blue(src[i])
                    d[0] += red(got[i]); d[1] += green(got[i]); d[2] += blue(got[i])
            n = block * block
            for ch in range(3):
                total += abs(s[ch] / n - d[ch] / n)
            blocks += 1
    return total / (blocks * 3)


# --------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------

PC98_STANDARD_16 = [snap_to_pc98(rgb(*c)) for c in [
    (0x00, 0x00, 0x00), (0x00, 0x00, 0xAA), (0xAA, 0x00, 0x00), (0xAA, 0x00, 0xAA),
    (0x00, 0xAA, 0x00), (0x00, 0xAA, 0xAA), (0xAA, 0xAA, 0x00), (0xAA, 0xAA, 0xAA),
    (0x55, 0x55, 0x55), (0x55, 0x55, 0xFF), (0xFF, 0x55, 0x55), (0xFF, 0x55, 0xFF),
    (0x55, 0xFF, 0x55), (0x55, 0xFF, 0xFF), (0xFF, 0xFF, 0x55), (0xFF, 0xFF, 0xFF),
]]


def report(source, w, h, palette, tag):
    """跑完三档抖动，打印一张评价表。"""
    results = {
        "不抖动": dither_none(source, w, h, palette),
        "网点抖动": dither_ordered(source, w, h, palette),
        "误差扩散": dither_diffuse(source, w, h, palette),
    }
    print(f"\n  [{tag}] 用了 {len(palette)} 色")
    print(f"      {'模式':<10}{'用了几色':>9}{'逐格色差':>11}{'块平均误差':>12}")
    for name, got in results.items():
        print(
            f"      {name:<10}{distinct_count(got):>9}"
            f"{mean_channel_error(source, got):>11.2f}"
            f"{block_mean_error(source, got, w, h, 8):>12.2f}"
        )
    return results


def main():
    w, h = WIDTH, HEIGHT
    source = make_scene(w, h)

    print("=" * 68)
    print("PC-98 画风流水线演示")
    print("=" * 68)
    print(f"\n画面 {w}×{h} = {w * h} 格，源画面有 {distinct_count(source)} 种不同颜色")

    # 1) 自动取色：从画面里挑 16 个代表色
    auto = quantize(source, PALETTE_SIZE)
    print(f"\n[1] 量化（中位切分）→ 从画面里挑出 {len(auto)} 色")
    for c in auto:
        raw = (red(c), green(c), blue(c))
        ok = all(v % CHANNEL_STEP == 0 for v in raw)
        print(f"      #{c:06X}  RGB{raw}  {'12 位精度 ✓' if ok else '✗ 越界!'}")
    print("\n    这 16 个颜色是**跟着这张图算出来的**，换一张图就完全不一样——")
    print("    PC-98 没有固定色表，当年每个程序都自己往调色板里填。")

    # 2) 三档抖动 × 两套调色板
    print(f"\n[2] 抖动评价（块 = 8×8）")
    auto_results = report(source, w, h, auto, "自动取色 16 色（本 App 的默认）")
    report(source, w, h, PC98_STANDARD_16, "PC-98 标准 16 色（硬件常用那套，固定）")

    print("\n    两点值得注意：")
    print("    · '逐格色差'抖动后是**变大**的，这不是 bug ——")
    print("      抖动为了把局部平均做对，必须让单个格子故意选得'不那么准'。")
    print("    · 标准 16 色那组的块平均误差明显更差：那是一套**为通用场景固定**的颜色，")
    print("      拿它去画一张黄昏照，当然不如按图挑的 16 色。这正是当年 PC-98 画师")
    print("      会为每张图单独选色的原因。")

    # 3) 输出（用自动取色的结果，这是 App 的默认行为）
    out_paths = [("对比-1-原图.png", source)]
    out_paths += list(zip(
        ["对比-2-不抖动.png", "对比-3-网点抖动.png", "对比-4-误差扩散.png"],
        auto_results.values(),
    ))

    print("\n[3] 写出图片（自动取色那组）")
    for path, pixels in out_paths:
        write_png(path, w, h, pixels)
        print(f"      {path}")

    print("\n[4] 写出放大图（左上角 48×48 放大 6 倍，看颗粒）")
    for path, pixels in out_paths:
        big, bw, bh = upscale(pixels, w, h, 48, 48, 6)
        zoom_path = path.replace("对比-", "放大-")
        write_png(zoom_path, bw, bh, big)
        print(f"      {zoom_path}")

    print("\n完成。")


if __name__ == "__main__":
    main()
