#!/usr/bin/env bash
# 编译 tAsciiArtPlayer 参考仓库（所有缓存落工作区内 .build-env，Gradle 8.14.3 来自国内镜像）
# Gradle 自身用 JDK21 运行，项目声明的 jvmToolchain(11) 由 JDK11 提供。
#
# 【换电脑迁移说明】路径按脚本所在目录推导，并把 SDK 路径写回 repo/local.properties。

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
winpath_fwd() {
  local w
  w="$(winpath "$1")"
  printf '%s' "${w//\\//}"
}

JDK21="$ENV/jdk/jdk-21.0.12.1+1"
JDK11="$ENV/jdk/jdk-11.0.32.1+1"
SDK="$ENV/sdk"
GHOME="$ENV/gradle-home"

for d in "$JDK21" "$JDK11" "$SDK" "$ENV/gradle-8.14.3" "$WS/repo"; do
  if [ ! -d "$d" ]; then
    echo "缺少目录：$d" >&2
    exit 1
  fi
done

export JAVA_HOME="$(winpath "$JDK21")"
export ANDROID_HOME="$(winpath "$SDK")"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$(winpath "$GHOME")"

# 自愈：SDK 路径写回 repo/local.properties
printf 'sdk.dir=%s\n' "$(winpath_fwd "$SDK")" > "$WS/repo/local.properties"

GRADLE="$ENV/gradle-8.14.3/bin/gradle.bat"
cd "$WS/repo" || exit 1
"$GRADLE" --no-daemon \
  -Dorg.gradle.java.installations.paths="$(winpath "$JDK11")" \
  "${@:-assembleDebug}"
