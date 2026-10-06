param(
 [Parameter(Mandatory=$true)][string]$Work,
 [Parameter(Mandatory=$true)][string]$Output
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath $Work).Path
$taskOutput = [System.IO.Path]::GetFullPath($Output)
if (Test-Path -LiteralPath $taskOutput) { throw 'Fresh observation output required' }
if ([System.IO.Path]::GetDirectoryName($taskOutput) -ne $taskRoot) { throw 'Observation must stay in the exact producer directory' }
$taskReceipts = @(Get-ChildItem -LiteralPath $taskRoot -Filter '*.execution.json' | ForEach-Object {
 $taskReceipt = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
 if ($taskReceipt.processId -and $taskReceipt.startedAtUtc -and $taskReceipt.argv.Count) {
  [PSCustomObject]@{path=$_.FullName;receipt=$taskReceipt}
 }
})
$taskIds = @($taskReceipts | ForEach-Object { $_.receipt.processId })
$taskProcesses = @(Get-CimInstance Win32_Process | Where-Object { $taskIds -contains $_.ProcessId })
$taskOwned = @()
$taskReused = @()
$taskUnknown = @()
foreach ($taskProcess in $taskProcesses) {
 $taskCandidates = @($taskReceipts | Where-Object { $_.receipt.processId -eq $taskProcess.ProcessId })
 foreach ($taskCandidate in $taskCandidates) {
  $taskReceipt = $taskCandidate.receipt
  if (-not $taskProcess.CreationDate -or -not $taskProcess.ExecutablePath) {
   $taskUnknown += [PSCustomObject]@{ProcessId=$taskProcess.ProcessId;receipt=$taskCandidate.path;reason='Process creation/executable identity unavailable'}
   continue
  }
  $taskCreated = [DateTimeOffset]$taskProcess.CreationDate.ToUniversalTime()
  $taskChecked = [DateTimeOffset]::Parse($taskReceipt.checkedAtUtc).ToUniversalTime()
  $taskStarted = [DateTimeOffset]::Parse($taskReceipt.startedAtUtc).ToUniversalTime()
  $taskExpectedExe = [System.IO.Path]::GetFullPath([string]$taskReceipt.argv[0])
  $taskExeMatches = [string]::Equals($taskExpectedExe,$taskProcess.ExecutablePath,[StringComparison]::OrdinalIgnoreCase)
  $taskCreatedMatches = $taskCreated -ge $taskChecked -and $taskCreated -le $taskStarted.AddSeconds(1)
  if ($taskExeMatches -and $taskCreatedMatches) {
   $taskOwned += [PSCustomObject]@{ProcessId=$taskProcess.ProcessId;ParentProcessId=$taskProcess.ParentProcessId;ExecutablePath=$taskProcess.ExecutablePath;createdAtUtc=$taskCreated.ToString('o');receipt=$taskCandidate.path;CommandLine=$taskProcess.CommandLine}
  } else {
   $taskReused += [PSCustomObject]@{ProcessId=$taskProcess.ProcessId;createdAtUtc=$taskCreated.ToString('o');receipt=$taskCandidate.path;executableMatches=$taskExeMatches;creationMatches=$taskCreatedMatches;reason='PID does not identify the receipt-owned process'}
  }
 }
}
$taskOwned = @($taskOwned | Sort-Object ProcessId -Unique)
$taskResult = @{
 atUtc=[DateTimeOffset]::UtcNow.ToString('o')
 activeOwnedProcessCount=$taskOwned.Count
 activeOwnedProcesses=$taskOwned
 ownedValidationProcesses=$taskOwned
 unknownIdentityCount=$taskUnknown.Count
 unknownIdentities=$taskUnknown
 rejectedReusedPids=$taskReused
 receiptsObserved=$taskReceipts.Count
 observedProducerDirectories=@($taskRoot)
 method='Exact receipt PID plus executable and aware-UTC OS creation interval; PID reuse is excluded, unavailable identity remains a blocker. Read-only, no process termination.'
}
$taskJson = $taskResult | ConvertTo-Json -Depth 8
$taskStream = [System.IO.File]::Open($taskOutput,[System.IO.FileMode]::CreateNew,[System.IO.FileAccess]::Write,[System.IO.FileShare]::None)
try {
 $taskBytes = [System.Text.UTF8Encoding]::new($false).GetBytes($taskJson+[Environment]::NewLine)
 $taskStream.Write($taskBytes,0,$taskBytes.Length)
} finally { $taskStream.Dispose() }
$taskResult | Select-Object atUtc,activeOwnedProcessCount,unknownIdentityCount,receiptsObserved | ConvertTo-Json
if ($taskOwned.Count -or $taskUnknown.Count) { throw 'Cannot finalize while exact owned processes or unknown identities remain' }

