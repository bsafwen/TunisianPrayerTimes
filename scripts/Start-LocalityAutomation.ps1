param(
    [ValidateSet('Run','Status','Pause')][string]$Action = 'Run',
    [string]$TaskRoot = 'C:\Users\barou\Documents\Codex\2026-09-06\the-android-app-app-currently-allows'
)
$ErrorActionPreference = 'Stop'
$repoDirectory = Split-Path -Parent $PSScriptRoot
$pythonPath = Join-Path $TaskRoot 'work\geo\venv\Scripts\python.exe'
$configPath = Join-Path $TaskRoot 'work\locality-automation-config.json'
$entryPoint = Join-Path $PSScriptRoot 'run_locality_automation.py'
if (-not (Test-Path -LiteralPath $pythonPath)) { throw "The existing geographic Python environment is missing: $pythonPath" }
if (-not (Test-Path -LiteralPath $configPath)) {
    & $pythonPath -B -X utf8 $entryPoint --config $configPath init --task-root $TaskRoot --repo $repoDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Configuration failed. No locality jobs started.' }
}
switch ($Action) {
    'Run' {
        & $pythonPath -B -X utf8 $entryPoint --config $configPath resume
        if ($LASTEXITCODE -ne 0) { throw 'Could not resume automation.' }
        & $pythonPath -B -X utf8 $entryPoint --config $configPath run
    }
    'Status' { & $pythonPath -B -X utf8 $entryPoint --config $configPath report }
    'Pause' { & $pythonPath -B -X utf8 $entryPoint --config $configPath pause }
}
$runExit = $LASTEXITCODE
$reportPath = Join-Path $TaskRoot 'work\locality-automation\report\report.html'
if ($Action -ne 'Pause' -and (Test-Path -LiteralPath $reportPath)) { Start-Process -FilePath $reportPath }
if ($runExit -ne 0) { throw "Automation stopped with an error (code $runExit). Keep its report and logs for review." }
if ($Action -eq 'Run') { Write-Host 'Finished. Send work\locality-automation\report\run-summary.json back to Codex.' }
