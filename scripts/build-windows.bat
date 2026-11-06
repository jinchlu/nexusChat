@echo off
REM ============================================================================
REM Windows 打包脚本 —— jlink 精简 JRE + jpackage
REM
REM 用法（在 Windows 的 cmd 中运行）：
REM   scripts\build-windows.bat                              生成免安装目录（默认）
REM   set PACKAGE_TYPE=msi ^&^& scripts\build-windows.bat      免安装目录 + .msi 安装包
REM   scripts\build-windows-msi.bat                          双击入口，等价于上面一行
REM   scripts\build-windows-zip.bat                          免安装目录打成单个 .zip
REM   set MAKE_ZIP=1 与 PACKAGE_TYPE=msi 同时使用            一次产出 .msi 和 .zip
REM
REM 前置条件：
REM   * 必须在 Windows 上运行（jpackage 不支持交叉打包）；x64 / ARM64 需分别构建。
REM   * JAVA_HOME 指向完整 JDK 25（必须含 bin\jpackage.exe 和 jmods）。
REM   * 打 MSI 需要 WiX Toolset 3.x，且 candle.exe / light.exe 在 PATH 中。
REM     下载 https://github.com/wixtoolset/wix3/releases 的 wix311-binaries.zip，
REM     解压到如 C:\tools\wix311，再把它加入 PATH。
REM ============================================================================
setlocal enabledelayedexpansion
chcp 65001 >nul

REM ---------------- 变量区（按需修改） ----------------
set "APP_NAME=NexusChat"
REM 打包版本号（仅用于安装包元数据，与 Maven 的 0.1.0 相互独立），可用 set APP_VERSION=x.y.z 覆盖
if not defined APP_VERSION set "APP_VERSION=1.0.0"
set "VENDOR=lujc"
set "MAIN_CLASS=com.lujc.nexuschat.NexusChatApp"
set "COPYRIGHT=Copyright (C) 2026 lujc"
REM 本项目实测需要的 JDK 模块：
REM   java.desktop/java.scripting/java.xml —— JavaFX + FXML
REM   java.sql —— sqlite-jdbc
REM   java.logging/java.prefs/java.naming/java.management —— logback / JavaFX
REM   jdk.unsupported —— Unsafe（gson、sqlite-jdbc）
set "JDK_MODULES=java.desktop,java.scripting,java.xml,java.sql,java.logging,java.prefs,java.naming,java.management,jdk.unsupported"
REM 消除 JavaFX / sqlite 原生库加载的 restricted method 警告
set "JAVA_OPTIONS=--enable-preview --enable-native-access=ALL-UNNAMED"
REM 固定的 MSI UpgradeCode —— 永不要修改；Windows 靠它识别同一产品的升级/卸载。
REM 换成不同 GUID 会被当作另一个新产品。
set "UPGRADE_UUID=BF3253A7-0217-47AF-AA6D-31283212ED61"
if not defined PACKAGE_TYPE set "PACKAGE_TYPE=app-image"
set "ZIP_ENABLED="
if /i "%PACKAGE_TYPE%"=="zip" set "ZIP_ENABLED=1"
if defined MAKE_ZIP set "ZIP_ENABLED=1"
REM -------------------------------------------------------------

REM 定位项目根目录（脚本位于 scripts\ 下）
set "SCRIPT_DIR=%~dp0"
for %%i in ("%SCRIPT_DIR%..") do set "ROOT_DIR=%%~fi"
cd /d "%ROOT_DIR%"

REM 定位 JDK 工具链：优先 JAVA_HOME，否则用 PATH
if defined JAVA_HOME (
    set "JLINK=%JAVA_HOME%\bin\jlink.exe"
    set "JPACKAGE=%JAVA_HOME%\bin\jpackage.exe"
    set "JMODS_DIR=%JAVA_HOME%\jmods"
) else (
    set "JLINK=jlink"
    set "JPACKAGE=jpackage"
    set "JMODS_DIR="
)
if not exist "%JPACKAGE%" (
    echo [ERROR] 找不到 jpackage，请安装完整 JDK 25 并设置 JAVA_HOME。
    exit /b 1
)
if /i "%PACKAGE_TYPE%"=="msi" (
    if not defined JMODS_DIR (
        echo [ERROR] 打 MSI 必须显式设置 JAVA_HOME，否则 jlink 找不到 jmods。
        exit /b 1
    )
    where candle.exe >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] PATH 中找不到 WiX Toolset 3.x ^(candle.exe 缺失^)。
        echo         jpackage 需要 WiX 才能生成 .msi，安装方式：
        echo           1^) 下载 https://github.com/wixtoolset/wix3/releases 的 wix311-binaries.zip
        echo           2^) 解压到如 C:\tools\wix311
        echo           3^) 把该目录加入 PATH，重开 cmd 后重新执行本脚本
        exit /b 1
    )
    where light.exe >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] 找到 candle.exe 但缺少 light.exe，WiX 安装不完整。
        echo         请使用官方 wix311-binaries.zip（同时包含两个工具）。
        exit /b 1
    )
)
if defined ZIP_ENABLED (
    where powershell.exe >nul 2>&1
    if errorlevel 1 (
        echo [ERROR] 找不到 powershell.exe，打 zip 需要 PowerShell 5+。
        exit /b 1
    )
)

REM CPU 架构：AMD64 -> x64，ARM64 -> aarch64
set "ARCH=%PROCESSOR_ARCHITECTURE%"
if "%ARCH%"=="AMD64" set "ARCH=x64"
if "%ARCH%"=="ARM64" set "ARCH=aarch64"
set "DEST=release\%APP_NAME%-%APP_VERSION%-win-%ARCH%"

echo ^>^>^> [1/6] Maven 编译打包（含依赖拷贝到 target\lib）
REM 必须用 call，否则 mvn.cmd 执行完不会回到本脚本
call mvn -q clean package -DskipTests
if errorlevel 1 exit /b 1

REM 定位主 jar（nexuschat-*.jar；shade 生成的 original-*.jar 不匹配此前缀，天然被排除）
set "JAR="
for %%f in (target\nexuschat-*.jar) do set "JAR=%%f"
if not defined JAR (
    echo [ERROR] 在 target 下找不到主 jar，请确认 mvn package 成功。
    exit /b 1
)
for %%f in ("%JAR%") do set "JAR_NAME=%%~nxf"

set "STAGING=target\staging"
echo ^>^>^> [2/6] 准备暂存目录: %JAR_NAME% + target\lib
if exist "%STAGING%" rmdir /s /q "%STAGING%"
mkdir "%STAGING%\lib"
copy /y "%JAR%" "%STAGING%\" >nul
REM 主 jar 的 Manifest Class-Path 是 lib/xxx.jar，依赖必须放在同级 lib\ 目录
copy /y "target\lib\*.jar" "%STAGING%\lib\" >nul
if errorlevel 1 (
    echo [ERROR] 拷贝依赖失败，请确认 target\lib 存在（由 maven-dependency-plugin 生成）。
    exit /b 1
)

echo ^>^>^> [3/6] jlink 生成精简运行时
if exist "target\runtime" rmdir /s /q "target\runtime"
if defined JMODS_DIR (
    "%JLINK%" --module-path "%JMODS_DIR%" ^
        --add-modules %JDK_MODULES% ^
        --strip-debug --compress=zip-6 --no-header-files --no-man-pages ^
        --output target\runtime
) else (
    "%JLINK%" ^
        --add-modules %JDK_MODULES% ^
        --strip-debug --compress=zip-6 --no-header-files --no-man-pages ^
        --output target\runtime
)
if errorlevel 1 exit /b 1

echo ^>^>^> [4/6] jpackage 生成免安装目录
if exist "%DEST%" rmdir /s /q "%DEST%"
"%JPACKAGE%" --type app-image ^
    --name "%APP_NAME%" ^
    --app-version "%APP_VERSION%" ^
    --vendor "%VENDOR%" ^
    --copyright "%COPYRIGHT%" ^
    --input "%STAGING%" ^
    --main-jar "%JAR_NAME%" ^
    --main-class "%MAIN_CLASS%" ^
    --runtime-image target\runtime ^
    --java-options "%JAVA_OPTIONS%" ^
    --dest "%DEST%"
if errorlevel 1 exit /b 1

set "ZIP_NAME=%APP_NAME%-%APP_VERSION%-win-%ARCH%.zip"
if defined ZIP_ENABLED (
    echo ^>^>^> [5/6] 用 PowerShell 打包成 zip
    REM -LiteralPath 指向 App 目录，使 zip 根目录是 NexusChat\（解压后是一个干净的文件夹）
    powershell -NoProfile -ExecutionPolicy Bypass -Command ^
        "Compress-Archive -LiteralPath '%ROOT_DIR%\%DEST%\%APP_NAME%' -DestinationPath '%ROOT_DIR%\%DEST%\%ZIP_NAME%' -Force"
    if errorlevel 1 (
        echo [ERROR] Compress-Archive 失败。
        exit /b 1
    )
) else (
    echo ^>^>^> [5/6] 跳过 zip ^(可用 scripts\build-windows-zip.bat^)
)

if /i "%PACKAGE_TYPE%"=="msi" (
    echo ^>^>^> [6/6] 通过 WiX 生成 .msi 安装包
    REM --win-upgrade-uuid : 固定 GUID，支持覆盖升级与干净卸载
    REM --win-dir-chooser  : 允许用户选择安装目录
    REM --win-menu/shortcut: 开始菜单 + 可选桌面快捷方式
    "%JPACKAGE%" --type msi ^
        --name "%APP_NAME%" ^
        --app-version "%APP_VERSION%" ^
        --vendor "%VENDOR%" ^
        --copyright "%COPYRIGHT%" ^
        --input "%STAGING%" ^
        --main-jar "%JAR_NAME%" ^
        --main-class "%MAIN_CLASS%" ^
        --runtime-image target\runtime ^
        --java-options "%JAVA_OPTIONS%" ^
        --win-dir-chooser --win-menu --win-shortcut --win-shortcut-prompt ^
        --win-upgrade-uuid "%UPGRADE_UUID%" ^
        --dest "%DEST%"
    if errorlevel 1 exit /b 1
) else (
    echo ^>^>^> [6/6] 跳过 msi ^(可用 scripts\build-windows-msi.bat^)
)

echo.
echo [OK] 输出目录: %DEST%\
echo      免安装启动: %APP_NAME%\%APP_NAME%.exe
if defined ZIP_ENABLED echo      ZIP 包:     %ZIP_NAME%
if /i "%PACKAGE_TYPE%"=="msi" echo      MSI 安装包: %APP_NAME%-%APP_VERSION%.msi
echo      目标机器无需安装 JDK，运行时已内置。
echo      提示：未签名程序首次启动可能触发 SmartScreen 警告。
endlocal
