@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Stop-Desktop.ps1" %*
set "result=%ERRORLEVEL%"
echo.
pause
exit /b %result%
