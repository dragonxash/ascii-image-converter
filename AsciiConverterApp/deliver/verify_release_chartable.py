"""校验 release APK 里的 ASCII 字符表与源码字面量完全一致。

与旧版的区别（换电脑迁移时踩过的坑）：
- 旧版把路径写死成 `D:/WorkBuddyWorkSpace/<时间戳>/.relcheck/classes.dex`，
  既依赖一个临时目录（随时会被清理掉），换个目录就彻底跑不起来。
- 现在路径一律「从脚本所在目录逐级向上找」，dex 直接从 deliver/ 里版本号最大的
  release APK 中取，不再需要任何外部临时文件。

用法： python verify_release_chartable.py    （退出码 0 = 通过）
"""
import os
import re
import sys
import zipfile

ANCHOR = b"Bg0MNWQ"  # 字符表尾部锚点
REL_SRC = os.path.join(
    "AsciiConverterApp", "app", "src", "main",
    "java", "com", "dragonxash", "asciiconverter", "core", "AsciiArtConverter.kt",
)


def find_ws_root():
    """从脚本所在目录逐级向上找「含 AsciiConverterApp/app/src/main」的目录。"""
    d = os.path.dirname(os.path.abspath(__file__))
    while True:
        if os.path.isdir(os.path.join(d, "AsciiConverterApp", "app", "src", "main")):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            raise SystemExit("没找到工作区根目录（其下应有 AsciiConverterApp/app/src/main）")
        d = parent


def find_release_apk(ws):
    """取 deliver/ 下版本号最大的 release APK（old/ 里的是归档包，不参与）。"""
    d = os.path.join(ws, "AsciiConverterApp", "deliver")
    best_name, best_key = None, None
    for name in os.listdir(d):
        if not (name.startswith("ASCII") and name.endswith(".apk")):
            continue
        m = re.search(r"(\d+)\.(\d+)\.(\d+)", name)
        if not m:
            continue
        key = tuple(int(x) for x in m.groups())
        if best_key is None or key > best_key:
            best_name, best_key = name, key
    if best_name is None:
        raise SystemExit("在 %s 里没找到 release APK" % d)
    return os.path.join(d, best_name)


def read_chartable_dex(apk):
    """从 APK 的 classes*.dex 里找出含字符表的那个，返回 (条目名, 字节)。"""
    with zipfile.ZipFile(apk) as z:
        names = [n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
        names.sort(key=lambda n: (len(n), n))  # classes.dex 优先
        for n in names:
            data = z.read(n)
            if ANCHOR in data:
                return n, data
    raise SystemExit("%s 的所有 dex 里都没找到字符表锚点" % apk)


ws = find_ws_root()
apk = find_release_apk(ws)
src_path = os.path.join(ws, REL_SRC)

print("工作区根目录 :", ws)
print("release APK  :", os.path.relpath(apk, ws))

# 1) 从源码里取出 ASCII_CHARS 字面量（去掉 Kotlin 的 \$ 转义）
src = open(src_path, encoding="utf-8").read()
m = re.search(r'const val ASCII_CHARS\s*=\s*"((?:[^"\\]|\\.)*)"', src)
assert m, "源码里没找到 ASCII_CHARS"
src_lit = m.group(1).replace('\\$', '$').replace('\\"', '"').replace('\\\\', '\\')
print("源码字面量长度 :", len(src_lit))

# 2) 从 release dex 里按 ULEB128 长度前缀精确读取该字符串
dex_name, data = read_chartable_dex(apk)
print("字符表所在 dex :", dex_name)
i = data.find(ANCHOR)
assert i > 0, "dex 里没找到字符表尾部锚点"
# 向前回溯到 ULEB128 长度前缀：长度 92 -> 单字节 0x5C
start = i
while start > 0 and 0x20 <= data[start - 1] < 0x7F:
    start -= 1
# data[start] 即 ULEB128 长度前缀字节，内容从 start+1 开始
prefix = data[start]
assert prefix == len(src_lit), "长度前缀 %d != 源码长度 %d" % (prefix, len(src_lit))
dex_lit = data[start + 1:start + 1 + len(src_lit)].decode("ascii")
print("dex  字面量长度 :", len(dex_lit))

# 3) 比对
print()
print("dex   :", repr(dex_lit))
print("源码  :", repr(src_lit))
print()
print("完全一致 :", dex_lit == src_lit)

# 4) 顺带校验 92 项字符级表长度
lvl = re.search(r'ASCII_CHARS_LIGHT_LEVEL[^=]*=\s*doubleArrayOf\((.*?)\)', src, re.S)
assert lvl, "没找到 ASCII_CHARS_LIGHT_LEVEL"
n = len([x for x in lvl.group(1).split(',') if x.strip()])
print("亮度级别表项数 :", n)
print("字符表==级别表 :", len(src_lit) == n)

sys.exit(0 if (dex_lit == src_lit and len(src_lit) == n) else 1)
