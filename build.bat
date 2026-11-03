@echo off
REM Build the Maven modules; artifacts remain in each module's target directory.
REM Usage: build.bat [Maven options]
setlocal

pushd "%~dp0"
if errorlevel 1 exit /b 1

if exist "mvnw.cmd" (
  call mvnw.cmd -DskipTests package %*
) else (
  call mvn -DskipTests package %*
)
set "BUILD_EXIT_CODE=%ERRORLEVEL%"

popd
exit /b %BUILD_EXIT_CODE%
