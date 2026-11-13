@echo off
cd /d "%~dp0"
start "" "C:\Program Files\Java\jdk-25\bin\javaw.exe" "-Dttm.client=%~dp0.local\client.properties" -jar "%~dp0target\TTM_CuoiKy-1.0-SNAPSHOT.jar" --quay=thanhtoan
