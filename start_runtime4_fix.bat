@echo off
setlocal EnableExtensions

set "REPO=D:\adobe\VerdantVice"
if not "%~1"=="" set "REPO=%~1"
set "BRANCH=fix/runtime-items-trade-localization"

cd /d "%REPO%" || (
  echo [BLAD] Nie znaleziono repozytorium: %REPO%
  exit /b 2
)

if not exist "settings.gradle.kts" (
  echo [BLAD] To nie wyglada jak repozytorium VerdantVice.
  exit /b 3
)

echo [1/5] Sprawdzam zmiany sledzone przez Git...
git diff --quiet -- || (
  echo [BLAD] Masz niezapisane zmiany w sledzonych plikach.
  echo.
  git status --short
  echo.
  echo Najpierw je commitnij albo schowaj przez git stash.
  exit /b 4
)
git diff --cached --quiet -- || (
  echo [BLAD] Masz pliki dodane do indeksu.
  git status --short
  exit /b 5
)

echo [2/5] Aktualizuje main...
git switch main || exit /b 6
git pull --ff-only origin main || exit /b 7

echo [3/5] Tworze lub otwieram branch %BRANCH%...
git show-ref --verify --quiet "refs/heads/%BRANCH%"
if not errorlevel 1 (
  git switch "%BRANCH%" || exit /b 8
  git merge --ff-only main || (
    echo [BLAD] Istniejacy branch nie moze zostac fast-forwardowany do main.
    echo Usun go lub wybierz nowa nazwe brancha.
    exit /b 9
  )
) else (
  git show-ref --verify --quiet "refs/remotes/origin/%BRANCH%"
  if not errorlevel 1 (
    git switch --track "origin/%BRANCH%" || exit /b 10
    git merge --ff-only main || exit /b 11
  ) else (
    git switch -c "%BRANCH%" || exit /b 12
  )
)

echo [4/5] Tworze archiwum aktualnego kodu...
if exist "runtime4-context.zip" del /q "runtime4-context.zip"
git archive --format=zip --output="runtime4-context.zip" HEAD || exit /b 13

echo [5/5] Gotowe.
echo.
echo Branch:
git branch --show-current
echo Commit:
git rev-parse --short HEAD
echo.
echo Wrzuc do rozmowy plik:
echo %REPO%\runtime4-context.zip
echo.
echo Pomocniczych plikow .bat, .py i logow skrypt nie dodal do archiwum.
exit /b 0
