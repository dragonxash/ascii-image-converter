# -*- coding: utf-8 -*-
"""
静态检查 Android 的字符串占位符与 getString 调用是否匹配。

**为什么必须查这个**：Resources.getString(id, args...) 内部就是
String.format(getString(id), args)，占位符和实参对不上会在**运行时**抛异常：

| 情况 | 异常 |
| --- | --- |
| `%2$d` 收到 String | IllegalFormatConversionException |
| 占位符 3 个但只传 2 个 | MissingFormatArgumentException |
| 串里有裸 `%`（如 "50% off"）又带了实参 | UnknownFormatConversionException |

而**编译能过、lintVitalRelease 也不拦**（StringFormatMatches 只是 warning），
只在用户点开那个界面时才炸。这个脚本就是来堵这个洞的。

检查三件事：
  1. 每个 getString(R.string.X, ...) 的实参个数 == X 里最大的占位符下标
  2. 明显类型对不上（如 %d 收到一个返回 String 的函数调用）
  3. 中文/英文两份资源的占位符集合必须一致（不然切语言就崩）

另外会点名「间接引用」——`getString(someResId, ...)` 这类第一个实参不是
`R.string.字面量` 的调用。这种查不出占位符对不对（id 是运行时才知道的），
但**必须被看见**：它是个检查盲区，很容易顺着它把上面那类崩溃漏过去。
正确写法是调用点就把提示文本拼好传进去（`getString(...)` 的结果当参数传），
而不是把一个资源 id 传进深层函数再在那里补实参。

跑法：
    python verify_string_formats.py
"""

import re
import sys
from pathlib import Path


def find_app_dir():
    """定位 `app/src/main`。

    这个脚本在**工作区根目录**和 **`deliver/` 交付目录**各放一份，
    所以不能写死 `脚本所在目录 / AsciiConverterApp`——`deliver/` 底下没有这个子目录，
    那一份会直接崩在"读不到 strings.xml"。从脚本所在目录逐级往上找最稳，
    实在找不到才退回本机的绝对路径。
    """
    here = Path(__file__).resolve().parent
    for base in (here, *here.parents):
        candidate = base / "AsciiConverterApp" / "app" / "src" / "main"
        if candidate.is_dir():
            return candidate
    return Path(
        r"D:/WorkBuddyWorkSpace/2026-09-15-22-55-59/AsciiConverterApp/app/src/main"
    )


APP = find_app_dir()
RES = APP / "res"
JAVA = APP / "java"

# 占位符：%1$d / %2$s / %3$.2f 之类
ARG_SPEC = re.compile(r"%(\d+)\$([a-zA-Z])")
# 裸转换（没有位置下标）：%d %s %f
BARE_SPEC = re.compile(r"%(?!%)(\d+)?[a-zA-Z]")

failures = []
warnings = []


def fail(msg):
    failures.append(msg)


def warn(msg):
    warnings.append(msg)


# ---------------------------------------------------------------------------
# 1. 解析 strings.xml
# ---------------------------------------------------------------------------

STRING_RE = re.compile(r'<string\s+name="([^"]+)"[^>]*>(.*?)</string>', re.S)


def parse_strings(path):
    """@return {name: (raw_text, [(index, conversion), ...])}"""
    text = path.read_text(encoding="utf-8")
    out = {}
    for name, body in STRING_RE.findall(text):
        # XML 转义还原，免得 "&amp;" 干扰 % 的识别
        body = (body.replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&#39;", "'").replace("&quot;", '"'))
        specs = [(int(i), c) for i, c in ARG_SPEC.findall(body)]
        # 裸 % 检查：先抹掉 %%，再看还有没有 %, 后面跟非数字$
        stripped = body.replace("%%", "")
        bare = [m for m in BARE_SPEC.finditer(stripped) if not ARG_SPEC.match(stripped, m.start())]
        out[name] = (body.strip(), specs, len(bare))
    return out


ZH = parse_strings(RES / "values" / "strings.xml")
EN = parse_strings(RES / "values-en" / "strings.xml")

print("=" * 74)
print("字符串占位符 × getString 调用点 一致性检查")
print("=" * 74)
print(f"\n中文资源 {len(ZH)} 条，英文资源 {len(EN)} 条")


# ---------------------------------------------------------------------------
# 2. 中英占位符必须一致
# ---------------------------------------------------------------------------

print("\n[1] 中英文资源的占位符是否一致")
only_zh = sorted(set(ZH) - set(EN))
only_en = sorted(set(EN) - set(ZH))
if only_zh:
    warn(f"只有中文有的字符串 {len(only_zh)} 条: {', '.join(only_zh[:8])}")
if only_en:
    warn(f"只有英文有的字符串 {len(only_en)} 条: {', '.join(only_en[:8])}")

mismatch = []
for name in sorted(set(ZH) & set(EN)):
    zh_specs = sorted(ZH[name][1])
    en_specs = sorted(EN[name][1])
    if zh_specs != en_specs:
        mismatch.append((name, zh_specs, en_specs))
for name, zh_specs, en_specs in mismatch:
    fail(f"占位符不一致 [{name}]  中文={zh_specs} 英文={en_specs}")
print(f"  中英都有 {len(set(ZH) & set(EN))} 条，占位符不一致 {len(mismatch)} 条")
if not mismatch:
    print("  [PASS] 所有共有字符串占位符完全一致")


# ---------------------------------------------------------------------------
# 3. 扫描 Kotlin 里的 getString 调用
# ---------------------------------------------------------------------------

def find_calls(src):
    """@return [(string_name, [arg, ...]), ...]，arg 是去掉首尾空白的原文。"""
    calls = []
    for m in re.finditer(r"getString\(\s*R\.string\.(\w+)", src):
        name = m.group(1)
        # 从实参列表起点开始做括号配平
        i = src.index("(", m.start())
        depth = 0
        args = []
        cur = []
        j = i
        while j < len(src):
            ch = src[j]
            if ch in "([{":
                depth += 1
                if depth == 1:
                    j += 1
                    continue
            elif ch in ")]}":
                depth -= 1
                if depth == 0:
                    break
            if depth == 1 and ch == ",":
                args.append("".join(cur).strip())
                cur = []
            else:
                cur.append(ch)
            j += 1
        tail = "".join(cur).strip()
        if tail:
            args.append(tail)
        # 第一个实参是 R.string.X，去掉
        args = [a for a in args if not a.startswith("R.string.")]
        calls.append((name, args))
    return calls


# 收集 Kotlin 函数的声明返回类型，用来做粗粒度类型推断
FUN_RET = re.compile(r"\bfun\s+(\w+)\s*\([^)]*\)\s*:\s*([A-Za-z_][\w<>.?]*)")


def collect_return_types():
    types = {}
    for kt in JAVA.rglob("*.kt"):
        src = kt.read_text(encoding="utf-8", errors="replace")
        for fname, ret in FUN_RET.findall(src):
            types.setdefault(fname, ret)
    return types


RETURN_TYPES = collect_return_types()


def arg_looks_like_string(arg):
    """粗判这个实参是不是 String（用来抓 %d 收到 String 的情况）。"""
    arg = arg.strip()
    if arg.startswith('"') or arg.startswith("$"):        # 字面量 / 模板
        return True
    m = re.match(r"([A-Za-z_]\w*)\s*\(", arg)             # 函数调用
    if m:
        return RETURN_TYPES.get(m.group(1)) == "String"
    m = re.match(r"([A-Za-z_]\w*)\.", arg)                # 属性访问
    if m:
        return None
    return None


print("\n[2] 逐个 getString 调用点核对")
total_calls = 0
checked = 0
for kt in sorted(JAVA.rglob("*.kt")):
    src = kt.read_text(encoding="utf-8", errors="replace")
    rel = kt.relative_to(JAVA)
    for name, args in find_calls(src):
        total_calls += 1
        if name not in ZH:
            fail(f"[{rel}] getString 引用了不存在的字符串 R.string.{name}")
            continue
        checked += 1
        raw, specs, bare = ZH[name]
        if not specs:
            if args:
                if bare:
                    fail(f"[{rel}] {name}：串里有裸 `%` 又带了 {len(args)} 个实参，"
                         f"String.format 会抛 UnknownFormatConversionException —— 「{raw}」")
                else:
                    warn(f"[{rel}] {name}：串里没有占位符，却传了 {len(args)} 个实参"
                         f"（不崩，但是多余的）")
            continue
        need = max(i for i, _ in specs)
        if len(args) != need:
            fail(f"[{rel}] {name}：占位符需要 {need} 个实参，实际传了 {len(args)} 个"
                 f" —— 「{raw}」  实参={args}")
            continue
        # 类型粗查
        by_index = {i: c for i, c in specs}
        for idx, conv in by_index.items():
            arg = args[idx - 1]
            if conv == "d":
                kind = arg_looks_like_string(arg)
                if kind:
                    fail(f"[{rel}] {name}：%{idx}$d 收到疑似 String 的实参 `{arg}`"
                         f" → IllegalFormatConversionException")

print(f"  共发现 getString 调用 {total_calls} 处，成功解析 {checked} 处")

# 间接引用：第一个实参不是 R.string.X 字面量，占位符对不上就查不出来。
#
# 先认接收者：`state.getString(KEY_URI)` / `prefs.getString(KEY_TEXT_FORMAT)`
# 是 Bundle、SharedPreferences 上的**同名方法**，和资源无关，必须排除；
# 只有裸调用和 `resources.getString(...)` 才是 Resources.getString。
# 另外 `fun getString(...)` 是**声明**不是调用，也要排掉（AppPrefs 里就有一个）。
# 接收者表达式允许带括号/引号，好认出 `prefs(context).getString(KEY, null)` 这种。
INDIRECT_RE = re.compile(r"([A-Za-z_][\w.()\[\]\"']*\.)?(?<!fun )getString\(\s*([^),\n]+)")
indirect = []
for kt in sorted(JAVA.rglob("*.kt")):
    src = kt.read_text(encoding="utf-8", errors="replace")
    rel = kt.relative_to(JAVA)
    for m in INDIRECT_RE.finditer(src):
        receiver = m.group(1) or ""
        # 带了接收者又不是 resources. → 同名方法，跳过
        if receiver and not receiver.endswith("resources."):
            continue
        if not receiver:
            # 跨行链式调用：`.getString(KEY, null)` 单起一行，接收者在上一行
            if src[:m.start()].rstrip().endswith("."):
                continue
        first = m.group(2).strip()
        if not first.startswith("R.string."):
            indirect.append((str(rel), first))
for rel, first in indirect:
    warn(f"[{rel}] getString 的第一个实参是 `{first}`（不是 R.string.字面量）："
         f"占位符对不对查不出来，请人工确认")

print("\n[3] 串里有占位符、但从未被带参调用（界面会显示 %1$d 这种原样文本）")
used_with_args = set()
used_anyway = set()
for kt in JAVA.rglob("*.kt"):
    src = kt.read_text(encoding="utf-8", errors="replace")
    for name, args in find_calls(src):
        used_anyway.add(name)
        if args:
            used_with_args.add(name)
idle = [n for n in ZH if ZH[n][1] and n not in used_with_args and n in used_anyway]
for n in sorted(idle):
    warn(f"{n} 有占位符但只用无参 getString 取过 —— 「{ZH[n][0]}」")
if not idle:
    print("  [PASS] 没有「带占位符却无参取用」的字符串")

# ---------------------------------------------------------------------------
print("\n" + "=" * 74)
if failures:
    print(f"结果：{len(failures)} 项未通过（这些都会在运行时崩）")
    for f in failures:
        print(f"  ✗ {f}")
    if warnings:
        print(f"\n另有 {len(warnings)} 条提醒")
        for w in warnings:
            print(f"  - {w}")
    print("=" * 74)
    sys.exit(1)

print(f"结果：全部通过（{len(warnings)} 条提醒）")
for w in warnings:
    print(f"  - {w}")
print("=" * 74)
