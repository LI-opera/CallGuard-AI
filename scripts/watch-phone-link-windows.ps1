[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Output,
    [ValidateRange(1, 300)]
    [int]$TimeoutSeconds = 120,
    [ValidateRange(100, 5000)]
    [int]$IntervalMilliseconds = 400
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$outputPath = if ([IO.Path]::IsPathRooted($Output)) {
    [IO.Path]::GetFullPath($Output)
} else {
    [IO.Path]::GetFullPath((Join-Path (Get-Location) $Output))
}
if (Test-Path -LiteralPath $outputPath) {
    throw "Refusing to overwrite existing evidence: $outputPath"
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputPath) | Out-Null

$lastSignature = $null
$timer = [Diagnostics.Stopwatch]::StartNew()
$changes = 0

while ($timer.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
    $roots = [System.Windows.Automation.AutomationElement]::RootElement.FindAll(
        [System.Windows.Automation.TreeScope]::Children,
        [System.Windows.Automation.Condition]::TrueCondition
    )
    $windows = @()
    foreach ($window in $roots) {
        try {
            $processId = $window.Current.ProcessId
            if ($processId -le 0) { continue }
            $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
            if ($null -eq $process) { continue }
            $windows += [ordered]@{
                process = $process.ProcessName
                process_id = $processId
                native_window_handle = $window.Current.NativeWindowHandle
                control_type = $window.Current.ControlType.ProgrammaticName
                offscreen = $window.Current.IsOffscreen
            }
        } catch [System.Windows.Automation.ElementNotAvailableException] {
            continue
        }
    }

    $windows = @($windows | Sort-Object process,process_id,native_window_handle)
    $signature = $windows | ConvertTo-Json -Compress -Depth 4
    if ($signature -ne $lastSignature) {
        $record = [ordered]@{
            schema = 'callguard.desktop_windows.v1'
            captured_at_utc = [DateTime]::UtcNow.ToString('o')
            elapsed_ms = [math]::Round($timer.Elapsed.TotalMilliseconds)
            window_count = $windows.Count
            windows = $windows
            privacy = 'Window titles, UI names, phone numbers and contact names are not read or stored.'
        } | ConvertTo-Json -Compress -Depth 6
        [IO.File]::AppendAllText($outputPath, $record + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
        $lastSignature = $signature
        $changes++
    }
    Start-Sleep -Milliseconds $IntervalMilliseconds
}

[ordered]@{
    output = $outputPath
    elapsed_seconds = [math]::Round($timer.Elapsed.TotalSeconds, 1)
    state_changes = $changes
} | ConvertTo-Json
