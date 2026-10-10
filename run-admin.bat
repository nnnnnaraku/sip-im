@echo off
rem ============================================================
rem  Admin console (web) - one click launcher
rem  Build -> collect deps -> start admin web server
rem
rem  NOTE: This file is intentionally ASCII-only, because
rem  non-ASCII characters inside a .bat file can break cmd parsing.
rem
rem  If java/mvn are not on your PATH, edit the two lines below.
rem ============================================================
chcp 65001 >nul
cd /d "%~dp0"

if not defined JAVA_HOME  set "JAVA_HOME=D:\dev\jdk8u504-b01"
if not defined MAVEN_HOME set "MAVEN_HOME=D:\dev\apache-maven-3.9.16"
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"

echo ==========================================
echo   Admin console (web)
echo ==========================================
echo.

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] java not found.
    echo         Install JDK 8, then set JAVA_HOME at the top of this file.
    pause
    exit /b 1
)

where mvn >nul 2>nul
if errorlevel 1 (
    echo [ERROR] mvn not found.
    echo         Install Maven, then set MAVEN_HOME at the top of this file.
    pause
    exit /b 1
)

if not exist "config.properties" (
    echo [INFO] config.properties not found - creating from template...
    copy /y "config.properties.example" "config.properties" >nul
    notepad "config.properties"
)

echo [1/3] Building...
call mvn -q clean package -B
if errorlevel 1 (
    echo [ERROR] Build failed.
    pause
    exit /b 1
)

echo [2/3] Collecting dependencies...
call mvn -q dependency:copy-dependencies "-DoutputDirectory=target\lib" -B
if errorlevel 1 (
    echo [ERROR] Failed to collect dependencies.
    pause
    exit /b 1
)

echo [3/3] Starting admin web server...
echo.
java "-Dfile.encoding=UTF-8" "-Dsun.stdout.encoding=UTF-8" "-Dsun.stderr.encoding=UTF-8" -cp "target\classes;target\lib\*" org.example.AdminServer

echo.
echo Admin console exited.
pause
