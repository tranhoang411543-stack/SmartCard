@echo off
setlocal
cd /d "%~dp0"
set "JAVA_HOME=C:\Program Files\Java\jdk-25"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts\Prepare.ps1 -Test
if errorlevel 1 exit /b 1
call "C:\Program Files\apache-maven-3.9.12\bin\mvn.cmd" -f build\pom.xml install
if errorlevel 1 exit /b 1
echo Build thanh cong. Chay 01-Chuan-bi.ps1, 02-Backend.ps1 va ung dung quay.
