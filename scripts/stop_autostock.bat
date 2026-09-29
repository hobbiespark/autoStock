@echo off
REM autoStock graceful stop - POST /actuator/shutdown (see application.yml management.endpoint.shutdown)
REM 127.0.0.1 explicit - app binds IPv4 loopback only (server.address), localhost may resolve to ::1 first
curl -s -X POST http://127.0.0.1:8080/actuator/shutdown
if %errorlevel%==0 (
  echo.
  echo [O] shutdown requested - app will stop gracefully in a few seconds
) else (
  echo [X] app not reachable on 127.0.0.1:8080 - already stopped?
)
timeout /t 3 /nobreak >nul
