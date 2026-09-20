#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
静态校验「界面语言」这一整套有没有被改坏。

背景：**加一门语言要同时改四处，任何一处漏掉都是静默失效**——不报错、不崩，
只是"选了没反应"或者"部分界面还是旧语言"，本地编译和单元测试都发现不了：

1. `core/AppLanguage.kt` 的枚举 + `selectable`
   —— 漏了它，语言菜单里根本没有这一项。
2. `res/values-<tag>/strings.xml`
   —— **缺键不会编译报错**：Android 会静默回退到默认资源（本 App 默认是中文），
   于是"日语界面里混着中文"，而且不会有任何提示。另外翻译时很容易把
   `%1$s` / `%2$d` 这类**格式占位符**漏掉或改错，那会在运行时直接闪退
   （`String.format` 参数对不上，抛 `IllegalFormatException`）。
3. `res/xml/locales_config.xml`
   —— 漏了它，Android 13+ 的系统 per-app language 列表里看不到这门语言。
4. `app/build.gradle.kts` 的 `localeFilters`
   —— **这个最阴**：资源会被 aapt 直接裁掉，装机后表现为
   "选了日语没反应、界面还是中文"，而本地编译毫无异常。

上面四处必须**完全对齐**；`verify_locales.py` 就是钉这个的。
不依赖第三方库，直接 `python verify_locales.py` 跑。
"""

import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"

# Android 的带序号格式占位符，如 %1$s / %2$d
ARG_SPEC = re.compile(r"%(\d+)\$([sdfxXeEgGaAcbBhn])")
# 裸 %（不是 %%。调用前先抹掉 %%）
BARE_SPEC = re.compile(r"%(?!\d+\$)")

# 枚举项：Name("tag", R.string.lang_xxx),
ENUM_ENTRY = re.compile(
    r'^\s*([A-Za-z][A-Za-z0-9]*)\s*\(\s*"([^"]*)"\s*,\s*R\.string\.(\w+)\s*\)',
    re.M,
)


def find_source_root():
    """定位 `app/src/main`。

    这份脚本在**工作区根目录**和 **`deliver/` 交付目录**各放一份，
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
RES = SRC / "res"
LANGUAGE_KT = SRC / "java/com/dragonxash/asciiconverter/core/AppLanguage.kt"
# SRC = .../AsciiConverterApp/app/src/main  →  上一层的上一层就是 app/
APP_GRADLE = SRC.parent.parent / "build.gradle.kts"
LOCALES_XML = RES / "xml/locales_config.xml"

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))


def parse_strings(path):
    """{name: text}。CDATA 与转义都还原成"给人看的一行"。"""
    root = ET.parse(path).getroot()
    out = {}
    for node in root.findall("string"):
        body = "".join(node.itertext())
        out[node.get("name")] = body.replace("\\n", "\n").replace("\\'", "'")
    return out


def specs(text):
    """取出占位符序列（保序、含重复），并统计裸 %。"""
    tmp = text.replace("%%", "")
    found = [(int(i), c) for i, c in ARG_SPEC.findall(tmp)]
    bare = len(BARE_SPEC.findall(tmp))
    return found, bare


# ── 1. 采集：资源目录 / 枚举 / 两个配置 ────────────────────────────────
base_strings = parse_strings(RES / "values/strings.xml")

lang_dirs = {}
for child in sorted(RES.iterdir()):
    if not child.is_dir() or not child.name.startswith("values-"):
        continue
    qualifier = child.name[len("values-"):]
    # 只认语言限定符（en / ja / zh-rTW…），排除 v27 / night / land 这类别的限定符
    if re.fullmatch(r"[a-z]{2,3}(-r[A-Z]{2})?", qualifier):
        strings = child / "strings.xml"
        if strings.is_file():
            lang_dirs[qualifier] = strings

language_src = LANGUAGE_KT.read_text(encoding="utf-8")
enum_entries = ENUM_ENTRY.findall(language_src)
enum_tags = {tag for _, tag, _ in enum_entries if tag}          # 排除 System("")
enum_label_keys = {key for _, _, key in enum_entries}

selectable_match = re.search(
    r"val\s+selectable\s*:\s*List<AppLanguage>\s*=\s*listOf\(([^)]*)\)", language_src
)
selectable = []
if selectable_match:
    selectable = [t.strip() for t in selectable_match.group(1).split(",") if t.strip()]

gradle_src = APP_GRADLE.read_text(encoding="utf-8")
filters_match = re.search(r"localeFilters\s*\+=\s*listOf\(([^)]*)\)", gradle_src)
filter_tags = []
if filters_match:
    filter_tags = re.findall(r'"([^"]*)"', filters_match.group(1))

config_tags = []
if LOCALES_XML.is_file():
    root = ET.parse(LOCALES_XML).getroot()
    config_tags = [el.get(ANDROID + "name") for el in root.findall("locale")]

disk_tags = set(lang_dirs) | {"zh"}   # values/ 本身就是中文（默认语言），没有目录后缀

print("=" * 74)
print("界面语言一致性检查")
print("=" * 74)
print()
print(f"默认资源（values/）      ：{len(base_strings)} 条字符串")
print(f"语言资源目录            ：{', '.join(sorted(lang_dirs)) or '无'}")
print(f"AppLanguage 枚举        ：{', '.join(t for _, t, _ in enum_entries)}")
print(f"localeFilters           ：{', '.join(filter_tags)}")
print(f"locales_config          ：{'、'.join(config_tags)}")
print()

# ── 2. 四处对齐 ───────────────────────────────────────────────────────
expected = set(filter_tags)

check(
    "枚举里的非 System 语言都有对应资源目录",
    enum_tags <= disk_tags,
    "缺目录：" + "、".join(sorted(enum_tags - disk_tags)) if enum_tags - disk_tags else "",
)
check(
    "每个资源目录都在 AppLanguage 枚举里",
    disk_tags <= enum_tags,
    "枚举里没有：" + "、".join(sorted(disk_tags - enum_tags))
    if disk_tags - enum_tags
    else "",
)
check(
    "localeFilters 与枚举一致（漏配会被 aapt 裁掉资源）",
    expected == enum_tags,
    f"filters={sorted(expected)} 枚举={sorted(enum_tags)}",
)
check(
    "locales_config 与枚举一致（漏配系统语言列表里看不到）",
    set(config_tags) == enum_tags,
    f"config={sorted(config_tags)} 枚举={sorted(enum_tags)}",
)
check(
    "selectable 覆盖全部语言（否则菜单里选不到）",
    set(selectable) == {name for name, _, _ in enum_entries},
    f"selectable={selectable} 枚举={[n for n, _, _ in enum_entries]}",
)
check(
    "selectable 里的项都是合法的枚举项名",
    all(s in {n for n, _, _ in enum_entries} for s in selectable),
    f"selectable={selectable}",
)

# ── 3. 各语言的键集 / 占位符 / 空串 ───────────────────────────────────
for qualifier, path in sorted(lang_dirs.items()):
    strings = parse_strings(path)
    missing = sorted(set(base_strings) - set(strings))
    extra = sorted(set(strings) - set(base_strings))
    check(
        f"{qualifier}：字符串条目数齐全（缺键会静默回退成中文）",
        not missing and not extra,
        (f"缺 {len(missing)} 条：{'、'.join(missing[:6])}" if missing else "")
        + (f"  多了：{'、'.join(extra[:6])}" if extra else ""),
    )

    mismatch, count_bad, bare_bad = [], [], []
    for key in sorted(set(base_strings) & set(strings)):
        want, _ = specs(base_strings[key])
        got, bare = specs(strings[key])
        if sorted(want) != sorted(got):
            mismatch.append(f"{key}（基准 {want} → {got}）")
        elif want != got:
            count_bad.append(f"{key}（{want} → {got}）")
        if bare:
            bare_bad.append(key)
    check(
        f"{qualifier}：格式占位符种类与基准一致（错了运行时会闪退）",
        not mismatch,
        "；".join(mismatch[:5]),
    )
    check(
        f"{qualifier}：占位符数量与出现顺序一致",
        not count_bad,
        "；".join(count_bad[:5]),
    )
    check(
        f"{qualifier}：没有裸 %（字面量必须写成 %% ）", not bare_bad, "；".join(bare_bad[:6])
    )

    empties = sorted(k for k, v in strings.items() if not v.strip())
    check(f"{qualifier}：没有空白条目", not empties, "；".join(empties[:6]))

    unused = sorted(enum_label_keys - set(strings))
    check(
        f"{qualifier}：语言菜单的标签文案齐全",
        not unused,
        "缺：" + "、".join(unused) if unused else "",
    )

# ── 4. 默认语言自己也要干净 ───────────────────────────────────────────
base_empties = sorted(k for k, v in base_strings.items() if not v.strip())
check("默认资源没有空白条目", not base_empties, "；".join(base_empties[:6]))
unused_default = sorted(enum_label_keys - set(base_strings))
check(
    "默认资源里有语言菜单的标签文案",
    not unused_default,
    "缺：" + "、".join(unused_default) if unused_default else "",
)

# ── 汇总 ──────────────────────────────────────────────────────────────
passed = sum(1 for _, ok, _ in results if ok)
print(f"界面语言校验：{passed}/{len(results)} 项通过")
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
print("全部通过：四处配置对齐，各语言键集与占位符一致，没有静默回退的漏译。")
