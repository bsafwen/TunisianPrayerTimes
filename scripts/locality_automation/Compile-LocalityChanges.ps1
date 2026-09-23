param([Parameter(Mandatory=$true)][string]$Repo, [Parameter(Mandatory=$true)][string]$Config, [Parameter(Mandatory=$true)][string]$SnapshotOutput)
$ErrorActionPreference = 'Stop'
$androidDirectory = Join-Path (Resolve-Path -LiteralPath $Repo).Path 'android-app'
$studioJava = 'C:\Program Files\Android\Android Studio\jbr'
if (-not $env:JAVA_HOME -and (Test-Path -LiteralPath $studioJava)) { $env:JAVA_HOME = $studioJava }
Push-Location -LiteralPath $androidDirectory
try {
    & .\gradlew.bat :app:compileDebugKotlin --console=plain
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }
$settings = Get-Content -LiteralPath $Config -Raw | ConvertFrom-Json
$entryPoint = Join-Path $Repo 'scripts\run_locality_automation.py'
& $settings.python -B -X utf8 $entryPoint --config $Config refresh-installed --output $SnapshotOutput
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
exit 0
