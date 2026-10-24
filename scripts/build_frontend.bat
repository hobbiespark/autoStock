@echo off
REM autoStock dashboard build (2026-10-23). Writes the bundle to app\src\main\resources\static (vite.config.ts outDir).
REM Run after frontend\src changes, then restart the app (scripts\start_autostock.bat) so it serves the new bundle.
REM Output: build\frontend-build.log. No goto/labels in this file (line-ending safe).
cd /d "%~dp0.."
if not exist build mkdir build
echo [%date% %time%] frontend build start > build\frontend-build.log
cd frontend
call npm run build >> ..\build\frontend-build.log 2>&1
set RC=%ERRORLEVEL%
cd ..
echo [%date% %time%] frontend build end, exit code %RC% >> build\frontend-build.log
echo.
if "%RC%"=="0" (echo [O] FRONTEND BUILD OK - app\src\main\resources\static updated) else (echo [X] FRONTEND BUILD FAILED - see build\frontend-build.log)
pause
