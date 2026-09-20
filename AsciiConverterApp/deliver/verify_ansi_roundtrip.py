"""独立验证 ASCII 图片转换器的 ANSI 文本格式。

把 Kotlin 那边的量化 / 编码 / 解码逻辑照搬一份到 Python，做三件事：

1. 中位切分量化：颜色数不超上限，且每个通道都落在 PC-98 的 16 级上（17 的倍数）
2. 真彩往返：encode -> decode 后字符和颜色都逐格一致
3. 16 色往返：往返后颜色数 <= 16，且每格颜色的偏差有界
4. SGR 解析：16 色 / 256 色 / 真彩三种写法都能认
"""
import random

ESC = "\x1b"
PALETTE_SIZE = 16

ANSI_16 = [
    0x000000, 0xCD0000, 0x00CD00, 0xCDCD00,
    0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
    0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00,
    0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
]
CUBE_LEVELS = [0, 95, 135, 175, 215, 255]


def red(c):
    return (c >> 16) & 0xFF


def green(c):
    return (c >> 8) & 0xFF


def blue(c):
    return c & 0xFF


def rgb(r, g, b):
    return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF)


def snap_channel(v):
    return ((v * 15 + 127) // 255) * 17


def snap_to_pc98(c):
    return rgb(snap_channel(red(c)), snap_channel(green(c)), snap_channel(blue(c)))


def channel_value(c, ch):
    return red(c) if ch == 0 else (green(c) if ch == 1 else blue(c))


def widest_channel_range(bucket):
    widest_ch, widest = 0, -1
    for ch in range(3):
        values = [channel_value(c, ch) for c in bucket]
        span = max(values) - min(values)
        if span > widest:
            widest, widest_ch = span, ch
    return widest_ch, widest


def average_of(bucket):
    n = len(bucket)
    return snap_to_pc98(rgb(
        sum(red(c) for c in bucket) // n,
        sum(green(c) for c in bucket) // n,
        sum(blue(c) for c in bucket) // n,
    ))


def snapped_colors(bucket):
    """桶里所有颜色量化到 12 位网格后，剩几种不同的。"""
    return {snap_to_pc98(c) for c in bucket}


def quantize(colors, max_colors):
    """中位切分选代表色。

    两个关键点，都是为了"把 16 个色位吃满"：

    1. **判停看不同颜色的个数，不看桶的个数。** 平均色会被 snap_to_pc98 吸到 12 位
       网格上，两个不同的桶很容易撞到同一格点。按桶数判停的话，16 个桶可能只给出
       6 种颜色，白扔 10 个色位。
    2. **选桶时按"桶内还有几种量化色"挑，而不是按原始通道跨度挑。** 只有当被劈的桶
       内部本来就含多种量化色时，劈开才有可能多分出一个色位；按跨度挑会挑到
       "跨度大但量化后只有一种色"的桶，白劈。
    """
    if not colors or max_colors <= 0:
        return []
    buckets = [list(colors)]
    palette = [average_of(buckets[0])]
    # 兜底上限：极端情况下劈分可能一直不增加色数，别让循环跑飞
    guard = max_colors * 4
    while len(set(palette)) < max_colors and guard > 0:
        guard -= 1
        target, best_distinct, best_span = -1, 1, 0
        for i, bucket in enumerate(buckets):
            if len(bucket) < 2:
                continue
            distinct = len(snapped_colors(bucket))
            if distinct < 2:
                continue
            _, span = widest_channel_range(bucket)
            if distinct > best_distinct or (distinct == best_distinct and span > best_span):
                best_distinct, best_span, target = distinct, span, i
        if target < 0:
            break
        bucket = buckets[target]
        ch, _ = widest_channel_range(bucket)
        bucket = sorted(bucket, key=lambda c: channel_value(c, ch))
        mid = len(bucket) // 2
        low, high = bucket[:mid], bucket[mid:]
        buckets[target] = low
        buckets.append(high)
        palette[target] = average_of(low)
        palette.append(average_of(high))
    # 去重：劈不动了就可能出现重复
    out = []
    for c in palette:
        if c not in out:
            out.append(c)
    return out


def distance(a, b):
    dr, dg, db = red(a) - red(b), green(a) - green(b), blue(a) - blue(b)
    return dr * dr * 3 + dg * dg * 6 + db * db


def nearest_in(palette, color):
    return min(palette, key=lambda p: distance(p, color))


def resolve_colors(colors, mode):
    if mode == "true":
        return list(colors)
    palette = quantize(colors, PALETTE_SIZE)
    return [nearest_in(palette, c) for c in colors]


def encode(rows, colors, columns, mode):
    """rows 是字符列表（已按行去掉尾部空格），colors 是扁平数组。"""
    if mode == "plain":
        return "\n".join("".join(r) for r in rows)
    resolved = resolve_colors(colors, mode)
    out, current, cell = [], None, 0
    for r_index, chars in enumerate(rows):
        row_has_color = False
        for c_index, ch in enumerate(chars):
            color = resolved[r_index * columns + c_index]
            if color != current:
                out.append("%s[38;2;%d;%d;%dm" % (ESC, red(color), green(color), blue(color)))
                current = color
                row_has_color = True
            out.append(ch)
            cell += 1
        if row_has_color:
            out.append("%s[0m" % ESC)
            current = None
        if r_index != len(rows) - 1:
            out.append("\n")
    return "".join(out)


def sgr_params_at(raw, start):
    if start + 1 >= len(raw) or raw[start + 1] != "[":
        return None
    i = start + 2
    while i < len(raw):
        ch = raw[i]
        if ch == "m":
            return raw[start + 2:i]
        if not ch.isdigit() and ch != ";":
            return None
        i += 1
    return None


def color_of_256(entry):
    index = max(0, min(255, entry))
    if index < 16:
        return ANSI_16[index]
    if index < 232:
        offset = index - 16
        return rgb(CUBE_LEVELS[offset // 36], CUBE_LEVELS[(offset // 6) % 6], CUBE_LEVELS[offset % 6])
    level = 8 + (index - 232) * 10
    return rgb(level, level, level)


def apply_sgr(params, current):
    if params == "":
        return None
    parts = params.split(";")
    color = current
    i = 0
    while i < len(parts):
        code = int(parts[i]) if parts[i].isdigit() else 0
        if code == 0 or code == 39:
            color = None
        elif 30 <= code <= 37:
            color = ANSI_16[code - 30]
        elif 90 <= code <= 97:
            color = ANSI_16[code - 90 + 8]
        elif code in (38, 48):
            foreground = code == 38
            mode = int(parts[i + 1]) if i + 1 < len(parts) and parts[i + 1].isdigit() else None
            if mode == 5:
                if foreground:
                    color = color_of_256(int(parts[i + 2]) if i + 2 < len(parts) and parts[i + 2].isdigit() else -1)
                i += 2
            elif mode == 2:
                vals = []
                for k in range(2, 5):
                    vals.append(int(parts[i + k]) if i + k < len(parts) and parts[i + k].isdigit() else 0)
                if foreground:
                    color = rgb(*[max(0, min(255, v)) for v in vals])
                i += 4
        i += 1
    return color


def decode(raw):
    if ESC not in raw:
        return None
    lines = []
    text, colors, current, saw = [], [], None, False
    i = 0
    while i < len(raw):
        ch = raw[i]
        if ch == ESC:
            params = sgr_params_at(raw, i)
            if params is not None:
                current = apply_sgr(params, current)
                saw = True
                i += len(params) + 3
                continue
            i += 1
            continue
        if ch == "\n":
            lines.append(("".join(text), list(colors) if saw else None))
            text, colors = [], []
            i += 1
            continue
        if ch == "\r":
            i += 1
            continue
        text.append(ch)
        colors.append(current)
        i += 1
    lines.append(("".join(text), list(colors) if saw else None))
    return lines


def make_grid(columns, rows, seed=7):
    """造一张有渐变也有噪点的"图"，模拟真实照片的颜色分布。"""
    rnd = random.Random(seed)
    colors, chars = [], []
    for r in range(rows):
        for c in range(columns):
            base_r = int(255 * c / max(1, columns - 1))
            base_g = int(255 * r / max(1, rows - 1))
            base_b = 128
            jitter = rnd.randint(-18, 18)
            colors.append(rgb(
                max(0, min(255, base_r + jitter)),
                max(0, min(255, base_g + jitter)),
                max(0, min(255, base_b + jitter)),
            ))
            chars.append(" .:-=+*#%@"[(r * columns + c) % 10])
    rows_list = [chars[r * columns:(r + 1) * columns] for r in range(rows)]
    return rows_list, colors


def main():
    columns, rows = 120, 90
    row_chars, colors = make_grid(columns, rows)
    # 模拟"去掉每行末尾空格"
    trimmed = []
    for line in row_chars:
        end = len(line)
        while end > 0 and line[end - 1] == " ":
            end -= 1
        trimmed.append(line[:end])
    print("网格 %d x %d，共 %d 格" % (columns, rows, columns * rows))

    print("\n=== 1. 中位切分量化 ===")
    palette = quantize(colors, PALETTE_SIZE)
    print("调色板大小: %d（要求 <= %d）" % (len(palette), PALETTE_SIZE))
    distinct_palette = len(set(palette))
    print("调色板里不同颜色数: %d（要求 == %d）" % (distinct_palette, len(palette)))
    off_grid = [c for c in palette if any(v % 17 for v in (red(c), green(c), blue(c)))]
    print("不在 16 级上的通道值: %d 个（要求 0）" % len(off_grid))
    assert len(palette) <= PALETTE_SIZE
    # 关键：槽位不能有重复。平均色会被吸到 12 位网格上，两个桶可能撞到同一格点，
    # 那样 16 个槽位可能只给出 6 种颜色——大块平涂的画面最容易踩这个坑。
    assert distinct_palette == len(palette), "调色板里有重复颜色，色位被浪费了"
    assert not off_grid
    mapped = [nearest_in(palette, c) for c in colors]
    print("映射后实际用到的颜色数: %d" % len(set(mapped)))

    errors = [distance(c, nearest_in(palette, c)) for c in colors]
    worst = max(errors)
    avg = sum(errors) / len(errors)
    # 反推近似的人眼可辨偏差（distance 是加权平方和，开方后除以权重粗估）
    print("量化误差 distance: 最大 %d，平均 %.0f" % (worst, avg))

    print("\n=== 2. 真彩往返 ===")
    encoded = encode(trimmed, colors, columns, "true")
    decoded = decode(encoded)
    assert decoded is not None, "真彩文本应该能被解析出来"
    ok_chars = all(decoded[r][0] == "".join(trimmed[r]) for r in range(rows))
    ok_colors = True
    for r in range(rows):
        line_colors = decoded[r][1]
        for c in range(len(trimmed[r])):
            if line_colors[c] != colors[r * columns + c]:
                ok_colors = False
                break
    print("纯字符文本大小: %d 字节" % len("\n".join("".join(t) for t in trimmed)))
    print("真彩文本大小:   %d 字节（%.1f 倍）"
          % (len(encoded), len(encoded) / max(1, len("\n".join("".join(t) for t in trimmed)))))
    print("字符逐格一致: %s" % ok_chars)
    print("颜色逐格一致: %s" % ok_colors)
    assert ok_chars and ok_colors, "真彩往返必须无损"

    print("\n=== 3. 16 色往返 ===")
    encoded16 = encode(trimmed, colors, columns, "palette16")
    decoded16 = decode(encoded16)
    distinct = set()
    for r in range(rows):
        for v in decoded16[r][1] or []:
            if v is not None:
                distinct.add(v)
    print("16 色文本大小: %d 字节（真彩的 %.0f%%）"
          % (len(encoded16), 100.0 * len(encoded16) / max(1, len(encoded))))
    print("往返后实际颜色数: %d（要求 <= 16）" % len(distinct))
    assert len(distinct) <= PALETTE_SIZE, "16 色模式往返后颜色数超标"
    in_palette = all(c in set(palette) for c in distinct)
    print("颜色都来自量化调色板: %s" % in_palette)
    assert in_palette

    print("\n=== 4. SGR 解析（别处来的转义也要认）===")
    cases = [
        (ESC + "[31m" + "A", "16 色里的红色", ANSI_16[1]),
        (ESC + "[91m" + "A", "亮红", ANSI_16[9]),
        (ESC + "[38;5;196m" + "A", "256 色索引 196", color_of_256(196)),
        (ESC + "[38;5;250m" + "A", "256 色灰度 250", color_of_256(250)),
        (ESC + "[38;2;10;20;30m" + "A", "真彩", rgb(10, 20, 30)),
        (ESC + "[1;38;2;10;20;30m" + "A", "粗体 + 真彩（粗体应被忽略）", rgb(10, 20, 30)),
        (ESC + "[0m" + "A", "重置后无颜色", None),
        (ESC + "[38;2;10;20;30;48;2;1;2;3m" + "A", "真彩前景 + 背景（只取前景）", rgb(10, 20, 30)),
    ]
    all_ok = True
    for raw, label, expect in cases:
        got = decode(raw)[0][1][0]
        ok = got == expect
        all_ok = all_ok and ok
        print("%-28s 期望 %s  实际 %s  %s"
              % (label,
                 "无" if expect is None else "#%06X" % expect,
                 "无" if got is None else "#%06X" % got,
                 "OK" if ok else "FAIL"))
    assert all_ok, "SGR 解析有不符合预期的情况"

    print("\n=== 5. 边界情况 ===")
    tiny = quantize([rgb(1, 2, 3)] * 5, PALETTE_SIZE)
    print("全是同一个颜色时调色板大小: %d（应 >= 1）" % len(tiny))
    assert len(tiny) >= 1
    print("空输入: %s" % quantize([], PALETTE_SIZE))
    assert quantize([], PALETTE_SIZE) == []

    print("\n全部通过。")


if __name__ == "__main__":
    main()
