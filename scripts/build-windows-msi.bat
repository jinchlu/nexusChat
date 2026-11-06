@echo off
REM ============================================================================
REM 一键入口：生成 Windows .msi 安装包（同时生成免安装目录）
REM 本质是用 PACKAGE_TYPE=msi 调用 build-windows.bat
REM
REM 前置条件（同 build-windows.bat）：
REM   * JDK 25 且已设置 JAVA_HOME
REM   * WiX Toolset 3.x 在 PATH 中（candle.exe + light.exe）
REM ============================================================================
setlocal
set "PACKAGE_TYPE=msi"
call "%~dp0build-windows.bat"
endlocal
