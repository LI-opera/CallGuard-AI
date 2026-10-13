[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$source = Join-Path $root 'voice\windows-phone-link-audio\WasapiLoopbackProbe.cpp'
$build = Join-Path $root 'voice\windows-phone-link-audio\build'
$vcvars = 'D:\Visual studio\Community\VC\Auxiliary\Build\vcvars64.bat'
if (-not (Test-Path -LiteralPath $vcvars)) { throw "Visual Studio C++ environment not found: $vcvars" }
New-Item -ItemType Directory -Force -Path $build | Out-Null
$output = Join-Path $build 'WasapiLoopbackProbe.exe'
$compile = 'call "' + $vcvars + '" >nul && cl.exe /nologo /std:c++17 /EHsc /O2 /Fe:"' +
    $output + '" "' + $source + '" ole32.lib uuid.lib'
& cmd.exe /d /s /c $compile
if ($LASTEXITCODE -ne 0) { throw "WASAPI probe build failed: $LASTEXITCODE" }
Write-Output $output
