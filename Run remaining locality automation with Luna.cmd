@echo off
setlocal
set "TASK_ROOT=C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows"
set "PYTHON=%TASK_ROOT%\work\geo\venv\Scripts\python.exe"
set "CONFIG=%TASK_ROOT%\work\locality-automation-luna-resume.json"
set "SCRIPT=%~dp0scripts\run_locality_automation.py"
if not exist "%CONFIG%" (
  echo Luna resume configuration is missing: %CONFIG%
  pause
  exit /b 1
)
"%PYTHON%" -B -X utf8 "%SCRIPT%" --config "%CONFIG%" resume
if errorlevel 1 goto failed
"%PYTHON%" -B -X utf8 "%SCRIPT%" --config "%CONFIG%" run --all-governorates
if errorlevel 1 goto failed
echo.
echo The automatic pass finished. Tell Codex so its results can be reviewed.
pause
exit /b 0
:failed
echo.
echo The run stopped with an error. Keep its report and logs for review.
pause
exit /b 1
