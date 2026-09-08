@echo off
REM ============================================================
REM jsqlint build script (Windows, located at project root)
REM Output: dist\jsqlint-server.jar + dist\web\
REM Usage: build.bat [skipUi] [skipServer]
REM ============================================================
setlocal

set "ROOT_DIR=%~dp0"

set "DIST_DIR=%ROOT_DIR%dist"
set "SERVER_MODULE=jsqlint-server"
set "JAR_NAME=jsqlint-server.jar"

set "SKIP_UI=%~1"
set "SKIP_SERVER=%~2"

if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"

REM ---------- server package ----------
if /i not "%SKIP_SERVER%"=="skipServer" (
  echo ==^> [1/2] packaging server, skip tests...
  if exist "%ROOT_DIR%\mvnw.cmd" (
    call "%ROOT_DIR%\mvnw.cmd" -q -pl %SERVER_MODULE% -am -DskipTests package
  ) else (
    call mvn -q -pl %SERVER_MODULE% -am -DskipTests package
  )
  if errorlevel 1 goto :fail
  REM exact fat jar name: spring-boot repackage keeps -SNAPSHOT.jar, original goes to .jar.original
  copy /y "%ROOT_DIR%\%SERVER_MODULE%\target\jsqlint-server-0.0.1-SNAPSHOT.jar" "%DIST_DIR%\%JAR_NAME%" >nul
  if errorlevel 1 goto :fail
  echo     server jar: %DIST_DIR%\%JAR_NAME%
)

REM ---------- web package ----------
if /i not "%SKIP_UI%"=="skipUi" (
  echo ==^> [2/2] packaging web...
  pushd "%ROOT_DIR%\jsqlint-ui"
  if not exist node_modules (
    call npm install --no-fund --no-audit
    if errorlevel 1 popd & goto :fail
  )
  call npm run build
  if errorlevel 1 popd & goto :fail
  popd
  if exist "%DIST_DIR%\web" rmdir /s /q "%DIST_DIR%\web"
  xcopy /e /i /q "%ROOT_DIR%\jsqlint-ui\dist" "%DIST_DIR%\web" >nul
  if errorlevel 1 goto :fail
  echo     web assets: %DIST_DIR%\web\
)

echo ==^> package done: %DIST_DIR%
dir /b "%DIST_DIR%"
goto :end

:fail
echo [ERROR] package failed, check log above
exit /b 1

:end
endlocal
exit /b 0
