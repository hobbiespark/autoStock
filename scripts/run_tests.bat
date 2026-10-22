@echo off
REM autoStock full test run (2026-10-21). Stop the app first: scripts\stop_autostock.bat (or Ctrl+C in its window).
REM Output: build\test-run.log (console) and app\build\test-results\test\*.xml, app\build\reports\tests\test\index.html
REM No goto/labels in this file (line-ending safe).
cd /d "%~dp0.."
if not exist build mkdir build
echo [%date% %time%] test start > build\test-run.log
call gradlew.bat :common:test :app:test --continue --console=plain >> build\test-run.log 2>&1
set RC=%ERRORLEVEL%
echo [%date% %time%] test end, exit code %RC% >> build\test-run.log
echo.
if "%RC%"=="0" (echo [O] ALL TESTS PASSED - now start the app: scripts\start_autostock.bat) else (echo [X] TESTS FAILED - see build\test-run.log)
pause
