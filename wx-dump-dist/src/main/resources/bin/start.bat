@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

set "JAVA_CMD=java"
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
)

"%JAVA_CMD%" -version 2>&1 | findstr /C:"version \"11." >nul
if errorlevel 1 (
    echo.
    echo [ERROR] wx-tool requires JDK 11.
    echo Current JAVA_HOME: %JAVA_HOME%
    echo Install JDK 11 and set JAVA_HOME to its installation directory.
    echo.
    pause
    exit /b 1
)

cd /d "%~dp0"

set "LOG_HOME=%~dp0/../logs"
set "SERVER_NAME=wx-tool"
set "CLASS_PATH=.;..\conf;..\lib\*;..\ext-lib\*"

set "JAVA_OPTS=-server"
set "JAVA_OPTS=%JAVA_OPTS% -Xms512m -Xmx2g"
set "JAVA_OPTS=%JAVA_OPTS% -XX:MaxMetaspaceSize=512m"
set "JAVA_OPTS=%JAVA_OPTS% -XX:+UseG1GC"
set "JAVA_OPTS=%JAVA_OPTS% -XX:InitiatingHeapOccupancyPercent=45"
set "JAVA_OPTS=%JAVA_OPTS% -XX:+ParallelRefProcEnabled"
set "JAVA_OPTS=%JAVA_OPTS% -XX:+HeapDumpOnOutOfMemoryError"
set "JAVA_OPTS=%JAVA_OPTS% -XX:HeapDumpPath=%LOG_HOME%\heapdump.hprof"
set "JAVA_OPTS=%JAVA_OPTS% -Dfile.encoding=UTF-8"
set "JAVA_OPTS=%JAVA_OPTS% -XX:-OmitStackTraceInFastThrow"

if not exist "..\logs" mkdir "..\logs"

echo Starting %SERVER_NAME% with JDK 11...

"%JAVA_CMD%" %JAVA_OPTS% -Dspring.thymeleaf.prefix=file:../html/ -Dlog.home=%LOG_HOME% -classpath %CLASS_PATH% com.xcs.wx.WxDumpApplication

if errorlevel 1 pause
