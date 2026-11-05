chcp 65001
@echo off
setlocal enabledelayedexpansion

set "JAVA_CMD=java"
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
)

"%JAVA_CMD%" -version 2>&1 | findstr /C:"version \"11." >nul
if %errorlevel% neq 0 (
    echo "未检测到 Java 环境，请先安装 Java。"
    echo "您可以访问 https://repo.huaweicloud.com/java/jdk/11.0.2+9/jdk-11.0.2_windows-x64_bin.exe 下载并安装。"
    echo "安装完成后，请重新运行此脚本。"
    pause
    exit /b
)

cd %~dp0

set LOG_HOME=%~dp0/../logs

set SERVER_NAME=wx-dump-admin

set CLASS_PATH=".;..\conf;..\lib\*;..\ext-lib\*"

set JAVA_OPTS=-server
set JAVA_OPTS=%JAVA_OPTS% -Xmx4g -Xms4g
set JAVA_OPTS=%JAVA_OPTS% -XX:MaxMetaspaceSize=512m
set JAVA_OPTS=%JAVA_OPTS% -XX:+UseG1GC
set JAVA_OPTS=%JAVA_OPTS% -XX:InitiatingHeapOccupancyPercent=45
set JAVA_OPTS=%JAVA_OPTS% -XX:+ParallelRefProcEnabled
set JAVA_OPTS=%JAVA_OPTS% -XX:+HeapDumpOnOutOfMemoryError
set JAVA_OPTS=%JAVA_OPTS% -XX:HeapDumpPath=%LOG_HOME%\heapdump.hprof
set JAVA_OPTS=%JAVA_OPTS% -Dfile.encoding=UTF-8
set JAVA_OPTS=%JAVA_OPTS% -XX:-OmitStackTraceInFastThrow

echo 正在启动 %SERVER_NAME% ...

"%JAVA_CMD%" %JAVA_OPTS% -Dspring.thymeleaf.prefix=file:../html/ -Dlog.home=%LOG_HOME% -classpath %CLASS_PATH% com.xcs.wx.WxDumpApplication

pause
