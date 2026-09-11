@echo off
REM autoStock graceful stop - POST /actuator/shutdown (see application.yml management.endpoint.shutdown)
curl -s -X POST http://localhost:8080/actuator/shutdown
if %errorlevel%==0 (
  echo.
  echo [O] shutdown requested - app will stop gracefully in a few seconds
) else (
  echo [X] app not reachable on localhost:8080 - already stopped?
)
timeout /t 3 /nobreak >nul
