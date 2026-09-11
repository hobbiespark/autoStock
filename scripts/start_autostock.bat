@echo off
REM autoStock start - paper profile, LIVE(mock) mode + C3 + WS + auto-start (RUNBOOK 5)
REM No goto/labels in this file (line-ending safe). Env load + docker wait are delegated to PowerShell.
cd /d D:\myApp\autoStock

if not exist ".env" (
  echo [X] .env not found in D:\myApp\autoStock
  pause
  exit /b 1
)

REM ---- load .env via scripts\load_env.ps1 (supports KEY_FILE=path indirection) ----
set "ENVCMD=%TEMP%\autostock_env.cmd"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\load_env.ps1 -OutCmd "%ENVCMD%"
if not exist "%ENVCMD%" (
  echo [X] env script not created - see load_env.ps1 output above
  pause
  exit /b 1
)
call "%ENVCMD%"
del "%ENVCMD%" >nul 2>&1

if "%KIWOOM_MOCK_G_APP_KEY%"=="" (
  echo [X] KIWOOM_MOCK_G_APP_KEY not loaded - check .env
  pause
  exit /b 1
)
echo [O] .env loaded

set "JAVA_HOME=D:\jdks\jdk-21.0.12.101-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM ---- wait for Docker engine (max 300s; after login Docker Desktop can take 30-60s+) ----
echo [.] waiting for Docker engine (max 300s)...
powershell -NoProfile -Command "$n=0; while($n -lt 60){ docker info *>$null; if($LASTEXITCODE -eq 0){ exit 0 }; Start-Sleep -Seconds 5; $n++ }; exit 1"
if errorlevel 1 (
  echo [X] Docker engine not ready after 300s - start Docker Desktop and rerun
  pause
  exit /b 1
)
echo [O] Docker ready
docker compose -f infra\docker-compose.yml up -d postgres

echo [O] starting autoStock (stop = Ctrl+C or scripts\stop_autostock.bat)
REM flags: LIVE(mock) mode, C3, WS, auto-start + DART(ipo/blacklist) + macro(FRED/ECOS) + holiday sync
call gradlew.bat :app:bootRun --args="--execution.mode=LIVE --strategy.c3.enabled=true --autostock.ws.enabled=true --autostock.trading.auto-start=true --dart.enabled=true --macrointel.enabled=true --macrointel.blacklist.enabled=true --market.holiday-api.enabled=true --autostock.minute-archive.enabled=true"
pause
