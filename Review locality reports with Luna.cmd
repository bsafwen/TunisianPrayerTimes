@echo off
setlocal
set "TASK_ROOT=C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows"
set "PYTHON=%TASK_ROOT%\work\geo\venv\Scripts\python.exe"
set "CONFIG=%TASK_ROOT%\work\locality-automation-luna-resume.json"
set "SCRIPT=%~dp0scripts\run_locality_review.py"
if not exist "%PYTHON%" goto missing
if not exist "%CONFIG%" goto missing
"%PYTHON%" -B -X utf8 "%SCRIPT%" --config "%CONFIG%" run --limit 8 --workers 3
if errorlevel 1 goto failed
echo.
echo The focused governorate review finished. See the JSON and HTML paths above.
echo Model findings are advice only; the geographic checklist is unchanged.
pause
exit /b 0
:missing
echo The task-local Python environment or Luna configuration is missing.
pause
exit /b 1
:failed
echo The review stopped with an error. Keep its report and logs for inspection.
pause
exit /b 1
