@echo off
REM ============================================================
REM jsqlint run script (Windows, located at project root)
REM Usage: run.bat {start|stop|restart|status}
REM
REM Prerequisites:
REM   1. build.bat executed, dist\jsqlint-server.jar exists
REM   2. MySQL reachable
REM
REM Runtime config (env vars override defaults below):
REM   JSQLINT_PORT / JSQLINT_DB_URL / JSQLINT_DB_USER
REM   JSQLINT_DB_PASSWORD / JSQLINT_JWT_SECRET / JAVA_OPTS
REM ============================================================
setlocal enabledelayedexpansion

set "APP_NAME=jsqlint-server"
set "ROOT_DIR=%~dp0"
set "DIST_DIR=%ROOT_DIR%dist"
set "JAR_PATH=%DIST_DIR%\jsqlint-server.jar"
set "PID_FILE=%DIST_DIR%\%APP_NAME%.pid"
set "LOG_FILE=%DIST_DIR%\%APP_NAME%.log"

if not defined JSQLINT_PORT set "JSQLINT_PORT=8080"
if not defined JSQLINT_DB_USER set "JSQLINT_DB_USER=root"
if not defined JSQLINT_DB_PASSWORD set "JSQLINT_DB_PASSWORD=123456"
if not defined JSQLINT_JWT_SECRET set "JSQLINT_JWT_SECRET=JSqlintSecretKey2026VeryLongAndSecureKeyForProduction"
if not defined JAVA_OPTS set "JAVA_OPTS=-Xms512m -Xmx1024m"

if "%~1"=="" goto :usage
if /i "%~1"=="start" goto :start
if /i "%~1"=="stop" goto :stop
if /i "%~1"=="restart" goto :restart
if /i "%~1"=="status" goto :status
goto :usage

:start
call :check_jar
if errorlevel 1 exit /b 1
call :read_pid
if defined pid (
  echo [WARN] %APP_NAME% already running ^(pid=!pid!^)
  goto :end
)
set "JAVA_CMD=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"

echo ==^> starting %APP_NAME% port=%JSQLINT_PORT%
REM detached start in minimized window; logging handled by logback RollingFileAppender (logs/server.log)
start "%APP_NAME%" /min "%JAVA_CMD%" %JAVA_OPTS% -jar "%JAR_PATH%" --server.port=%JSQLINT_PORT% --spring.datasource.username="%JSQLINT_DB_USER%" --spring.datasource.password="%JSQLINT_DB_PASSWORD%" --jwt.secret="%JSQLINT_JWT_SECRET%"

REM record pid by matching command line against jar name
ping -n 5 127.0.0.1 >nul
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*jsqlint-server.jar*' } | Select-Object -First 1 -ExpandProperty ProcessId" > "%PID_FILE%.tmp" 2>nul
set "NEW_PID="
set /p NEW_PID=<"%PID_FILE%.tmp" 2>nul
del /q "%PID_FILE%.tmp" 2>nul
if defined NEW_PID (
  echo !NEW_PID!> "%PID_FILE%"
  echo     started pid=!NEW_PID!
) else (
  echo     started ^(pid not recorded, check port %JSQLINT_PORT%^)
)
goto :end

:stop
call :read_pid
if not defined pid (
  echo     %APP_NAME% not running
  goto :end
)
echo ==^> stopping %APP_NAME% pid=!pid!
taskkill /pid !pid! >nul 2>&1
ping -n 6 127.0.0.1 >nul
powershell -NoProfile -Command "if (Get-Process -Id !pid! -ErrorAction SilentlyContinue) { Stop-Process -Id !pid! -Force }" >nul 2>&1
del /q "%PID_FILE%" 2>nul
echo     stopped
goto :end

:restart
call :stop_silent
goto :start

:status
call :read_pid
if defined pid (
  echo %APP_NAME% running ^(pid=!pid!, port %JSQLINT_PORT%^)
) else (
  echo %APP_NAME% not running
)
goto :end

:stop_silent
call :read_pid
if defined pid (
  taskkill /pid !pid! >nul 2>&1
  ping -n 6 127.0.0.1 >nul
  powershell -NoProfile -Command "if (Get-Process -Id !pid! -ErrorAction SilentlyContinue) { Stop-Process -Id !pid! -Force }" >nul 2>&1
  del /q "%PID_FILE%" 2>nul
)
goto :eof

:check_jar
if not exist "%JAR_PATH%" (
  echo [ERROR] jar not found: %JAR_PATH%
  echo         run build.bat first
  exit /b 1
)
goto :eof

:read_pid
REM read pid from file; empty it when the process no longer exists (powershell check is pipe-free and reliable)
set "pid="
if exist "%PID_FILE%" set /p pid=<"%PID_FILE%"
if defined pid (
  powershell -NoProfile -Command "if (Get-Process -Id !pid! -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }" >nul 2>&1 || set "pid="
)
goto :eof

:usage
echo Usage: %~nx0 {start|stop|restart|status}
exit /b 1

:end
endlocal
exit /b 0
