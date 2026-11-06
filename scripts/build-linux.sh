#!/usr/bin/env bash
# ============================================================================
# Linux 打包脚本 —— jlink 精简 JRE + jpackage
#
# 用法：
#   bash scripts/build-linux.sh                     # 生成 app-image 目录（免安装，解压即用）
#   PACKAGE_TYPE=deb bash scripts/build-linux.sh    # 生成 .deb（需 dpkg-deb / fakeroot）
#   PACKAGE_TYPE=rpm bash scripts/build-linux.sh    # 生成 .rpm（需 rpm-build）
#
# 前置条件：在 Linux 上运行，JAVA_HOME 指向 JDK 25（含 jlink/jpackage）
# 注意：jpackage 不能跨平台交叉打包，Linux 包必须在 Linux 上构建。
# ============================================================================
set -euo pipefail

# ---------------- 变量区（按需修改） ----------------
APP_NAME="NexusChat"
# 打包版本号（与 Maven 的 1.5.0 保持一致），可用 APP_VERSION=x.y.z 覆盖
APP_VERSION="${APP_VERSION:-1.5.0}"
VENDOR="lujc"
MAIN_CLASS="com.lujc.nexuschat.NexusChatApp"
JDK_MODULES="java.desktop,java.scripting,java.xml,java.sql,java.logging,java.prefs,java.naming,java.management,jdk.unsupported"
JAVA_OPTIONS="--enable-preview --enable-native-access=ALL-UNNAMED"
# ----------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/jpackage" ]; then
    JLINK="$JAVA_HOME/bin/jlink"
    JPACKAGE="$JAVA_HOME/bin/jpackage"
    JMODS_DIR="$JAVA_HOME/jmods"
else
    JLINK="$(command -v jlink)"
    JPACKAGE="$(command -v jpackage)"
    JMODS_DIR="$(dirname "$(dirname "$(command -v jlink)")")/jmods"
fi
[ -x "$JPACKAGE" ] || { echo "错误：找不到 jpackage，请安装 JDK 25 并设置 JAVA_HOME"; exit 1; }
[ -d "$JMODS_DIR" ] || { echo "错误：找不到 $JMODS_DIR，请确认使用的是完整版 JDK（非 JRE）"; exit 1; }

ARCH="$(uname -m)"
DEST="release/${APP_NAME}-${APP_VERSION}-linux-${ARCH}"

echo ">>> [1/5] Maven 编译打包（含依赖拷贝到 target/lib）"
mvn -q clean package -DskipTests

JAR="$(ls target/nexuschat-*.jar 2>/dev/null | grep -v original | head -1)"
[ -f "$JAR" ] || { echo "错误：找不到主 jar，请先确认 mvn package 成功"; exit 1; }

STAGING="target/staging"
echo ">>> [2/5] 准备暂存目录: $JAR + target/lib"
rm -rf "$STAGING" && mkdir -p "$STAGING/lib"
cp "$JAR" "$STAGING/"
# 主 jar 的 Manifest Class-Path 是 lib/xxx.jar，依赖必须放在同级 lib/
cp target/lib/*.jar "$STAGING/lib/"
echo "    依赖 jar 数量: $(ls "$STAGING/lib" | wc -l | tr -d ' ')"

echo ">>> [3/5] jlink 生成精简运行时"
rm -rf target/runtime
"$JLINK" \
    --module-path "$JMODS_DIR" \
    --add-modules "$JDK_MODULES" \
    --strip-debug --compress=zip-6 --no-header-files --no-man-pages \
    --output target/runtime

PKG_TYPE="${PACKAGE_TYPE:-app-image}"
echo ">>> [4/5] jpackage 生成 $PKG_TYPE"
rm -rf "$DEST"
"$JPACKAGE" --type "$PKG_TYPE" \
    --name "$APP_NAME" \
    --app-version "$APP_VERSION" \
    --vendor "$VENDOR" \
    --input "$STAGING" \
    --main-jar "$(basename "$JAR")" \
    --main-class "$MAIN_CLASS" \
    --runtime-image target/runtime \
    --java-options "$JAVA_OPTIONS" \
    --dest "$DEST"

echo ""
echo "✅ 打包完成：$DEST"
if [ "$PKG_TYPE" = "app-image" ]; then
    echo "   运行: \"$DEST/$APP_NAME/bin/$APP_NAME\""
else
    echo "   安装包已生成在 $DEST 目录下"
fi
echo "   体积: $(du -sh "$DEST" | cut -f1)"
