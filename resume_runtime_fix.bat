@echo off
setlocal EnableExtensions

if "%~1"=="" (
  set "REPO=D:\adobe\VerdantVice"
) else (
  set "REPO=%~1"
)

if not exist "%REPO%\settings.gradle.kts" (
  echo [BLAD] Nie znaleziono repozytorium: %REPO%
  exit /b 2
)

cd /d "%REPO%"

echo [1/4] Sprawdzam branch...
for /f "delims=" %%B in ('git branch --show-current') do set "BRANCH=%%B"
if /I not "%BRANCH%"=="fix/plant-persistence-runtime" (
  echo [BLAD] Aktualny branch to %BRANCH%.
  echo Wykonaj: git switch fix/plant-persistence-runtime
  exit /b 3
)

echo [2/4] Szukam Pythona...
set "PYTHON_CMD="
py -3 -c "import sys" >nul 2>nul
if not errorlevel 1 set "PYTHON_CMD=py -3"
if not defined PYTHON_CMD (
  python -c "import sys" >nul 2>nul
  if not errorlevel 1 set "PYTHON_CMD=python"
)
if not defined PYTHON_CMD (
  python3 -c "import sys" >nul 2>nul
  if not errorlevel 1 set "PYTHON_CMD=python3"
)
if not defined PYTHON_CMD (
  echo [BLAD] Nie znaleziono dzialajacego Pythona.
  exit /b 4
)

echo [3/4] Wznawiam poprawke od tlumaczen...
%PYTHON_CMD% "%~dp0resume_runtime_fix.py" "%REPO%"
if errorlevel 1 exit /b %errorlevel%

echo [4/4] Uruchamiam pelny build...
call gradlew.bat clean build --no-configuration-cache --stacktrace > full-build.log 2>&1
set "RESULT=%errorlevel%"

echo Wynik: %REPO%\full-build.log
findstr /C:"BUILD SUCCESSFUL" /C:"BUILD FAILED" full-build.log
exit /b %RESULT%
