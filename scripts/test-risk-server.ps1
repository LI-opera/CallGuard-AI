param([switch]$Integration)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$saved=@{}
try {
    if($Integration) {
        foreach($line in Get-Content -LiteralPath (Join-Path $root '.env')) {
            if($line -match '^((?:CALLGUARD|DEEPSEEK|MODEL)_[A-Z_]+)=(.*)$') {
                $key=$Matches[1];$saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')
                [Environment]::SetEnvironmentVariable($key,$Matches[2],'Process')
            }
        }
        $saved['CALLGUARD_INTEGRATION']=$env:CALLGUARD_INTEGRATION
        $env:CALLGUARD_INTEGRATION='1'
    }
    Push-Location $root
    try {
        & (Join-Path $root '.venv/Scripts/python.exe') -m pytest -q --basetemp=.tmp/pytest-risk
        if($LASTEXITCODE -ne 0) {throw 'Risk tests failed.'}
    } finally {Pop-Location}
} finally {
    foreach($key in $saved.Keys) {[Environment]::SetEnvironmentVariable($key,$saved[$key],'Process')}
}
