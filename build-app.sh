#!/usr/bin/env bash
# 编译「ASCII 图片转换器」独立 App
# Gradle 自身用 JDK21 跑，项目 jvmTarget=17（JDK21 自带支持），不需要额外的 JDK。
#
# 【换电脑迁移说明】路径全部按「脚本自己所在的目录」推导，不再写死盘符与目录名。
# 顺带把探测到的绝对路径写回下面两个配置文件，省得手动改：
#   AsciiConverterApp/local.properties          （AGP 靠它找 SDK）
#   .build-env/gradle-home/gradle.properties    （Gradle 靠它找 JDK11，项目声明了 jvmToolchain(11)）

WS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV="$WS/.build-env"

# MSYS 路径 /d/foo -> Windows 路径 D:\foo；有 cygpath 就用，没有就自己转
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
# 正斜杠版 Windows 路径，写进 .properties 用（AGP 两种都认，正斜杠不用转义）
winpath_fwd() {
  local w
  w="$(winpath "$1")"
  printf '%s' "${w//\\//}"
}

JDK21="$ENV/jdk/jdk-21.0.12.1+1"
JDK11="$ENV/jdk/jdk-11.0.32.1+1"
SDK="$ENV/sdk"
GHOME="$ENV/gradle-home"
GRADLE="$ENV/gradle-8.14.3/bin/gradle.bat"

for d in "$JDK21" "$JDK11" "$SDK" "$ENV/gradle-8.14.3"; do
  if [ ! -d "$d" ]; then
    echo "缺少构建环境目录：$d" >&2
    echo "（.build-env 没跟着一起搬过来？或者解压不完整）" >&2
    exit 1
  fi
done

export JAVA_HOME="$(winpath "$JDK21")"
export ANDROID_HOME="$(winpath "$SDK")"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="$(winpath "$GHOME")"

# 自愈：把绝对路径写进配置
printf 'sdk.dir=%s\n' "$(winpath_fwd "$SDK")" > "$WS/AsciiConverterApp/local.properties"
mkdir -p "$GHOME"
cat > "$GHOME/gradle.properties" <<EOF
# 本文件由 build-app.sh 每次运行时自动生成（换电脑后路径自动跟上，不要手工改）。
# 项目声明 jvmToolchain(11)，Gradle 自身用 JDK21 运行，这里告诉 Gradle 的 toolchain 探测去哪个目录找 JDK11。
org.gradle.java.installations.paths=$(winpath_fwd "$JDK11")
org.gradle.java.installations.auto-detect=true
org.gradle.java.installations.auto-download=false
EOF

cd "$WS/AsciiConverterApp" || exit 1
# --no-build-cache：本机沙箱不允许写 .build-env/gradle-home/caches/build-cache-1/*.part，
# 开着缓存会在 lintVitalAnalyzeRelease 收尾时报"拒绝访问"整个构建失败（编译本身是过的）。
"$GRADLE" --no-daemon --no-build-cache "${@:-assembleDebug}"
