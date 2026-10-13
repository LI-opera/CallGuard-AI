[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDir,
    [ValidateRange(10, 180)]
    [int]$DurationSeconds = 90,
    [ValidateSet('console', 'multimedia', 'communications')]
    [string]$AudioRole = 'console'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$events = Join-Path $root 'voice\windows-phone-link-ui\build\PhoneLinkEventProbe.exe'
$audio = Join-Path $root 'voice\windows-phone-link-audio\build\WasapiLoopbackProbe.exe'
foreach ($exe in @($events, $audio)) {
    if (-not (Test-Path -LiteralPath $exe)) { throw "Build the probe first: $exe" }
}

$target = if ([IO.Path]::IsPathRooted($OutputDir)) {
    [IO.Path]::GetFullPath($OutputDir)
} else {
    [IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputDir))
}
if (Test-Path -LiteralPath $target) { throw "Refusing to overwrite: $target" }
New-Item -ItemType Directory -Path $target -Force | Out-Null

$eventPath = Join-Path $target 'uia-events.jsonl'
$audioPath = Join-Path $target 'wasapi-levels.json'
$eventStdout = Join-Path $target 'uia-stdout.txt'
$eventStderr = Join-Path $target 'uia-stderr.txt'
$audioStdout = Join-Path $target 'audio-stdout.txt'
$audioStderr = Join-Path $target 'audio-stderr.txt'

$eventProcess = Start-Process -FilePath $events -ArgumentList @(
    '--output', ('"' + $eventPath + '"'), '--seconds', $DurationSeconds
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $eventStdout -RedirectStandardError $eventStderr
$audioProcess = Start-Process -FilePath $audio -ArgumentList @(
    '--output', ('"' + $audioPath + '"'), $DurationSeconds, '--role', $AudioRole
) -PassThru -WindowStyle Hidden -RedirectStandardOutput $audioStdout -RedirectStandardError $audioStderr

Write-Output "LISTENING output=$target durationSeconds=$DurationSeconds eventPid=$($eventProcess.Id) audioPid=$($audioProcess.Id)"
$eventProcess.WaitForExit()
$audioProcess.WaitForExit()
$eventProcess.Refresh()
$audioProcess.Refresh()

$audioReport = $null
if (Test-Path -LiteralPath $audioPath) {
    $audioReport = Get-Content -LiteralPath $audioPath -Raw | ConvertFrom-Json
}
$eventRecords = @()
if (Test-Path -LiteralPath $eventPath) {
    $eventRecords = @(Get-Content -LiteralPath $eventPath | ForEach-Object { $_ | ConvertFrom-Json })
}
[ordered]@{
    output = $target
    eventExit = $eventProcess.ExitCode
    audioExit = $audioProcess.ExitCode
    eventRecords = $eventRecords.Count
    phoneLinkEvents = @($eventRecords | Where-Object { $_.process_id -eq $_.target_process_id -and $_.event -notin @('started','stopped') }).Count
    audioPackets = if ($audioReport) { $audioReport.packets } else { $null }
    audioFrames = if ($audioReport) { $audioReport.total_frames } else { $null }
    audioRole = if ($audioReport) { $audioReport.selected_role } else { $null }
    audioEndpoint = if ($audioReport) { $audioReport.selected_endpoint } else { $null }
    phoneLinkAudioVerified = $false
} | ConvertTo-Json
