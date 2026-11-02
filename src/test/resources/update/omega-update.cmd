@echo off
rem Omega-Plugin update helper. Written by the program; removes itself when done.
chcp 65001 >nul
set "PID=4242"
set "SRC=C:\Users\Pat Smith\AppData\Local\Temp\omega-update\OmegaPlugin-1.8.1-beta"
set "DST=C:\Users\Pat Smith\Desktop\OmegaPlugin-1.8.0-beta"
set "ZIP=C:\Users\Pat Smith\AppData\Local\Temp\Omega-Plugin-1.8.1-beta-Windows.zip"
set "STAGE=C:\Users\Pat Smith\AppData\Local\Temp\omega-update"
title Omega-Plugin update
:wait
"%SystemRoot%\System32\tasklist.exe" /FI "PID eq %PID%" 2>nul | "%SystemRoot%\System32\find.exe" "%PID%" >nul
if not errorlevel 1 (
    "%SystemRoot%\System32\ping.exe" -n 2 127.0.0.1 >nul
    goto wait
)
del /q "%DST%\app\*.jar" 2>nul
"%SystemRoot%\System32\robocopy.exe" "%SRC%" "%DST%" /E /R:5 /W:1 /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 (
    start "" "%SystemRoot%\explorer.exe" /select,"%ZIP%"
    start "" "%DST%\OmegaPlugin.exe"
    goto end
)
start "" "%DST%\OmegaPlugin.exe"
rd /s /q "%STAGE%" 2>nul
del /q "%ZIP%" 2>nul
:end
(goto) 2>nul & del "%~f0"
