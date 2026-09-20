# -*- coding: utf-8 -*-
"""生成自适应图标：点阵字母 A（对应 ASCII 点阵的概念）。

路径从脚本所在目录逐级向上找，换电脑 / 换盘符 / 换目录名后仍可直接运行。
"""
import io, os


def _res_dir():
    """从脚本所在目录逐级向上找工作区，返回 app/src/main/res 目录。"""
    d = os.path.dirname(os.path.abspath(__file__))
    while True:
        candidate = os.path.join(d, "AsciiConverterApp", "app", "src", "main", "res")
        if os.path.isdir(candidate):
            return candidate
        parent = os.path.dirname(d)
        if parent == d:
            raise SystemExit("没找到工作区根目录（其下应有 AsciiConverterApp/app/src/main/res）")
        d = parent


ROOT = _res_dir()

# 5x5 点阵的字母 A
GLYPH = [
    ".###.",
    "#...#",
    "#####",
    "#...#",
    "#...#",
]

SPACING = 11.0
RADIUS = 2.75
LEFT = 32.0
TOP = 32.0
DOT_COLOR = "#4CE38B"


def circle_path(cx, cy, r):
    return (
        "M{cx:.2f},{y0:.2f} "
        "a{r:.2f},{r:.2f} 0 1,0 0,{d:.2f} "
        "a{r:.2f},{r:.2f} 0 1,0 0,-{d:.2f} Z"
    ).format(cx=cx, y0=cy - r, r=r, d=2 * r)


paths = []
for row, line in enumerate(GLYPH):
    for col, ch in enumerate(line):
        if ch == "#":
            cx = LEFT + col * SPACING
            cy = TOP + row * SPACING
            paths.append('        <path\n            android:fillColor="%s"\n            android:pathData="%s" />'
                         % (DOT_COLOR, circle_path(cx, cy, RADIUS)))

vector = '''<?xml version="1.0" encoding="utf-8"?>
<!-- 自适应图标前景层：点阵字母 A。由脚本生成，勿手改坐标。 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

%s

</vector>
''' % "\n".join(paths)

out_dir = os.path.join(ROOT, "drawable")
os.makedirs(out_dir, exist_ok=True)
io.open(os.path.join(out_dir, "ic_launcher_foreground.xml"), "w", encoding="utf-8", newline="\n").write(vector)
print("生成前景矢量：%d 个点" % len(paths))

# 背景层用一个纯色 drawable
bg = '''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="@color/ic_launcher_background" />
</shape>
'''
io.open(os.path.join(out_dir, "ic_launcher_background.xml"), "w", encoding="utf-8", newline="\n").write(bg)

# 自适应图标描述文件（minSdk 26，只需要 anydpi-v26）
mipmap_dir = os.path.join(ROOT, "mipmap-anydpi-v26")
os.makedirs(mipmap_dir, exist_ok=True)
for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    xml = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
'''
    io.open(os.path.join(mipmap_dir, name), "w", encoding="utf-8", newline="\n").write(xml)

print("生成自适应图标完成")
