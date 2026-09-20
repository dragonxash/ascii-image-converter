# -*- coding: utf-8 -*-
"""纯 Python 复刻 AsciiArtConverter 的查表逻辑，验证字符映射表是否正确。"""
import io, math, os, re

def _ws_root():
    """从脚本所在目录逐级向上找工作区根目录（其下含 repo/app/src/main）。"""
    d = os.path.dirname(os.path.abspath(__file__))
    while True:
        if os.path.isdir(os.path.join(d, "repo", "app", "src", "main")):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            raise SystemExit("没找到工作区根目录（其下应有 repo/app/src/main）")
        d = parent


ws = _ws_root()
print("工作区根目录 :", ws)
kt = io.open(os.path.join(ws, "repo", "app", "src", "main", "java", "com", "tans",
                          "tasciiartplayer", "image", "AsciiArtConverter.kt"),
             encoding="utf-8").read()

chars_lit = re.search(r'const val ASCII_CHARS = "((?:[^"\\]|\\.)*)"', kt).group(1)
CHARS = chars_lit.replace("\\$", "$").replace('\\"', '"').replace("\\\\", "\\")
lvl_src = re.search(r"ASCII_CHARS_LIGHT_LEVEL = doubleArrayOf\((.*?)\n\s*\)", kt, re.S).group(1)
LEVELS = [float(x) for x in lvl_src.replace("\n", " ").split(",") if x.strip()]

assert len(CHARS) == len(LEVELS) == 92


def char_index(light: float) -> int:
    """照抄 Kotlin 的 charIndexForLightLevel。"""
    pre, nxt = 0, -1
    for i, lv in enumerate(LEVELS):
        if lv < light:
            pre = i
        if lv > light:
            nxt = i
            break
    if nxt < 0:
        return len(LEVELS) - 1
    return nxt if abs(light - LEVELS[pre]) > abs(light - LEVELS[nxt]) else pre


table = [char_index(i / 255.0) for i in range(256)]
print("luma 0   ->", repr(CHARS[table[0]]), "(应为空格)")
print("luma 128 ->", repr(CHARS[table[128]]))
print("luma 255 ->", repr(CHARS[table[255]]), "(应为 @)")
print("不同字符数 =", len(set(table)))

# 合成一张 48x24 的测试图：左黑右白的水平渐变 + 中间一个白色方块
W, H, COLS = 48, 24, 96
rows = max(1, int(COLS * H / W))
lines = []
for r in range(rows):
    line = []
    for c in range(COLS):
        lum = int(c / (COLS - 1) * 255)
        # 中间挖一个方块，模拟"图案"
        if 8 <= r <= rows - 9 and COLS * 0.42 <= c <= COLS * 0.58:
            lum = 255 - lum
        line.append(CHARS[table[lum]])
    lines.append("".join(line))

out = os.path.join(ws, "deliver", "algorithm-demo.txt")
io.open(out, "w", encoding="utf-8").write("\n".join(lines))
print("\n=== 合成渐变图 + 中间反色方块的转换结果（%d x %d 字符）===" % (COLS, rows))
print("\n".join(lines))
print("\n已写入:", out)
