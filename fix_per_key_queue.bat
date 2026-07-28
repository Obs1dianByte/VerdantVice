@echo off
setlocal EnableExtensions

set "REPO=D:\adobe\VerdantVice"
if not "%~1"=="" set "REPO=%~1"

cd /d "%REPO%" || exit /b 2

for /f "delims=" %%B in ('git branch --show-current') do set "BRANCH=%%B"
if /I not "%BRANCH%"=="fix/plant-persistence-runtime" (
  echo [BLAD] Aktualny branch: %BRANCH%
  echo Wykonaj: git switch fix/plant-persistence-runtime
  exit /b 3
)

set "PYTHON_CMD="
py -3 -c "import sys" >nul 2>nul
if not errorlevel 1 set "PYTHON_CMD=py -3"
if not defined PYTHON_CMD (
  python -c "import sys" >nul 2>nul
  if not errorlevel 1 set "PYTHON_CMD=python"
)
if not defined PYTHON_CMD (
  echo [BLAD] Brak dzialajacego Pythona.
  exit /b 4
)

echo [1/2] Poprawiam PerKeyAsyncQueue...
%PYTHON_CMD% "%~dp0fix_per_key_queue.py" "%REPO%" || exit /b 5

echo [2/2] Uruchamiam pelny build...
call gradlew.bat clean build --no-configuration-cache --stacktrace > full-build.log 2>&1
set "RESULT=%errorlevel%"

echo.
findstr /C:"BUILD SUCCESSFUL" /C:"BUILD FAILED" full-build.log
echo Log: %REPO%\full-build.log
exit /b %RESULT%
