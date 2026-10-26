@echo off
REM autoStock graceful stop - POST /actuator/shutdown (see application.yml management.endpoint.shutdown)
REM 127.0.0.1 explicit - app binds IPv4 loopback only (server.address), localhost may resolve to ::1 first
REM exit code: 0 = shutdown requested, 1 = request failed (app not reachable or HTTP error; -f makes curl fail on 4xx/5xx)
REM wait uses ping, not timeout - timeout fails with "Input redirection is not supported" when run non-interactively (2026-10-26)
curl -s -f -X POST http://127.0.0.1:8080/actuator/shutdown
set "STOP_RC=%errorlevel%"
if "%STOP_RC%"=="0" (
  echo.
  echo [O] shutdown requested - app will stop gracefully in a few seconds
) else (
  echo [X] shutdown request failed - curl exit %STOP_RC% ^(7 = not reachable on 127.0.0.1:8080, already stopped? / 22 = HTTP error^)
)
REM about 3 seconds (4 pings at 1s interval)
ping -n 4 127.0.0.1 >nul
if not "%STOP_RC%"=="0" exit /b 1
exit /b 0
