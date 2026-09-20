"""把 lib-ref/ascii_table.json 转成可直接粘进 Kotlin 的常量字面量。

路径从脚本所在目录逐级向上找，换电脑 / 换目录后可直接运行。
"""
import io, json, os


def _ws_root():
    """从脚本所在目录逐级向上找工作区根目录（其下含 AsciiConverterApp/app/src/main）。"""
    d = os.path.dirname(os.path.abspath(__file__))
    while True:
        if os.path.isdir(os.path.join(d, "AsciiConverterApp", "app", "src", "main")):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            raise SystemExit("没找到工作区根目录（其下应有 AsciiConverterApp/app/src/main）")
        d = parent


WS = _ws_root()
TABLE = os.path.join(WS, "lib-ref", "ascii_table.json")
print("读取 :", TABLE)

d = json.load(io.open(TABLE, encoding="utf-8"))
levels = d["levels"]
idx = d["index"]
chars = d["chars"]
assert len(levels) == len(chars) == 92, (len(levels), len(chars))
assert len(idx) == 256

def kotlin_string(s):
    out = []
    for ch in s:
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "$":
            out.append("\\$")
        else:
            out.append(ch)
    return '"' + "".join(out) + '"'

print("chars literal:")
print(kotlin_string(chars))
print()
print("levels (%d):" % len(levels))
lines = []
row = []
for i, v in enumerate(levels):
    row.append(("%g" % v))
    if len(row) == 12:
        lines.append("            " + ", ".join(row) + ",")
        row = []
if row:
    lines.append("            " + ", ".join(row) + ",")
print("\n".join(lines))
print()
print("index (256):")
lines = []
row = []
for i, v in enumerate(idx):
    row.append(str(v))
    if len(row) == 16:
        lines.append("            " + ", ".join(row) + ",")
        row = []
if row:
    lines.append("            " + ", ".join(row) + ",")
print("\n".join(lines))
