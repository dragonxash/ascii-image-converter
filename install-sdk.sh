#!/usr/bin/env bash
# 在已有 .build-env 的基础上，补装 / 重装 Android SDK 组件
# （platform-tools、platforms;android-36、build-tools;36.0.0）。
# 只有在新电脑上 .build-env/sdk 不完整（比如为了省体积没搬 SDK）时才需要跑这个。
#
# 【换电脑迁移说明】路径按脚本所在目录推导。

WS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV="$WS/.build-env"

winpath() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    local p="$1"
    if [[ "$p" =~ ^/([a-zA-Z])/(.*)$ ]]; then
      printf '%s:\\%s' "${BASH_REMATCH[1]^^}" "$(printf '%s' "${BASH_REMATCH[2]}" | tr '/' '\\')"
    else
      printf '%s' "$p"
    fi
  fi
}

JDK21="$ENV/jdk/jdk-21.0.12.1+1"
SDKMGR="$ENV/sdk/cmdline-tools/latest/bin/sdkmanager.bat"

if [ ! -f "$SDKMGR" ]; then
  echo "找不到 sdkmanager：$SDKMGR" >&2
  echo "先跑 setup-android-env.sh 把 cmdline-tools 装上。" >&2
  exit 1
fi

export JAVA_HOME="$(winpath "$JDK21")"
WSDK="$(winpath "$ENV/sdk")"

echo "===== 接受许可 ====="
yes 2>/dev/null | "$SDKMGR" --sdk_root="$WSDK" --licenses > "$ENV/dl/licenses.log" 2>&1 || true
tail -4 "$ENV/dl/licenses.log"

echo "===== 安装组件 ====="
"$SDKMGR" --sdk_root="$WSDK" "platform-tools" "platforms;android-36" "build-tools;36.0.0" 2>&1 | tail -12

echo "===== DONE ====="
ls "$ENV/sdk/platforms" "$ENV/sdk/build-tools"
