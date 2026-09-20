#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# ⚠️ 已废弃（历史遗留）——这是最早那一版环境搭建脚本，别拿它当现在的构建入口。
#
#   它把工具链装在 /d/android-build（工作区**之外**），后来发现后台任务不能写工作区
#   以外的目录，于是整套挪进了工作区的 .build-env/，改用 build-app.sh 驱动。
#   本文件保留下来只为记录当时的过程，路径未随之后的重构更新。
#
#   当前构建入口：工程自带的 ./gradlew（见 README），或工作区里的 build-app.sh
#   （后者假设工作区存在 .build-env/，那套便携工具链不在本仓库内）。
# ---------------------------------------------------------------------------
# 原用途：搭建 Android 构建环境到 D 盘（当时 C 盘只剩 5G，不能污染）
set -e
BASE=/d/android-build
WBASE="D:/android-build"
# 找一个可用的 Python（原来这里写死了一台机器上的绝对路径）
PY="$(command -v python3 || command -v python || echo python)"
mkdir -p "$BASE/dl" "$BASE/jdk" "$BASE/sdk/cmdline-tools" "$BASE/gradle-home"

echo "===== [1/4] 解压 JDK 21 ====="
if [ ! -f "$BASE/dl/jdk21.zip" ]; then
  curl -sSL --retry 3 -o "$BASE/dl/jdk21.zip" \
    "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/windows/OpenJDK21U-jdk_x64_windows_hotspot_21.0.12.1_1.zip"
fi
if [ ! -x "$BASE/jdk/jdk-21.0.12.1+1/bin/java.exe" ]; then
  "$PY" -m zipfile -e "$WBASE/dl/jdk21.zip" "$WBASE/jdk"
fi
JAVA_HOME=$(ls -d "$BASE"/jdk/jdk-21* | head -1)
export JAVA_HOME
echo "JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java.exe" -version 2>&1 | head -3

echo "===== [2/4] Android cmdline-tools ====="
if [ ! -f "$BASE/sdk/cmdline-tools/latest/bin/sdkmanager.bat" ]; then
  if [ ! -f "$BASE/dl/cmdline-tools.zip" ]; then
    curl -sSL --retry 3 -o "$BASE/dl/cmdline-tools.zip" \
      "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"
  fi
  rm -rf "$BASE/sdk/tmp-cmdline"
  "$PY" -m zipfile -e "$WBASE/dl/cmdline-tools.zip" "$WBASE/sdk/tmp-cmdline"
  mv "$BASE/sdk/tmp-cmdline/cmdline-tools" "$BASE/sdk/cmdline-tools/latest"
fi

echo "===== [3/4] 接受许可 ====="
SDKMGR="$BASE/sdk/cmdline-tools/latest/bin/sdkmanager.bat"
yes 2>/dev/null | "$SDKMGR" --sdk_root="$BASE/sdk" --licenses > "$BASE/dl/licenses.log" 2>&1 || true
tail -3 "$BASE/dl/licenses.log"

echo "===== [4/4] 安装 platform-36 / build-tools 36 ====="
"$SDKMGR" --sdk_root="$BASE/sdk" "platform-tools" "platforms;android-36" "build-tools;36.0.0" 2>&1 | tail -15

echo "===== DONE ====="
ls "$BASE/sdk/platforms" "$BASE/sdk/build-tools"
