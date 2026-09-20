import re, io, os

# 定位「同时含 repo\ 与 lib-ref\ 的工作区根」——从脚本所在目录逐级向上找，
# 不写死绝对路径（工作区目录名一变、或脚本被拷到别处跑，写死的就废了）。
# 本脚本要读的是**参考实现**（repo\ / lib-ref\，都在工作区根），所以锚点是这两个目录，
# 而不是 AsciiConverterApp\app\src\main。
def find_ws_root():
    d = os.path.dirname(os.path.abspath(__file__))
    for _ in range(8):
        if os.path.isdir(os.path.join(d, "repo")) and os.path.isdir(os.path.join(d, "lib-ref")):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            break
        d = parent
    return r"D:\WorkBuddyWorkSpace\2026-09-15-22-55-59"  # 兜底：老路径

ws = find_ws_root()
kt = io.open(os.path.join(ws, r"repo\app\src\main\java\com\tans\tasciiartplayer\image\AsciiArtConverter.kt"),
             encoding="utf-8").read()
ref = io.open(os.path.join(ws, r"lib-ref\AsciiArtImageFilter.kt"), encoding="utf-8").read()

# 数组的收尾括号前**不保证有换行**：拆分出来的参考文件是一整行写完的，
# 老正则是 `(.*?)\n\s*\)`，参考文件一改排版就整个匹配不上（不是内容不对，是格式变了）。
# 数组元素里不会出现 `)`，所以直接找最近的收尾括号最稳。
ARRAY = r"%s\s*=\s*doubleArrayOf\(([^)]*)\)"

m = re.search(r'const val ASCII_CHARS = "((?:[^"\\]|\\.)*)"', kt)
body = m.group(1)
dec = body.replace("\\$", "$").replace('\\"', '"').replace("\\\\", "\\")
print("kotlin ASCII_CHARS len =", len(dec))
print("kotlin chars =", repr(dec))

# 参考实现里的原始字符串
m2 = re.search(r'asciiChars = "((?:[^"\\]|\\.)*)"', ref)
refbody = m2.group(1)
refdec = refbody.replace("\\$", "$").replace('\\"', '"').replace("\\\\", "\\")
print("reference len =", len(refdec))
print("reference chars =", repr(refdec))
print("MATCH =", dec == refdec)

lv = re.search(ARRAY % "ASCII_CHARS_LIGHT_LEVEL", kt, re.S).group(1)
nums = [float(x.strip()) for x in lv.replace("\n", " ").split(",") if x.strip()]
print("kotlin levels =", len(nums))

lv2 = re.search(ARRAY % "asciiCharsLightLevel", ref, re.S).group(1)
nums2 = [float(x.strip()) for x in lv2.replace("\n", " ").split(",") if x.strip()]
print("reference levels =", len(nums2))

# 比**数值**不比字面量：`0.0` 和 `0.00` 是同一个数，字面比对会把它当成差异报出来
# （曾经就这样误报过一次，差点去"修"一个本来没错的表）。
diff = [i for i, (x, y) in enumerate(zip(nums, nums2)) if x != y]
print("LEVELS MATCH =", not diff and len(nums) == len(nums2))
if diff:
    for i in diff[:10]:
        print(f"  第 {i} 项：本工程 {nums[i]} / 参考 {nums2[i]}")

ok = dec == refdec and not diff and len(nums) == len(nums2) and len(nums) == 92
print()
print("结果：", "一致" if ok else "不一致")
print("说明：字符表与亮度级别表必须和参考实现完全一致——它们直接决定每个格子落哪个字符，")
print("      差一位就会让整幅画整体偏亮或偏暗，而且这种偏差肉眼很难察觉。")
raise SystemExit(0 if ok else 1)
