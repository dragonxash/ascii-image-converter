#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
静态校验「外部 Uri 只读一次」这条规矩有没有被破坏。

背景：相册（照片选择器）给的 `content://media/picker/...` 是**临时授权**，
同一个 Uri 反复开就会 `open failed: ENOENT`；而文件选择器（SAF）给的是
**可持久化授权**，开多少次都行。所以"文件浏览器正常、系统相册报错"这类
报错，根子几乎都是"每次转换都拿原始 Uri 重新解码一遍"。

修法是：选图时由 SourceCache 把 Uri 读一次落成本地文件，之后一律读本地。
这个脚本没法跑 Android 代码，但可以把那条规矩的**结构特征**钉死：
谁还能碰 Uri、谁只能碰 File、重试和兜底还在不在。

不依赖任何第三方库，直接 `python verify_source_pipeline.py` 跑。
"""

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

def find_source_root():
    """定位 `app/src/main`。

    这个脚本在**工作区根目录**和 **`deliver/` 交付目录**各放一份，
    所以不能写死 `脚本所在目录 / AsciiConverterApp`——`deliver/` 底下没有这个子目录，
    那一份会直接崩在"读不到文件"。从脚本所在目录逐级往上找最稳。
    （原来这里是硬编码绝对路径，工作区一改名就废，改成向上查找。）
    """
    here = pathlib.Path(__file__).resolve().parent
    for base in (here, *here.parents):
        candidate = base / "AsciiConverterApp" / "app" / "src" / "main"
        if candidate.is_dir():
            return candidate
    return pathlib.Path(
        r"D:/WorkBuddyWorkSpace/2026-09-15-22-55-59/AsciiConverterApp/app/src/main"
    )


SRC_ROOT = find_source_root()
JAVA = SRC_ROOT / "java/com/dragonxash/asciiconverter"
CORE = JAVA / "core"

results = []


def strip_comments(text):
    """把注释行去掉再比对。

    不这么做的话，"不要写成 X" 这种注释本身会把 X 的检查判成失败——
    等于把说明写清楚反而过不了校验。这里只丢整行的注释（`//`、`/*`、`*` 开头），
    行尾注释和字符串里的 `content://` 都不动。
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


MAIN = strip_comments((JAVA / "MainActivity.kt").read_text(encoding="utf-8"))
BATCH = strip_comments((JAVA / "BatchActivity.kt").read_text(encoding="utf-8"))
APP = strip_comments((JAVA / "App.kt").read_text(encoding="utf-8"))

DECODER = strip_comments((CORE / "PictureDecoder.kt").read_text(encoding="utf-8"))
STREAMS = strip_comments((CORE / "ContentStreams.kt").read_text(encoding="utf-8"))
CACHE = strip_comments((CORE / "SourceCache.kt").read_text(encoding="utf-8"))
THUMB = strip_comments((CORE / "ThumbnailLoader.kt").read_text(encoding="utf-8"))


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))


def count(haystack, needle):
    return haystack.count(needle)


def body_of(source, signature):
    """粗略取出某个函数体：从签名所在行起，按大括号配平截到对应收尾。"""
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


# ── 1. 解码器只认 File，从类型上堵死"再回去读一次 provider" ──────────────
bad_decode = re.findall(r"fun decode\([^)]*Uri[^)]*\)", DECODER)
check("PictureDecoder 没有吃 Uri 的 decode 重载", not bad_decode, str(bad_decode))
check(
    "PictureDecoder 自己不碰 ContentResolver 的流",
    "openInputStream" not in DECODER
    and "openFileDescriptor" not in DECODER
    and "copyTo" not in DECODER,
)
check(
    "PictureDecoder.decode(file) 存在且是 File 参数",
    re.search(r"fun decode\(file: File", DECODER) is not None,
)

# ── 2. 主界面：转换只读本地文件 ────────────────────────────────────────
check("MainActivity 不再持有 sourceUri", "sourceUri" not in MAIN)
check("MainActivity 改持 sourceFile", "sourceFile" in MAIN)

do_convert = body_of(MAIN, "private suspend fun doConvert()")
check("doConvert 取到的是本地文件", "sourceFile ?: return" in do_convert)
check("doConvert 用本地文件解码", "PictureDecoder.decode(file)" in do_convert)
check(
    "doConvert 不再直接开 provider",
    "openInputStream" not in do_convert and "contentResolver" not in do_convert,
)
check(
    "MainActivity 全程不直接开 provider 的流",
    "contentResolver.openInputStream" not in MAIN
    and "contentResolver.openFileDescriptor" not in MAIN,
)

# 取消上一个转换不能弹错误框（拖滑杆会狂取消）
check(
    "doConvert 把 CancellationException 原样抛出",
    "catch (e: CancellationException)" in do_convert,
)

# ── 3. 落盘只发生一次，且在选图那一刻 ──────────────────────────────────
check(
    "MainActivity 只有一处 materialize 调用",
    count(MAIN, "SourceCache.materialize") == 1,
    f"实际 {count(MAIN, 'SourceCache.materialize')} 处",
)
check(
    "materialize 在 loadImage 里（选图即落盘）",
    "SourceCache.materialize" in body_of(MAIN, "private fun loadImage("),
)
check(
    "落盘在转换之前触发",
    body_of(MAIN, "private fun loadImage(").find("SourceCache.materialize")
    < body_of(MAIN, "private fun loadImage(").find("scheduleConvert(0L)"),
)
check(
    "BatchActivity 只有一处 materialize 调用",
    count(BATCH, "SourceCache.materialize") == 1,
    f"实际 {count(BATCH, 'SourceCache.materialize')} 处",
)
check(
    "批量页退出时清掉中转文件",
    "SourceCache.clear(applicationContext)" in BATCH,
)
check("冷启动清理残留", "SourceCache.sweepStale" in APP)

# ── 4. 批量页与缩略图也走本地文件 ──────────────────────────────────────
check(
    "BatchActivity 不再用 Uri 解码",
    re.search(r"PictureDecoder\.decode\(applicationContext", BATCH) is None,
)
check(
    "BatchActivity 不再用 Uri 取缩略图",
    re.search(r"ThumbnailLoader\.load\(ctx,", BATCH) is None,
)
check(
    "BatchActivity 的转换入口收 File",
    re.search(r"fun convertOne\(file: File\)", BATCH) is not None,
)
check("缩略图加载器只认 File", re.search(r"fun load\(file: File,", THUMB) is not None)
check("缩略图加载器不碰 ContentResolver", "contentResolver" not in THUMB)
check(
    "缩略图降采样不再提前一轮停手",
    "while (max(width, height) / sampleSize > maxSizePx)" in THUMB
    and "/ (sampleSize * 2) >= maxSizePx" not in THUMB,
)

# ── 5. ContentStreams 的重试、兜底、诊断还在 ───────────────────────────
check("有整体重试的等待节奏", "RETRY_DELAYS_MS" in STREAMS)
check("重试会判断值不值得", "fun worthRetrying()" in STREAMS)
check(
    "权限类失败直接放弃重试",
    "if (cause !is SecurityException)" in STREAMS,
)
check("相册 Uri 有 MediaStore 兜底", "mediaStoreCandidates" in STREAMS)
check(
    "兜底同时试 Images 和 Files 两处",
    "MediaStore.Images.Media.EXTERNAL_CONTENT_URI" in STREAMS
    and 'MediaStore.Files.getContentUri("external")' in STREAMS,
)
check("失败时会给来源探测信息", "private fun probe(" in STREAMS)
check("探测会看 is_pending / _data 这类关键列", "is_pending" in STREAMS and "_data" in STREAMS)
check(
    "open() 会把数据流原样交出去，不被 use{} 提前关掉",
    "closeStream = false" in STREAMS,
)
check(
    "fd 用 AutoCloseInputStream，不再泄漏 ParcelFileDescriptor",
    "ParcelFileDescriptor.AutoCloseInputStream" in STREAMS
    and "FileInputStream(pfd.fileDescriptor)" not in STREAMS,
)
check("失败信息里带「怎么办」的指引", "怎么办：" in STREAMS)

# ── 6. 落盘只有 SourceCache 一个人干 ───────────────────────────────────
callers = []
for path in sorted(JAVA.rglob("*.kt")):
    text = path.read_text(encoding="utf-8")
    if "ContentStreams.copyTo" in text:
        callers.append(path.name)
check(
    "copyTo 只被 SourceCache 调用",
    callers == ["SourceCache.kt"],
    "、".join(callers) or "无人调用",
)

# ── 7. 状态保存靠文件路径，不靠会过期的 Uri ────────────────────────────
check("状态里不再存 Uri", "KEY_URI" not in MAIN)
check("状态里存的是文件路径", "KEY_FILE" in MAIN and "sourceFile?.absolutePath" in MAIN)
check(
    "恢复时先确认文件还在",
    "SourceCache.existing(state.getString(KEY_FILE))" in MAIN,
)

# ── 8. 读不出来的提示要有替代出口 ──────────────────────────────────────
check("有 reportSourceError", "private fun reportSourceError(" in MAIN)
check(
    "提示里带「换文件浏览器重选」按钮",
    "R.string.source_retry_with_file" in MAIN,
)

# ── 9. 批量页必须有「文件浏览器」这第二条选图通道 ───────────────────────
# 只留相册一个入口的后果很实在：相册这条通道一挂，整个批量功能就是死路一条。
check(
    "批量页有 SAF 多选入口",
    "ActivityResultContracts.OpenMultipleDocuments" in BATCH,
)
check(
    "SAF 入口真被挂到选图流程上",
    "private fun launchFilePick()" in BATCH and "pickFileLauncher.launch" in BATCH,
)
check(
    "两条通道由用户二选一，不替用户拍板",
    "private fun showPickChooser()" in BATCH
    and "R.string.batch_pick_gallery" in BATCH
    and "R.string.batch_pick_file" in BATCH,
)
check(
    "菜单点「选图」走的是二选一",
    re.search(r"R\.id\.action_pick -> \{\s*showPickChooser\(\)", BATCH) is not None,
)
check(
    "菜单里不再直接调起相册",
    re.search(r"R\.id\.action_pick -> \{[^}]*pickLauncher\.launch", BATCH) is None,
)
check(
    "SAF 拿到的也是可持久化读权限",
    "FLAG_GRANT_PERSISTABLE_URI_PERMISSION" in BATCH
    and "takePersistableUriPermission" in BATCH,
)
check(
    "整批失败时会给出换道的出口",
    "private fun showAllFailedDialog()" in BATCH
    and "R.string.batch_all_failed_title" in BATCH
    and "R.string.source_retry_with_file" in BATCH,
)
check(
    "换道前把注定读不出来的项清掉",
    re.search(
        r"private fun showAllFailedDialog\(\).*?setPositiveButton\(R\.string\.source_retry_with_file\).*?clearAll\(\).*?launchFilePick\(\)",
        BATCH,
        re.S,
    )
    is not None,
)
check(
    "文件浏览器多选也有张数封顶",
    "MAX_PICK_COUNT - items.size" in BATCH and "uris.take(room)" in BATCH,
)
check(
    "两条通道都兜住了调起失败",
    "private fun launchGalleryPick()" in BATCH
    and count(BATCH, "R.string.toast_source_failed") == 2,
)

# ── 11. 拍照链路：写权限要授出去、结果不能静默丢、待回填文件要能跨重建 ──
# 这一节来自一个真实故障：点「拍照」→ 在相机 / 图库里选了图 → 回主界面
# **依然显示「还没有图片」，而且一声不吭**。
# 根因是 androidx 的 `TakePicture` 契约只做 `putExtra(EXTRA_OUTPUT, uri)`、
# **一次 `addFlags` 都没有**（反汇编 1.11.0 的 class 确认过），相机拿不到我们
# FileProvider Uri 的写权限，拍完文件还是 0 字节；而旧代码对"有结果、没文件"
# 这种情况直接什么都不做。所以这三件事都要钉住：
#   ① 权限必须**显式**授（按包名 grantUriPermission，不怕 ROM 转发时丢 flag）；
#   ② 结果为空必须**说话**，不能静默；
#   ③ 待回填的文件必须进 savedInstanceState（相机是重 Activity，我们的界面
#      在后台很容易被回收重建，重建后那个字段就是 null 了）。
CAPTURE = body_of(MAIN, "private fun resolveCaptureResult(")
check(
    "拍照走的是能拿到回传 Intent 的契约（TakePicture 只回一个 Boolean）",
    "ActivityResultContracts.StartActivityForResult" in MAIN
    and "TakePicture" not in strip_comments(MAIN),
)
check(
    "启动相机前把 Uri 的读写权限显式授出去",
    "private fun grantCaptureUri(" in MAIN
    and "grantUriPermission(" in MAIN
    and "FLAG_GRANT_WRITE_URI_PERMISSION" in MAIN,
)
check(
    "按包名授给每个能响应拍照的 App（ROM 转发时 intent flag 会丢）",
    "queryIntentActivities" in MAIN and "MATCH_DEFAULT_ONLY" in MAIN,
)
check(
    "intent 自己也带上 grant flag（双保险）",
    "addFlags(CAPTURE_GRANT_FLAGS)" in MAIN,
)
check("明确写了输出位置", "MediaStore.EXTRA_OUTPUT" in MAIN)
check(
    "没拿到图时会提示，不再静默",
    "private fun showCaptureEmptyDialog(" in MAIN
    and "R.string.capture_empty_title" in MAIN,
)
check(
    "提示框里给的是能走通的路（复用相册/文件二选一），不是干看着",
    "R.string.capture_empty_use_pick" in MAIN
    and re.search(r"R\.string\.capture_empty_use_pick\s*\)\s*\{[^}]*showSourceChooser\(\)", MAIN)
    is not None,
)
check(
    "提示用弹框不用 Toast（一闪就没，用户正盯着空预览区，会错过）",
    "toast(getString(R.string.capture_empty" not in MAIN,
)
check(
    "三条回退路都在：指定文件 / 回传 Uri / 缩略图 Bitmap",
    "file.length() > 0L" in CAPTURE
    and "data?.data" in CAPTURE
    and "Bitmap" in CAPTURE,
)
check(
    "缩略图走类型化取值（无类型 get 在 API 33 起废弃，会破掉「代码级警告 0 条」）",
    "getParcelable(EXTRA_CAPTURE_BITMAP, Bitmap::class.java)" in CAPTURE
    and "extras?.get(" not in CAPTURE,
)
check(
    "用户取消时不弹任何东西",
    "Activity.RESULT_OK" in MAIN and "return@registerForActivityResult" in MAIN,
)
check(
    "待回填的拍照文件进了 savedInstanceState",
    "outState.putString(KEY_CAPTURE" in MAIN
    and 'KEY_CAPTURE = "state_capture"' in MAIN,
)
check(
    "重建时把它接回来",
    "state.getString(KEY_CAPTURE)" in MAIN,
)

# ── 12. 新字符串中英双语都在，且占位符对得上 ────────────────────────────
NEW_STRINGS = [
    "source_read_failed",
    "source_retry_with_file",
    "batch_all_failed_title",
    "batch_all_failed_message",
    "batch_pick_title",
    "batch_pick_gallery",
    "batch_pick_file",
    "batch_pick_too_many",
    "capture_empty_title",
    "capture_empty_message",
    "capture_empty_use_pick",
]
locale_names = {}
for lang, rel in (
    ("zh", "res/values/strings.xml"),
    ("en", "res/values-en/strings.xml"),
    ("ja", "res/values-ja/strings.xml"),
):
    root = ET.parse(SRC_ROOT / rel).getroot()
    names = {node.get("name") for node in root.findall("string")}
    locale_names[lang] = names
    missing = [k for k in NEW_STRINGS if k not in names]
    check(f"{lang} 有新增字符串", not missing, "缺：" + "、".join(missing) if missing else "")
check(
    "各语言字符串条目数一致",
    len({len(v) for v in locale_names.values()}) == 1,
    "  ".join(f"{k}={len(v)}" for k, v in sorted(locale_names.items())),
)

# ── 汇总 ──────────────────────────────────────────────────────────────
passed = sum(1 for _, ok, _ in results if ok)
print(f"源图读取链路校验：{passed}/{len(results)} 项通过")
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
print("全部通过：外部 Uri 只在选图时读一次，之后全部读本地文件。")
