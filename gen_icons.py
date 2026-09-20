# -*- coding: utf-8 -*-
"""生成界面图标（描边风格，24x24，白色，由按钮 tint 上色）。

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


OUT = os.path.join(_res_dir(), "drawable")
os.makedirs(OUT, exist_ok=True)

STROKE = "#FFFFFF"


def circle(cx, cy, r):
    return "M{:.2f},{:.2f} a{:.2f},{:.2f} 0 1,0 {:.2f},0 a{:.2f},{:.2f} 0 1,0 {:.2f},0".format(
        cx - r, cy, r, r, 2 * r, r, r, -2 * r
    )


# 每个图标： (文件名, 路径列表, 需要实心填充的点)
ICONS = {
    # 选图：相框 + 小太阳 + 山
    "ic_action_pick": [
        "M4,5.5 h16 v13 h-16 Z",
        "M5.5,16.5 L10,11.5 L13.5,15.2 L16,12.8 L18.5,15.6",
    ],
    # 拍照：机身 + 镜头
    "ic_action_camera": [
        "M3.5,9 h4 L9,6.5 h6 L16.5,9 h4 v10.5 h-17 Z",
        circle(12, 13.8, 3.3),
    ],
    # 批量：四个方块
    "ic_action_batch": [
        "M4,4 h6 v6 h-6 Z",
        "M14,4 h6 v6 h-6 Z",
        "M4,14 h6 v6 h-6 Z",
        "M14,14 h6 v6 h-6 Z",
    ],
    # 存图片：向下箭头 + 托盘
    "ic_action_save": [
        "M12,3.5 v11",
        "M7.5,10 L12,14.5 L16.5,10",
        "M4,19.5 h16",
    ],
    # 分享：三个点连起来
    "ic_action_share": [
        circle(17.5, 5.8, 2.3),
        circle(6.5, 12, 2.3),
        circle(17.5, 18.2, 2.3),
        "M8.6,10.9 L15.4,7.0",
        "M8.6,13.1 L15.4,17.0",
    ],
    # 样式：三条滑杆
    "ic_action_style": [
        "M4,6.5 h16",
        "M4,12 h16",
        "M4,17.5 h16",
        circle(9, 6.5, 2.1),
        circle(15, 12, 2.1),
        circle(8.5, 17.5, 2.1),
    ],
    # 复制：两个叠起来的框
    "ic_action_copy": [
        "M5,8.5 h10.5 v10.5 h-10.5 Z",
        "M8.5,4.5 h11 v11",
    ],
    # 文本：三行
    "ic_action_text": [
        "M4,7 h16",
        "M4,12 h16",
        "M4,17 h10",
    ],
    # 关于：圆圈 + i
    "ic_action_info": [
        circle(12, 12, 8.5),
        "M12,11.3 v5.2",
    ],
    # 关闭：叉
    "ic_action_close": [
        "M6.5,6.5 L17.5,17.5",
        "M17.5,6.5 L6.5,17.5",
    ],
}

# 需要额外实心画的装饰（太阳、i 的点）
DOTS = {
    "ic_action_pick": [(8.6, 9.6, 1.4)],
    "ic_action_info": [(12, 8.1, 1.15)],
}

TEMPLATE = '''<?xml version="1.0" encoding="utf-8"?>
<!-- 由 gen_icons.py 生成 -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">

{paths}
</vector>
'''

STROKE_PATH = '''    <path
        android:fillColor="#00000000"
        android:pathData="{d}"
        android:strokeColor="{color}"
        android:strokeWidth="1.7"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />'''

FILL_PATH = '''    <path
        android:fillColor="{color}"
        android:pathData="{d}" />'''

count = 0
for name, paths in ICONS.items():
    parts = []
    for d in paths:
        parts.append(STROKE_PATH.format(d=d, color=STROKE))
    for (cx, cy, r) in DOTS.get(name, []):
        parts.append(FILL_PATH.format(d=circle(cx, cy, r), color=STROKE))
    xml = TEMPLATE.format(paths="\n\n".join(parts))
    io.open(os.path.join(OUT, name + ".xml"), "w", encoding="utf-8", newline="\n").write(xml)
    count += 1

print("生成 %d 个图标到 %s" % (count, OUT))
