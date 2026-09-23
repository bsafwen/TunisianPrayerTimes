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
"%PYTHON%" -B -X utf8 -c "import json,sys; r=json.load(open(sys.argv[1],encoding='utf-8')); print('Run status:',r['batch']['status'],'Recorded cases:',r['recordedCases'])" "%TASK_ROOT%\work\locality-automation\last-run.json"
echo The runner stopped. A PAUSED status means the full automatic pass is unfinished.
echo Tell Codex the displayed status so its results can be reviewed.
pause
exit /b 0
:failed
echo.
echo The run stopped with an error. Keep its report and logs for review.
pause
exit /b 1
