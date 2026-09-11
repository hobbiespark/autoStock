@echo off
REM autoStock start - paper profile, LIVE(mock) mode + C3 + WS + auto-start (RUNBOOK 5)
cd /d D:\myApp\autoStock

REM ---- load .env (KEY=VALUE, lines starting with # are skipped) ----
if not exist ".env" (
  echo [X] .env not found in D:\myApp\autoStock
  pause
  exit /b 1
)
for /f "usebackq eol=# tokens=1,* delims==" %%a in (".env") do set "%%a=%%b"
if "%KIWOOM_MOCK_G_APP_KEY%"=="" (
  echo [X] KIWOOM_MOCK_G_APP_KEY not loaded - check .env
  pause
  exit /b 1
)

set "JAVA_HOME=D:\jdks\jdk-21.0.12.101-hotspot"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM ---- wait for Docker engine (max ~180s; Docker Desktop must be set to start at login) ----
set /a TRIES=0
:dockerwait
docker info >nul 2>&1
if %errorlevel%==0 goto dockerok
set /a TRIES+=1
if %TRIES% geq 36 (
  echo [X] Docker engine not ready after 180s - start Docker Desktop and rerun
  pause
  exit /b 1
)
timeout /t 5 /nobreak >nul
goto dockerwait
:dockerok
docker compose -f infra\docker-compose.yml up -d postgres

echo [O] starting autoStock (close = Ctrl+C or scripts\stop_autostock.bat)
call gradlew.bat :app:bootRun --args="--execution.mode=LIVE --strategy.c3.enabled=true --autostock.ws.enabled=true --autostock.trading.auto-start=true"
pause
