[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Output,
    [ValidateRange(1, 300)]
    [int]$TimeoutSeconds = 90,
    [ValidateRange(100, 5000)]
    [int]$IntervalMilliseconds = 250
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$probe = Join-Path $projectRoot 'voice\windows-phone-link-ui\build\PhoneLinkUiProbe.exe'
$outputPath = if ([IO.Path]::IsPathRooted($Output)) {
    [IO.Path]::GetFullPath($Output)
} else {
    [IO.Path]::GetFullPath((Join-Path (Get-Location) $Output))
}
$historyPath = [IO.Path]::ChangeExtension($outputPath, '.samples.jsonl')

if (-not (Test-Path -LiteralPath $probe)) {
    throw "Probe has not been built: $probe"
}
if ((Test-Path -LiteralPath $outputPath) -or (Test-Path -LiteralPath $historyPath)) {
    throw "Refusing to overwrite existing evidence: $outputPath or $historyPath"
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputPath) | Out-Null
$tempDir = Join-Path $projectRoot '.tmp'
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
$tempPath = Join-Path $tempDir ("phone-link-ui-" + [Guid]::NewGuid().ToString('N') + '.json')
$bestReport = $null
$bestCount = -1
$lastSignature = $null
$timer = [Diagnostics.Stopwatch]::StartNew()

try {
    while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
        & $probe --output $tempPath | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "Read-only probe failed with exit code $LASTEXITCODE"
        }

        $raw = Get-Content -LiteralPath $tempPath -Raw
        $report = $raw | ConvertFrom-Json
        $count = [int]$report.interactive_element_count
        $signature = @(
            $report.status,
            $report.matching_window_count,
            ($report.interactive_elements | ConvertTo-Json -Compress -Depth 8),
            ($report.actions | ConvertTo-Json -Compress -Depth 8)
        ) -join '|'

        if ($signature -ne $lastSignature) {
            [IO.File]::AppendAllText($historyPath, $raw.TrimEnd() + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
            $lastSignature = $signature
        }
        if ($count -gt $bestCount) {
            $bestCount = $count
            $bestReport = $raw
        }
        if (@($report.actions).Count -gt 0) {
            [IO.File]::WriteAllText($outputPath, $raw, [Text.UTF8Encoding]::new($false))
            Get-Content -LiteralPath $outputPath -Raw
            return
        }

        Start-Sleep -Milliseconds $IntervalMilliseconds
    }

    if ($null -ne $bestReport) {
        [IO.File]::WriteAllText($outputPath, $bestReport, [Text.UTF8Encoding]::new($false))
    }
    throw "No recognized Phone Link call action appeared within $TimeoutSeconds seconds; strongest snapshot and changed states were saved."
}
finally {
    if (Test-Path -LiteralPath $tempPath) {
        Remove-Item -LiteralPath $tempPath -Force
    }
}
