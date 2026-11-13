@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Update-Backend.ps1" %*
set "updateExitCode=%errorlevel%"
if not "%updateExitCode%"=="0" pause
exit /b %updateExitCode%
