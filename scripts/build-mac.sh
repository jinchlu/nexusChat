#!/usr/bin/env bash
# ============================================================================
# macOS 打包脚本 —— jlink 精简 JRE + jpackage 生成 .app
#
# 用法：
#   bash scripts/build-mac.sh                      # 生成 .app（app-image，无第三方依赖）
#   PACKAGE_TYPE=dmg bash scripts/build-mac.sh     # 额外生成 .dmg 磁盘映像（需系统自带 hdiutil）
#
# 前置条件：在 macOS 上运行，JAVA_HOME 指向 JDK 25（含 jlink/jpackage）
# 注意：jpackage 不能跨平台交叉打包，本脚本只能产出 macOS 包；
#       Intel(x86_64) 与 Apple Silicon(aarch64) 也需分别在对应机器上构建。
# ============================================================================
set -euo pipefail

# ---------------- 变量区（按需修改） ----------------
APP_NAME="NexusChat"
# 打包版本号（仅用于 .app/.dmg 元数据，与 Maven 的 0.1.0 相互独立）。
# macOS 的 CFBundleVersion 不允许首段为 0，所以默认用 1.0.0；可用 APP_VERSION=x.y.z 覆盖。
APP_VERSION="${APP_VERSION:-1.0.0}"
VENDOR="lujc"
MAIN_CLASS="com.lujc.nexuschat.NexusChatApp"
# 本项目实测需要的 JDK 模块：
#   java.desktop    —— AWT/JavaFX 基础
#   java.scripting  —— FXML 加载的硬依赖
#   java.xml        —— FXML 解析
#   java.sql        —— sqlite-jdbc
#   java.logging / java.prefs / java.naming / java.management —— logback / JavaFX
#   jdk.unsupported —— Unsafe（gson、sqlite-jdbc 需要）
JDK_MODULES="java.desktop,java.scripting,java.xml,java.sql,java.logging,java.prefs,java.naming,java.management,jdk.unsupported"
# 消除 JavaFX / sqlite 原生库加载的 restricted method 警告
# --enable-preview 与 pom.xml 的编译参数保持一致（当前代码未使用预览特性，保留无副作用）
JAVA_OPTIONS="--enable-preview --enable-native-access=ALL-UNNAMED"
# ----------------------------------------------------

# 定位项目根目录（脚本位于 scripts/ 下）
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

# JDK 工具链：优先 JAVA_HOME，否则取 PATH
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
DEST="release/${APP_NAME}-${APP_VERSION}-mac-${ARCH}"

echo ">>> [1/5] Maven 编译打包（含依赖拷贝到 target/lib）"
mvn -q clean package -DskipTests

# 主 jar（nexuschat-0.1.0.jar），排除可能出现的 original-*.jar
JAR="$(ls target/nexuschat-*.jar 2>/dev/null | grep -v original | head -1)"
[ -f "$JAR" ] || { echo "错误：找不到主 jar，请先确认 mvn package 成功"; exit 1; }

STAGING="target/staging"
echo ">>> [2/5] 准备暂存目录: $JAR + target/lib"
rm -rf "$STAGING" && mkdir -p "$STAGING/lib"
cp "$JAR" "$STAGING/"
# 主 jar 的 Manifest Class-Path 是 lib/xxx.jar，所以依赖必须放在同级 lib/ 目录
cp target/lib/*.jar "$STAGING/lib/"
echo "    依赖 jar 数量: $(ls "$STAGING/lib" | wc -l | tr -d ' ')"

echo ">>> [3/5] jlink 生成精简运行时（约 50M）"
rm -rf target/runtime
"$JLINK" \
    --module-path "$JMODS_DIR" \
    --add-modules "$JDK_MODULES" \
    --strip-debug --compress=zip-6 --no-header-files --no-man-pages \
    --output target/runtime

echo ">>> [4/5] jpackage 生成 .app"
rm -rf "$DEST"
"$JPACKAGE" --type app-image \
    --name "$APP_NAME" \
    --app-version "$APP_VERSION" \
    --vendor "$VENDOR" \
    --input "$STAGING" \
    --main-jar "$(basename "$JAR")" \
    --main-class "$MAIN_CLASS" \
    --runtime-image target/runtime \
    --java-options "$JAVA_OPTIONS" \
    --dest "$DEST"

# 可选：生成 dmg 安装映像
if [ "${PACKAGE_TYPE:-app-image}" = "dmg" ]; then
    echo ">>> [5/5] 生成 .dmg"
    "$JPACKAGE" --type dmg \
        --name "$APP_NAME" \
        --app-version "$APP_VERSION" \
        --vendor "$VENDOR" \
        --input "$STAGING" \
        --main-jar "$(basename "$JAR")" \
        --main-class "$MAIN_CLASS" \
        --runtime-image target/runtime \
        --java-options "$JAVA_OPTIONS" \
        --dest "$DEST"
else
    echo ">>> [5/5] 跳过安装映像（PACKAGE_TYPE=dmg 可生成 .dmg）"
fi

echo ""
echo "✅ 打包完成：$DEST/$APP_NAME.app"
echo "   双击运行，或执行: open \"$DEST/$APP_NAME.app\""
echo "   体积: $(du -sh "$DEST/$APP_NAME.app" | cut -f1)"
echo ""
echo "提示：未签名的 App 首次分发到他人机器时，需在\"系统设置 → 隐私与安全性\"中允许打开。"
