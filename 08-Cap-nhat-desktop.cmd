@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Update-Desktop.ps1"
if errorlevel 1 (
    if /i not "%~1"=="--no-launch" pause
    exit /b 1
)
if /i "%~1"=="--no-launch" exit /b 0
set "launchError="
echo Dang mo quay quan ly...
call "%~dp003-Quay-quan-ly.cmd"
if errorlevel 1 set "launchError=1"
echo Dang mo quay thanh toan...
call "%~dp004-Quay-thanh-toan.cmd"
if errorlevel 1 set "launchError=1"
if defined launchError (
    echo Khong mo duoc mot hoac ca hai quay. Hay kiem tra lai Java va chay cac lenh mo quay.
    pause
    exit /b 1
)
exit /b 0
