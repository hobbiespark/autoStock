@echo off
REM autoStock PC migration backup, git mode (2026-10-24) - DB dump + logs into backup\, commit backup/data/private docs, git push,
REM secrets only (.env, key files) -> D:\backup\autostock\secrets_<time>\ for the external disk.
REM Details: aiDoc\pc-migration-2026-10-24.md, restore on the new PC: aiDoc\restore-from-backup.md. No goto/labels (line-ending safe).
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pc_migration_backup.ps1"
set RC=%ERRORLEVEL%
echo.
if "%RC%"=="0" (echo [O] MIGRATION BACKUP OK - pushed to GitHub. Copy ONLY D:\backup\autostock\secrets_* to the external disk) else (echo [X] MIGRATION BACKUP FAILED - see migration.log in D:\backup\autostock\secrets_*)
pause
