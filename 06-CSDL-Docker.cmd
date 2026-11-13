@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\DockerDatabase.ps1 -Action Start
set "TTM_DB_EXIT=%errorlevel%"
if not "%TTM_DB_EXIT%"=="0" pause
exit /b %TTM_DB_EXIT%
