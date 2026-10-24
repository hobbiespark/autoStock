@echo off
REM PC migration backup stays outside Git. Transfer the entire migration folder securely.
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pc_migration_backup.ps1"
set RC=%ERRORLEVEL%
echo.
if "%RC%"=="0" (echo [O] LOCAL BACKUP OK - copy the entire migration folder shown above) else (echo [X] BACKUP FAILED - see migration.log)
pause
