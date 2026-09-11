@echo off
REM install: run autoStock automatically at Windows login (current user Startup folder)
set "SU=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\autostock_start.bat"
> "%SU%" echo @echo off
>> "%SU%" echo start "autoStock" cmd /k "D:\myApp\autoStock\scripts\start_autostock.bat"
echo [O] installed: %SU%
echo     NOTE: Docker Desktop must also be set to "Start when you sign in" (Docker Settings - General)
echo     remove with: scripts\uninstall_autostart.bat
pause
