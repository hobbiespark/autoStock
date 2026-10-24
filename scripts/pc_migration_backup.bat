@echo off
REM autoStock PC migration backup (2026-10-24) - git push, git bundle, DB dump, files outside git -> D:\backup\autostock\migration_<time>\
REM Details: aiDoc\pc-migration-2026-10-24.md, restore on the new PC: aiDoc\restore-from-backup.md (also RESTORE_GUIDE.md in the backup folder).
REM No goto/labels in this file (line-ending safe).
cd /d "%~dp0.."
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pc_migration_backup.ps1" -Push
set RC=%ERRORLEVEL%
echo.
if "%RC%"=="0" (echo [O] MIGRATION BACKUP OK - copy D:\backup\autostock\migration_* to an external disk) else (echo [X] MIGRATION BACKUP FAILED - see migration.log in D:\backup\autostock\migration_*)
pause
