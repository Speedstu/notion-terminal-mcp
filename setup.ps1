$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvPath = Join-Path $ProjectRoot ".env"

if (Test-Path -LiteralPath $EnvPath) {
    Write-Host ".env already exists; it was not overwritten."
    exit 0
}

$Bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($Bytes)
$Token = [Convert]::ToHexString($Bytes).ToLowerInvariant()

@"
MCP_API_KEY=$Token
PORT=3000
HOST=127.0.0.1
ALLOWED_HOSTS=localhost:3000;127.0.0.1:3000
FULL_ACCESS=false
FILES_ROOT=$ProjectRoot\workspace
COMMAND_TIMEOUT_MS=120000
MAX_OUTPUT_BYTES=1048576
MAX_FILE_BYTES=10485760
"@ | Set-Content -LiteralPath $EnvPath -Encoding utf8

New-Item -ItemType Directory -Force -Path (Join-Path $ProjectRoot "workspace") | Out-Null
Write-Host "Created .env with a random API key."
Write-Host "Review FULL_ACCESS before exposing the server publicly."
