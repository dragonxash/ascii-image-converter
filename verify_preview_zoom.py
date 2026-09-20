#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
静态校验「预览区能双指缩放」这套东西有没有被改坏。

背景：预览区原来是一个 `ScrollView`，1.4.3 换成了自写的 `ZoomPaneLayout`
（双指缩放 + 单指拖动 + 两轴滚动）。这个替换把几件事同时押上了：

1. **滚动不再是系统给的**。原来「图比屏幕高」能滚，现在靠自己的平移逻辑。
   要是有人把 `preview_pane` 换回 ScrollView、或者把 `canPan()` 写错，
   长图的下半截就再也够不着了——这种坏法不报错，只是"看不见"。
2. **手势会和文字选择打架**。文本预览是 `textIsSelectable`，长按要能选字。
   所以单指拖动必须先过触摸阈值、而且只在内容确实超出视口时才接管。
3. **RTL 会把坐标假设掀翻**。`supportsRtl="true"` 的前提下，`FrameLayout` 默认的
   `Gravity.START` 在阿拉伯语里会解析成右对齐，内容直接跑出屏幕。
4. **角标是唯一的"退出放大"入口**（双击不能用，那是选词）。它没了，用户就被困在放大状态。
5. **惯性滑动的起始位置**。`OverScroller.fling()` 的头两个参数是起始滚动位置，不是"用不到"
   的占位。1.4.3 在这儿写了 `0, 0`（见 `更新说明-1.4.4.md`），结果松手第一帧就把平移量按回 0，
   用户看到的是「一挪动就跳回左上角」。滚动的**起点、范围、驱动方式**任一项写错都是这种
   "不崩但没法用"的坏法，所以三条都钉。

这些都是"改错了也不会崩、只是没法用"的坑，所以钉成结构特征。
不依赖第三方库，直接 `python verify_preview_zoom.py` 跑。
"""

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/apk/res-auto}"


def find_source_root():
    """定位 `app/src/main`。

    这个脚本在**工作区根目录**和 **`deliver/` 交付目录**各放一份，
    所以不能写死 `脚本所在目录 / AsciiConverterApp`——`deliver/` 底下没有这个子目录，
    那一份会直接崩在"读不到文件"。从脚本所在目录逐级往上找最稳。
    """
    here = pathlib.Path(__file__).resolve().parent
    for base in (here, *here.parents):
        candidate = base / "AsciiConverterApp" / "app" / "src" / "main"
        if candidate.is_dir():
            return candidate
    return pathlib.Path(
        r"D:/WorkBuddyWorkSpace/2026-09-15-22-55-59/AsciiConverterApp/app/src/main"
    )


SRC = find_source_root()
JAVA = SRC / "java/com/dragonxash/asciiconverter"
LAYOUT = SRC / "res/layout/activity_main.xml"
DRAWABLE = SRC / "res/drawable/bg_zoom_chip.xml"

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))


def strip_comments(text):
    """剔掉整行注释再比对。

    否则注释里写一句「不要用 ScrollView」就会把 ScrollView 的检查判成失败——
    说明写得越清楚越容易过不了校验。只丢整行注释，行尾注释和字符串都不动。
    """
    kept = []
    in_block = False
    for line in text.splitlines():
        stripped = line.strip()
        if in_block:
            if "*/" in stripped:
                in_block = False
            continue
        if stripped.startswith("/*"):
            if "*/" not in stripped:
                in_block = True
            continue
        if stripped.startswith("//") or stripped.startswith("*"):
            continue
        kept.append(line)
    return "\n".join(kept)


ZOOM = strip_comments((JAVA / "ZoomPaneLayout.kt").read_text(encoding="utf-8"))
MAIN = strip_comments((JAVA / "MainActivity.kt").read_text(encoding="utf-8"))
LAYOUT_TEXT = LAYOUT.read_text(encoding="utf-8")
LAYOUT_NC = strip_comments(LAYOUT_TEXT)


def body_of(source, signature):
    """粗略取函数体：从签名所在行起按大括号配平。"""
    idx = source.find(signature)
    if idx < 0:
        return ""
    start = source.find("{", idx)
    if start < 0:
        return ""
    depth = 0
    for i in range(start, len(source)):
        ch = source[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return source[start : i + 1]
    return ""


# ── 1. 布局：预览容器换成了自写的缩放容器 ───────────────────────────────
root = ET.parse(LAYOUT).getroot()
all_views = []
for node in root.iter():
    all_views.append(node)

pane = None
for node in root.iter():
    if node.get(ANDROID + "id") == "@+id/preview_pane":
        pane = node
        break
check("布局里有 preview_pane", pane is not None)
check(
    "preview_pane 就是 ZoomPaneLayout（不是 ScrollView）",
    pane is not None and pane.tag.endswith("ZoomPaneLayout"),
    pane.tag if pane is not None else "",
)
check(
    "布局里预览区不再有 ScrollView",
    "ScrollView" not in LAYOUT_NC,
    "还残留 ScrollView" if "ScrollView" in LAYOUT_NC else "",
)

# 被缩放的内容：preview_pane 的唯一直接子 View
content = None
if pane is not None:
    children = list(pane)
    check("preview_pane 只有一个直接子 View", len(children) == 1, f"实际 {len(children)} 个")
    content = children[0] if children else None
check(
    "内容子 View 是 FrameLayout",
    content is not None and content.tag == "FrameLayout",
    content.tag if content is not None else "",
)

# ── 2. RTL：内容必须钉在左上角 ─────────────────────────────────────────
gravity = (content.get(ANDROID + "layout_gravity") or "") if content is not None else ""
check(
    "内容的 layout_gravity 是 top|left（不能用 start）",
    "left" in gravity,
    f"实际为「{gravity}」",
)
check(
    "ZoomPaneLayout 里也用代码钉了一次（onFinishInflate）",
    "onFinishInflate" in ZOOM and "Gravity.TOP or Gravity.LEFT" in ZOOM,
)

# ── 3. 测量：match_parent 卡宽度、wrap_content 不设上限 ─────────────────
measure = body_of(ZOOM, "override fun measureChildWithMargins(")
check("实现了 measureChildWithMargins（FrameLayout 量孩子的唯一入口）", bool(measure))
check(
    "宽度按 match_parent / wrap_content 分了两条路",
    "MeasureSpec.EXACTLY" in measure and "MeasureSpec.UNSPECIFIED" in measure,
)
check(
    "wrap_content 的内容不设宽度上限（否则文字会换行，字符画就散了）",
    re.search(r"else \{\s*MeasureSpec\.makeMeasureSpec\(0, MeasureSpec\.UNSPECIFIED\)", measure)
    is not None,
)
check(
    "高度一律不设上限（内容比视口高是常态）",
    measure.count("MeasureSpec.UNSPECIFIED") >= 2,
)

# ── 4. 缩放：捏合 + 焦点不动 + 上下限 ──────────────────────────────────
check("有 ScaleGestureDetector", "ScaleGestureDetector(" in ZOOM)
check(
    "关掉了 quick scale（双击按住拖动）",
    "isQuickScaleEnabled = false" in ZOOM,
)
zoom_to = body_of(ZOOM, "private fun zoomTo(")
check("有带焦点的缩放 zoomTo", bool(zoom_to))
check(
    "缩放时把手指按住的那点固定在原地",
    "focusX - (focusX - offsetX) * ratio" in zoom_to
    and "focusY - (focusY - offsetY) * ratio" in zoom_to,
)
check(
    "缩放倍数被夹在 [MIN_SCALE, MAX_SCALE] 里",
    "coerceIn(MIN_SCALE, MAX_SCALE)" in zoom_to,
)
check("MIN_SCALE 是 1（不能缩到比铺满还小）", "const val MIN_SCALE = 1f" in ZOOM)
check(
    "有倍数上限，别让它无限放大",
    re.search(r"const val MAX_SCALE = [\d.]+f", ZOOM) is not None,
)

# ── 5. 平移：夹取 + 变换 ───────────────────────────────────────────────
check("有 minOffsetX / minOffsetY", "private fun minOffsetX()" in ZOOM and "private fun minOffsetY()" in ZOOM)
check(
    "内容不比视口大时偏移归 0（也就是不滚）",
    "return if (scaled <= width) 0f else width - scaled" in ZOOM
    and "return if (scaled <= height) 0f else height - scaled" in ZOOM,
)
check("偏移量被夹回合法范围", "coerceIn(minOffsetX(), 0f)" in ZOOM and "coerceIn(minOffsetY(), 0f)" in ZOOM)
apply_t = body_of(ZOOM, "private fun applyTransform()")
check(
    "只叠变换、不动布局尺寸",
    "c.scaleX = scale" in apply_t and "c.translationX = offsetX" in apply_t,
)
check(
    "支点放在左上角（平移量才能当坐标用）",
    "c.pivotX = 0f" in apply_t and "c.pivotY = 0f" in apply_t,
)
check(
    "内容尺寸变了会重新夹一次（换图 / 换字号 / 转屏）",
    "clampOffsets()" in body_of(ZOOM, "override fun onLayout("),
)

# ── 6. 手势不能抢走文字选择 ────────────────────────────────────────────
intercept = body_of(ZOOM, "override fun onInterceptTouchEvent(")
check("单指拖动要过触摸阈值才接管", "touchSlop" in intercept)
check(
    "内容滚不动时一律不接管（文本预览的选字、单击定位都靠这个）",
    "if (!canPan())" in intercept,
)
check("两指以上无条件接管（缩放优先）", "ev.pointerCount >= 2" in intercept)
check(
    "第二根手指落下就抢（不然第一根手指在子 View 手里，缩放起不来）",
    "MotionEvent.ACTION_POINTER_DOWN" in intercept,
)

# ── 7. 惯性滑动：不误触发、不依赖 computeScroll、及时回收 ────────────────
check("有 OverScroller", "OverScroller(context)" in ZOOM)
check(
    "惯性滑动自己 postOnAnimation 推，不依赖 computeScroll()",
    "postOnAnimation(flingStep)" in ZOOM and "override fun computeScroll()" not in ZOOM,
)
check(
    "捏过就不甩（捏合时算出来的速度会把画面甩飞）",
    "zoomedInGesture" in ZOOM and "if (zoomedInGesture)" in ZOOM,
)
check(
    "甩动速度只在超过阈值才触发",
    "abs(vx) < minFlingVelocity && abs(vy) < minFlingVelocity" in ZOOM,
)
# `OverScroller.fling()` 的前两个参数是**起始滚动位置**（currX/currY 从它开始）。
# 1.4.3 在这儿写成了 `0, 0`，而 flingStep 里是 `offsetX = scroller.currX`——
# 于是松手第一帧就把平移量按回 0，表现是"一挪动就跳回左上角，再从那儿甩出去"。
# 这个坏法不报错、不崩，只是手感完全不对，必须钉住。
_fling = re.search(r"scroller\.fling\(\s*([^,\n]+),\s*([^,\n]+),", ZOOM)
_fling_start_x = _fling.group(1).strip() if _fling else ""
_fling_start_y = _fling.group(2).strip() if _fling else ""
check(
    "惯性滑动从**当前**平移量起步，不是写死的 0",
    _fling is not None
    and _fling_start_x.startswith("offsetX")
    and _fling_start_y.startswith("offsetY"),
    f"fling 起始 = {_fling_start_x or '?'}, {_fling_start_y or '?'}",
)
_flat = re.sub(r"\s+", " ", ZOOM)
check(
    "惯性滑动的滚动范围 = 当前可平移范围（不能超出夹取区间）",
    "minOffsetX().toInt(), 0, minOffsetY().toInt(), 0" in _flat,
)
check(
    "起新惯性前先撤掉上一轮的回调（否则两个 runnable 叠加，速度翻倍）",
    body_of(ZOOM, "private fun startFling(").count("removeCallbacks(flingStep)") >= 1,
)
dispatch = body_of(ZOOM, "override fun dispatchTouchEvent(")
touch_event = body_of(ZOOM, "override fun onTouchEvent(")
track = body_of(ZOOM, "private fun trackVelocity(")
check("事件从 dispatchTouchEvent 统一喂给检测器和速度追踪", "scaleDetector.onTouchEvent(ev)" in dispatch)
check(
    "速度追踪在 dispatchTouchEvent 里建/喂（被抢之前的事件也要算进去）",
    "trackVelocity(ev)" in dispatch and "trackVelocity(" not in touch_event,
)
check(
    "按下时重新起一个 VelocityTracker，之后逐个喂事件",
    "VelocityTracker.obtain()" in track and "addMovement(ev)" in track,
)
check(
    "VelocityTracker 在抬手 / 取消时回收",
    "releaseVelocityTracker()" in dispatch,
)
check(
    "onDetachedFromWindow 里停掉惯性并回收",
    # 要查 onDetachedFromWindow 的**函数体**：只在整个文件里找 removeCallbacks，
    # startFling 里也有一句，会让这条检查永远为真、形同虚设。
    "onDetachedFromWindow" in ZOOM
    and "removeCallbacks(flingStep)" in body_of(ZOOM, "override fun onDetachedFromWindow("),
)

# ── 8. 走得到的复位入口 ────────────────────────────────────────────────
check("ZoomPaneLayout 有 resetZoom()", "fun resetZoom()" in ZOOM)
check("复位会把倍数和平移一起清掉", "offsetX = 0f" in body_of(ZOOM, "fun resetZoom()"))
check(
    "MainActivity 接了 onScaleChanged",
    "binding.previewPane.onScaleChanged" in MAIN,
)
check(
    "角标点了会复位",
    "binding.zoomResetTv.clicks { binding.previewPane.resetZoom() }" in MAIN,
)
check(
    "倍数小于等于 1 时角标隐藏",
    "if (zoom <= 1.01f)" in MAIN and "binding.zoomResetTv.visibility = View.GONE" in MAIN,
)
check("有 bg_zoom_chip 这个角标背景", DRAWABLE.is_file())

# ── 9. 换源复位、调样式不复位 ─────────────────────────────────────────
load_image = body_of(MAIN, "private fun loadImage(")
import_text = body_of(MAIN, "private fun applyImportedText(")
render = body_of(MAIN, "private fun renderResult(")
check("换图会复位缩放", "resetPreviewZoom()" in load_image)
check("导入文字会复位缩放", "resetPreviewZoom()" in import_text)
check(
    "调样式不复位（先放大看细节、再微调参数，不该被弹回原始大小）",
    "resetPreviewZoom()" not in render,
)
check(
    "renderResult 只碰 previewPane 的可见性和底色",
    "binding.previewPane.visibility = View.VISIBLE" in render
    and "binding.previewPane.setBackgroundColor(options.backgroundColor)" in render,
)

# ── 10. 中英字符串都在，占位符对得上 ───────────────────────────────────
# ── 10. 各语言的字符串都在，占位符对得上 ───────────────────────────────
# 语言列表与 verify_locales.py 保持一致；新增语言时两边都要加。
LOCALES = (
    ("zh", "res/values/strings.xml"),
    ("en", "res/values-en/strings.xml"),
    ("ja", "res/values-ja/strings.xml"),
)
names = {}
for lang, rel in LOCALES:
    tree = ET.parse(SRC / rel).getroot()
    names[lang] = {node.get("name"): (node.text or "") for node in tree.findall("string")}
check(
    "各语言都有 preview_zoom_reset",
    all("preview_zoom_reset" in names[lang] for lang, _ in LOCALES),
    "缺：" + "、".join(lang for lang, _ in LOCALES if "preview_zoom_reset" not in names[lang]),
)
check(
    "preview_zoom_reset 各语言占位符一致",
    all("%1$s" in names[lang].get("preview_zoom_reset", "") for lang, _ in LOCALES),
    "  ".join(f"{lang}「{names[lang].get('preview_zoom_reset', '')}」" for lang, _ in LOCALES),
)
check(
    "各语言字符串条目数一致",
    len({len(v) for v in names.values()}) == 1,
    "  ".join(f"{k}={len(v)}" for k, v in sorted(names.items())),
)

# ── 汇总 ──────────────────────────────────────────────────────────────
passed = sum(1 for _, ok, _ in results if ok)
print(f"预览缩放校验：{passed}/{len(results)} 项通过")
print()
width = max(len(name) for name, _, _ in results)
for name, ok, detail in results:
    mark = "OK  " if ok else "FAIL"
    line = f"  [{mark}] {name.ljust(width)}"
    if detail:
        line += f"  ← {detail}"
    print(line)
print()

failed = [name for name, ok, _ in results if not ok]
if failed:
    print("未通过：")
    for name in failed:
        print("  ·", name)
    sys.exit(1)
print("全部通过：预览区两种模式都能双指放大，且没把文字选择弄坏。")
