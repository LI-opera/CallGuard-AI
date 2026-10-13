[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$sourceDir = Join-Path $projectRoot 'voice\windows-phone-link-ui'
$buildDir = Join-Path $sourceDir 'build'
$compiler = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$frameworkDir = 'C:\Windows\Microsoft.NET\Framework64\v4.0.30319'

if (-not (Test-Path -LiteralPath $compiler)) {
    throw "C# compiler not found: $compiler"
}

New-Item -ItemType Directory -Force -Path $buildDir | Out-Null

$references = @(
    (Join-Path $frameworkDir 'WPF\UIAutomationClient.dll'),
    (Join-Path $frameworkDir 'WPF\UIAutomationTypes.dll'),
    (Join-Path $frameworkDir 'WPF\WindowsBase.dll'),
    (Join-Path $frameworkDir 'System.Web.Extensions.dll')
)

foreach ($reference in $references) {
    if (-not (Test-Path -LiteralPath $reference)) {
        throw "Required framework assembly not found: $reference"
    }
}

$output = Join-Path $buildDir 'PhoneLinkUiProbe.exe'
$arguments = @(
    '/nologo',
    '/target:exe',
    '/platform:x64',
    '/optimize+',
    "/out:$output"
) + ($references | ForEach-Object { "/reference:$_" }) + @(
    (Join-Path $sourceDir 'PhoneLinkUiProbe.cs')
)

& $compiler @arguments
if ($LASTEXITCODE -ne 0) {
    throw "C# compilation failed with exit code $LASTEXITCODE"
}

$eventOutput = Join-Path $buildDir 'PhoneLinkEventProbe.exe'
$eventArguments = @(
    '/nologo',
    '/target:exe',
    '/platform:x64',
    '/optimize+',
    "/out:$eventOutput"
) + ($references | ForEach-Object { "/reference:$_" }) + @(
    (Join-Path $sourceDir 'PhoneLinkEventProbe.cs')
)
& $compiler @eventArguments
if ($LASTEXITCODE -ne 0) {
    throw "Event probe compilation failed with exit code $LASTEXITCODE"
}
Write-Output $output
Write-Output $eventOutput
