@echo off
REM ============================================================================
REM 一键入口：生成 Windows 免安装 .zip 包
REM 本质是用 PACKAGE_TYPE=zip 调用 build-windows.bat
REM
REM zip 内是一个顶层 NexusChat\ 目录（exe + app\ + 内置 runtime\），
REM 终端用户解压后直接双击 NexusChat.exe 即可，无需 JDK、无需安装。
REM
REM 前置条件（同 build-windows.bat）：
REM   * JDK 25 且已设置 JAVA_HOME
REM   * PowerShell 5+（Windows 10 / Server 2016 及以上自带）
REM ============================================================================
setlocal
set "PACKAGE_TYPE=zip"
call "%~dp0build-windows.bat"
endlocal
