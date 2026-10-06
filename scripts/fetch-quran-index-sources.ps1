param([string]$Destination = (Join-Path $PSScriptRoot '..\work\quran-import'))
$ErrorActionPreference = 'Stop'
$destinationPath = [IO.Path]::GetFullPath($Destination)
$surahDirectory = Join-Path $destinationPath 'source'
New-Item -ItemType Directory -Force $surahDirectory | Out-Null
$proxy = 'http://127.0.0.1:8888'
Invoke-WebRequest -Uri 'https://tanzil.net/res/text/metadata/quran-data.xml' -Proxy $proxy -OutFile (Join-Path $destinationPath 'quran-data.xml')
Invoke-WebRequest -Uri 'https://tanzil.net/pub/download/index.php?quranType=simple-clean&outType=txt-2' -Proxy $proxy -OutFile (Join-Path $destinationPath 'quran-simple.txt')
1..114 | ForEach-Object -Parallel {
    $file = Join-Path $using:surahDirectory ('surah-{0:d3}.html' -f $_)
    Invoke-WebRequest -Uri "https://quranpedia.net/surah/7/$_" -Proxy $using:proxy -OutFile $file
} -ThrottleLimit 6
Write-Output "Downloaded Quran index inputs through $proxy to $destinationPath"
