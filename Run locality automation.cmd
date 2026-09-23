@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\Start-LocalityAutomation.ps1" -Action Run
echo.
pause
