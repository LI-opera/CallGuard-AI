param([switch]$StartInfrastructure, [int]$Port=8000)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$envFile=Join-Path $root '.env'
if (!(Test-Path -LiteralPath $envFile)) { throw 'Create project .env using .env.example first.' }
$saved=@{}
try {
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match '^((?:CALLGUARD|DEEPSEEK|MODEL)_[A-Z_]+)=(.*)$') {
            $key=$Matches[1]
            $saved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')
            [Environment]::SetEnvironmentVariable($key,$Matches[2],'Process')
        }
    }
    if ($StartInfrastructure) {
        & docker compose --env-file $envFile -f (Join-Path $root 'server/compose.yaml') up -d --wait
        if ($LASTEXITCODE -ne 0) { throw 'Docker infrastructure failed; server not started.' }
    }
    Push-Location $root
    try {
        & (Join-Path $root '.venv/Scripts/python.exe') -m server.run --port $Port
        if ($LASTEXITCODE -ne 0) { throw 'Risk server failed; check infrastructure and local environment.' }
    } finally { Pop-Location }
} finally {
    foreach($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
}
