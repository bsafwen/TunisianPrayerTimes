param(
    [Parameter(Mandatory=$true)][string]$Catalog,
    [Parameter(Mandatory=$true)][string]$DataDir,
    [string]$Python = 'python',
    [string]$Scores,
    [string]$MapsKeyFile,
    [int]$Port = 8769,
    [switch]$NoBrowser
)
$ErrorActionPreference = 'Stop'
$catalogPath = (Resolve-Path -LiteralPath $Catalog).Path
$dataPath = [System.IO.Path]::GetFullPath($DataDir)
$snapshot = Get-Content -LiteralPath $catalogPath -Raw -Encoding UTF8 | ConvertFrom-Json
$scorePath = $null
$scoreHash = $null
if ($Scores) {
    $scorePath = (Resolve-Path -LiteralPath $Scores).Path
    $scoreHash = (Get-FileHash -LiteralPath $scorePath -Algorithm SHA256).Hash.ToLowerInvariant()
}
$mapsKeyPath = $null
if ($MapsKeyFile) { $mapsKeyPath = (Resolve-Path -LiteralPath $MapsKeyFile).Path }
$mapsExpected = [bool]$mapsKeyPath
$url = "http://127.0.0.1:$Port"
$health = $null
try { $health = Invoke-RestMethod -Uri "$url/api/health" -TimeoutSec 2 } catch {}
if ($null -ne $health) {
    if ($health.app -ne 'manual-locality-review' -or $health.catalogFingerprint -ne $snapshot.catalogFingerprint -or $health.scoreReportSha256 -ne $scoreHash -or [bool]$health.mapsEnabled -ne $mapsExpected) {
        throw "Port $Port is in use by a different review snapshot or another application."
    }
} else {
    [System.IO.Directory]::CreateDirectory($dataPath) | Out-Null
    $pythonPath = (Get-Command $Python -ErrorAction Stop).Source
    $serverPath = Join-Path $PSScriptRoot 'serve.py'
    $arguments = @('-B', '-X', 'utf8', ('"' + $serverPath + '"'), '--catalog', ('"' + $catalogPath + '"'), '--data-dir', ('"' + $dataPath + '"'), '--port', $Port)
    if ($scorePath) { $arguments += @('--scores', ('"' + $scorePath + '"')) }
    if ($mapsKeyPath) { $arguments += @('--maps-key-file', ('"' + $mapsKeyPath + '"')) }
    $process = Start-Process -FilePath $pythonPath -ArgumentList $arguments -WorkingDirectory $PSScriptRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $dataPath 'server.stdout.log') -RedirectStandardError (Join-Path $dataPath 'server.stderr.log') -PassThru
    $process.Id | Set-Content -LiteralPath (Join-Path $dataPath 'server.pid') -Encoding ASCII
    $deadline = (Get-Date).AddSeconds(12)
    do {
        Start-Sleep -Milliseconds 200
        $process.Refresh()
        if ($process.HasExited) { throw "Review server did not start. See $(Join-Path $dataPath 'server.stderr.log')." }
        try { $health = Invoke-RestMethod -Uri "$url/api/health" -TimeoutSec 1 } catch {}
    } while ($null -eq $health -and (Get-Date) -lt $deadline)
    if ($null -eq $health) { throw 'Review server has not responded yet. Check server.stderr.log before starting another copy.' }
    if ($health.app -ne 'manual-locality-review' -or $health.catalogFingerprint -ne $snapshot.catalogFingerprint -or $health.scoreReportSha256 -ne $scoreHash -or [bool]$health.mapsEnabled -ne $mapsExpected) { throw 'Unexpected application answered on the review port.' }
}
if (-not $NoBrowser) { Start-Process -FilePath "$url/?q=megrine" }
Write-Output "$url/?q=megrine"
