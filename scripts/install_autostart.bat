@echo off
REM install: run autoStock automatically at Windows login (current user Startup folder)
set "SU=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\autostock_start.bat"
> "%SU%" echo @echo off
>> "%SU%" echo start "autoStock" cmd /k "%~dp0start_autostock.bat"
echo [O] installed: %SU%
echo     NOTE: start_autostock.bat starts Docker Desktop when the engine is stopped
echo     remove with: scripts\uninstall_autostart.bat
pause
