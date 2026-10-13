$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$python = Join-Path $root '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $python)) { throw 'Project .venv not found.' }
Push-Location $root
try {
    & $python -m server.run_demo
    if ($LASTEXITCODE -ne 0) { throw 'Text demo failed to start.' }
} finally {
    Pop-Location
}
